package com.example.doomscroll_gaurd.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.doomscroll_gaurd.data.PreferencesRepository

/**
 * Screen that lets the user choose which apps appear in the overlay redirect grid.
 *
 * ## Minimum-1 constraint (hard enforcement — Option A)
 * When only one app is selected its checkbox is **disabled** so it cannot be unchecked.
 * This guarantees `selectedApps.size ≥ 1` at all times through normal UI interaction.
 *
 * @param preferencesRepository Persists the selected package names.
 * @param onBack                Called when the user taps the back arrow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    preferencesRepository: PreferencesRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    // Load all installed, launchable apps once, sorted by label.
    val allApps: List<AppEntry> = remember {
        loadInstalledApps(context.packageManager)
    }

    // Mutable selection state — initialised from persisted preferences.
    var selectedPackages by remember {
        mutableStateOf(preferencesRepository.getRedirectApps())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Redirect Apps") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Text(
                text = "Choose apps to show when your session limit is reached.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            // Minimum-1 hint — only shown when exactly one app is selected.
            if (selectedPackages.size == 1) {
                Text(
                    text = "At least one app must remain selected.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                items(allApps, key = { it.packageName }) { app ->
                    val isChecked = app.packageName in selectedPackages

                    // Hard enforcement: disable the checkbox when this is the only selected app.
                    // Invariant: selectedPackages.size >= 1 is always maintained.
                    val isLastSelected = isChecked && selectedPackages.size == 1

                    AppRow(
                        app = app,
                        checked = isChecked,
                        enabled = !isLastSelected,
                        onCheckedChange = { nowChecked ->
                            val updated = if (nowChecked) {
                                selectedPackages + app.packageName
                            } else {
                                selectedPackages - app.packageName
                            }
                            selectedPackages = updated
                            preferencesRepository.setRedirectApps(updated)
                        },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// App row
// ---------------------------------------------------------------------------

@Composable
private fun AppRow(
    app: AppEntry,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        app.icon?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = app.label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )

        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

// ---------------------------------------------------------------------------
// Data model + loader
// ---------------------------------------------------------------------------

private data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
)

/**
 * Pure predicate that decides whether an app should appear in the redirect-app list.
 *
 * Extracted from [loadInstalledApps] so it can be exercised on the JVM without a real
 * [PackageManager].
 *
 * An app is included only when:
 * 1. It has a launcher intent (i.e. it is user-launchable), AND
 * 2. It is not a system app ([ApplicationInfo.FLAG_SYSTEM] is not set), AND
 * 3. It is not an updated system app ([ApplicationInfo.FLAG_UPDATED_SYSTEM_APP] is not set), AND
 * 4. Its package name is not `com.instagram.android` (Instagram is the monitored app and must
 *    never appear as a redirect destination).
 *
 * @param launchIntent Result of [PackageManager.getLaunchIntentForPackage]; `null` means the
 *                     app has no launcher activity and should be excluded.
 * @param flags        [ApplicationInfo.flags] for the app.
 * @param packageName  [ApplicationInfo.packageName] for the app; used to exclude explicitly
 *                     blocked packages such as `com.instagram.android`.
 */
internal fun shouldIncludeApp(
    launchIntent: android.content.Intent?,
    flags: Int,
    packageName: String,
): Boolean {
    if (launchIntent == null) return false
    if (flags and ApplicationInfo.FLAG_SYSTEM != 0) return false
    if (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0) return false
    if (packageName == "com.instagram.android") return false
    return true
}

private fun loadInstalledApps(pm: PackageManager): List<AppEntry> {
    @Suppress("DEPRECATION")
    val apps: List<ApplicationInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getInstalledApplications(
            PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
        )
    } else {
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
    }

    return apps
        .filter { info ->
            val launchIntent = pm.getLaunchIntentForPackage(info.packageName)
            shouldIncludeApp(launchIntent, info.flags, info.packageName)
        }
        .map { info ->
            AppEntry(
                packageName = info.packageName,
                label = pm.getApplicationLabel(info).toString(),
                icon = try {
                    pm.getApplicationIcon(info).toImageBitmap()
                } catch (_: Exception) {
                    null
                },
            )
        }
        .sortedBy { it.label.lowercase() }
}

/** Converts any [Drawable] to an [ImageBitmap] for use in Compose without extra dependencies. */
private fun Drawable.toImageBitmap(): ImageBitmap {
    val bitmap = Bitmap.createBitmap(
        intrinsicWidth.coerceAtLeast(1),
        intrinsicHeight.coerceAtLeast(1),
        Bitmap.Config.ARGB_8888,
    )
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap.asImageBitmap()
}
