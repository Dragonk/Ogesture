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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

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

    private val enforcer = SystemNavigationEnforcer(gateway, deviceSupported, permissionGranted)

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            enforcer.reassert(tag = "observer")
        }
    }

    fun start() {
        // React to the combination of the opt-in setting + master gesture switch. The service
        // binding flag is set via [onServiceBound]/[onServiceUnbound]; permission/device checks
        // are evaluated at enforcement time.
        scope.launch {
            combine(repo.hideSystemNavigation, repo.masterEnabled) { hide, master -> hide to master }
                .distinctUntilChanged()
                .collect { (hide, master) -> onSettingsChanged(hide = hide, master = master) }
        }
    }

    /** Called when the opt-in setting or the master gesture switch changes. Public for tests. */
    fun onSettingsChanged(hide: Boolean, master: Boolean) {
        enabled = hide
        masterOn = master
        recomputeEnforcement(cause = "settings")
    }

    fun onServiceBound() {
        bound = true
        recomputeEnforcement(cause = "bound")
    }

    fun onServiceUnbound() {
        bound = false
        recomputeEnforcement(cause = "unbind")
    }

    fun stop() {
        bound = false
        enabled = false
        masterOn = false
        enforcer.stopEnforcing(tag = "stop")
        unregisterObserver()
    }

    /**
     * Recompute whether enforcement should be active and act on the transition. Called from any
     * state change (setting toggle, master switch, bind/unbind). Safe to call repeatedly.
     */
    private fun recomputeEnforcement(cause: String) {
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

    private val retryRunnable = Runnable { enforcer.reassert(tag = "retry") }

    private fun registerObserver() {
        val cr = context.contentResolver
        try {
            cr.registerContentObserver(Settings.Global.getUriFor(KEY_FORCE_FSG_NAV_BAR), false, observer)
            cr.registerContentObserver(Settings.Global.getUriFor(KEY_HIDE_GESTURE_LINE), false, observer)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to register observers", t)
        }
    }

    private fun unregisterObserver() {
        try {
            context.contentResolver.unregisterContentObserver(observer)
        } catch (_: Throwable) { /* never registered or already unregistered */ }
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
    /** Reads the integer value for [key], or null if the setting is absent. */
    fun getIntOrNull(key: String): Int?
    /** Writes an integer value. Throws [SecurityException] if the permission is missing. */
    fun putInt(key: String, value: Int)
    /** Deletes the setting (restores an absent value). */
    fun delete(key: String)
}

/** Real [Settings.Global]-backed implementation. */
private class AndroidSecureSettingsGateway(private val context: Context) : SecureSettingsGateway {
    private val resolver: ContentResolver get() = context.contentResolver

    override fun getIntOrNull(key: String): Int? =
        Settings.Global.getInt(resolver, key, ABSENT_SENTINEL).takeIf { it != ABSENT_SENTINEL }

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
