package com.ogesture.service

/**
 * Pure, Android-Context-free enforcement logic for the system-navigation watchdog. Separated
 * from [SystemNavigationController] so it can be unit-tested without a real SettingsProvider:
 * it depends only on a [SecureSettingsGateway] (a fake in tests) and two predicates
 * (`deviceSupported`, `permissionGranted`).
 *
 * Responsibilities:
 *  - capture the prior values of `force_fsg_nav_bar` / `hide_gesture_line` once per enable;
 *  - enforce both to `1`, writing only keys not already `1` (no redundant writes);
 *  - restore the captured baseline when enforcement ends (handling a previously-absent/null key
 *    by deleting it);
 *  - never write when the device is unsupported or the permission is missing.
 *
 * The controller owns the lifecycle/observer wiring; this class owns the state machine.
 */
class SystemNavigationEnforcer(
    private val gateway: SecureSettingsGateway,
    private val deviceSupported: () -> Boolean,
    private val permissionGranted: () -> Boolean,
) {
    @Volatile private var enforcing = false
    private var baselineForce: Int? = null
    private var baselineHide: Int? = null
    private var capturedBaseline = false

    /** Whether enforcement is currently active (captured baseline + should be enforcing). */
    val isActive: Boolean get() = enforcing

    /**
     * Begin enforcing the desired state. Captures the baseline values once. Returns true if
     * enforcement started (or was already active), false if a precondition fails (unsupported
     * device or no permission). Idempotent: safe to call when already enforcing (re-asserts).
     */
    fun startEnforcing(tag: String = "enable"): Boolean {
        if (!deviceSupported() || !permissionGranted()) return false
        if (!enforcing) {
            enforcing = true
            captureBaseline()
        }
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
     * before enforcement). Clears the baseline so the next enable captures fresh values.
     */
    fun stopEnforcing(tag: String = "disable") {
        if (!enforcing) return
        restoreBaseline(tag)
        enforcing = false
        capturedBaseline = false
        baselineForce = null
        baselineHide = null
    }

    private fun captureBaseline() {
        if (capturedBaseline) return
        baselineForce = readOrNull(SystemNavigationController.KEY_FORCE_FSG_NAV_BAR)
        baselineHide = readOrNull(SystemNavigationController.KEY_HIDE_GESTURE_LINE)
        capturedBaseline = true
        // Captured baseline: force=$baselineForce hide=$baselineHide
    }

    private fun enforceNow(tag: String) {
        writeIfNotOne(SystemNavigationController.KEY_FORCE_FSG_NAV_BAR, "$tag:force")
        writeIfNotOne(SystemNavigationController.KEY_HIDE_GESTURE_LINE, "$tag:hide")
    }

    private fun restoreBaseline(tag: String) {
        restoreKey(SystemNavigationController.KEY_FORCE_FSG_NAV_BAR, baselineForce, "$tag:force")
        restoreKey(SystemNavigationController.KEY_HIDE_GESTURE_LINE, baselineHide, "$tag:hide")
    }

    private fun writeIfNotOne(key: String, tag: String) {
        if (readOrNull(key) == 1) return
        try {
            gateway.putInt(key, 1)
        } catch (_: SecurityException) {
            // No WRITE_SECURE_SETTINGS — leave the system value as-is.
        } catch (_: Throwable) {
            // Other failure — leave the system value as-is.
        }
    }

    private fun restoreKey(key: String, baseline: Int?, tag: String) {
        try {
            if (baseline == null) gateway.delete(key)
            else gateway.putInt(key, baseline)
        } catch (_: SecurityException) {
            // No WRITE_SECURE_SETTINGS — cannot restore.
        } catch (_: Throwable) {
            // Other failure — cannot restore.
        }
    }

    private fun readOrNull(key: String): Int? = try {
        gateway.getIntOrNull(key)
    } catch (_: Throwable) {
        null
    }
}
