package com.example.doomscroll_gaurd.domain

import io.kotest.core.spec.style.StringSpec
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.enum
import io.kotest.property.forAll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Example-based unit tests for [SessionStateMachine] state transitions.
 *
 * Each test covers exactly one transition arc from the state diagram.
 * All tests run on the JVM — no Android runtime, emulator, or device required.
 *
 * Defaults used throughout (matching [SessionStateMachine] constructor defaults):
 *   sessionLimitMs = 15 * 60 * 1_000  (15 minutes)
 *   gracePeriodMs  =  3 * 60 * 1_000  ( 3 minutes)
 *   extensionMs    =  5 * 60 * 1_000  ( 5 minutes)
 */
class SessionStateMachineTest {

    // Short, deterministic limits make tests fast and readable.
    private val sessionLimitMs = 15 * 60 * 1_000L   // 15 min
    private val gracePeriodMs  =  3 * 60 * 1_000L   //  3 min
    private val extensionMs    =  5 * 60 * 1_000L   //  5 min

    private lateinit var machine: SessionStateMachine

    @Before
    fun setUp() {
        machine = SessionStateMachine(
            sessionLimitMs = sessionLimitMs,
            gracePeriodMs  = gracePeriodMs,
            extensionMs    = extensionMs,
        )
    }

    // -------------------------------------------------------------------------
    // Helper: drive the machine into ACTIVE with a given elapsed time
    // -------------------------------------------------------------------------

    /**
     * Puts the machine into [SessionState.Active] with [elapsedMs] already accumulated.
     *
     * Strategy: tick with Instagram in foreground at t=0 (IDLE→ACTIVE), then tick again
     * at t=elapsedMs so the delta is recorded as the accumulated time.
     */
    private fun driveToActive(elapsedMs: Long): SessionState.Active {
        machine.tick(instagramInForeground = true, nowMs = 0L)          // IDLE → ACTIVE
        machine.tick(instagramInForeground = true, nowMs = elapsedMs)   // accumulate time
        return machine.state.value as SessionState.Active
    }

    /**
     * Puts the machine into [SessionState.Triggered] by accumulating exactly
     * [sessionLimitMs] of foreground time.
     */
    private fun driveToTriggered(): SessionState.Triggered {
        machine.tick(instagramInForeground = true, nowMs = 0L)
        machine.tick(instagramInForeground = true, nowMs = sessionLimitMs)
        return machine.state.value as SessionState.Triggered
    }

    // =========================================================================
    // Transition arc: IDLE → ACTIVE
    // =========================================================================

    @Test
    fun `IDLE to ACTIVE when Instagram enters foreground`() {
        // Precondition: machine starts in IDLE
        assertTrue(machine.state.value is SessionState.Idle)

        machine.tick(instagramInForeground = true, nowMs = 1_000L)

        val state = machine.state.value
        assertTrue("Expected Active, got $state", state is SessionState.Active)
        assertEquals(0L, (state as SessionState.Active).elapsedMs)
    }

    @Test
    fun `IDLE stays IDLE when Instagram is not in foreground`() {
        machine.tick(instagramInForeground = false, nowMs = 1_000L)

        assertTrue(machine.state.value is SessionState.Idle)
    }

    // =========================================================================
    // Transition arc: ACTIVE → GRACE
    // =========================================================================

    @Test
    fun `ACTIVE to GRACE when Instagram leaves foreground`() {
        val elapsed = 2 * 60 * 1_000L   // 2 minutes accumulated
        driveToActive(elapsed)

        val graceStartMs = elapsed + 1_000L
        machine.tick(instagramInForeground = false, nowMs = graceStartMs)

        val state = machine.state.value
        assertTrue("Expected Grace, got $state", state is SessionState.Grace)
        val grace = state as SessionState.Grace
        assertEquals(elapsed, grace.elapsedMs)
        assertEquals(graceStartMs, grace.graceStartMs)
    }

    // =========================================================================
    // Transition arc: GRACE → ACTIVE (Instagram returns within grace window)
    // =========================================================================

    @Test
    fun `GRACE to ACTIVE when Instagram returns within grace window`() {
        val elapsed = 2 * 60 * 1_000L
        driveToActive(elapsed)

        val graceStartMs = elapsed + 1_000L
        machine.tick(instagramInForeground = false, nowMs = graceStartMs)   // → GRACE

        // Return within grace window (1 minute into the 3-minute window)
        val returnMs = graceStartMs + 60_000L
        machine.tick(instagramInForeground = true, nowMs = returnMs)        // → ACTIVE

        val state = machine.state.value
        assertTrue("Expected Active, got $state", state is SessionState.Active)
        // Elapsed time must be preserved — timer was paused, not reset
        assertEquals(elapsed, (state as SessionState.Active).elapsedMs)
    }

    @Test
    fun `GRACE to ACTIVE preserves elapsed session time`() {
        val elapsed = 5 * 60 * 1_000L   // 5 minutes
        driveToActive(elapsed)

        val graceStartMs = elapsed + 500L
        machine.tick(instagramInForeground = false, nowMs = graceStartMs)

        // Return just before grace expires
        val returnMs = graceStartMs + gracePeriodMs - 1_000L
        machine.tick(instagramInForeground = true, nowMs = returnMs)

        val state = machine.state.value as SessionState.Active
        assertEquals(
            "Elapsed time must be preserved across GRACE round-trip",
            elapsed,
            state.elapsedMs,
        )
    }

