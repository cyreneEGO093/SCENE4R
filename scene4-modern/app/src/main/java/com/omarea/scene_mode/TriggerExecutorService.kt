package com.omarea.scene_mode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.omarea.store.TriggerStorage
import com.omarea.vtools.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TriggerExecutorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(992, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(992, notification)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel("vtool-task", getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(this, "vtool-task")
            .setSmallIcon(R.drawable.ic_menu_digital)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("正在执行场景触发器")
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serviceScope.launch {
            try {
                val triggers = intent?.getStringArrayListExtra("triggers")
                if (triggers != null) {
                    executeTriggers(triggers)
                }
            } finally {
                ServiceCompat.stopForeground(this@TriggerExecutorService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun executeTriggers(triggers: ArrayList<String>) {
        val context = this
        val storage = TriggerStorage(this)
        triggers.forEach {
            storage.load(it)?.run {
                TaskActionsExecutor(taskActions, customTaskActions, context).run()
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
