package com.khereba1.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs.isAutoStart(context)) {
            val svcIntent = Intent(context, RepeaterService::class.java).apply {
                action = RepeaterService.ACTION_START
            }
            ContextCompat.startForegroundService(context, svcIntent)
        }
    }
}
