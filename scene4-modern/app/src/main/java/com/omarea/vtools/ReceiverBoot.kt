package com.omarea.vtools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.omarea.vtools.services.BootService

class ReceiverBoot : BroadcastReceiver() {
    companion object {
        @Volatile
        var bootCompleted: Boolean = false
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }
        if (bootCompleted) {
            return
        }

        try {
            val service = Intent(context, BootService::class.java)
            ContextCompat.startForegroundService(context, service)
            bootCompleted = true
        } catch (_: Exception) {
        }
    }
}
