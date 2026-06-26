package com.example.doomscroll_gaurd.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.doomscroll_gaurd.MainActivity
import com.example.doomscroll_gaurd.R
import com.example.doomscroll_gaurd.data.SharedPreferencesRepository
import com.example.doomscroll_gaurd.domain.SessionState
import com.example.doomscroll_gaurd.domain.SessionStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Persistent foreground service that polls [UsageStatsHelper] every 2 seconds,
 * drives [SessionStateMachine], and shows/hides the overlay via [OverlayManager].
 *
 * Key design decisions:
 * - Uses [Handler.postDelayed] on the main looper — no background threads, no wake locks.
 * - Skips processing when [PowerManager.isInteractive] is false (screen off / Doze).
 * - Returns [START_STICKY] so Android restarts the service if it is killed.
 * - Observes [SessionStateMachine.state] via a [CoroutineScope] on [Dispatchers.Main].
 */
class GuardService : Service() {

    // ---------------------------------------------------------------------------
    // Constants
    // ---------------------------------------------------------------------------

    companion object {
        private const val TAG = "GuardService"
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "doomscroll_guard_channel"
        private const val POLL_INTERVAL_MS = 2_000L
        private const val INSTAGRAM_PACKAGE = "com.instagram.android"
    }

    // ---------------------------------------------------------------------------
    // Fields
    // ---------------------------------------------------------------------------

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var powerManager: PowerManager

    // Task 6.1 — state machine instantiated in onCreate
    private lateinit var sessionStateMachine: SessionStateMachine

    // Task 6.2 — overlay manager instantiated in onCreate
    private lateinit var overlayManager: OverlayManager

    // Coroutine scope for collecting state flow on the main thread (Task 6.2)
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    /** Caches the last non-null foreground resolution from [UsageStatsHelper].
     *  Reset to false on every [onStartCommand] to ensure a clean lifecycle baseline. */
    private var lastConfirmedInstagramForeground: Boolean = false

    /** Polling runnable — reschedules itself every [POLL_INTERVAL_MS] milliseconds. */
    private val pollRunnable = object : Runnable {
        override fun run() {
            poll()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    // ---------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        powerManager = getSystemService(POWER_SERVICE) as PowerManager

        // Task 6.1 — instantiate the state machine
        sessionStateMachine = SessionStateMachine()

        // Task 6.2 — instantiate the overlay manager
        overlayManager = OverlayManager(
            context = this,
            preferencesRepository = SharedPreferencesRepository(this),
        )

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        // Task 6.2 — observe state changes and drive the overlay
        observeSessionState()

        Log.d(TAG, "GuardService created — starting polling loop")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand — service (re)started")

        // Task 6.3 — reset to IDLE on every (re)start, including after boot
        sessionStateMachine.reset()
        lastConfirmedInstagramForeground = false

        // Kick off the polling loop (safe to call multiple times; handler deduplicates).
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(pollRunnable)
        overlayManager.hide()
        serviceScope.cancel()
        Log.d(TAG, "GuardService destroyed — polling loop stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------------------
    // State observation (Task 6.2)
    // ---------------------------------------------------------------------------

    /**
     * Collects [SessionStateMachine.state] on the main thread and shows/hides the overlay
     * in response to state transitions.
     *
     * - [SessionState.Triggered] → show overlay
     * - Any other state → hide overlay (if it was showing)
     *
     * [distinctUntilChanged] ensures we only react to actual state changes, not repeated
     * emissions of the same state on every tick.
     */
    private fun observeSessionState() {
        serviceScope.launch {
            sessionStateMachine.state
                .collect { state ->
                    when (state) {
                        is SessionState.Triggered -> {
                            Log.d(TAG, "State → TRIGGERED (extensionUsed=${state.extensionUsed})")
                            overlayManager.show(
                                elapsedMs = state.elapsedMs,
                                extensionUsed = state.extensionUsed,
                                onReason = { action ->
                                    sessionStateMachine.onUserAction(action)
                                },
                            )
                        }

                        is SessionState.Idle,
                        is SessionState.Active,
                        is SessionState.Grace,
                        is SessionState.Extended -> {
                            if (overlayManager.isShowing) {
                                Log.d(TAG, "State → ${state::class.simpleName} — hiding overlay")
                                overlayManager.hide()
                            }
                        }
                    }
                }
        }
    }

    // ---------------------------------------------------------------------------
    // Polling (Task 6.1)
    // ---------------------------------------------------------------------------

    private fun poll() {
        // Skip all work when the screen is off — compatible with Doze mode.
        if (!powerManager.isInteractive) {
            return
        }

        val nowMs = System.currentTimeMillis()
        val foregroundPkg = UsageStatsHelper.getForegroundPackage(this, nowMs)
        val instagramInForeground = resolveInstagramForeground(foregroundPkg, lastConfirmedInstagramForeground)
        if (foregroundPkg != null) {
            lastConfirmedInstagramForeground = instagramInForeground
        }

        Log.d(TAG, "Foreground package: $foregroundPkg (instagram=$instagramInForeground)")

        // Task 6.1 — feed the tick into the state machine
        sessionStateMachine.tick(instagramInForeground, nowMs)
    }

    // ---------------------------------------------------------------------------
    // Notification helpers
    // ---------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Doomscroll Guard",
            NotificationManager.IMPORTANCE_LOW  // No sound or vibration
        ).apply {
            description = "Persistent notification while Doomscroll Guard is monitoring usage"
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Doomscroll Guard is active")
        .setContentText("Monitoring Instagram session time")
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()
}

/**
 * Pure helper that resolves whether Instagram is in the foreground, with null-awareness.
 *
 * When [foregroundPkg] is non-null, the result is `foregroundPkg == "com.instagram.android"`.
 * When [foregroundPkg] is null (no usage events — e.g. passive Reels playback), the
 * [lastConfirmed] cached value is returned unchanged to avoid a spurious ACTIVE → GRACE
 * transition.
 *
 * This function has no Android dependencies and can be tested on the JVM without Robolectric.
 */
internal fun resolveInstagramForeground(
    foregroundPkg: String?,
    lastConfirmed: Boolean,
): Boolean = if (foregroundPkg != null) {
    foregroundPkg == "com.instagram.android"
} else {
    lastConfirmed
}