    // =========================================================================
    // Transition arc: GRACE → IDLE (grace period expires)
    // =========================================================================

    @Test
    fun `GRACE to IDLE when grace period expires`() {
        val elapsed = 2 * 60 * 1_000L
        driveToActive(elapsed)

        val graceStartMs = elapsed + 1_000L
        machine.tick(instagramInForeground = false, nowMs = graceStartMs)   // → GRACE

        // Advance past the full grace period
        val afterGraceMs = graceStartMs + gracePeriodMs + 1_000L
        machine.tick(instagramInForeground = false, nowMs = afterGraceMs)   // → IDLE

        assertTrue("Expected Idle, got ${machine.state.value}", machine.state.value is SessionState.Idle)
        assertEquals(0L, machine.elapsedSessionMs.value)
    }

    // Boundary: grace period expires at exactly gracePeriodMs (not one ms over)
    @Test
    fun `GRACE to IDLE at exactly gracePeriodMs boundary`() {
        val elapsed = 1 * 60 * 1_000L
        driveToActive(elapsed)

        val graceStartMs = elapsed + 1_000L
        machine.tick(instagramInForeground = false, nowMs = graceStartMs)   // → GRACE

        // Tick at exactly the grace boundary
        val exactBoundaryMs = graceStartMs + gracePeriodMs
        machine.tick(instagramInForeground = false, nowMs = exactBoundaryMs)

        assertTrue(
            "Expected Idle at exact grace boundary, got ${machine.state.value}",
            machine.state.value is SessionState.Idle,
        )
        assertEquals(0L, machine.elapsedSessionMs.value)
    }

    // =========================================================================
    // Transition arc: ACTIVE → TRIGGERED (session limit reached)
    // =========================================================================

    @Test
    fun `ACTIVE to TRIGGERED when session limit is reached`() {
        machine.tick(instagramInForeground = true, nowMs = 0L)   // IDLE → ACTIVE

        // Tick at a time that pushes elapsed past the session limit
        val overLimitMs = sessionLimitMs + 1_000L
        machine.tick(instagramInForeground = true, nowMs = overLimitMs)

        val state = machine.state.value
        assertTrue("Expected Triggered, got $state", state is SessionState.Triggered)
        val triggered = state as SessionState.Triggered
        assertTrue(triggered.elapsedMs >= sessionLimitMs)
        assertEquals(false, triggered.extensionUsed)
    }

    // Boundary: elapsed time exactly equals sessionLimitMs
    @Test
    fun `ACTIVE to TRIGGERED at exactly sessionLimitMs boundary`() {
        machine.tick(instagramInForeground = true, nowMs = 0L)
        machine.tick(instagramInForeground = true, nowMs = sessionLimitMs)

        val state = machine.state.value
        assertTrue(
            "Expected Triggered at exact session limit, got $state",
            state is SessionState.Triggered,
        )
        assertEquals(false, (state as SessionState.Triggered).extensionUsed)
    }

    // =========================================================================
    // Transition arc: TRIGGERED → EXTENDED (purposeful reason selected)
    // =========================================================================

    @Test
    fun `TRIGGERED to EXTENDED when user selects purposeful reason MESSAGING`() {
        driveToTriggered()

        machine.onUserAction(UserAction.PurposefulReason(Reason.MESSAGING))

        val state = machine.state.value
        assertTrue("Expected Extended, got $state", state is SessionState.Extended)
    }

    @Test
    fun `TRIGGERED to EXTENDED when user selects purposeful reason WORK_RELATED`() {
        driveToTriggered()

        machine.onUserAction(UserAction.PurposefulReason(Reason.WORK_RELATED))

        assertTrue(machine.state.value is SessionState.Extended)
    }

    @Test
    fun `TRIGGERED to EXTENDED when user selects purposeful reason OTHER`() {
        driveToTriggered()

        machine.onUserAction(UserAction.PurposefulReason(Reason.OTHER))

        assertTrue(machine.state.value is SessionState.Extended)
    }

    @Test
    fun `TRIGGERED to EXTENDED preserves elapsed time`() {
        driveToTriggered()
        val elapsedBeforeAction = machine.elapsedSessionMs.value

        machine.onUserAction(UserAction.PurposefulReason(Reason.OTHER))

        val extended = machine.state.value as SessionState.Extended
        assertEquals(elapsedBeforeAction, extended.elapsedMs)
    }

    // =========================================================================
    // Transition arc: TRIGGERED stays TRIGGERED on "Just scrolling"
    // =========================================================================

    @Test
    fun `TRIGGERED stays TRIGGERED when user selects Just Scrolling`() {
        driveToTriggered()

        machine.onUserAction(UserAction.JustScrolling)

        val state = machine.state.value
        assertTrue("Expected Triggered, got $state", state is SessionState.Triggered)
        assertEquals(false, (state as SessionState.Triggered).extensionUsed)
    }

