package com.ogesture.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure enforcement logic of the system-navigation watchdog
 * ([SystemNavigationEnforcer]) and the device-support detection. No real Android
 * SettingsProvider/Context is needed — a [FakeGateway] stands in for Settings.Global.
 */
class SystemNavigationEnforcerTest {

    /** In-memory Settings.Global stand-in. null = absent key. */
    private class FakeGateway : SecureSettingsGateway {
        val store = mutableMapOf<String, Int>()
        val writes = mutableListOf<Pair<String, Int>>()
        val deletes = mutableListOf<String>()
        var denyWrites = false // simulates missing WRITE_SECURE_SETTINGS

        override fun getIntOrNull(key: String): Int? = store[key]
        override fun putInt(key: String, value: Int) {
            if (denyWrites) throw SecurityException("no permission")
            store[key] = value
            writes += key to value
        }
        override fun delete(key: String) {
            if (denyWrites) throw SecurityException("no permission")
            store.remove(key)
            deletes += key
        }
    }

    private fun enforcer(
        fake: FakeGateway,
        supported: Boolean = true,
        granted: Boolean = true,
    ) = SystemNavigationEnforcer(
        gateway = fake,
        deviceSupported = { supported },
        permissionGranted = { granted },
    )

    private val FORCE = SystemNavigationController.KEY_FORCE_FSG_NAV_BAR
    private val HIDE = SystemNavigationController.KEY_HIDE_GESTURE_LINE

    @Test
    fun isXiaomiEcosystemDevice_recognizesXiaomiRedmiPoco() {
        assertTrue(SystemNavigationController.isXiaomiEcosystemDevice(manufacturer = "Xiaomi", brand = "xiaomi"))
        assertTrue(SystemNavigationController.isXiaomiEcosystemDevice(manufacturer = "Xiaomi", brand = "redmi"))
        assertTrue(SystemNavigationController.isXiaomiEcosystemDevice(manufacturer = "redmi", brand = "redmi"))
        assertTrue(SystemNavigationController.isXiaomiEcosystemDevice(manufacturer = "poco", brand = "POCO"))
        assertTrue(SystemNavigationController.isXiaomiEcosystemDevice(manufacturer = "XIAOMI", brand = "XIAOMI"))
    }

    @Test
    fun isXiaomiEcosystemDevice_rejectsOtherOems() {
        assertFalse(SystemNavigationController.isXiaomiEcosystemDevice(manufacturer = "Samsung", brand = "samsung"))
        assertFalse(SystemNavigationController.isXiaomiEcosystemDevice(manufacturer = "Google", brand = "google"))
        assertFalse(SystemNavigationController.isXiaomiEcosystemDevice(manufacturer = "OnePlus", brand = "OnePlus"))
    }

    @Test
    fun unsupportedDevice_startEnforcing_doesNothing() {
        val fake = FakeGateway()
        val e = enforcer(fake, supported = false)
        assertFalse(e.startEnforcing())
        assertFalse(e.isActive)
        assertTrue(fake.writes.isEmpty())
    }

    @Test
    fun permissionMissing_startEnforcing_doesNothing_noCrash() {
        val fake = FakeGateway()
        fake.denyWrites = true
        val e = enforcer(fake, supported = true, granted = false)
        assertFalse(e.startEnforcing())
        assertFalse(e.isActive)
        assertTrue(fake.writes.isEmpty())
    }

    @Test
    fun startEnforcing_writesKeysToOne() {
        val fake = FakeGateway()
        val e = enforcer(fake)
        e.startEnforcing()
        assertEquals(1, fake.store[FORCE])
        assertEquals(1, fake.store[HIDE])
    }

    @Test
    fun alreadyOne_noRedundantWrites() {
        val fake = FakeGateway().apply {
            store[FORCE] = 1; store[HIDE] = 1
        }
        val e = enforcer(fake)
        e.startEnforcing()
        // No writes — both already 1, so no redundant write (avoids observer write loop).
        assertTrue(fake.writes.isEmpty())
    }

    @Test
    fun forceChangesToZero_isRestoredToOne() {
        val fake = FakeGateway().apply { store[FORCE] = 1; store[HIDE] = 1 }
        val e = enforcer(fake)
        e.startEnforcing()
        fake.writes.clear()
        // HyperOS resets force_fsg_nav_bar to 0.
        fake.store[FORCE] = 0
        e.reassert(tag = "observer")
        assertEquals(1, fake.store[FORCE])
        assertEquals(FORCE to 1, fake.writes.last())
    }

