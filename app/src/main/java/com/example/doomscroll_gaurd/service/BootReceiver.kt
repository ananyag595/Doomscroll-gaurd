package com.example.doomscroll_gaurd.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * BroadcastReceiver that starts [GuardService] after the device boots.
 *
 * Listens for both [Intent.ACTION_BOOT_COMPLETED] (standard Android) and
 * `QUICKBOOT_POWERON` (HTC/some OEM devices) so the service is reliably
 * restarted regardless of the device manufacturer.
 *
 * Registered in AndroidManifest.xml with android:exported="true" and the
 * RECEIVE_BOOT_COMPLETED permission.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.i(TAG, "Boot broadcast received ($action) — starting GuardService")
            ContextCompat.startForegroundService(
                context,
                Intent(context, GuardService::class.java)
            )
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
