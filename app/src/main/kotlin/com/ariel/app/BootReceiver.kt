package com.ariel.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Starting SirenService on boot")
            val serviceIntent = Intent(context, SirenService::class.java).apply {
                action = "START_MONITORING"
            }
            context.startForegroundService(serviceIntent)
        }
    }
}
