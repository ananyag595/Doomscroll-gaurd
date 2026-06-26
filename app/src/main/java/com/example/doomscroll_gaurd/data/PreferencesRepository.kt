package com.example.doomscroll_gaurd.data

/**
 * Repository interface for persisting user configuration.
 *
 * V1 methods cover redirect app selection.
 * V2 methods are stubs that return defaults — they are design-only and not yet configurable via UI.
 */
interface PreferencesRepository {

    // --- V1: Redirect app selection ---

    /** Returns the set of package names the user has selected as redirect apps. */
    fun getRedirectApps(): Set<String>

    /** Persists the given set of package names as the user's redirect app selection. */
    fun setRedirectApps(packages: Set<String>)

    // --- V2 stubs (design only — return defaults, not yet user-configurable) ---

    /** V2: Returns the configured grace period in milliseconds. Defaults to 3 minutes. */
    fun getGracePeriodMs(): Long = 3 * 60 * 1000L

    /** V2: Returns the configured session limit in milliseconds. Defaults to 15 minutes. */
    fun getSessionLimitMs(): Long = 15 * 60 * 1000L

    /** V2: Returns the configured extension duration in milliseconds. Defaults to 5 minutes. */
    fun getExtensionMs(): Long = 5 * 60 * 1000L
}
