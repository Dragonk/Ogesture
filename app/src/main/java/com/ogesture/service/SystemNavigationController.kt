package com.ogesture.service

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.ogesture.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Optional Xiaomi/HyperOS system-navigation watchdog. When the user opts in, and all
 * prerequisites hold (supported device, [android.Manifest.permission.WRITE_SECURE_SETTINGS]
 * granted, master gestures on, service bound), it keeps the OEM three-button navigation bar
 * hidden by enforcing two `Settings.Global` keys:
 *
 *  - `force_fsg_nav_bar = 1`
 *  - `hide_gesture_line = 1`
 *
 * The design is **observer-driven, not polling-driven**: [ContentObserver]s on those two keys
 * fire when HyperOS resets them (e.g. when the system resolver/chooser appears), and the
 * controller re-enforces the desired state. It does not try to predict which UI transition
 * caused the reset — it observes the actual settings and maintains the user-selected state.
 *
 * The pure enforcement state machine lives in [SystemNavigationEnforcer] (testable without
 * Android); this class owns the AccessibilityService lifecycle, the DataStore flow reaction,
 * the ContentObserver registration, and the delayed HyperOS-retry scheduling.
 */
class SystemNavigationController(
    private val context: Context,
    private val repo: SettingsRepository,
    private val scope: CoroutineScope,
    private val gateway: SecureSettingsGateway = AndroidSecureSettingsGateway(context),
    private val handler: Handler = Handler(Looper.getMainLooper()),
    /** Test seam for device support. Production default: [isXiaomiEcosystemDevice]. */
    private val deviceSupported: () -> Boolean = { isXiaomiEcosystemDevice() },
    /** Test seam for the WRITE_SECURE_SETTINGS permission check. Production default checks it. */
    private val permissionGranted: () -> Boolean = {
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
    },
) {
    private var enabled = false
    private var masterOn = false
    private var bound = false
    private var observerRegistered = false

    private val enforcer = SystemNavigationEnforcer(
        gateway = gateway,
        baselineStore = object : BaselineStore {
            override suspend fun read() = repo.navBaseline.first()
            override suspend fun write(baseline: SettingsRepository.NavBaseline) = repo.setNavBaseline(baseline)
            override suspend fun clear() = repo.clearNavBaseline()
        },
        deviceSupported = deviceSupported,
        permissionGranted = permissionGranted,
    )

    // Single-writer serialization: every state transition goes through this mutex so baseline
    // capture/enforce/restore can't race.
    private val mutex = Mutex()

    // Independent scope for fail-safe restore operations that must survive a connection-scope
    // cancellation (e.g. onUnbind cancels connectionJob, but the system-nav restore must still
    // complete so the user isn't left without navigation).
    private val restoreScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            // Observer fires on the main thread; schedule serialized enforcement off-main.
            restoreScope.launch { mutex.withLock { enforcer.reassert(tag = "observer") } }
        }
    }

    fun start() {
        // React to the combination of the opt-in setting + master gesture switch. The service
        // binding flag is set via [onServiceBound]/[onServiceUnbound]; permission/device checks
        // are evaluated at enforcement time.
        scope.launch {
            // On (re)bind, load any persisted baseline (from a previous process that died
            // mid-enforcement, or a pending restore after permission loss).
            enforcer.loadPersistedBaseline()
            combine(repo.hideSystemNavigation, repo.masterEnabled) { hide, master -> hide to master }
                .distinctUntilChanged()
                .collect { (hide, master) -> onSettingsChanged(hide = hide, master = master) }
        }
        // If the preference is OFF but a pending baseline exists (e.g. restore failed before
        // due to missing permission, then the service reconnected), attempt the restore now —
        // but ONLY if the preference is OFF. If it's ON, the DataStore collector will enforce;
        // running attemptPendingRestore here would briefly restore 0/0 then re-enforce 1/1.
        restoreScope.launch {
            enforcer.loadPersistedBaseline()
            val requested = repo.hideSystemNavigation.first()
            if (!requested) {
                mutex.withLock { enforcer.attemptPendingRestore() }
            }
        }
    }

    /** Called when the opt-in setting or the master gesture switch changes. Public for tests. */
    fun onSettingsChanged(hide: Boolean, master: Boolean) {
        enabled = hide
        masterOn = master
        scope.launch { recomputeEnforcement(cause = "settings") }
    }

    fun onServiceBound() {
        bound = true
        restoreScope.launch { recomputeEnforcement(cause = "bound") }
    }

    fun onServiceUnbound() {
        bound = false
        // Do NOT restore here — [stop] (called right after onServiceUnbound by the service's
        // onUnbind) does the single serialized restore. Avoid a double-restore race.
    }

    /**
     * Called by the service's 30s watchdog + ON_RESUME. Retries a pending restore (one that
     * failed earlier due to missing permission) if permission has returned. No SettingsProvider
     * work when there's no pending restore, so the watchdog pays nothing 99.99% of the time.
     */
    fun retryPendingRestoreIfNeeded() {
        restoreScope.launch { mutex.withLock { enforcer.retryPendingRestoreIfNeeded() } }
    }

    fun stop() {
        bound = false
        enabled = false
        masterOn = false
        handler.removeCallbacks(retryRunnable)
        // Single serialized restore on the non-cancellable scope — survives connectionJob cancel
        // and is the only restore path (no double-restore from onServiceUnbound).
        restoreScope.launch {
            mutex.withLock {
                enforcer.stopEnforcing(tag = "stop")
                unregisterObserver()
            }
        }
    }

    /**
     * Recompute whether enforcement should be active and act on the transition. Called from any
     * state change (setting toggle, master switch, bind/unbind). Serialized through [mutex].
     */
    private suspend fun recomputeEnforcement(cause: String) {
        mutex.withLock {
            val shouldEnforce = enabled && masterOn && bound && deviceSupported() && permissionGranted()
            if (shouldEnforce) {
                enforcer.startEnforcing(tag = "enable:$cause")
                registerObserver()
                // HyperOS may rewrite its nav setting several times during one UI transition. A
                // couple of delayed re-checks catch a reset that lands slightly after enforcement.
                handler.removeCallbacks(retryRunnable)
                handler.postDelayed(retryRunnable, RETRY_1_MS)
                handler.postDelayed(retryRunnable, RETRY_2_MS)
            } else {
                enforcer.stopEnforcing(tag = "disable:$cause")
                unregisterObserver()
            }
        }
    }

    private val retryRunnable = Runnable {
        // Observer/timeout fires on the main thread; serialize enforcement off-main. Also retry
        // a pending restore (failed earlier due to missing permission) — no-op when not pending.
        restoreScope.launch {
            mutex.withLock {
                enforcer.reassert(tag = "retry")
                enforcer.retryPendingRestoreIfNeeded()
            }
        }
    }

    private fun registerObserver() {
        if (observerRegistered) return
        val cr = context.contentResolver
        try {
            cr.registerContentObserver(Settings.Global.getUriFor(KEY_FORCE_FSG_NAV_BAR), false, observer)
            cr.registerContentObserver(Settings.Global.getUriFor(KEY_HIDE_GESTURE_LINE), false, observer)
            observerRegistered = true
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to register observers", t)
        }
    }

    private fun unregisterObserver() {
        if (!observerRegistered) return
        try {
            context.contentResolver.unregisterContentObserver(observer)
        } catch (_: Throwable) { /* never registered or already unregistered */ }
        observerRegistered = false
    }

    companion object {
        private const val TAG = "SysNavWatchdog"
        const val KEY_FORCE_FSG_NAV_BAR = "force_fsg_nav_bar"
        const val KEY_HIDE_GESTURE_LINE = "hide_gesture_line"
        private const val RETRY_1_MS = 250L
        private const val RETRY_2_MS = 1000L

        /** Xiaomi/Redmi/POCO ecosystem detection (manufacturer + brand, case-insensitive). */
        fun isXiaomiEcosystemDevice(
            manufacturer: String = Build.MANUFACTURER,
            brand: String = Build.BRAND,
        ): Boolean {
            val m = manufacturer.lowercase()
            val b = brand.lowercase()
            return m == "xiaomi" || b == "xiaomi" ||
                m == "redmi" || b == "redmi" ||
                m == "poco" || b == "poco"
        }
    }
}

