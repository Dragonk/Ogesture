package com.ogesture.data

enum class GestureAction { BACK, HOME, RECENTS }

enum class ZoneId { BOTTOM, LEFT_EDGE, RIGHT_EDGE }

enum class SwipeDirection { UP, RIGHT, LEFT }

data class ZoneConfig(
    val id: ZoneId,
    val action: GestureAction,
    val longAction: GestureAction?,
    val lengthPercent: Int,
    val thicknessDp: Int,
) {
    val swipeDirection: SwipeDirection get() = when (id) {
        ZoneId.BOTTOM -> SwipeDirection.UP
        ZoneId.LEFT_EDGE -> SwipeDirection.RIGHT
        ZoneId.RIGHT_EDGE -> SwipeDirection.LEFT
    }
}

/**
 * The user-tunable geometry of the gesture activation zones. Only physical dimensions are
 * configurable; the action/direction mapping (Back/Home/Recents, swipe directions) is fixed
 * and never derived from these settings.
 *
 * - [backActivationHeightPercent] / [bottomActivationWidthPercent] describe how much of the
 *   relevant edge can *start* a gesture (10%..100%).
 * - [backEdgeSensitivity] / [bottomEdgeSensitivity] are multipliers on the *base* zone
 *   thickness (how far inward from the physical edge a touch may begin). They do NOT change
 *   the swipe-distance threshold — only the starting hit-region depth.
 *
 * Side Back zones are anchored toward the bottom of the screen (measured upward from just
 * above the reserved bottom gesture band), not vertically centered.
 */
data class GestureZoneSettings(
    val backActivationHeightPercent: Int,
    val bottomActivationWidthPercent: Int,
    val backEdgeSensitivity: Float,
    val bottomEdgeSensitivity: Float,
) {
    /** Effective Back zone thickness in dp, before the nav-bar inset is added. */
    val backThicknessDp: Int get() = (BASE_BACK_THICKNESS_DP * backEdgeSensitivity).toInt().coerceAtLeast(1)

    /** Effective bottom zone thickness in dp, before the nav-bar inset is added. */
    val bottomThicknessDp: Int get() = (BASE_BOTTOM_THICKNESS_DP * bottomEdgeSensitivity).toInt().coerceAtLeast(1)

    companion object {
        // Base (1.0×) gesture-zone thicknesses. Sensitivity multiplies these; the nav-bar
        // inset is added afterward and is never multiplied.
        const val BASE_BACK_THICKNESS_DP = 16
        const val BASE_BOTTOM_THICKNESS_DP = 12

        // Activation-area percentage bounds (10%..100%, 10% step).
        const val PERCENT_MIN = 10
        const val PERCENT_MAX = 100
        const val PERCENT_STEP = 10
        const val DEFAULT_BACK_HEIGHT_PERCENT = 80
        const val DEFAULT_BOTTOM_WIDTH_PERCENT = 80

        // Edge-sensitivity multiplier bounds (0.5×..4.0×, 0.25× step).
        const val SENSITIVITY_MIN = 0.5f
        const val SENSITIVITY_MAX = 4.0f
        const val SENSITIVITY_STEP = 0.25f
        const val DEFAULT_BACK_SENSITIVITY = 1.0f
        const val DEFAULT_BOTTOM_SENSITIVITY = 1.0f

        val DEFAULT = GestureZoneSettings(
            backActivationHeightPercent = DEFAULT_BACK_HEIGHT_PERCENT,
            bottomActivationWidthPercent = DEFAULT_BOTTOM_WIDTH_PERCENT,
            backEdgeSensitivity = DEFAULT_BACK_SENSITIVITY,
            bottomEdgeSensitivity = DEFAULT_BOTTOM_SENSITIVITY,
        )

        /** Clamps a raw persisted percentage to the valid range. */
        fun clampPercent(value: Int): Int =
            value.coerceIn(PERCENT_MIN, PERCENT_MAX)

        /** Rounds a raw persisted sensitivity to the nearest 0.25 step and clamps it. */
        fun clampSensitivity(value: Float): Float {
            val stepped = (value / SENSITIVITY_STEP).toInt().let { steps ->
                (value - steps * SENSITIVITY_STEP).let { remainder ->
                    if (remainder >= SENSITIVITY_STEP / 2f) steps + 1 else steps
                }
            }
            return (stepped * SENSITIVITY_STEP).coerceIn(SENSITIVITY_MIN, SENSITIVITY_MAX)
        }
    }
}

/**
 * Builds the runtime [ZoneConfig] list from the user's [settings]. Action/direction mapping
 * is static; only geometry (length percent, thickness) comes from the settings.
 */
fun buildGestureZones(settings: GestureZoneSettings): List<ZoneConfig> = listOf(
    ZoneConfig(
        id = ZoneId.BOTTOM,
        action = GestureAction.HOME,
        longAction = GestureAction.RECENTS,
        lengthPercent = settings.bottomActivationWidthPercent,
        thicknessDp = settings.bottomThicknessDp,
    ),
    ZoneConfig(
        id = ZoneId.LEFT_EDGE,
        action = GestureAction.BACK,
        longAction = null,
        lengthPercent = settings.backActivationHeightPercent,
        thicknessDp = settings.backThicknessDp,
    ),
    ZoneConfig(
        id = ZoneId.RIGHT_EDGE,
        action = GestureAction.BACK,
        longAction = null,
        lengthPercent = settings.backActivationHeightPercent,
        thicknessDp = settings.backThicknessDp,
    ),
)

/**
 * The fixed default gesture layout: swipe up from the bottom for Home (hold for Recents),
 * swipe in from either side edge for Back. Kept as the default-configuration source and for
 * tests that assert the static action/direction mapping.
 */
val GESTURE_ZONES: List<ZoneConfig> = buildGestureZones(GestureZoneSettings.DEFAULT)

/**
 * Maps a bottom activation-width percentage to the Home handle's visual width in dp. The
 * handle stays a compact navigation bar (not a giant screen-wide strip): at the default 80%
 * it is [DEFAULT_HANDLE_WIDTH_DP] (108dp), scaling linearly with the percentage, floored at
 * [MIN_HANDLE_WIDTH_DP] and capped at [MAX_HANDLE_WIDTH_DP].
 *
 * 10% → 24dp, 50% → ~67.5dp, 80% → 108dp, 100% → 135dp.
 */
fun homeHandleWidthDp(bottomActivationWidthPercent: Int): Int {
    val raw = DEFAULT_HANDLE_WIDTH_DP * bottomActivationWidthPercent.toFloat() / GestureZoneSettings.DEFAULT_BOTTOM_WIDTH_PERCENT
    return raw.toInt().coerceIn(MIN_HANDLE_WIDTH_DP, MAX_HANDLE_WIDTH_DP)
}

private const val DEFAULT_HANDLE_WIDTH_DP = 108
private const val MIN_HANDLE_WIDTH_DP = 24
private const val MAX_HANDLE_WIDTH_DP = 135
