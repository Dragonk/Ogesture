package com.ogesture.service

import com.ogesture.data.SettingsRepository

/**
 * Persisted crash-recovery snapshot of the two navigation settings, captured before the watchdog
 * first enforces. Separated as an interface so the pure [SystemNavigationEnforcer] logic can be
 * unit-tested without a real DataStore.
 */
interface BaselineStore {
    /** The currently-persisted baseline (captured=false if none). */
    suspend fun read(): SettingsRepository.NavBaseline
    /** Persist the baseline BEFORE any enforcement write. */
    suspend fun write(baseline: SettingsRepository.NavBaseline)
    /** Clear after a successful restore. */
    suspend fun clear()
}

/**
 * Pure, Android-Context-free enforcement logic for the system-navigation watchdog. Separated
 * from [SystemNavigationController] so it can be unit-tested without a real SettingsProvider.
 *
 * Responsibilities:
 *  - capture the prior values of `force_fsg_nav_bar` / `hide_gesture_line` once per enable,
 *    **persisted before the first enforcement write** so a process death mid-enforcement can
 *    still restore the real original state;
 *  - distinguish an absent key from a read failure (a temporary provider exception never becomes
 *    "absent" and never triggers a delete during restore);
 *  - enforce both to `1`, writing only keys not already `1` (no redundant writes / observer loop);
 *  - restore the persisted baseline when enforcement ends (handling a previously-absent key by
 *    deleting it);
 *  - never write when the device is unsupported or the permission is missing.
 */
class SystemNavigationEnforcer(
    private val gateway: SecureSettingsGateway,
    private val baselineStore: BaselineStore,
    private val deviceSupported: () -> Boolean,
    private val permissionGranted: () -> Boolean,
) {
    @Volatile private var enforcing = false
    private var baselineForce: Int? = null
    private var baselineForcePresent = false
    private var baselineHide: Int? = null
    private var baselineHidePresent = false
    private var capturedBaseline = false

    /** Whether enforcement is currently active. */
    val isActive: Boolean get() = enforcing

    /**
     * Begin enforcing the desired state. Captures + persists the baseline values once (before any
     * write). Returns true if enforcement started, false if a precondition fails (unsupported
     * device, no permission, or a baseline read failure). Idempotent.
     */
    suspend fun startEnforcing(tag: String = "enable"): Boolean {
        if (!deviceSupported() || !permissionGranted()) return false
        if (!enforcing) {
            if (!captureBaseline()) return false // fail safe: read failure -> no enforcement
        }
        enforcing = true
        enforceNow(tag)
        return true
    }

    /** Re-assert the desired state without re-capturing the baseline. No-op if not enforcing. */
    fun reassert(tag: String) {
        if (!enforcing) return
        enforceNow(tag)
    }

    /**
     * Stop enforcing and restore the captured baseline values (or delete keys that were absent
     * before enforcement). Clears the persisted baseline only after a successful restore.
     */
    suspend fun stopEnforcing(tag: String = "disable") {
        if (!enforcing) return
        restoreBaseline(tag)
        enforcing = false
        capturedBaseline = false
        baselineForce = null
        baselineForcePresent = false
        baselineHide = null
        baselineHidePresent = false
    }

    /**
     * Load a persisted baseline (from a previous process that died mid-enforcement) WITHOUT
     * recapturing. Called on startup if the preference is already enabled. Does not overwrite an
     * existing in-memory baseline.
     */
    suspend fun loadPersistedBaseline() {
        if (capturedBaseline) return
        val b = baselineStore.read()
        if (b.captured) {
            baselineForcePresent = b.forcePresent
            baselineForce = if (b.forcePresent) b.forceValue else null
            baselineHidePresent = b.hidePresent
            baselineHide = if (b.hidePresent) b.hideValue else null
            capturedBaseline = true
        }
    }

    /**
     * Capture the prior values of both keys exactly once per enforcement lifecycle, persisting
     * them BEFORE any write. Returns false if a read failed (fail safe: do not enforce).
     */
    private suspend fun captureBaseline(): Boolean {
        if (capturedBaseline) return true
        val force = read(KEY_FORCE_FSG_NAV_BAR)
        val hide = read(KEY_HIDE_GESTURE_LINE)
        // A read failure must block enforcement — never conflate with absence.
        if (force is SettingReadResult.Failure || hide is SettingReadResult.Failure) return false
        baselineForcePresent = force is SettingReadResult.Present
        baselineForce = (force as? SettingReadResult.Present)?.value
        baselineHidePresent = hide is SettingReadResult.Present
        baselineHide = (hide as? SettingReadResult.Present)?.value
        capturedBaseline = true
        // Persist before enforcing so a process death can restore the real original state.
        baselineStore.write(
            SettingsRepository.NavBaseline(
                captured = true,
                forcePresent = baselineForcePresent,
                forceValue = baselineForce ?: 0,
                hidePresent = baselineHidePresent,
                hideValue = baselineHide ?: 0,
            ),
        )
        return true
    }

    private fun enforceNow(tag: String) {
        writeIfNotOne(KEY_FORCE_FSG_NAV_BAR, "$tag:force")
        writeIfNotOne(KEY_HIDE_GESTURE_LINE, "$tag:hide")
    }

    private suspend fun restoreBaseline(tag: String): Boolean {
        val okForce = restoreKey(KEY_FORCE_FSG_NAV_BAR, baselineForcePresent, baselineForce, "$tag:force")
        val okHide = restoreKey(KEY_HIDE_GESTURE_LINE, baselineHidePresent, baselineHide, "$tag:hide")
        // Only clear the persisted baseline after a successful restore; a failed restore
        // (e.g. permission revoked) keeps the baseline pending for a later retry.
        if (okForce && okHide) baselineStore.clear()
        return okForce && okHide
    }

    private fun writeIfNotOne(key: String, tag: String) {
        when (val current = read(key)) {
            is SettingReadResult.Present -> if (current.value == 1) return
            is SettingReadResult.Failure -> return // can't read -> don't write blindly
            is SettingReadResult.Absent -> { } // absent -> enforce
        }
        try {
            gateway.putInt(key, 1)
        } catch (_: SecurityException) {
            // No WRITE_SECURE_SETTINGS — leave the system value as-is.
        } catch (_: Throwable) {
            // Other failure — leave the system value as-is.
        }
    }

    private fun restoreKey(key: String, present: Boolean, baseline: Int?, tag: String): Boolean {
        return try {
            if (!present) gateway.delete(key)
            else if (baseline != null) gateway.putInt(key, baseline)
            true
        } catch (_: SecurityException) {
            false // No WRITE_SECURE_SETTINGS — baseline stays pending.
        } catch (_: Throwable) {
            false // Other failure — baseline stays pending.
        }
    }

    private fun read(key: String): SettingReadResult = try {
        gateway.read(key)
    } catch (t: Throwable) {
        SettingReadResult.Failure(t)
    }

    companion object {
        const val KEY_FORCE_FSG_NAV_BAR = "force_fsg_nav_bar"
        const val KEY_HIDE_GESTURE_LINE = "hide_gesture_line"
    }
}
