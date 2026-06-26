package com.example.doomscroll_gaurd.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.doomscroll_gaurd.R
import com.example.doomscroll_gaurd.data.PreferencesRepository
import com.example.doomscroll_gaurd.domain.Reason
import com.example.doomscroll_gaurd.domain.RedirectApp
import com.example.doomscroll_gaurd.domain.UserAction
import java.util.concurrent.TimeUnit

/**
 * Manages the full-screen [WindowManager] overlay shown when the session limit is reached.
 *
 * The overlay is a plain [View] inflated from `overlay_view.xml` — not a Compose screen —
 * to avoid Compose lifecycle issues with [WindowManager].
 *
 * Two display modes:
 *  - **First appearance** (`extensionUsed = false`): shows elapsed time, contextual message,
 *    reason-selection buttons, and the redirect grid.
 *  - **Final appearance** (`extensionUsed = true`): shows elapsed time and redirect grid only;
 *    the reason-selection section is hidden.
 *
 * Usage:
 * ```kotlin
 * val overlayManager = OverlayManager(context, preferencesRepository)
 * overlayManager.show(elapsedMs = 900_000L, extensionUsed = false) { action ->
 *     sessionStateMachine.onUserAction(action)
 * }
 * // later…
 * overlayManager.hide()
 * ```
 */
