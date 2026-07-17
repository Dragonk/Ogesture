package com.ogesture.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.ogesture.data.SettingsRepository
import kotlinx.coroutines.runBlocking

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        val pending = goAsync()
        try {
            val enabled = runBlocking { SettingsRepository.get(context).isMasterEnabled() }
            if (!enabled) return
            if (!Settings.canDrawOverlays(context)) {
                Log.w(TAG, "Skipping auto-start: overlay permission missing")
                return
            }
            EdgeOverlayService.start(context)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to auto-start on boot", t)
        } finally {
            pending.finish()
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