    @Test
    fun `TRIGGERED stays TRIGGERED and extensionUsed remains false after Just Scrolling`() {
        driveToTriggered()
        val elapsedBefore = machine.elapsedSessionMs.value

        machine.onUserAction(UserAction.JustScrolling)

        val state = machine.state.value as SessionState.Triggered
        assertEquals(false, state.extensionUsed)
        // Elapsed time is also unchanged
        assertEquals(elapsedBefore, state.elapsedMs)
    }

    // =========================================================================
    // Transition arc: EXTENDED → TRIGGERED (extension expires)
    // =========================================================================

    @Test
    fun `EXTENDED to TRIGGERED when extension expires`() {
        driveToTriggered()
        machine.onUserAction(UserAction.PurposefulReason(Reason.MESSAGING))

        val extended = machine.state.value as SessionState.Extended
        val afterExtensionMs = extended.extensionStartMs + extensionMs + 1_000L

        machine.tick(instagramInForeground = true, nowMs = afterExtensionMs)

        val state = machine.state.value
        assertTrue("Expected Triggered, got $state", state is SessionState.Triggered)
        assertEquals(true, (state as SessionState.Triggered).extensionUsed)
    }

    @Test
    fun `EXTENDED to TRIGGERED at exactly extensionMs boundary`() {
        driveToTriggered()
        machine.onUserAction(UserAction.PurposefulReason(Reason.MESSAGING))

        val extended = machine.state.value as SessionState.Extended
        val exactBoundaryMs = extended.extensionStartMs + extensionMs

        machine.tick(instagramInForeground = true, nowMs = exactBoundaryMs)

        val state = machine.state.value
        assertTrue(
            "Expected Triggered at exact extension boundary, got $state",
            state is SessionState.Triggered,
        )
        assertEquals(true, (state as SessionState.Triggered).extensionUsed)
    }

    @Test
    fun `EXTENDED stays EXTENDED before extension expires`() {
        driveToTriggered()
        machine.onUserAction(UserAction.PurposefulReason(Reason.MESSAGING))

        val extended = machine.state.value as SessionState.Extended
        // Tick at one second before the extension expires
        val beforeExpiryMs = extended.extensionStartMs + extensionMs - 1_000L

        machine.tick(instagramInForeground = true, nowMs = beforeExpiryMs)

        assertTrue(machine.state.value is SessionState.Extended)
    }

    // =========================================================================
    // Extension consumed: second PurposefulReason does NOT grant another extension
    // =========================================================================

    @Test
    fun `no second extension after extensionUsed is true`() {
        driveToTriggered()
        machine.onUserAction(UserAction.PurposefulReason(Reason.MESSAGING))   // → EXTENDED

        val extended = machine.state.value as SessionState.Extended
        val afterExtensionMs = extended.extensionStartMs + extensionMs + 1_000L
        machine.tick(instagramInForeground = true, nowMs = afterExtensionMs)  // → TRIGGERED (extensionUsed=true)

        // Attempt a second extension — must be ignored
        machine.onUserAction(UserAction.PurposefulReason(Reason.OTHER))

        val state = machine.state.value
        assertTrue(
            "Second extension must not be granted; expected Triggered, got $state",
            state is SessionState.Triggered,
        )
        assertEquals(true, (state as SessionState.Triggered).extensionUsed)
    }

    // =========================================================================
    // Reset from any state → IDLE with zero timer
    // =========================================================================

    @Test
    fun `reset from IDLE stays IDLE with zero timer`() {
        machine.reset()

        assertTrue(machine.state.value is SessionState.Idle)
        assertEquals(0L, machine.elapsedSessionMs.value)
    }

    @Test
    fun `reset from ACTIVE returns to IDLE with zero timer`() {
        driveToActive(5 * 60 * 1_000L)

        machine.reset()

        assertTrue(machine.state.value is SessionState.Idle)
        assertEquals(0L, machine.elapsedSessionMs.value)
    }

    @Test
    fun `reset from GRACE returns to IDLE with zero timer`() {
        driveToActive(2 * 60 * 1_000L)
        machine.tick(instagramInForeground = false, nowMs = 3 * 60 * 1_000L)   // → GRACE

        machine.reset()

        assertTrue(machine.state.value is SessionState.Idle)
        assertEquals(0L, machine.elapsedSessionMs.value)
    }

    @Test
    fun `reset from TRIGGERED returns to IDLE with zero timer`() {
        driveToTriggered()

        machine.reset()

        assertTrue(machine.state.value is SessionState.Idle)
        assertEquals(0L, machine.elapsedSessionMs.value)
    }

    @Test
    fun `reset from EXTENDED returns to IDLE with zero timer`() {
        driveToTriggered()
        machine.onUserAction(UserAction.PurposefulReason(Reason.MESSAGING))   // → EXTENDED

        machine.reset()

        assertTrue(machine.state.value is SessionState.Idle)
        assertEquals(0L, machine.elapsedSessionMs.value)
    }
}

// Feature: doomscroll-guard, Property 1: IDLE → ACTIVE on Instagram foreground
/**
 * Property-based tests for [SessionStateMachine].
 *
 * Validates: Requirements 3.2
 *
 * Runs on the JVM via Kotest's JUnit 4 runner — no Android runtime required.
 */
