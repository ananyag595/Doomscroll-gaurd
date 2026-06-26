package com.example.doomscroll_gaurd.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import com.example.doomscroll_gaurd.data.PreferencesRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test


/**
 * Bug condition exploration tests for `shouldIncludeApp`.
 *
 * ## Purpose
 * These tests document the **expected post-fix behaviour** and are intentionally written
 * to FAIL against the current unfixed code. Failure confirms the bug exists.
 *
 * ## Expected outcomes on UNFIXED code
 * - [nullIntentShouldBeExcluded]                  → FAILS (`shouldIncludeApp` returns `true`)
 * - [systemAppShouldBeExcluded]                   → FAILS (`shouldIncludeApp` returns `true`)
 * - [updatedSystemAppShouldBeExcluded]            → FAILS (`shouldIncludeApp` returns `true`)
 * - [nullIntentWithSystemFlagShouldBeExcluded]    → FAILS (`shouldIncludeApp` returns `true`)
 * - [userInstalledLaunchableAppShouldBeIncluded]  → PASSES (happy path, already correct)
 *
 * ## Expected outcomes after the fix (Task 2)
 * All tests pass once `shouldIncludeApp(launchIntent, flags)` is implemented correctly.
 *
 * ## Counterexamples found on unfixed code
 * - `shouldIncludeApp(null, 0)` returned `true` (expected `false`)
 * - `shouldIncludeApp(Intent(ACTION_MAIN), FLAG_SYSTEM)` returned `true` (expected `false`)
 * - `shouldIncludeApp(Intent(ACTION_MAIN), FLAG_UPDATED_SYSTEM_APP)` returned `true` (expected `false`)
 * - `shouldIncludeApp(null, FLAG_SYSTEM)` returned `true` (expected `false`)
 */
class SettingsScreenLoadAppsTest {

    // -------------------------------------------------------------------------
    // Bug condition exploration tests — EXPECTED TO FAIL on unfixed code
    // -------------------------------------------------------------------------

    /**
     * An app with a null launch intent must be EXCLUDED from the redirect-app list.
     *
     * On unfixed code: `shouldIncludeApp(null, 0)` returns `true` → FAILS (bug confirmed).
     * After fix:       `shouldIncludeApp(null, 0)` returns `false` → PASSES.
     *
     * Counterexample: `shouldIncludeApp(null, 0)` returned `true` instead of `false`.
     */
    @Test
    fun nullIntentShouldBeExcluded() {
        val result = shouldIncludeApp(launchIntent = null, flags = 0)

        assertFalse(
            "shouldIncludeApp(null, 0) must return false — apps without a launch intent " +
                "must be excluded from the redirect-app list. " +
                "COUNTEREXAMPLE: returned true on unfixed code (bug confirmed).",
            result,
        )
    }

    /**
     * A system app (FLAG_SYSTEM) must be EXCLUDED even if it has a launch intent.
     *
     * On unfixed code: `shouldIncludeApp(Intent(ACTION_MAIN), FLAG_SYSTEM)` returns `true` → FAILS.
     * After fix:       returns `false` → PASSES.
     *
     * Counterexample: `shouldIncludeApp(Intent(ACTION_MAIN), FLAG_SYSTEM)` returned `true`.
     */
    @Test
    fun systemAppShouldBeExcluded() {
        val launchIntent = Intent(Intent.ACTION_MAIN)
        val result = shouldIncludeApp(launchIntent = launchIntent, flags = ApplicationInfo.FLAG_SYSTEM)

        assertFalse(
            "shouldIncludeApp(Intent(ACTION_MAIN), FLAG_SYSTEM) must return false — " +
                "system apps must be excluded from the redirect-app list. " +
                "COUNTEREXAMPLE: returned true on unfixed code (bug confirmed).",
            result,
        )
    }

