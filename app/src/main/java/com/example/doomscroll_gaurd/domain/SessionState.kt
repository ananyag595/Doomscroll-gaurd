package com.example.doomscroll_gaurd.domain

/**
 * All possible states of the [SessionStateMachine].
 *
 * No Android imports — this sealed class is part of the pure domain layer and can be
 * instantiated and tested on the JVM without any Android runtime.
 *
 * State diagram:
 *
 *   IDLE ──(Instagram foreground)──► ACTIVE
 *   ACTIVE ──(Instagram background)──► GRACE
 *   ACTIVE ──(elapsed ≥ sessionLimit)──► TRIGGERED
 *   GRACE ──(Instagram returns within grace window)──► ACTIVE
 *   GRACE ──(grace period expires)──► IDLE
 *   TRIGGERED ──(PurposefulReason)──► EXTENDED
 *   TRIGGERED ──(JustScrolling)──► TRIGGERED (unchanged)
 *   EXTENDED ──(extension expires)──► TRIGGERED (extensionUsed = true)
 *   ANY ──(SessionReset)──► IDLE
 */
sealed class SessionState {

    /** Instagram is not in use; session timer is at zero. */
    object Idle : SessionState()

    /**
     * Instagram is in the foreground; session timer is incrementing.
     *
     * @param elapsedMs Accumulated foreground time for this session in milliseconds.
     */
    data class Active(val elapsedMs: Long) : SessionState()

    /**
     * Instagram left the foreground; the session timer is paused but not reset.
     * If Instagram returns within [gracePeriodMs] the session resumes; otherwise
     * the machine transitions to [Idle].
     *
     * @param elapsedMs Accumulated foreground time at the moment Instagram left.
     * @param graceStartMs Wall-clock timestamp (ms) when the grace period began.
     */
    data class Grace(val elapsedMs: Long, val graceStartMs: Long) : SessionState()

    /**
     * The session limit has been reached; the overlay is shown.
     *
     * @param elapsedMs Accumulated foreground time at the moment the limit was hit.
     * @param extensionUsed Whether the one-time 5-minute extension has already been consumed.
     */
    data class Triggered(val elapsedMs: Long, val extensionUsed: Boolean) : SessionState()

    /**
     * The user claimed a purposeful-use extension; a 5-minute countdown is running.
     *
     * @param elapsedMs Accumulated foreground time at the moment the extension was granted.
     * @param extensionStartMs Wall-clock timestamp (ms) when the extension began.
     */
    data class Extended(val elapsedMs: Long, val extensionStartMs: Long) : SessionState()
}