@RunWith(io.kotest.runner.junit4.KotestTestRunner::class)
class SessionStateMachinePropertyTest : StringSpec({

    "Property 1: IDLE → ACTIVE on Instagram foreground" {
        // Feature: doomscroll-guard, Property 1: IDLE → ACTIVE on Instagram foreground
        // Validates: Requirements 3.2
        forAll(PropTestConfig(iterations = 100), Arb.long()) { nowMs ->
            val machine = SessionStateMachine()
            // Precondition: machine starts in IDLE
            machine.state.value is SessionState.Idle &&
                run {
                    machine.tick(instagramInForeground = true, nowMs = nowMs)
                    machine.state.value is SessionState.Active
                }
        }
    }

    // Feature: doomscroll-guard, Property 2: ACTIVE → GRACE on Instagram background
    /**
     * Property 2: ACTIVE → GRACE on Instagram background
     *
     * Validates: Requirements 3.3
     *
     * For any SessionStateMachine in the ACTIVE state with any accumulated elapsed time
     * below the session limit, calling tick(instagramInForeground = false, nowMs) SHALL
     * transition the state to GRACE.
     */
    "Property 2: ACTIVE → GRACE on Instagram background" {
        // Feature: doomscroll-guard, Property 2: ACTIVE → GRACE on Instagram background
        // Validates: Requirements 3.3
        val sessionLimitMs = 15 * 60 * 1_000L
        // Generate elapsed times strictly below the session limit (0 .. sessionLimitMs - 1)
        val elapsedArb = Arb.long(0L, sessionLimitMs - 1L)
        // Generate arbitrary nowMs values for the background tick
        val nowArb = Arb.long()
        forAll(PropTestConfig(iterations = 100), elapsedArb, nowArb) { elapsedMs, nowMs ->
            val machine = SessionStateMachine(sessionLimitMs = sessionLimitMs)
            // Seed into ACTIVE with elapsedMs accumulated below the session limit
            machine.tick(instagramInForeground = true, nowMs = 0L)          // IDLE → ACTIVE
            machine.tick(instagramInForeground = true, nowMs = elapsedMs)   // accumulate time
            // Precondition: must be in ACTIVE state
            machine.state.value is SessionState.Active &&
                run {
                    // Instagram leaves the foreground
                    machine.tick(instagramInForeground = false, nowMs = nowMs)
                    machine.state.value is SessionState.Grace
                }
        }
    }

    // Feature: doomscroll-guard, Property 3: ACTIVE → GRACE → ACTIVE round-trip preserves elapsed time
    /**
     * Property 3: ACTIVE → GRACE → ACTIVE round-trip preserves elapsed time
     *
     * Validates: Requirements 3.3, 3.4
     *
     * For any SessionStateMachine that transitions from ACTIVE to GRACE and then back to
     * ACTIVE (Instagram returns within the grace window), the elapsedSessionMs at re-entry
     * to ACTIVE SHALL equal the elapsedSessionMs at the moment of leaving ACTIVE — the
     * timer is paused during grace, not reset.
     */
    "Property 3: ACTIVE → GRACE → ACTIVE round-trip preserves elapsed time" {
        // Feature: doomscroll-guard, Property 3: ACTIVE → GRACE → ACTIVE round-trip preserves elapsed time
        // Validates: Requirements 3.3, 3.4
        val sessionLimitMs = 15 * 60 * 1_000L
        val gracePeriodMs  =  3 * 60 * 1_000L

        // Elapsed time must be strictly below the session limit so the machine stays ACTIVE
        // (not TRIGGERED) when we accumulate it.
        val elapsedArb = Arb.long(0L, sessionLimitMs - 1L)

        // graceOffsetMs: how far into the grace window Instagram returns (must be < gracePeriodMs).
        // We use 1..gracePeriodMs-1 to guarantee the return is strictly within the window.
        val graceOffsetArb = Arb.long(1L, gracePeriodMs - 1L)

        forAll(
            PropTestConfig(iterations = 100),
            elapsedArb,
            graceOffsetArb,
        ) { elapsedMs, graceOffsetMs ->
            val machine = SessionStateMachine(
                sessionLimitMs = sessionLimitMs,
                gracePeriodMs  = gracePeriodMs,
            )

            // Step 1: IDLE → ACTIVE, accumulate elapsedMs
            machine.tick(instagramInForeground = true, nowMs = 0L)           // IDLE → ACTIVE
            machine.tick(instagramInForeground = true, nowMs = elapsedMs)    // accumulate time

            // Capture elapsed time at the moment Instagram leaves the foreground
            val elapsedAtDeparture = (machine.state.value as? SessionState.Active)?.elapsedMs
                ?: return@forAll false  // precondition: must be ACTIVE

            // Step 2: ACTIVE → GRACE (Instagram leaves foreground)
            val graceStartMs = elapsedMs + 1_000L
            machine.tick(instagramInForeground = false, nowMs = graceStartMs)

            if (machine.state.value !is SessionState.Grace) return@forAll false  // precondition

            // Step 3: GRACE → ACTIVE (Instagram returns within grace window)
            val returnMs = graceStartMs + graceOffsetMs
            machine.tick(instagramInForeground = true, nowMs = returnMs)

            // Assertion: elapsed time after re-entry must equal elapsed time at departure
            val stateAfterReturn = machine.state.value
            stateAfterReturn is SessionState.Active &&
                (stateAfterReturn as SessionState.Active).elapsedMs == elapsedAtDeparture
        }
    }
})

