package com.omarea.scene_mode

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.omarea.Scene
import com.omarea.data.EventType
import com.omarea.data.GlobalStatus
import com.omarea.data.IEventReceiver
import com.omarea.library.shell.BatteryUtils
import com.omarea.model.BatteryStatus
import com.omarea.store.BatteryHistoryStore
import com.omarea.store.SpfConfig
import com.omarea.utils.ElectricityUnit
import com.omarea.vtools.R

/**
 * 常驻通知
 */
internal class AlwaysNotification(
        private var context: Context,
        notify: Boolean = false,
        override val isAsync: Boolean = false) : ModeSwitcher(), IEventReceiver {
    override fun eventFilter(eventType: EventType): Boolean {
        return eventType == EventType.SCENE_MODE_ACTION
    }

    override fun onReceive(eventType: EventType, data: HashMap<String, Any>?) {
        notify(false)
    }

    override fun onSubscribe() {

    }

    override fun onUnsubscribe() {

    }

    private var showNofity: Boolean = false
    private var notification: Notification? = null
    private var notificationManager: NotificationManager? = null
    private var batteryHistoryStore: BatteryHistoryStore? = null
    private var globalSPF = context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
    private var batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val batteryUtils = BatteryUtils()

    private fun getAppName(packageName: String): CharSequence? {
        try {
            return context.packageManager.getPackageInfo(packageName, 0).applicationInfo?.loadLabel(context.packageManager) ?: packageName
        } catch (ex: Exception) {
            return packageName
        }
    }

    private fun getBatteryIcon(capacity: Int): Int {
        if (capacity < 20)
            return R.drawable.b_0
        if (capacity < 30)
            return R.drawable.b_1
        if (capacity < 70)
            return R.drawable.b_2

        return R.drawable.b_3
    }

    //显示通知
    internal fun notify(saveLog: Boolean = false) {
        try {
            var currentMode = getCurrentPowerMode()
            if (currentMode.length == 0) {
                currentMode = ""
            }

            var currentApp = getCurrentPowermodeApp()
            if (currentApp.isEmpty()) {
                currentApp = "android"

                notifyPowerModeChange(currentApp, currentMode, false)
            } else {
                notifyPowerModeChange(currentApp, currentMode, saveLog)
            }
        } catch (ex: Exception) {
        }
    }

    private fun updateBatteryStatus() {
        // 电流
        GlobalStatus.batteryCurrentNow = ElectricityUnit.calcCurrentMa(
            batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            globalSPF
        )

        // 电量
        GlobalStatus.batteryCapacity = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 状态
            val batteryStatus = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            if (batteryStatus != BatteryManager.BATTERY_STATUS_UNKNOWN) {
                GlobalStatus.batteryStatus = batteryStatus;
            }
        }

        GlobalStatus.updateBatteryTemperature() // 触发温度数据更新
    }

    private fun notifyPowerModeChange(packageName: String, mode: String, saveLog: Boolean = false) {
        // 开机5分钟之内不统计耗电记录，避免刚开机时系统服务繁忙导致数据不准确
        if (saveLog && SystemClock.elapsedRealtime() > 300000L) {
            val status = BatteryStatus()
            status.packageName = packageName
            status.mode = mode
            status.time = System.currentTimeMillis()
            status.temperature = GlobalStatus.temperatureCurrent
            status.status = GlobalStatus.batteryStatus
            status.io = GlobalStatus.batteryCurrentNow.toInt()

            if (batteryHistoryStore == null) {
                batteryHistoryStore = BatteryHistoryStore(context)
            }
            batteryHistoryStore!!.insertHistory(status)
        }

        if (!showNofity) {
            return
        }

        var batteryIconRes = R.drawable.b_4
        var batteryIO = ""
        var batteryTemp = ""
        val modeIconRes = getModImage(mode)

        try {
            updateBatteryStatus()

            batteryIO = "${GlobalStatus.batteryCurrentNow}mA"
            batteryTemp = "${GlobalStatus.temperatureCurrent}°C"

            batteryIconRes = if (GlobalStatus.batteryStatus == BatteryManager.BATTERY_STATUS_DISCHARGING) {
                getBatteryIcon(GlobalStatus.batteryCapacity)
            } else {
                R.drawable.b_4
            }
        } catch (ex: Exception) {
        }

        val appTitle = getAppName(packageName)
        val modeName = getModName(mode)
        val batterySummary = "$batteryIO ${GlobalStatus.batteryCapacity}% $batteryTemp"
        val powerWatts = ElectricityUnit.calcPowerWatts(context, GlobalStatus.batteryCurrentNow, globalSPF)
        val powerText = ElectricityUnit.formatPowerWatts(powerWatts, 2, false)
        val batteryTitle = if (powerText.isNotEmpty()) "电池 $powerText" else "电池"

        val remoteViews = this.getRemoteViews().apply {
            setTextViewText(R.id.notify_title, appTitle)
            setTextViewText(R.id.notify_text, modeName)
            setTextViewText(R.id.notify_battery_title, batteryTitle)
            setTextViewText(R.id.notify_battery_text, batterySummary)
            setImageViewResource(R.id.notify_mode, modeIconRes)
            setImageViewResource(R.id.notify_battery_icon, batteryIconRes)
        }

        val clickFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val clickIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, ReceiverSceneMode::class.java).putExtra("packageName", packageName),
                clickFlags)

        val icon = getModIcon(mode)
        notificationManager = context.getSystemService(Service.NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (notificationManager!!.getNotificationChannel("vtool-long-time") == null) {
                notificationManager!!.createNotificationChannel(NotificationChannel("vtool-long-time", "常驻通知", NotificationManager.IMPORTANCE_LOW))
            }
            NotificationCompat.Builder(context, "vtool-long-time")
        } else {
            NotificationCompat.Builder(context)
        }
        notification =
                builder.setSmallIcon(icon)
                        .setContentTitle(appTitle)
                        .setContentText("$modeName · ${if (powerText.isNotEmpty()) "$powerText " else ""}$batterySummary")
                        .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                        .setCustomContentView(remoteViews)
                        .setCustomBigContentView(remoteViews)
                        .setCustomHeadsUpContentView(remoteViews)
                        .setShowWhen(false)
                        .setOnlyAlertOnce(true)
                        .setAutoCancel(false)
                        .setOngoing(true)
                        .setContentIntent(clickIntent)
                        .build()

        notification!!.flags = Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE
        notificationManager?.notify(0x100, notification)
    }

    private fun getRemoteViews(): RemoteViews {
        val layout = (if (Scene.isNightMode && globalSPF.getBoolean(SpfConfig.GLOBAL_NIGHT_BLACK_NOTIFICATION, false)) {
            R.layout.layout_notification_dark
        } else {
            R.layout.layout_notification
        })
        return RemoteViews(context.packageName, layout)
    }

    //隐藏通知
    internal fun hideNotify() {
        if (notification != null) {
            notificationManager?.cancel(0x100)
            notification = null
            notificationManager = null
        }
    }

    internal fun setNotify(show: Boolean) {
        this.showNofity = show
        if (!show) {
            hideNotify()
        } else {
            notify()
        }
    }

    init {
        showNofity = notify
    }
}