    @Test
    fun hideChangesToZero_isRestoredToOne() {
        val fake = FakeGateway().apply { store[FORCE] = 1; store[HIDE] = 1 }
        val e = enforcer(fake)
        e.startEnforcing()
        fake.writes.clear()
        fake.store[HIDE] = 0
        e.reassert(tag = "observer")
        assertEquals(1, fake.store[HIDE])
        assertEquals(HIDE to 1, fake.writes.last())
    }

    @Test
    fun bothChangeToZero_bothRestoredToOne() {
        val fake = FakeGateway().apply { store[FORCE] = 1; store[HIDE] = 1 }
        val e = enforcer(fake)
        e.startEnforcing()
        fake.writes.clear()
        fake.store[FORCE] = 0
        fake.store[HIDE] = 0
        e.reassert(tag = "observer")
        assertEquals(1, fake.store[FORCE])
        assertEquals(1, fake.store[HIDE])
        assertEquals(2, fake.writes.size)
    }

    @Test
    fun baselineCapturedOnlyOncePerEnableLifecycle() {
        val fake = FakeGateway().apply { store[FORCE] = 0; store[HIDE] = 0 }
        val e = enforcer(fake)
        e.startEnforcing()
        // After enforcement, values are 1. A reassert should NOT re-capture 1 as the baseline.
        fake.store[FORCE] = 0
        fake.store[HIDE] = 0
        e.reassert(tag = "observer")
        // Now disable — the baseline should still be the original 0, not the 1 we enforced.
        e.stopEnforcing()
        // Restored to original 0 (baseline was 0, captured once at the start).
        assertEquals(0, fake.store[FORCE])
        assertEquals(0, fake.store[HIDE])
    }

    @Test
    fun disablingRestoresOriginalNonZeroValues() {
        val fake = FakeGateway().apply { store[FORCE] = 2; store[HIDE] = 0 }
        val e = enforcer(fake)
        e.startEnforcing()
        assertEquals(1, fake.store[FORCE])
        assertEquals(1, fake.store[HIDE])
        e.stopEnforcing()
        // Restore the actual previous values: 2 and 0 (not blindly 0).
        assertEquals(2, fake.store[FORCE])
        assertEquals(0, fake.store[HIDE])
    }

    @Test
    fun nullOriginalValuesHandledOnRestore_byDeleting() {
        val fake = FakeGateway() // both keys absent
        val e = enforcer(fake)
        e.startEnforcing()
        assertEquals(1, fake.store[FORCE])
        assertEquals(1, fake.store[HIDE])
        e.stopEnforcing()
        // Keys were absent before — restore deletes them (returns to absent).
        assertNull(fake.store[FORCE])
        assertNull(fake.store[HIDE])
        assertTrue(fake.deletes.contains(FORCE))
        assertTrue(fake.deletes.contains(HIDE))
    }

    @Test
    fun reEnforceAfterStop_capturesFreshBaseline() {
        val fake = FakeGateway().apply { store[FORCE] = 0; store[HIDE] = 0 }
        val e = enforcer(fake)
        e.startEnforcing()
        e.stopEnforcing()
        // Baseline is now cleared; change the underlying values and re-enable.
        fake.store[FORCE] = 3
        fake.store[HIDE] = 4
        e.startEnforcing()
        assertEquals(1, fake.store[FORCE])
        assertEquals(1, fake.store[HIDE])
        e.stopEnforcing()
        // Fresh baseline of 3/4 restored.
        assertEquals(3, fake.store[FORCE])
        assertEquals(4, fake.store[HIDE])
    }

    @Test
    fun stopEnforcing_isNoOpWhenNotEnforcing() {
        val fake = FakeGateway()
        val e = enforcer(fake)
        // Never started — stop should be a no-op, no crash, no writes.
        e.stopEnforcing()
        assertTrue(fake.writes.isEmpty())
        assertTrue(fake.deletes.isEmpty())
    }

    @Test
    fun startEnforcing_isIdempotent_doesNotRecaptureBaseline() {
        val fake = FakeGateway().apply { store[FORCE] = 0; store[HIDE] = 0 }
        val e = enforcer(fake)
        e.startEnforcing()
        val writesAfterStart = fake.writes.size
        e.startEnforcing() // already enforcing — should not recapture, should re-assert
        // Re-assert writes nothing new because values are now 1.
        assertEquals(writesAfterStart, fake.writes.size)
    }

    @Test
    fun permissionRevokedMidEnforcement_writesFailSafely_noCrash() {
        val fake = FakeGateway()
        val e = enforcer(fake, granted = true)
        e.startEnforcing()
        assertEquals(1, fake.store[FORCE])
        // HyperOS resets, and the permission was revoked.
        fake.store[FORCE] = 0
        fake.denyWrites = true
        e.reassert(tag = "observer")
        // The write threw SecurityException, caught — no crash, value stays 0 (system's value).
        assertEquals(0, fake.store[FORCE])
    }
}