// Feature: doomscroll-guard, Property 4: GRACE → IDLE on grace period expiry resets timer
/**
 * Property 4: GRACE → IDLE on grace period expiry resets timer
 *
 * Validates: Requirements 3.5
 *
 * For any SessionStateMachine in the GRACE state, if the grace period elapses without
 * Instagram returning to the foreground, the state SHALL transition to IDLE and
 * elapsedSessionMs SHALL be reset to zero.
 */
@RunWith(io.kotest.runner.junit4.KotestTestRunner::class)
class SessionStateMachineProperty4Test : StringSpec({

    "Property 4: GRACE → IDLE on grace period expiry resets timer" {
        // Feature: doomscroll-guard, Property 4: GRACE → IDLE on grace period expiry resets timer
        // Validates: Requirements 3.5
        val sessionLimitMs = 15 * 60 * 1_000L
        val gracePeriodMs  =  3 * 60 * 1_000L

        // Elapsed time must be strictly below the session limit so the machine stays ACTIVE
        // (not TRIGGERED) when we accumulate it.
        val elapsedArb = Arb.long(0L, sessionLimitMs - 1L)

        // overageMs: how far past the grace period the tick occurs (at least 0 ms over,
        // i.e. exactly at the boundary or beyond). Using 0..gracePeriodMs covers both the
        // exact-boundary case and well-past-expiry cases.
        val overageArb = Arb.long(0L, gracePeriodMs)

        forAll(
            PropTestConfig(iterations = 100),
            elapsedArb,
            overageArb,
        ) { elapsedMs, overageMs ->
            val machine = SessionStateMachine(
                sessionLimitMs = sessionLimitMs,
                gracePeriodMs  = gracePeriodMs,
            )

            // Step 1: IDLE → ACTIVE, accumulate elapsedMs
            machine.tick(instagramInForeground = true, nowMs = 0L)          // IDLE → ACTIVE
            machine.tick(instagramInForeground = true, nowMs = elapsedMs)   // accumulate time

            if (machine.state.value !is SessionState.Active) return@forAll false  // precondition

            // Step 2: ACTIVE → GRACE (Instagram leaves foreground)
            val graceStartMs = elapsedMs + 1_000L
            machine.tick(instagramInForeground = false, nowMs = graceStartMs)

            if (machine.state.value !is SessionState.Grace) return@forAll false  // precondition

            // Step 3: tick past the grace period without Instagram returning
            // nowMs = graceStartMs + gracePeriodMs + overageMs guarantees elapsed >= gracePeriodMs
            val expiredMs = graceStartMs + gracePeriodMs + overageMs
            machine.tick(instagramInForeground = false, nowMs = expiredMs)

            // Assertions: state must be IDLE and timer must be reset to zero
            machine.state.value is SessionState.Idle &&
                machine.elapsedSessionMs.value == 0L
        }
    }
})

// Feature: doomscroll-guard, Property 5: ACTIVE → TRIGGERED when session limit is reached
/**
 * Property 5: ACTIVE → TRIGGERED when session limit is reached
 *
 * Validates: Requirements 3.6
 *
 * For any SessionStateMachine in the ACTIVE state where the accumulated session time
 * reaches or exceeds sessionLimitMs, the next tick call SHALL transition the state to
 * TRIGGERED.
 */
@RunWith(io.kotest.runner.junit4.KotestTestRunner::class)
class SessionStateMachineProperty5Test : StringSpec({

    "Property 5: ACTIVE → TRIGGERED when session limit is reached" {
        // Feature: doomscroll-guard, Property 5: ACTIVE → TRIGGERED when session limit is reached
        // Validates: Requirements 3.6
        val sessionLimitMs = 15 * 60 * 1_000L

        // Generate an overage on top of sessionLimitMs (0 = exactly at limit, up to 1x limit over).
        // The machine is seeded into ACTIVE using a non-zero base timestamp (t0 = 1L) so that
        // lastActiveTickMs is set to a positive value, making the delta calculation reliable.
        val overageArb = Arb.long(0L, sessionLimitMs)

        forAll(
            PropTestConfig(iterations = 100),
            overageArb,
        ) { overageMs ->
            val machine = SessionStateMachine(sessionLimitMs = sessionLimitMs)

            // Step 1: IDLE → ACTIVE at t=0 (delta is 0 on this first tick — no elapsed time yet)
            machine.tick(instagramInForeground = true, nowMs = 0L)

            // Precondition: machine must be in ACTIVE (elapsed = 0, well below limit)
            if (machine.state.value !is SessionState.Active) return@forAll false

            // Step 2: tick at nowMs = sessionLimitMs + overageMs.
            // The delta from lastActiveTickMs (0) to nowMs is sessionLimitMs + overageMs,
            // which is always >= sessionLimitMs, so the machine must transition to TRIGGERED.
            val triggerMs = sessionLimitMs + overageMs
            machine.tick(instagramInForeground = true, nowMs = triggerMs)

            // Assertion: state must be TRIGGERED with elapsed >= sessionLimitMs and no extension used
            val state = machine.state.value
            state is SessionState.Triggered &&
                (state as SessionState.Triggered).elapsedMs >= sessionLimitMs &&
                !state.extensionUsed
        }
    }
})

