package com.example.doomscroll_gaurd.domain

import android.graphics.drawable.Drawable

/**
 * Represents an app that the user has configured as a redirect alternative.
 *
 * Instances are constructed at display time from [android.content.pm.PackageManager];
 * only the [packageName] is persisted in SharedPreferences.
 *
 * @param packageName The app's unique package identifier (e.g. "com.spotify.music").
 * @param label       The human-readable app name resolved from PackageManager.
 * @param icon        The app's launcher icon resolved from PackageManager.
 */
data class RedirectApp(
    val packageName: String,
    val label: String,
    val icon: Drawable,
)
