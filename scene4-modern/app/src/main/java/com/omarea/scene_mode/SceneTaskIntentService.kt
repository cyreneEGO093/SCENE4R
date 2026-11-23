package com.omarea.scene_mode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.omarea.data.EventBus
import com.omarea.data.EventType
import com.omarea.data.GlobalStatus
import com.omarea.data.IEventReceiver
import com.omarea.library.basic.ScreenState
import com.omarea.store.TimingTaskStorage
import com.omarea.vtools.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SceneTaskIntentService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(991, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(991, notification)
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
            .setContentText("正在执行定时任务")
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serviceScope.launch {
            try {
                val taskId = intent?.getStringExtra("taskId")
                if (taskId != null) {
                    executeTask(taskId)
                }
            } finally {
                ServiceCompat.stopForeground(this@SceneTaskIntentService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun executeTask(taskId: String) {
        val context = this
        val timingTask = TimingTaskStorage(context).load(taskId) ?: return

        val expired = timingTask.expireDate > 0 && timingTask.expireDate <= System.currentTimeMillis()
        if (!timingTask.enabled || expired) {
            if (expired && timingTask.enabled) {
                timingTask.enabled = false
                TimingTaskManager(this@SceneTaskIntentService).setTaskAndSave(timingTask)
            } else {
                TimingTaskManager(this@SceneTaskIntentService).cancelTask(timingTask)
            }
            return
        }

        TimingTaskManager(this@SceneTaskIntentService).setTask(timingTask)

        if (timingTask.chargeOnly && GlobalStatus.batteryStatus == BatteryManager.BATTERY_STATUS_DISCHARGING) {
            Toast.makeText(context, "未在充电状态，跳过定时任务", Toast.LENGTH_LONG).show()
        } else if (timingTask.batteryCapacityRequire > 0 && GlobalStatus.batteryStatus == BatteryManager.BATTERY_STATUS_DISCHARGING && GlobalStatus.batteryCapacity < timingTask.batteryCapacityRequire) {
            Toast.makeText(context, "电量低于" + timingTask.batteryCapacityRequire + "%，跳过定时任务", Toast.LENGTH_LONG).show()
        } else if (timingTask.afterScreenOff && ScreenState(context).isScreenOn()) {
            EventBus.subscribe(ScreenDelayTaskReceiver(taskId, context.applicationContext))
        } else {
            TaskActionsExecutor(timingTask.taskActions, timingTask.customTaskActions, context).run()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    class ScreenDelayTaskReceiver(private val taskId: String, private val context: Context, override val isAsync: Boolean = false) : IEventReceiver {
        override fun onReceive(eventType: EventType, data: HashMap<String, Any>?) {
            val taskIntent = Intent(context, SceneTaskIntentService::class.java)
            taskIntent.putExtra("taskId", taskId)
            taskIntent.action = taskId

            try {
                ContextCompat.startForegroundService(this.context, taskIntent)
                EventBus.unsubscribe(this)
            } catch (_: Exception) {
                // Keep subscribed or drop gracefully if FGS start is denied in background
                EventBus.unsubscribe(this)
            }
        }

        override fun onSubscribe() {}
        override fun onUnsubscribe() {}

        override fun eventFilter(eventType: EventType): Boolean {
            return eventType == EventType.SCREEN_OFF
        }
    }
}