// Feature: doomscroll-guard, Property 6: TRIGGERED → EXTENDED on any purposeful reason
/**
 * Property 6: TRIGGERED → EXTENDED on any purposeful reason
 *
 * Validates: Requirements 3.7
 *
 * For any SessionStateMachine in the TRIGGERED state and any reason drawn from
 * {MESSAGING, WORK_RELATED, OTHER}, calling onUserAction(PurposefulReason(reason))
 * SHALL transition the state to EXTENDED.
 */
@RunWith(io.kotest.runner.junit4.KotestTestRunner::class)
class SessionStateMachineProperty6Test : StringSpec({

    "Property 6: TRIGGERED → EXTENDED on any purposeful reason" {
        // Feature: doomscroll-guard, Property 6: TRIGGERED → EXTENDED on any purposeful reason
        // Validates: Requirements 3.7
        val sessionLimitMs = 15 * 60 * 1_000L

        forAll(
            PropTestConfig(iterations = 100),
            Arb.enum<Reason>(),
        ) { reason ->
            val machine = SessionStateMachine(sessionLimitMs = sessionLimitMs)

            // Step 1: Drive machine into TRIGGERED state
            // Tick at t=0 (IDLE → ACTIVE), then tick at sessionLimitMs to reach the limit
            machine.tick(instagramInForeground = true, nowMs = 0L)
            machine.tick(instagramInForeground = true, nowMs = sessionLimitMs)

            // Precondition: machine must be in TRIGGERED with no extension used yet
            val triggeredState = machine.state.value
            if (triggeredState !is SessionState.Triggered) return@forAll false
            if (triggeredState.extensionUsed) return@forAll false

            // Step 2: User selects a purposeful reason
            machine.onUserAction(UserAction.PurposefulReason(reason))

            // Assertion: state must be EXTENDED
            machine.state.value is SessionState.Extended
        }
    }
})

// Feature: doomscroll-guard, Property 7: TRIGGERED stays TRIGGERED on "Just scrolling"
/**
 * Property 7: TRIGGERED stays TRIGGERED on "Just scrolling"
 *
 * Validates: Requirements 3.8
 *
 * For any SessionStateMachine in the TRIGGERED state, calling
 * onUserAction(JustScrolling) SHALL leave the state as TRIGGERED with
 * extensionUsed = false — no extension is granted and no state change occurs.
 */
@RunWith(io.kotest.runner.junit4.KotestTestRunner::class)
class SessionStateMachineProperty7Test : StringSpec({

    "Property 7: TRIGGERED stays TRIGGERED on \"Just scrolling\"" {
        // Feature: doomscroll-guard, Property 7: TRIGGERED stays TRIGGERED on "Just scrolling"
        // Validates: Requirements 3.8
        val sessionLimitMs = 15 * 60 * 1_000L

        // Generate an overage on top of sessionLimitMs so the machine reliably reaches TRIGGERED.
        val overageArb = Arb.long(0L, sessionLimitMs)

        forAll(
            PropTestConfig(iterations = 100),
            overageArb,
        ) { overageMs ->
            val machine = SessionStateMachine(sessionLimitMs = sessionLimitMs)

            // Step 1: IDLE → ACTIVE at t=0
            machine.tick(instagramInForeground = true, nowMs = 0L)

            // Precondition: must be in ACTIVE
            if (machine.state.value !is SessionState.Active) return@forAll false

            // Step 2: tick at sessionLimitMs + overageMs to reach TRIGGERED
            val triggerMs = sessionLimitMs + overageMs
            machine.tick(instagramInForeground = true, nowMs = triggerMs)

            // Precondition: must be in TRIGGERED with extensionUsed = false
            val triggeredState = machine.state.value
            if (triggeredState !is SessionState.Triggered) return@forAll false
            if (triggeredState.extensionUsed) return@forAll false

            // Step 3: user taps "Just scrolling"
            machine.onUserAction(UserAction.JustScrolling)

            // Assertion: state must still be TRIGGERED with extensionUsed = false
            val stateAfter = machine.state.value
            stateAfter is SessionState.Triggered &&
                !(stateAfter as SessionState.Triggered).extensionUsed
        }
    }
})

// Feature: doomscroll-guard, Property 8: EXTENDED → TRIGGERED (extension consumed) on extension expiry
/**
 * Property 8: EXTENDED → TRIGGERED (extension consumed) on extension expiry
 *
 * Validates: Requirements 3.9
 *
 * For any SessionStateMachine in the EXTENDED state, once the extension duration elapses,
 * the state SHALL transition to TRIGGERED with extensionUsed = true, so that a subsequent
 * PurposefulReason action SHALL NOT grant another extension in the same session.
 */
