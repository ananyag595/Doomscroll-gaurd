package com.example.doomscroll_gaurd

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.doomscroll_gaurd.data.SharedPreferencesRepository
import com.example.doomscroll_gaurd.service.GuardService
import com.example.doomscroll_gaurd.ui.PermissionsScreen
import com.example.doomscroll_gaurd.ui.SettingsScreen
import com.example.doomscroll_gaurd.ui.theme.DoomscrollgaurdTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DoomscrollgaurdTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    DoomscrollGuardApp(
                        context = this,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Top-level navigation
// ---------------------------------------------------------------------------

/** Simple screen enum — no NavHost needed for two destinations. */
private enum class Screen { PERMISSIONS, MAIN, SETTINGS }

@Composable
private fun DoomscrollGuardApp(context: Context, modifier: Modifier = Modifier) {
    var allPermissionsGranted by remember {
        mutableStateOf(checkAllPermissions(context))
    }
    var currentScreen by remember {
        mutableStateOf(if (allPermissionsGranted) Screen.MAIN else Screen.PERMISSIONS)
    }

    when (currentScreen) {
        Screen.PERMISSIONS -> {
            PermissionsScreen(
                onAllPermissionsGranted = {
                    allPermissionsGranted = true
                    // Task 9.4: start the service automatically once permissions are granted.
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, GuardService::class.java)
                    )
                    currentScreen = Screen.MAIN
                }
            )
        }

        Screen.MAIN -> {
            MainScreen(
                modifier = modifier,
                onOpenSettings = { currentScreen = Screen.SETTINGS },
            )
        }

        Screen.SETTINGS -> {
            SettingsScreen(
                preferencesRepository = SharedPreferencesRepository(context),
                onBack = { currentScreen = Screen.MAIN },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Main screen (Task 6.4 — replaces the debug placeholder)
// ---------------------------------------------------------------------------

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Doomscroll Guard",
            style = MaterialTheme.typography.headlineMedium,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Your Instagram session guard is active.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(40.dp))

        Button(
            onClick = {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, GuardService::class.java)
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Start / Restart Guard Service")
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Configure Redirect Apps")
        }
    }
}

// ---------------------------------------------------------------------------
// Permission check helpers (mirrors PermissionsScreen logic)
// ---------------------------------------------------------------------------

private fun checkAllPermissions(context: Context): Boolean {
    return checkUsageStatsPermission(context) &&
        Settings.canDrawOverlays(context) &&
        checkNotificationPermission(context)
}

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

private fun checkNotificationPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}