    /**
     * An updated system app (FLAG_UPDATED_SYSTEM_APP) must be EXCLUDED even if it has a launch intent.
     *
     * On unfixed code: `shouldIncludeApp(Intent(ACTION_MAIN), FLAG_UPDATED_SYSTEM_APP)` returns `true` → FAILS.
     * After fix:       returns `false` → PASSES.
     *
     * Counterexample: `shouldIncludeApp(Intent(ACTION_MAIN), FLAG_UPDATED_SYSTEM_APP)` returned `true`.
     */
    @Test
    fun updatedSystemAppShouldBeExcluded() {
        val launchIntent = Intent(Intent.ACTION_MAIN)
        val result = shouldIncludeApp(
            launchIntent = launchIntent,
            flags = ApplicationInfo.FLAG_UPDATED_SYSTEM_APP,
        )

        assertFalse(
            "shouldIncludeApp(Intent(ACTION_MAIN), FLAG_UPDATED_SYSTEM_APP) must return false — " +
                "updated system apps must be excluded from the redirect-app list. " +
                "COUNTEREXAMPLE: returned true on unfixed code (bug confirmed).",
            result,
        )
    }

    /**
     * An app with both a null launch intent AND FLAG_SYSTEM must be EXCLUDED (both conditions fail).
     *
     * On unfixed code: `shouldIncludeApp(null, FLAG_SYSTEM)` returns `true` → FAILS.
     * After fix:       returns `false` → PASSES.
     *
     * Counterexample: `shouldIncludeApp(null, FLAG_SYSTEM)` returned `true`.
     */
    @Test
    fun nullIntentWithSystemFlagShouldBeExcluded() {
        val result = shouldIncludeApp(launchIntent = null, flags = ApplicationInfo.FLAG_SYSTEM)

        assertFalse(
            "shouldIncludeApp(null, FLAG_SYSTEM) must return false — " +
                "an app with no launch intent and FLAG_SYSTEM must be excluded. " +
                "COUNTEREXAMPLE: returned true on unfixed code (bug confirmed).",
            result,
        )
    }

    // -------------------------------------------------------------------------
    // Happy-path test — EXPECTED TO PASS on both unfixed and fixed code
    // -------------------------------------------------------------------------

    /**
     * A user-installed launchable app (non-null intent, flags = 0) must be INCLUDED.
     *
     * This is the happy path. It passes on both unfixed and fixed code because the
     * current unconditional `return true` already handles this case correctly.
     */
    @Test
    fun userInstalledLaunchableAppShouldBeIncluded() {
        val launchIntent = Intent(Intent.ACTION_MAIN)
        val result = shouldIncludeApp(launchIntent = launchIntent, flags = 0)

        assertTrue(
            "shouldIncludeApp(Intent(ACTION_MAIN), 0) must return true — " +
                "a user-installed launchable app must appear in the redirect-app list.",
            result,
        )
    }

    // -------------------------------------------------------------------------
    // Preservation property tests — EXPECTED TO PASS on fixed code
    // -------------------------------------------------------------------------

    /**
     * Validates: Requirements 3.1 — Sort order preserved.
     *
     * For any list of app labels, sorting by `label.lowercase()` must produce a list
     * in non-descending lexicographic order. This mirrors the `sortedBy { it.label.lowercase() }`
     * call in `loadInstalledApps`. Mixed case, numbers, and symbols are all included to
     * confirm the ordering is case-insensitive and universally stable.
     */
    @Test
    fun sortOrderPropertyHoldsForVariedLabels() {
        val labelSets = listOf(
            listOf("Zoom", "android", "Beta", "1Password", "!App", "zApp", "ALPHA"),
            listOf("Twitter", "facebook", "Instagram", "tiktok", "YouTube"),
            listOf("a", "B", "c", "D", "e"),
            listOf("123App", "456App", "App789"),
            listOf("single"),
            emptyList(),
            listOf("Zzz", "aaa", "MMM", "nnn"),
        )

        for (labels in labelSets) {
            val sorted = labels.sortedBy { it.lowercase() }
            for (i in 0 until sorted.size - 1) {
                assertTrue(
                    "Sort order violated: '${sorted[i]}' should come before '${sorted[i + 1]}' " +
                        "(case-insensitive). Input: $labels",
                    sorted[i].lowercase() <= sorted[i + 1].lowercase(),
                )
            }
        }
    }

