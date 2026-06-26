package com.example.doomscroll_gaurd.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pure Kotlin state machine that models a single Instagram usage session.
 *
 * **No Android imports** — this class can be instantiated and tested on the JVM
 * without any Android runtime, emulator, or device.
 *
 * ## Usage
 * - Call [tick] on every 2-second poll from [GuardService], passing whether Instagram
 *   is currently in the foreground and the current wall-clock time.
 * - Call [onUserAction] when the user interacts with the overlay.
 * - Call [reset] (or [onUserAction] with [UserAction.SessionReset]) on service start
 *   and after every device reboot to ensure a clean [SessionState.Idle] baseline.
 *
 * ## State transitions
 * ```
 * IDLE ──(Instagram fg)──────────────────────────────► ACTIVE
 * ACTIVE ──(Instagram bg)────────────────────────────► GRACE
 * ACTIVE ──(elapsed ≥ sessionLimitMs)────────────────► TRIGGERED
 * GRACE ──(Instagram fg within grace window)─────────► ACTIVE  (timer preserved)
 * GRACE ──(grace period expires)─────────────────────► IDLE    (timer reset)
 * TRIGGERED ──(PurposefulReason)─────────────────────► EXTENDED
 * TRIGGERED ──(JustScrolling)────────────────────────► TRIGGERED (no change)
 * EXTENDED ──(extension expires)─────────────────────► TRIGGERED (extensionUsed=true)
 * ANY ──(SessionReset)────────────────────────────────► IDLE    (timer reset)
 * ```
 *
 * @param sessionLimitMs  Accumulated foreground time that triggers the overlay (default 15 min).
 * @param gracePeriodMs   How long to wait after Instagram leaves the foreground before resetting
 *                        the session timer (default 3 min).
 * @param extensionMs     Duration of the one-time purposeful-use extension (default 5 min).
 */
class SessionStateMachine(
    private val sessionLimitMs: Long = 15 * 60 * 1_000L,
    private val gracePeriodMs: Long = 3 * 60 * 1_000L,
    private val extensionMs: Long = 5 * 60 * 1_000L,
) {

    // ---------------------------------------------------------------------------
    // Observable state
    // ---------------------------------------------------------------------------

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)

    /**
     * The current session state. Observed by [GuardService] to show/hide the overlay.
     */
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _elapsedSessionMs = MutableStateFlow(0L)

    /**
     * Accumulated Instagram foreground time for the current session in milliseconds.
     * Paused during [SessionState.Grace] and reset to zero on [SessionState.Idle].
     */
    val elapsedSessionMs: StateFlow<Long> = _elapsedSessionMs.asStateFlow()

    // ---------------------------------------------------------------------------
    // Internal bookkeeping (not exposed — derived from state when needed)
    // ---------------------------------------------------------------------------

    /**
     * Wall-clock timestamp of the last [tick] call while [SessionState.Active].
     * `null` means the machine has never entered ACTIVE (or was reset), so no delta
     * should be accumulated on the very first ACTIVE tick regardless of the `nowMs` value.
     */
    private var lastActiveTickMs: Long? = null

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /**
     * Called by [GuardService] on every 2-second poll.
     *
     * @param instagramInForeground Whether Instagram is currently the foreground app.
     * @param nowMs                 Current wall-clock time in milliseconds.
     */
    fun tick(instagramInForeground: Boolean, nowMs: Long) {
        when (val current = _state.value) {
            is SessionState.Idle -> {
                if (instagramInForeground) {
                    lastActiveTickMs = nowMs
                    _state.value = SessionState.Active(elapsedMs = 0L)
                    _elapsedSessionMs.value = 0L
                }
            }

            is SessionState.Active -> {
                if (!instagramInForeground) {
                    // Instagram left the foreground — start grace period.
                    _state.value = SessionState.Grace(
                        elapsedMs = current.elapsedMs,
                        graceStartMs = nowMs,
                    )
                } else {
                    // Instagram still in foreground — accumulate elapsed time.
                    val delta = lastActiveTickMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L
                    lastActiveTickMs = nowMs
                    val newElapsed = current.elapsedMs + delta
                    _elapsedSessionMs.value = newElapsed

                    if (newElapsed >= sessionLimitMs) {
                        // Session limit reached — trigger the overlay.
                        _state.value = SessionState.Triggered(
                            elapsedMs = newElapsed,
                            extensionUsed = false,
                        )
                    } else {
                        _state.value = SessionState.Active(elapsedMs = newElapsed)
                    }
                }
            }

            is SessionState.Grace -> {
                if (instagramInForeground) {
                    // Instagram returned within the grace window — resume session.
                    lastActiveTickMs = nowMs
                    _state.value = SessionState.Active(elapsedMs = current.elapsedMs)
                    _elapsedSessionMs.value = current.elapsedMs
                } else {
                    // Check whether the grace period has expired.
                    val graceElapsed = nowMs - current.graceStartMs
                    if (graceElapsed >= gracePeriodMs) {
                        // Grace period expired — reset session.
                        _state.value = SessionState.Idle
                        _elapsedSessionMs.value = 0L
                        lastActiveTickMs = null
                    }
                    // else: still within grace window, no state change
                }
            }

            is SessionState.Triggered -> {
                // Overlay is visible; no automatic transitions from tick.
                // User action (PurposefulReason / JustScrolling) drives the next transition.
            }

            is SessionState.Extended -> {
                // Check whether the extension has expired.
                val extensionElapsed = nowMs - current.extensionStartMs
                if (extensionElapsed >= extensionMs) {
                    // Extension consumed — show overlay again with no further extension available.
                    _state.value = SessionState.Triggered(
                        elapsedMs = current.elapsedMs,
                        extensionUsed = true,
                    )
                }
                // else: extension still running, no state change
            }
        }
    }

    /**
     * Called by [GuardService] when the user interacts with the overlay.
     *
     * @param action The action the user took (or [UserAction.SessionReset] on boot).
     */
    fun onUserAction(action: UserAction) {
        when (action) {
            is UserAction.PurposefulReason -> {
                val current = _state.value
                if (current is SessionState.Triggered && !current.extensionUsed) {
                    // Grant the one-time extension.
                    _state.value = SessionState.Extended(
                        elapsedMs = current.elapsedMs,
                        extensionStartMs = System.currentTimeMillis(),
                    )
                }
                // If extensionUsed == true, ignore — no second extension in the same session.
            }

            is UserAction.JustScrolling -> {
                // Stay in TRIGGERED; no extension granted. State is unchanged.
            }

            is UserAction.SessionReset -> {
                _state.value = SessionState.Idle
                _elapsedSessionMs.value = 0L
                lastActiveTickMs = null
            }
        }
    }

    /**
     * Convenience wrapper — resets the machine to [SessionState.Idle] with a zeroed timer.
     * Equivalent to calling `onUserAction(UserAction.SessionReset)`.
     */
    fun reset() {
        onUserAction(UserAction.SessionReset)
    }
}