class OverlayManager(
    private val context: Context,
    private val preferencesRepository: PreferencesRepository,
) {

    companion object {
        private const val TAG = "OverlayManager"
    }

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /** The currently attached overlay view, or null if the overlay is not shown. */
    private var overlayView: View? = null

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /**
     * Inflates and attaches the overlay to the [WindowManager].
     *
     * Safe to call when the overlay is already visible — the existing view is removed first.
     *
     * @param elapsedMs     Accumulated Instagram foreground time for the current session (ms).
     * @param extensionUsed Whether the one-time extension has already been consumed.
     * @param onReason      Callback invoked when the user taps a reason button or the overlay
     *                      needs to communicate a [UserAction] back to the state machine.
     */
    fun show(
        elapsedMs: Long,
        extensionUsed: Boolean,
        onReason: (UserAction) -> Unit,
    ) {
        // Remove any existing overlay before adding a new one.
        hide()

        val view = LayoutInflater.from(context).inflate(R.layout.overlay_view, null)

        // ── Elapsed time ──────────────────────────────────────────────────────
        view.findViewById<TextView>(R.id.tv_elapsed_time).text = formatElapsed(elapsedMs)

        // ── Display mode ──────────────────────────────────────────────────────
        val reasonSection = view.findViewById<LinearLayout>(R.id.reason_section)
        val finalModeContainer = view.findViewById<LinearLayout>(R.id.final_mode_container)

        if (extensionUsed) {
            // Final appearance: hide reason selection, show final message.
            reasonSection.visibility = View.GONE
            finalModeContainer.visibility = View.VISIBLE
        } else {
            // First appearance: show reason selection, hide final message.
            reasonSection.visibility = View.VISIBLE
            finalModeContainer.visibility = View.GONE
            wireReasonButtons(view, onReason)
        }

        // ── Redirect grid ─────────────────────────────────────────────────────
        val redirectApps = resolveRedirectApps()
        populateRedirectGrid(view, redirectApps)

        // ── WindowManager params ──────────────────────────────────────────────
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager.addView(view, params)
            overlayView = view
            Log.d(TAG, "Overlay shown (extensionUsed=$extensionUsed, elapsed=${elapsedMs}ms)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view: ${e.message}", e)
        }
    }

    /**
     * Removes the overlay from the [WindowManager] if it is currently attached.
     *
     * Safe to call when the overlay is not shown.
     */
    fun hide() {
        val view = overlayView ?: return
        try {
            windowManager.removeView(view)
            Log.d(TAG, "Overlay hidden")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove overlay view: ${e.message}", e)
        } finally {
            overlayView = null
        }
    }

    /** Returns true if the overlay is currently attached to the window. */
    val isShowing: Boolean get() = overlayView != null

    // ---------------------------------------------------------------------------
    // Reason buttons (first-appearance mode only)
    // ---------------------------------------------------------------------------

    private fun wireReasonButtons(view: View, onReason: (UserAction) -> Unit) {
        view.findViewById<android.widget.Button>(R.id.btn_reason_messaging).setOnClickListener {
            hide()
            onReason(UserAction.PurposefulReason(Reason.MESSAGING))
        }
        view.findViewById<android.widget.Button>(R.id.btn_reason_work).setOnClickListener {
            hide()
            onReason(UserAction.PurposefulReason(Reason.WORK_RELATED))
        }
        view.findViewById<android.widget.Button>(R.id.btn_reason_other).setOnClickListener {
            hide()
            onReason(UserAction.PurposefulReason(Reason.OTHER))
        }
        // "Just scrolling" — stays in TRIGGERED, hides reason section but keeps overlay visible.
        view.findViewById<android.widget.Button>(R.id.btn_reason_just_scrolling).setOnClickListener {
            onReason(UserAction.JustScrolling)
            // The state machine stays TRIGGERED; GuardService will call show() again with
            // extensionUsed=false but the reason section hidden. For the test-button path
            // we simply hide the reason section and show the final container inline.
            val reasonSection = view.findViewById<LinearLayout>(R.id.reason_section)
            val finalModeContainer = view.findViewById<LinearLayout>(R.id.final_mode_container)
            reasonSection.visibility = View.GONE
            finalModeContainer.visibility = View.VISIBLE
        }
    }

    // ---------------------------------------------------------------------------
    // Redirect grid (tasks 5.3 + 5.4)
    // ---------------------------------------------------------------------------

    /**
     * Resolves each package name from [PreferencesRepository] into a [RedirectApp].
     * Packages that are no longer installed are silently skipped (Requirement 5.5).
     */
    private fun resolveRedirectApps(): List<RedirectApp> {
        val pm = context.packageManager
        return preferencesRepository.getRedirectApps().mapNotNull { pkg ->
            try {
                val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getApplicationInfo(pkg, 0)
                }
                RedirectApp(
                    packageName = pkg,
                    label = pm.getApplicationLabel(appInfo).toString(),
                    icon = pm.getApplicationIcon(appInfo),
                )
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Redirect app not installed, skipping: $pkg")
                null
            }
        }
    }

    /**
     * Inflates one [R.layout.overlay_redirect_cell] per [RedirectApp] and adds it to the
     * [GridLayout]. Each cell launches the target app and hides the overlay on tap.
     *
     * **Runtime safety (Part 2):** if [apps] is empty (e.g. all configured apps were
     * uninstalled), the grid is replaced with an actionable fallback: a message and a
     * "Configure Apps" button that brings [MainActivity] to the foreground so the user
     * can select at least one redirect app.
     */
    private fun populateRedirectGrid(view: View, apps: List<RedirectApp>) {
        val grid = view.findViewById<GridLayout>(R.id.redirect_grid)
        grid.removeAllViews()

        if (apps.isEmpty()) {
            // ── Runtime fallback: no apps available ───────────────────────────
            // This can happen when all previously configured apps have been uninstalled.
            // Show an actionable message so the user is never left in a dead-end state.
            Log.w(TAG, "No redirect apps resolved — showing configure-apps fallback")

            val container = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(0, 24, 0, 24)
            }

            val message = TextView(context).apply {
                text = "No redirect apps configured."
                setTextColor(android.graphics.Color.parseColor("#CCFFFFFF"))
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setPadding(0, 0, 0, 16)
            }

            val configureButton = android.widget.Button(context).apply {
                text = "Configure Apps"
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.parseColor("#44FFFFFF"))
                setOnClickListener {
                    // Bring MainActivity to the foreground so the user can open SettingsScreen.
                    val intent = Intent(context, com.example.doomscroll_gaurd.MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    context.startActivity(intent)
                    hide()
                }
            }

            container.addView(message)
            container.addView(configureButton)

            val params = GridLayout.LayoutParams().apply {
                columnSpec = GridLayout.spec(0, 3, 1f)
                width = 0
            }
            grid.addView(container, params)
            return
        }

        apps.forEach { app ->
            val cell = LayoutInflater.from(context)
                .inflate(R.layout.overlay_redirect_cell, grid, false)

            cell.findViewById<ImageView>(R.id.iv_app_icon).setImageDrawable(app.icon)
            cell.findViewById<TextView>(R.id.tv_app_label).text = app.label

            cell.setOnClickListener {
                launchApp(app.packageName)
                hide()
            }

            grid.addView(cell)
        }
    }

    /**
     * Launches [packageName] via an explicit [Intent.ACTION_MAIN] intent.
     * Requirement 4.8: tapping a redirect app launches it and moves Instagram to the background.
     */
    private fun launchApp(packageName: String) {
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent(Intent.ACTION_MAIN).apply {
                    setPackage(packageName)
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch app $packageName: ${e.message}", e)
        }
    }

    // ---------------------------------------------------------------------------
    // Formatting helpers
    // ---------------------------------------------------------------------------

    /**
     * Formats [elapsedMs] as `MM:SS` for display in the overlay header.
     */
    private fun formatElapsed(elapsedMs: Long): String {
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(elapsedMs)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }
}