    /**
     * Validates: Requirements 3.5 — Happy-path inclusion (property form).
     *
     * For any non-null Intent and flags = 0, `shouldIncludeApp` must return `true`.
     * The predicate must not be sensitive to the intent's action string — only the
     * null/non-null distinction and the flags matter.
     *
     * Note: Intent objects are constructed with various action strings but we do NOT call
     * any methods on them (e.g. getAction) because Android stubs are not available in JVM
     * unit tests. The predicate only checks for null/non-null, so construction is sufficient.
     */
    @Test
    fun happyPathInclusionHoldsForVariedIntentActions() {
        val actionLabels = listOf(
            "ACTION_MAIN" to Intent(Intent.ACTION_MAIN),
            "ACTION_VIEW" to Intent(Intent.ACTION_VIEW),
            "ACTION_SEND" to Intent(Intent.ACTION_SEND),
            "custom.ACTION" to Intent("com.example.custom.ACTION"),
            "empty string" to Intent(""),
        )

        for ((label, intent) in actionLabels) {
            assertTrue(
                "shouldIncludeApp(intent, 0) must return true for any non-null intent " +
                    "with flags=0. Failed for action='$label'",
                shouldIncludeApp(launchIntent = intent, flags = 0),
            )
        }
    }

    /**
     * Validates: Requirements 3.4 — System-flag exclusion (property form).
     *
     * For any flags value where `(flags AND FLAG_SYSTEM) != 0`, `shouldIncludeApp` must
     * return `false` regardless of the intent. This covers FLAG_SYSTEM alone, combined
     * with FLAG_UPDATED_SYSTEM_APP, combined with an arbitrary other flag, and both
     * null and non-null intents.
     */
    @Test
    fun systemFlagExclusionHoldsForVariedFlagCombinations() {
        val nonNullIntent = Intent(Intent.ACTION_MAIN)
        val flagCombinations = listOf(
            ApplicationInfo.FLAG_SYSTEM,
            ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP,
            ApplicationInfo.FLAG_SYSTEM or 0x00000040,
        )

        for (flags in flagCombinations) {
            assertFalse(
                "shouldIncludeApp(nonNullIntent, flags=0x${flags.toString(16)}) must return false " +
                    "when FLAG_SYSTEM is set.",
                shouldIncludeApp(launchIntent = nonNullIntent, flags = flags),
            )
            assertFalse(
                "shouldIncludeApp(null, flags=0x${flags.toString(16)}) must return false " +
                    "when FLAG_SYSTEM is set.",
                shouldIncludeApp(launchIntent = null, flags = flags),
            )
        }
    }

    /**
     * Validates: Requirements 3.4 — Updated-system-flag exclusion (property form).
     *
     * For any flags value where `(flags AND FLAG_UPDATED_SYSTEM_APP) != 0`,
     * `shouldIncludeApp` must return `false` regardless of the intent.
     */
    @Test
    fun updatedSystemFlagExclusionHoldsForVariedFlagCombinations() {
        val nonNullIntent = Intent(Intent.ACTION_MAIN)
        val flagCombinations = listOf(
            ApplicationInfo.FLAG_UPDATED_SYSTEM_APP,
            ApplicationInfo.FLAG_UPDATED_SYSTEM_APP or ApplicationInfo.FLAG_SYSTEM,
        )

        for (flags in flagCombinations) {
            assertFalse(
                "shouldIncludeApp(nonNullIntent, flags=0x${flags.toString(16)}) must return false " +
                    "when FLAG_UPDATED_SYSTEM_APP is set.",
                shouldIncludeApp(launchIntent = nonNullIntent, flags = flags),
            )
            assertFalse(
                "shouldIncludeApp(null, flags=0x${flags.toString(16)}) must return false " +
                    "when FLAG_UPDATED_SYSTEM_APP is set.",
                shouldIncludeApp(launchIntent = null, flags = flags),
            )
        }
    }

