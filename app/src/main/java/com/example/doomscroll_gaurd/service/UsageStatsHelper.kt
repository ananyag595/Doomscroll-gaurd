package com.example.doomscroll_gaurd.service

import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log

/**
 * Helper for querying the currently foregrounded app via [UsageStatsManager].
 *
 * Uses a short 3-second look-back window to reliably identify the foreground
 * package on API 26+. The window is intentionally small to avoid returning
 * stale data from apps that were recently in the foreground but are no longer.
 */
object UsageStatsHelper {

    private const val TAG = "UsageStatsHelper"

    /**
     * Returns the package name of the app currently in the foreground, or
     * `null` if the query fails (e.g. [SecurityException] due to revoked
     * [android.Manifest.permission.PACKAGE_USAGE_STATS]).
     *
     * @param context Any valid [Context].
     * @param nowMs   Current time in milliseconds (typically [System.currentTimeMillis]).
     */
    fun getForegroundPackage(context: Context, nowMs: Long): String? {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            // Query a 3-second window ending at nowMs to catch the current foreground app.
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                nowMs - 3_000L,
                nowMs
            )
            if (stats.isNullOrEmpty()) return null

            // Return the package with the most recent lastTimeUsed timestamp.
            stats.maxByOrNull { it.lastTimeUsed }?.packageName
        } catch (e: SecurityException) {
            Log.w(TAG, "PACKAGE_USAGE_STATS revoked — cannot query foreground app: ${e.message}")
            null
        }
    }
}
