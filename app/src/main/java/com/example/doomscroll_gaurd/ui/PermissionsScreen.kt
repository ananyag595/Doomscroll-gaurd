package com.example.doomscroll_gaurd.ui

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Onboarding screen that checks and requests all permissions required by Doomscroll Guard.
 *
 * Permissions checked:
 * - PACKAGE_USAGE_STATS  — special permission, granted via system Settings
 * - SYSTEM_ALERT_WINDOW  — special permission, granted via system Settings
 * - POST_NOTIFICATIONS   — runtime permission on API 33+
 *
 * Each missing permission is shown as an explanation card with a deep-link button.
 * The "Continue" button is only shown when all required permissions are granted.
 *
 * @param onAllPermissionsGranted Called when the user taps "Continue" with all permissions granted.
 */
@Composable
fun PermissionsScreen(onAllPermissionsGranted: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // --- Permission state ---
    var hasUsageStats by remember { mutableStateOf(checkUsageStatsPermission(context)) }
    var hasOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var hasNotifications by remember { mutableStateOf(checkNotificationPermission(context)) }

    // Re-evaluate all permissions whenever the screen resumes (e.g. returning from system Settings)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasUsageStats = checkUsageStatsPermission(context)
                hasOverlay = Settings.canDrawOverlays(context)
                hasNotifications = checkNotificationPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Launcher for POST_NOTIFICATIONS runtime permission
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotifications = granted
    }

    val allGranted = hasUsageStats && hasOverlay && hasNotifications

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Permissions Required",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = "Doomscroll Guard needs the following permissions to monitor Instagram usage and display the session overlay.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(4.dp))

        // --- PACKAGE_USAGE_STATS card ---
        if (!hasUsageStats) {
            PermissionCard(
                title = "Usage Access",
                description = "Allows the app to detect which app is in the foreground so it can track your Instagram session time.",
                buttonLabel = "Open Usage Access Settings",
                onButtonClick = {
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
            )
        }

        // --- SYSTEM_ALERT_WINDOW card ---
        if (!hasOverlay) {
            PermissionCard(
                title = "Display Over Other Apps",
                description = "Allows the session-limit overlay to appear on top of Instagram when your time is up.",
                buttonLabel = "Open Overlay Settings",
                onButtonClick = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                }
            )
        }

        // --- POST_NOTIFICATIONS card (API 33+) ---
        if (!hasNotifications) {
            PermissionCard(
                title = "Notifications",
                description = "Required to show the persistent notification that keeps the monitoring service running in the background.",
                buttonLabel = "Grant Notification Permission",
                onButtonClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // "Continue" is only shown when every permission is satisfied
        if (allGranted) {
            Button(
                onClick = onAllPermissionsGranted,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally)
            ) {
                Text("Continue")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Permission check helpers
// ---------------------------------------------------------------------------

/** Returns true if PACKAGE_USAGE_STATS has been granted via system Settings. */
private fun checkUsageStatsPermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

/** Returns true if POST_NOTIFICATIONS is granted (always true below API 33). */
private fun checkNotificationPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    } else {
        true // Permission not required below API 33
    }
}

// ---------------------------------------------------------------------------
// Reusable permission card
// ---------------------------------------------------------------------------

@Composable
private fun PermissionCard(
    title: String,
    description: String,
    buttonLabel: String,
    onButtonClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onButtonClick) {
                Text(buttonLabel)
            }
        }
    }
}
