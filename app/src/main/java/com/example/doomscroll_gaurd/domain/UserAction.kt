package com.example.doomscroll_gaurd.domain

/**
 * Actions that the user (or the system on boot) can send to [SessionStateMachine].
 *
 * No Android imports — this sealed class is part of the pure domain layer and can be
 * instantiated and tested on the JVM without any Android runtime.
 */
sealed class UserAction {

    /**
     * The user selected a purposeful reason on the overlay (Messaging, Work-related, or Other).
     * Transitions [SessionState.Triggered] → [SessionState.Extended].
     */
    data class PurposefulReason(val reason: Reason) : UserAction()

    /**
     * The user admitted they are "just scrolling" on the overlay.
     * Keeps the state in [SessionState.Triggered] with no extension granted.
     */
    object JustScrolling : UserAction()

    /**
     * Resets the state machine to [SessionState.Idle] with a zeroed timer.
     * Sent by [GuardService] on boot and on every service (re)start.
     */
    object SessionReset : UserAction()
}