/**
 * Abstraction over [Settings.Global] so the enforcement logic can be unit-tested without a real
 * Android ContentResolver/SettingsProvider. The real implementation is
 * [AndroidSecureSettingsGateway]; tests supply a fake that records reads/writes.
 */
interface SecureSettingsGateway {
    /**
     * Reads a Settings.Global integer, distinguishing three outcomes: the key is present with
     * a value, the key is absent, or the read itself failed (a temporary provider exception).
     * A failure must NEVER be conflated with absence.
     */
    fun read(key: String): SettingReadResult
    /** Writes an integer value. Throws [SecurityException] if the permission is missing. */
    fun putInt(key: String, value: Int)
    /** Deletes the setting (restores an absent value). */
    fun delete(key: String)
}

sealed interface SettingReadResult {
    data class Present(val value: Int) : SettingReadResult
    data object Absent : SettingReadResult
    data class Failure(val cause: Throwable) : SettingReadResult
}

/** Real [Settings.Global]-backed implementation. */
private class AndroidSecureSettingsGateway(private val context: Context) : SecureSettingsGateway {
    private val resolver: ContentResolver get() = context.contentResolver

    override fun read(key: String): SettingReadResult = try {
        val v = Settings.Global.getInt(resolver, key, ABSENT_SENTINEL)
        if (v == ABSENT_SENTINEL) SettingReadResult.Absent else SettingReadResult.Present(v)
    } catch (t: Throwable) {
        SettingReadResult.Failure(t)
    }

    override fun putInt(key: String, value: Int) {
        Settings.Global.putInt(resolver, key, value)
    }

    override fun delete(key: String) {
        Settings.Global.putString(resolver, key, null)
    }

    private companion object {
        // Settings.Global.getInt(resolver, key, def) returns `def` when the key is absent. Use a
        // sentinel unlikely to collide with a real nav-bar setting value.
        private const val ABSENT_SENTINEL = Int.MIN_VALUE + 7
    }
}