@RunWith(io.kotest.runner.junit4.KotestTestRunner::class)
class SessionStateMachineProperty8Test : StringSpec({

    "Property 8: EXTENDED → TRIGGERED (extension consumed) on extension expiry" {
        // Feature: doomscroll-guard, Property 8: EXTENDED → TRIGGERED (extension consumed) on extension expiry
        // Validates: Requirements 3.9
        val sessionLimitMs = 15 * 60 * 1_000L
        val extensionMs    =  5 * 60 * 1_000L

        // overageMs: how far past the extension expiry the tick occurs.
        // 0 = exactly at the boundary; up to extensionMs over = well past expiry.
        val overageArb = Arb.long(0L, extensionMs)

        // reasonArb: the purposeful reason used to enter EXTENDED.
        val reasonArb = Arb.enum<Reason>()

        forAll(
            PropTestConfig(iterations = 100),
            overageArb,
            reasonArb,
        ) { overageMs, reason ->
            val machine = SessionStateMachine(
                sessionLimitMs = sessionLimitMs,
                extensionMs    = extensionMs,
            )

            // Step 1: Drive machine into TRIGGERED state
            // Tick at t=0 (IDLE → ACTIVE), then tick at sessionLimitMs to reach the limit.
            machine.tick(instagramInForeground = true, nowMs = 0L)
            machine.tick(instagramInForeground = true, nowMs = sessionLimitMs)

            // Precondition: must be in TRIGGERED with no extension used yet
            val triggeredState = machine.state.value
            if (triggeredState !is SessionState.Triggered) return@forAll false
            if (triggeredState.extensionUsed) return@forAll false

            // Step 2: User selects a purposeful reason → EXTENDED
            machine.onUserAction(UserAction.PurposefulReason(reason))

            // Precondition: must be in EXTENDED
            val extendedState = machine.state.value
            if (extendedState !is SessionState.Extended) return@forAll false

            // Step 3: Tick past the extension expiry.
            // extensionStartMs is captured from the state (set by onUserAction via
            // System.currentTimeMillis()), so we compute the expiry relative to it.
            val expiredMs = extendedState.extensionStartMs + extensionMs + overageMs
            machine.tick(instagramInForeground = true, nowMs = expiredMs)

            // Assertion 1: state must be TRIGGERED with extensionUsed = true
            val stateAfterExpiry = machine.state.value
            if (stateAfterExpiry !is SessionState.Triggered) return@forAll false
            if (!(stateAfterExpiry as SessionState.Triggered).extensionUsed) return@forAll false

            // Assertion 2: a subsequent PurposefulReason action must NOT grant another extension.
            // The machine must remain in TRIGGERED (extensionUsed = true) after the action.
            machine.onUserAction(UserAction.PurposefulReason(reason))

            val stateAfterSecondAction = machine.state.value
            stateAfterSecondAction is SessionState.Triggered &&
                (stateAfterSecondAction as SessionState.Triggered).extensionUsed
        }
    }
})

// Feature: doomscroll-guard, Property 9: Session timer is monotonically non-decreasing while ACTIVE
/**
 * Property 9: Session timer is monotonically non-decreasing while ACTIVE
 *
 * Validates: Requirements 3.2, 3.6
 *
 * For any sequence of consecutive tick calls while the state remains ACTIVE, the
 * elapsedSessionMs value SHALL be non-decreasing — it never goes backwards.
 */
@RunWith(io.kotest.runner.junit4.KotestTestRunner::class)
class SessionStateMachineProperty9Test : StringSpec({

    "Property 9: Session timer is monotonically non-decreasing while ACTIVE" {
        // Feature: doomscroll-guard, Property 9: Session timer is monotonically non-decreasing while ACTIVE
        // Validates: Requirements 3.2, 3.6

        // Generate a sequence length between 2 and 20 ticks so we always have at least one
        // successive pair to compare, without making individual iterations too slow.
        val sequenceLengthArb = Arb.long(2L, 20L)

        // Generate a starting timestamp in a reasonable positive range so that adding
        // increments never overflows Long.
        val startMsArb = Arb.long(0L, 1_000_000L)

        // Generate a per-tick increment in 1..10_000 ms (always positive, so the sequence
        // is strictly monotonically increasing — a valid precondition for the property).
        val incrementArb = Arb.long(1L, 10_000L)

        forAll(
            PropTestConfig(iterations = 100),
            sequenceLengthArb,
            startMsArb,
            incrementArb,
        ) { sequenceLength, startMs, incrementMs ->
            val sessionLimitMs = Long.MAX_VALUE   // effectively no limit — stay ACTIVE throughout
            val machine = SessionStateMachine(sessionLimitMs = sessionLimitMs)

            // Step 1: IDLE → ACTIVE at startMs (first tick sets lastActiveTickMs; elapsed = 0)
            machine.tick(instagramInForeground = true, nowMs = startMs)

            // Precondition: machine must be in ACTIVE
            if (machine.state.value !is SessionState.Active) return@forAll false

            // Step 2: Feed a monotonically increasing sequence of nowMs values, all with
            // Instagram in the foreground, and assert the timer never decreases.
            var previousElapsed = machine.elapsedSessionMs.value   // 0 after the first tick
            var allNonDecreasing = true

            for (i in 1..sequenceLength) {
                val nowMs = startMs + i * incrementMs
                machine.tick(instagramInForeground = true, nowMs = nowMs)

                // If the machine left ACTIVE (e.g. hit the session limit), stop checking —
                // the property only applies while the state remains ACTIVE.
                if (machine.state.value !is SessionState.Active) break

                val currentElapsed = machine.elapsedSessionMs.value
                if (currentElapsed < previousElapsed) {
                    allNonDecreasing = false
                    break
                }
                previousElapsed = currentElapsed
            }

            allNonDecreasing
        }
    }
})

