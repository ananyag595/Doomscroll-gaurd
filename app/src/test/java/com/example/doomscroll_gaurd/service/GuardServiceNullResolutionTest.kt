package com.example.doomscroll_gaurd.service

import com.example.doomscroll_gaurd.domain.SessionState
import com.example.doomscroll_gaurd.domain.SessionStateMachine
import io.kotest.core.spec.style.StringSpec
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.string
import io.kotest.property.forAll
import org.junit.runner.RunWith

// ---------------------------------------------------------------------------
// Helper: the current (unfixed) resolution expression extracted for testing
// ---------------------------------------------------------------------------

/**
 * Mirrors the unfixed resolution logic in GuardService.poll():
 *   val instagramInForeground = foregroundPkg == INSTAGRAM_PACKAGE
 *
 * When foregroundPkg is null, Kotlin evaluates null == "com.instagram.android" as false.
 * This is the root cause of the bug.
 */
fun resolveInstagramForeground_original(foregroundPkg: String?): Boolean =
    foregroundPkg == "com.instagram.android"

// ---------------------------------------------------------------------------
// Bug condition exploration tests
//
// CRITICAL: These tests are EXPECTED TO FAIL on unfixed code.
// Failure is the SUCCESS case — it proves the bug exists.
// DO NOT fix the test or the production code when these tests fail.
// ---------------------------------------------------------------------------

/**
 * Property-based bug condition exploration test.
 *
 * Validates: Requirements 1.1, 1.2
 *
 * Bug Condition: foregroundPkg = null AND lastConfirmed = true
 *
 * This test asserts the EXPECTED (correct) behavior:
 *   resolveInstagramForeground_original(null) == true
 *
 * On UNFIXED code this FAILS because null == "com.instagram.android" evaluates to false.
 * The failure IS the counterexample — it proves the bug exists.
 */
