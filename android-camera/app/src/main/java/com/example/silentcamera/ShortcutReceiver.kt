package com.example.silentcamera

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class ShortcutReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (BackgroundCameraService.isRunning(context)) {
            context.stopService(Intent(context, BackgroundCameraService::class.java))
        } else {
            val svc = Intent(context, BackgroundCameraService::class.java)
            ContextCompat.startForegroundService(context, svc)
        }
    }
}