// Feature: doomscroll-guard, Property 10: Reset from any state returns to IDLE with zero timer
/**
 * Property 10: Reset from any state returns to IDLE with zero timer
 *
 * Validates: Requirements 6.4
 *
 * For any SessionStateMachine in any state, calling onUserAction(SessionReset) SHALL
 * transition the state to IDLE and set elapsedSessionMs to zero.
 */
@RunWith(io.kotest.runner.junit4.KotestTestRunner::class)
class SessionStateMachineProperty10Test : StringSpec({

    "Property 10: Reset from any state returns to IDLE with zero timer" {
        // Feature: doomscroll-guard, Property 10: Reset from any state returns to IDLE with zero timer
        // Validates: Requirements 6.4
        val sessionLimitMs = 15 * 60 * 1_000L
        val gracePeriodMs  =  3 * 60 * 1_000L
        val extensionMs    =  5 * 60 * 1_000L

        // Generate an elapsed time strictly below the session limit so we can seed ACTIVE/GRACE
        // without accidentally triggering TRIGGERED.
        val elapsedArb = Arb.long(0L, sessionLimitMs - 1L)

        // Generate an arbitrary reason for entering EXTENDED.
        val reasonArb = Arb.enum<Reason>()

        forAll(
            PropTestConfig(iterations = 100),
            elapsedArb,
            reasonArb,
        ) { elapsedMs, reason ->

            // Helper: assert that reset always produces IDLE with zero timer.
            fun assertResetProducesIdle(machine: SessionStateMachine): Boolean {
                machine.onUserAction(UserAction.SessionReset)
                return machine.state.value is SessionState.Idle &&
                    machine.elapsedSessionMs.value == 0L
            }

            // ── State 1: IDLE ────────────────────────────────────────────────
            val machineIdle = SessionStateMachine(
                sessionLimitMs = sessionLimitMs,
                gracePeriodMs  = gracePeriodMs,
                extensionMs    = extensionMs,
            )
            // Machine starts in IDLE by default — no setup needed.
            if (!assertResetProducesIdle(machineIdle)) return@forAll false

            // ── State 2: ACTIVE ──────────────────────────────────────────────
            val machineActive = SessionStateMachine(
                sessionLimitMs = sessionLimitMs,
                gracePeriodMs  = gracePeriodMs,
                extensionMs    = extensionMs,
            )
            machineActive.tick(instagramInForeground = true, nowMs = 0L)          // IDLE → ACTIVE
            machineActive.tick(instagramInForeground = true, nowMs = elapsedMs)   // accumulate time
            if (machineActive.state.value !is SessionState.Active) return@forAll false
            if (!assertResetProducesIdle(machineActive)) return@forAll false

            // ── State 3: GRACE ───────────────────────────────────────────────
            val machineGrace = SessionStateMachine(
                sessionLimitMs = sessionLimitMs,
                gracePeriodMs  = gracePeriodMs,
                extensionMs    = extensionMs,
            )
            machineGrace.tick(instagramInForeground = true, nowMs = 0L)
            machineGrace.tick(instagramInForeground = true, nowMs = elapsedMs)
            val graceStartMs = elapsedMs + 1_000L
            machineGrace.tick(instagramInForeground = false, nowMs = graceStartMs) // ACTIVE → GRACE
            if (machineGrace.state.value !is SessionState.Grace) return@forAll false
            if (!assertResetProducesIdle(machineGrace)) return@forAll false

            // ── State 4: TRIGGERED ───────────────────────────────────────────
            val machineTriggered = SessionStateMachine(
                sessionLimitMs = sessionLimitMs,
                gracePeriodMs  = gracePeriodMs,
                extensionMs    = extensionMs,
            )
            machineTriggered.tick(instagramInForeground = true, nowMs = 0L)
            machineTriggered.tick(instagramInForeground = true, nowMs = sessionLimitMs) // → TRIGGERED
            if (machineTriggered.state.value !is SessionState.Triggered) return@forAll false
            if (!assertResetProducesIdle(machineTriggered)) return@forAll false

            // ── State 5: EXTENDED ────────────────────────────────────────────
            val machineExtended = SessionStateMachine(
                sessionLimitMs = sessionLimitMs,
                gracePeriodMs  = gracePeriodMs,
                extensionMs    = extensionMs,
            )
            machineExtended.tick(instagramInForeground = true, nowMs = 0L)
            machineExtended.tick(instagramInForeground = true, nowMs = sessionLimitMs) // → TRIGGERED
            machineExtended.onUserAction(UserAction.PurposefulReason(reason))          // → EXTENDED
            if (machineExtended.state.value !is SessionState.Extended) return@forAll false
            if (!assertResetProducesIdle(machineExtended)) return@forAll false

            true
        }
    }
})
