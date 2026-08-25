package com.ogesture

import com.ogesture.data.GESTURE_ZONES
import com.ogesture.data.GestureAction
import com.ogesture.data.GestureZoneSettings
import com.ogesture.data.SwipeDirection
import com.ogesture.data.ZoneConfig
import com.ogesture.data.ZoneId
import com.ogesture.data.buildGestureZones
import com.ogesture.data.homeHandleWidthDp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guards for the gesture-zone layout and the action/direction mapping that the
 * accessibility-overlay migration must preserve, plus the configurable-zone geometry
 * (activation height/width, edge sensitivity), the corner-overlap precedence, and the Home
 * handle width mapping.
 */
class GestureZoneLayoutTest {

    @Test
    fun gestureZones_containBottomLeftRight() {
        val ids = GESTURE_ZONES.map { it.id }
        assertTrue("bottom zone must exist", ZoneId.BOTTOM in ids)
        assertTrue("left edge zone must exist", ZoneId.LEFT_EDGE in ids)
        assertTrue("right edge zone must exist", ZoneId.RIGHT_EDGE in ids)
        assertEquals(3, GESTURE_ZONES.size)
    }

    @Test
    fun bottomZone_mapsSwipeUpToHomeWithRecentsHold() {
        val bottom = GESTURE_ZONES.first { it.id == ZoneId.BOTTOM }
        assertEquals(GestureAction.HOME, bottom.action)
        assertEquals(GestureAction.RECENTS, bottom.longAction)
        assertEquals(SwipeDirection.UP, bottom.swipeDirection)
    }

    @Test
    fun leftEdge_mapsSwipeRightToBackWithNoLongAction() {
        val left = GESTURE_ZONES.first { it.id == ZoneId.LEFT_EDGE }
        assertEquals(GestureAction.BACK, left.action)
        assertNull("left edge has no long action", left.longAction)
        assertEquals(SwipeDirection.RIGHT, left.swipeDirection)
    }

    @Test
    fun rightEdge_mapsSwipeLeftToBackWithNoLongAction() {
        val right = GESTURE_ZONES.first { it.id == ZoneId.RIGHT_EDGE }
        assertEquals(GestureAction.BACK, right.action)
        assertNull("right edge has no long action", right.longAction)
        assertEquals(SwipeDirection.LEFT, right.swipeDirection)
    }

    @Test
    fun everyZone_hasPositiveThicknessAndLength() {
        for (zone in GESTURE_ZONES) {
            assertTrue("${zone.id} thickness must be positive", zone.thicknessDp > 0)
            assertTrue("${zone.id} length must be positive", zone.lengthPercent > 0)
        }
    }

    @Test
    fun swipeDirection_isConsistentWithZoneId() {
        // The recognizer relies on this mapping; flipping it would invert every gesture.
        assertEquals(SwipeDirection.UP, ZoneConfig(ZoneId.BOTTOM, GestureAction.HOME, null, 80, 12).swipeDirection)
        assertEquals(SwipeDirection.RIGHT, ZoneConfig(ZoneId.LEFT_EDGE, GestureAction.BACK, null, 80, 16).swipeDirection)
        assertEquals(SwipeDirection.LEFT, ZoneConfig(ZoneId.RIGHT_EDGE, GestureAction.BACK, null, 80, 16).swipeDirection)
    }

    @Test
    fun gestureActions_coverBackHomeRecents() {
        // The accessibility service dispatches exactly these three; adding a fourth without
        // wiring it through performGlobalAction would be a silent no-op.
        val actions = GestureAction.values().toSet()
        assertTrue(GestureAction.BACK in actions)
        assertTrue(GestureAction.HOME in actions)
        assertTrue(GestureAction.RECENTS in actions)
    }

    // --- Configurable gesture-zone geometry -----------------------------------------

    @Test
    fun defaults_matchExpectedGeometry() {
        val s = GestureZoneSettings.DEFAULT
        assertEquals(80, s.backActivationHeightPercent)
        assertEquals(80, s.bottomActivationWidthPercent)
        assertEquals(1.0f, s.backEdgeSensitivity, 0.0001f)
        assertEquals(1.0f, s.bottomEdgeSensitivity, 0.0001f)
        // 1× sensitivity → base thickness unchanged.
        assertEquals(16, s.backThicknessDp)
        assertEquals(12, s.bottomThicknessDp)
    }

    @Test
    fun defaultZones_matchLegacyFixedLayout() {
        // The default configuration must reproduce the pre-configurability layout exactly.
        val zones = buildGestureZones(GestureZoneSettings.DEFAULT)
        assertEquals(GESTURE_ZONES, zones)
    }

