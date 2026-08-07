package com.ogesture.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** An installed app the user can turn Ogesture off for. */
data class AppEntry(val packageName: String, val label: String)

/**
 * Launchable apps on this phone, for the compatibility screen's picker. Ogesture ships no
 * per-app claims: the user adds apps they have hit dead edge-taps in themselves (apps
 * whose touch security ignores accessibility-injected taps — verified for e.g. Swiggy).
 * Visibility comes from the manifest's <queries> launcher-intent declaration, so this
 * sees launchable apps only — no QUERY_ALL_PACKAGES.
 */
fun installedLaunchableApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0L))
    } else {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(launcher, 0)
    }
    return resolved
        .mapNotNull { it.activityInfo?.applicationInfo }
        .distinctBy { it.packageName }
        .filter { it.packageName != context.packageName }
        .map { AppEntry(it.packageName, it.loadLabel(pm).toString()) }
        .sortedBy { it.label.lowercase() }
}

/** Label for a stored package, falling back to the raw package name if it's gone. */
fun appLabel(context: Context, packageName: String): String = try {
    val pm = context.packageManager
    pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
} catch (_: PackageManager.NameNotFoundException) {
    packageName
}
