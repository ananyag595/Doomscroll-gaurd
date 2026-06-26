package com.example.doomscroll_gaurd.data

import android.content.Context
import android.content.SharedPreferences

/**
 * [PreferencesRepository] implementation backed by [SharedPreferences].
 *
 * All data is stored in a single file named "doomscroll_prefs".
 */
class SharedPreferencesRepository(context: Context) : PreferencesRepository {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    override fun getRedirectApps(): Set<String> {
        return prefs.getStringSet(KEY_REDIRECT_APPS, emptySet()) ?: emptySet()
    }

    override fun setRedirectApps(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_REDIRECT_APPS, packages).apply()
    }

    // V2 stubs — read from prefs if present, otherwise return design defaults.
    // These keys are not yet written by any UI; they exist for future configurability.

    override fun getGracePeriodMs(): Long =
        prefs.getLong(KEY_GRACE_PERIOD_MS, DEFAULT_GRACE_PERIOD_MS)

    override fun getSessionLimitMs(): Long =
        prefs.getLong(KEY_SESSION_LIMIT_MS, DEFAULT_SESSION_LIMIT_MS)

    override fun getExtensionMs(): Long =
        prefs.getLong(KEY_EXTENSION_MS, DEFAULT_EXTENSION_MS)

    companion object {
        private const val PREFS_FILE = "doomscroll_prefs"

        private const val KEY_REDIRECT_APPS = "redirect_apps"
        private const val KEY_GRACE_PERIOD_MS = "grace_period_ms"
        private const val KEY_SESSION_LIMIT_MS = "session_limit_ms"
        private const val KEY_EXTENSION_MS = "extension_ms"

        private const val DEFAULT_GRACE_PERIOD_MS = 3 * 60 * 1000L   // 3 minutes
        private const val DEFAULT_SESSION_LIMIT_MS = 15 * 60 * 1000L  // 15 minutes
        private const val DEFAULT_EXTENSION_MS = 5 * 60 * 1000L       // 5 minutes
    }
}
