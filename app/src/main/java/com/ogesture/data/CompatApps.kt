package com.ogesture.data

import android.content.Context
import android.content.pm.PackageManager

/** An app known (or very likely) to ignore the simulated taps Ogesture replays. */
data class KnownApp(
    val packageName: String,
    /** Shown if the package manager can't resolve a label. */
    val fallbackLabel: String,
)

/**
 * Apps whose touch-security ignores taps injected through the accessibility service, so
 * taps landing on a gesture zone never reach them. Swipe gestures still work; only the
 * tap pass-through dies.
 *
 * VERIFIED ENTRIES ONLY — never add an app on suspicion (e.g. "it's a payments app").
 * To verify: with gestures on, tap a known-clickable element inside a zone band and
 * confirm the replay logs "completed=true" while the app ignores it, then confirm a
 * shell-injected tap ("adb shell input tap") at the same point does work. PhonePe, for
 * example, handles replayed taps fine and must not be listed. Each package here must
 * also be declared in the manifest's <queries> block, or the package manager can't see
 * it on API 30+.
 */
val KNOWN_FILTERING_APPS = listOf(
    // Verified 2026-08-05 on Android 16: drops accessibility-injected taps everywhere.
    KnownApp("in.swiggy.android", "Swiggy"),
)

/**
 * Package names from [KNOWN_FILTERING_APPS]. Pass-through only honors exclusions for
 * these, so an exclusion left behind by an app later removed from the list can't keep
 * silently disabling gestures with no switch left to undo it.
 */
val KNOWN_FILTERING_PACKAGES: Set<String> =
    KNOWN_FILTERING_APPS.mapTo(hashSetOf()) { it.packageName }

/** The subset of [KNOWN_FILTERING_APPS] installed on this phone, with resolved labels. */
fun installedKnownApps(context: Context): List<KnownApp> {
    val pm = context.packageManager
    return KNOWN_FILTERING_APPS.mapNotNull { app ->
        try {
            val info = pm.getApplicationInfo(app.packageName, 0)
            app.copy(fallbackLabel = info.loadLabel(pm).toString())
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
}