@RunWith(io.kotest.runner.junit4.KotestTestRunner::class)
class GuardServiceNullResolutionTest : StringSpec({

    // Property 1: Bug Condition — Null Result Preserves Confirmed Foreground State
    // Validates: Requirements 1.1, 1.2
    "Property 1: null foregroundPkg with lastConfirmed=true should resolve to true (bug condition)" {
        // Scope the property to the concrete failing case:
        //   foregroundPkg = null, lastConfirmed = true
        // This is deterministic and directly encodes isBugCondition(X) = true.
        forAll(
            PropTestConfig(iterations = 10),
            Arb.constant(null as String?),  // foregroundPkg is always null
        ) { foregroundPkg ->
            // lastConfirmed = true (Instagram was last confirmed in foreground)
            // Expected (correct) behavior: resolving null should return true (use cached state)
            // Fixed behavior: resolveInstagramForeground(null, true) returns true (cache forwarded)
            resolveInstagramForeground(foregroundPkg, lastConfirmed = true) == true
        }
    }

    // SessionStateMachine-level bug condition test
    // Validates: Requirements 1.1, 1.2
    "SessionStateMachine stays Active after tick(true) simulating fixed null→true conversion" {
        // Drive the machine to ACTIVE:
        //   tick(true, 0L)    → IDLE → ACTIVE
        //   tick(true, 1000L) → ACTIVE (1 second accumulated)
        val machine = SessionStateMachine()
        machine.tick(instagramInForeground = true, nowMs = 0L)
        machine.tick(instagramInForeground = true, nowMs = 1000L)

        // Precondition: machine must be in ACTIVE
        val stateBeforeBug = machine.state.value
        check(stateBeforeBug is SessionState.Active) {
            "Precondition failed: expected Active, got $stateBeforeBug"
        }

        // Simulate the fixed null→true conversion:
        //   getForegroundPackage returns null
        //   fixed code: resolveInstagramForeground(null, lastConfirmed=true) → true
        //   so tick(true, 2000L) is called (cache forwarded)
        val resolvedValue = resolveInstagramForeground(foregroundPkg = null, lastConfirmed = true)
        machine.tick(instagramInForeground = resolvedValue, nowMs = 2000L)

        // Expected (correct) behavior: state should still be Active
        //   (null during active Instagram session should NOT start grace period)
        val stateAfterFix = machine.state.value
        assert(stateAfterFix is SessionState.Active) {
            "Expected Active after null poll with fix applied (null→true), but got $stateAfterFix"
        }
    }

    // ---------------------------------------------------------------------------
    // Preservation tests (Property 2)
    //
    // These tests verify that non-buggy poll cycles are unaffected by the fix.
    // They MUST PASS on UNFIXED code — passing confirms the baseline behavior to preserve.
    // ---------------------------------------------------------------------------

    // Property 2: Preservation — Non-Buggy Poll Cycles Are Unaffected
    // Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6
    //
    // For all non-null foregroundPkg values, the fixed resolution logic
    // (resolveInstagramForeground(foregroundPkg, lastConfirmed)) must equal
    // (foregroundPkg == "com.instagram.android") — identical to the original.
    // This property captures the full non-null domain.
    "Property 2: for all non-null foregroundPkg, fixed resolution equals (foregroundPkg == INSTAGRAM_PACKAGE)" {
        // Validates: Requirements 3.1, 3.2, 3.3, 3.5
        forAll(
            PropTestConfig(iterations = 1000),
            Arb.string(),  // generates arbitrary non-null String values
        ) { foregroundPkg ->
            // foregroundPkg is non-null (Arb.string() never generates null)
            // The fixed resolution must equal the direct equality check for non-null inputs.
            // lastConfirmed value is irrelevant for non-null foregroundPkg (cache not consulted).
            resolveInstagramForeground(foregroundPkg, lastConfirmed = true) == (foregroundPkg == "com.instagram.android") &&
            resolveInstagramForeground(foregroundPkg, lastConfirmed = false) == (foregroundPkg == "com.instagram.android")
        }
    }

    // Preservation: null with lastConfirmed=false is NOT a bug condition
    // Validates: Requirements 3.1, 3.3, 3.6
    //
    // When foregroundPkg is null AND lastConfirmed is false, both the original and fixed
    // code return false. This is NOT a bug condition (isBugCondition requires lastConfirmed=true).
    // Asserting false here confirms the baseline that must be preserved.
    "Property 2: null foregroundPkg with lastConfirmed=false resolves to false (not a bug condition)" {
        // Validates: Requirements 3.1, 3.3, 3.6
        // lastConfirmed = false means Instagram was NOT last confirmed in the foreground.
        // Both original and fixed code agree: null → false in this case.
        val result = resolveInstagramForeground(null, lastConfirmed = false)
        assert(result == false) {
            "Expected false for null foregroundPkg with lastConfirmed=false, but got $result"
        }
    }

    // Preservation: SessionStateMachine non-null poll cycles produce correct state transitions
    // Validates: Requirements 3.1, 3.2, 3.3, 3.5
    //
    // Drive the machine to ACTIVE via tick(true), then tick with a non-null non-Instagram
    // package and assert the state transitions to GRACE correctly (real app switch preserved).
    "SessionStateMachine transitions ACTIVE→GRACE correctly for non-null non-Instagram package (preservation)" {
        // Validates: Requirements 3.1, 3.3
        val machine = SessionStateMachine()

        // Drive to ACTIVE
        machine.tick(instagramInForeground = true, nowMs = 0L)
        machine.tick(instagramInForeground = true, nowMs = 1000L)

        val stateBeforeSwitch = machine.state.value
        check(stateBeforeSwitch is SessionState.Active) {
            "Precondition failed: expected Active, got $stateBeforeSwitch"
        }

        // Simulate a real app switch: non-null non-Instagram package (e.g. Chrome)
        // resolveInstagramForeground("com.android.chrome", lastConfirmed=true) == false
        val chromeResolution = resolveInstagramForeground("com.android.chrome", lastConfirmed = true)
        assert(chromeResolution == false) {
            "Expected false for Chrome package, got $chromeResolution"
        }

        machine.tick(instagramInForeground = chromeResolution, nowMs = 2000L)

        // A real app switch MUST transition ACTIVE → GRACE (preservation of existing behavior)
        val stateAfterSwitch = machine.state.value
        assert(stateAfterSwitch is SessionState.Grace) {
            "Expected Grace after real app switch (non-null non-Instagram package), but got $stateAfterSwitch"
        }
    }
})