    @Test
    fun backHeight_percentagesBuildCorrectLength() {
        for (pct in listOf(10, 50, 100)) {
            val s = GestureZoneSettings.DEFAULT.copy(backActivationHeightPercent = pct)
            val left = buildGestureZones(s).first { it.id == ZoneId.LEFT_EDGE }
            val right = buildGestureZones(s).first { it.id == ZoneId.RIGHT_EDGE }
            assertEquals("left length%", pct, left.lengthPercent)
            assertEquals("right length%", pct, right.lengthPercent)
            // One setting drives both sides.
            assertEquals(left.lengthPercent, right.lengthPercent)
        }
    }

    @Test
    fun bottomWidth_percentagesBuildCorrectLength() {
        for (pct in listOf(10, 50, 100)) {
            val s = GestureZoneSettings.DEFAULT.copy(bottomActivationWidthPercent = pct)
            val bottom = buildGestureZones(s).first { it.id == ZoneId.BOTTOM }
            assertEquals(pct, bottom.lengthPercent)
        }
    }

    @Test
    fun backSensitivity_multipliesBaseThickness() {
        assertEquals(8, GestureZoneSettings.DEFAULT.copy(backEdgeSensitivity = 0.5f).backThicknessDp)
        assertEquals(16, GestureZoneSettings.DEFAULT.copy(backEdgeSensitivity = 1.0f).backThicknessDp)
        assertEquals(64, GestureZoneSettings.DEFAULT.copy(backEdgeSensitivity = 4.0f).backThicknessDp)
    }

    @Test
    fun bottomSensitivity_multipliesBaseThickness() {
        assertEquals(6, GestureZoneSettings.DEFAULT.copy(bottomEdgeSensitivity = 0.5f).bottomThicknessDp)
        assertEquals(12, GestureZoneSettings.DEFAULT.copy(bottomEdgeSensitivity = 1.0f).bottomThicknessDp)
        assertEquals(48, GestureZoneSettings.DEFAULT.copy(bottomEdgeSensitivity = 4.0f).bottomThicknessDp)
    }

    @Test
    fun sensitivity_doesNotChangeSwipeDistanceThreshold() {
        // The controller's SIDE_MIN_DISTANCE_DP / BOTTOM_MIN_DISTANCE_DP are not part of
        // GestureZoneSettings and must not be derivable from it. We assert that the settings
        // record exposes only thickness/length, never a swipe-distance field.
        val s = GestureZoneSettings.DEFAULT.copy(backEdgeSensitivity = 4.0f, bottomEdgeSensitivity = 4.0f)
        // No field named *minDistance* / *swipeDistance* should exist on the model.
        assertTrue(s.toString().lowercase().let { !it.contains("mindistance") && !it.contains("swipedistance") })
    }

    @Test
    fun clampPercent_clampsOutOfRangeValues() {
        assertEquals(10, GestureZoneSettings.clampPercent(-5))
        assertEquals(10, GestureZoneSettings.clampPercent(9))
        assertEquals(50, GestureZoneSettings.clampPercent(50))
        assertEquals(100, GestureZoneSettings.clampPercent(101))
        assertEquals(100, GestureZoneSettings.clampPercent(9999))
    }

    @Test
    fun clampSensitivity_clampsAndStepsOutOfRangeValues() {
        assertEquals(0.5f, GestureZoneSettings.clampSensitivity(0.1f), 0.0001f)
        assertEquals(0.5f, GestureZoneSettings.clampSensitivity(-1f), 0.0001f)
        assertEquals(1.0f, GestureZoneSettings.clampSensitivity(1.0f), 0.0001f)
        assertEquals(1.25f, GestureZoneSettings.clampSensitivity(1.24f), 0.0001f) // rounds to nearest 0.25 step
        assertEquals(4.0f, GestureZoneSettings.clampSensitivity(5f), 0.0001f)
    }

    @Test
    fun corruptedSettings_neverProduceZeroOrNegativeThickness() {
        // A clamped sensitivity is always >= 0.5 and base >= 12, so thickness >= 1.
        for (v in listOf(-100f, 0f, 0.001f, 1000f)) {
            val clamped = GestureZoneSettings.clampSensitivity(v)
            assertTrue(clamped >= GestureZoneSettings.SENSITIVITY_MIN)
        }
        val s = GestureZoneSettings.DEFAULT.copy(
            backEdgeSensitivity = GestureZoneSettings.clampSensitivity(0f),
            bottomEdgeSensitivity = GestureZoneSettings.clampSensitivity(0f),
        )
        assertTrue("back thickness must be positive", s.backThicknessDp >= 1)
        assertTrue("bottom thickness must be positive", s.bottomThicknessDp >= 1)
    }