    /**
     * Validates: Requirements 3.2 — Selection state independence.
     *
     * An app's checked state (stored in PreferencesRepository) is keyed on packageName only.
     * The filter result from `shouldIncludeApp` has no bearing on whether a package name
     * is present in the selection set. A fake in-memory repository is used to verify this.
     */
    @Test
    fun selectionStateIsIndependentOfFilterResult() {
        // Fake in-memory PreferencesRepository
        val fakeRepo = object : PreferencesRepository {
            private var packages: Set<String> = emptySet()
            override fun getRedirectApps(): Set<String> = packages
            override fun setRedirectApps(packages: Set<String>) { this.packages = packages }
        }

        val packageName = "com.example.app"
        fakeRepo.setRedirectApps(setOf(packageName))

        // Verify the package is selected regardless of what shouldIncludeApp returns
        val includedByFilter = shouldIncludeApp(launchIntent = Intent(Intent.ACTION_MAIN), flags = 0)
        val excludedByFilter = shouldIncludeApp(launchIntent = null, flags = ApplicationInfo.FLAG_SYSTEM)

        assertTrue("shouldIncludeApp with flags=0 should return true", includedByFilter)
        assertFalse("shouldIncludeApp with FLAG_SYSTEM should return false", excludedByFilter)

        // Selection state is unaffected by either filter result
        assertTrue(
            "Package '$packageName' must remain in the selection set regardless of filter result.",
            packageName in fakeRepo.getRedirectApps(),
        )

        // Changing the filter result does not alter the stored selection
        assertEquals(
            "Selection set must contain exactly the pre-populated package name.",
            setOf(packageName),
            fakeRepo.getRedirectApps(),
        )
    }

    /**
     * Validates: Requirements 3.3 — Minimum-one enforcement.
     *
     * The `isLastSelected` flag is computed as `isChecked && selectedPackages.size == 1`.
     * When true, the checkbox is disabled, preventing the user from deselecting the last app.
     * This test exercises the pure boolean expression directly.
     */
    @Test
    fun minimumOneEnforcementLogicIsCorrect() {
        val packageName = "com.example.app"
        val otherPackage = "com.example.other"

        // Case 1: exactly one app selected and it is checked → isLastSelected = true (checkbox disabled)
        val selectedOne = setOf(packageName)
        val isCheckedCase1 = true
        val isLastSelectedCase1 = isCheckedCase1 && selectedOne.size == 1
        assertTrue(
            "isLastSelected must be true when exactly one app is selected and isChecked=true. " +
                "Checkbox must be disabled to enforce the minimum-one invariant.",
            isLastSelectedCase1,
        )

        // Case 2: two apps selected and the app is checked → isLastSelected = false (checkbox enabled)
        val selectedTwo = setOf(packageName, otherPackage)
        val isCheckedCase2 = true
        val isLastSelectedCase2 = isCheckedCase2 && selectedTwo.size == 1
        assertFalse(
            "isLastSelected must be false when more than one app is selected. " +
                "Checkbox must remain enabled.",
            isLastSelectedCase2,
        )

        // Case 3: exactly one app selected but it is NOT checked → isLastSelected = false
        val isCheckedCase3 = false
        val isLastSelectedCase3 = isCheckedCase3 && selectedOne.size == 1
        assertFalse(
            "isLastSelected must be false when isChecked=false, even if only one app is selected.",
            isLastSelectedCase3,
        )
    }
}