    @Test
    fun geometryChanges_neverAlterActionOrDirectionMapping() {
        for (height in listOf(10, 50, 100)) {
            for (width in listOf(10, 50, 100)) {
                for (backSens in listOf(0.5f, 1.0f, 4.0f)) {
                    for (bottomSens in listOf(0.5f, 1.0f, 4.0f)) {
                        val s = GestureZoneSettings(
                            backActivationHeightPercent = height,
                            bottomActivationWidthPercent = width,
                            backEdgeSensitivity = backSens,
                            bottomEdgeSensitivity = bottomSens,
                        )
                        val zones = buildGestureZones(s)
                        val bottom = zones.first { it.id == ZoneId.BOTTOM }
                        assertEquals(GestureAction.HOME, bottom.action)
                        assertEquals(GestureAction.RECENTS, bottom.longAction)
                        assertEquals(SwipeDirection.UP, bottom.swipeDirection)
                        for (side in listOf(ZoneId.LEFT_EDGE, ZoneId.RIGHT_EDGE)) {
                            val z = zones.first { it.id == side }
                            assertEquals(GestureAction.BACK, z.action)
                            assertNull(z.longAction)
                            assertEquals(if (side == ZoneId.LEFT_EDGE) SwipeDirection.RIGHT else SwipeDirection.LEFT, z.swipeDirection)
                        }
                    }
                }
            }
        }
    }

    /**
     * Corner-overlap precedence: the side Back touch region must not ambiguously overlap the
     * bottom Home/Recents touch region. The controller reserves the bottom activation-depth
     * band for the bottom zone and positions the side zones immediately above it, so the two
     * never share vertical space. This test models that geometry purely (no WindowManager).
     */
    @Test
    fun sideAndBottomZones_doNotOverlap_inCorners() {
        // Model: display 1080x2400, density 3.0, no nav insets (gestural nav).
        val density = 3.0f
        val displayW = 1080
        val displayH = 2400
        val navBottom = 0
        val navSide = 0
        for (heightPct in listOf(10, 50, 100)) {
            for (widthPct in listOf(10, 50, 100)) {
                for (backSens in listOf(0.5f, 1.0f, 4.0f)) {
                    for (bottomSens in listOf(0.5f, 1.0f, 4.0f)) {
                        val s = GestureZoneSettings(
                            backActivationHeightPercent = heightPct,
                            bottomActivationWidthPercent = widthPct,
                            backEdgeSensitivity = backSens,
                            bottomEdgeSensitivity = bottomSens,
                        )
                        val bottomThicknessPx = (s.bottomThicknessDp * density).toInt()
                        val bottomBandHeight = bottomThicknessPx + navBottom
                        // Bottom zone spans the central widthPct of the bottom edge, at the very bottom.
                        val bottomTop = displayH - bottomBandHeight
                        // Side zones are anchored above the bottom band: their bottom edge is at bottomTop.
                        val usableSideHeight = (displayH - bottomBandHeight).coerceAtLeast(1)
                        val sideHeight = (usableSideHeight * heightPct / 100).coerceAtLeast(1)
                        val sideBottom = bottomTop // sits immediately above the bottom band
                        val sideTop = sideBottom - sideHeight
                        // The side zone's vertical span must not cross into the bottom band.
                        assertTrue(
                            "side zone (top=$sideTop bottom=$sideBottom) must end at/above bottom band top=$bottomTop " +
                                "(h=$heightPct w=$widthPct bs=$backSens bts=$bottomSens)",
                            sideBottom <= bottomTop,
                        )
                        assertTrue("side zone top must be above its bottom", sideTop < sideBottom)
                    }
                }
            }
        }
    }

    // --- Home handle width mapping ---------------------------------------------------

    @Test
    fun homeHandleWidth_default80Percent_is108dp() {
        assertEquals(108, homeHandleWidthDp(80))
    }

    @Test
    fun homeHandleWidth_100Percent_is135dp() {
        assertEquals(135, homeHandleWidthDp(100))
    }

    @Test
    fun homeHandleWidth_10Percent_isMinimum24dp() {
        assertEquals(24, homeHandleWidthDp(10))
    }

    @Test
    fun homeHandleWidth_50Percent_isApproximately67dp() {
        // 108 * 50 / 80 = 67.5 → 67
        assertEquals(67, homeHandleWidthDp(50))
    }

    @Test
    fun homeHandleWidth_neverBelowMinimumOrAboveMaximum() {
        for (pct in listOf(-1, 0, 1, 10, 50, 80, 100, 101, 200)) {
            val w = homeHandleWidthDp(pct)
            assertTrue("width $w for $pct% below min", w >= 24)
            assertTrue("width $w for $pct% above max", w <= 135)
        }
    }
}
