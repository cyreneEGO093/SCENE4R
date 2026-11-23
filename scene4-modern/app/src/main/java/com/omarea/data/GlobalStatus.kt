package com.omarea.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.omarea.library.shell.BatteryUtils
import com.omarea.permissions.CheckRootStatus.Companion.lastCheckResult
import com.omarea.utils.ElectricityUnit

object GlobalStatus {
    var temperatureCurrent = -1f
        private set
    private var batteryTempTime = 0L
    fun setBatteryTemperature(temperature: Float) {
        batteryTempTime = System.currentTimeMillis()
        temperatureCurrent = temperature
    }

    var batteryVoltageMv: Int = -1
        private set
    private var batteryVoltageTime = 0L
    fun setBatteryVoltageMv(voltageMv: Int) {
        if (voltageMv > 0) {
            batteryVoltageTime = System.currentTimeMillis()
            batteryVoltageMv = voltageMv
        }
    }

    fun updateBatteryVoltageMv(context: Context): Int {
        if (batteryVoltageMv <= 0 || System.currentTimeMillis() - batteryVoltageTime >= 3000L) {
            try {
                val intent = context.applicationContext.registerReceiver(
                    null,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                )
                if (intent != null) {
                    val raw = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                    val norm = ElectricityUnit.normalizeVoltageMv(raw)
                    if (norm > 0) {
                        setBatteryVoltageMv(norm)
                    }
                }
            } catch (_: Exception) {
            }
        }
        return batteryVoltageMv
    }

    /**
     * 获取实时温度（如果举例上次更新已过去较长时间，使用ROOT权限重新获取温度）
     */
    fun updateBatteryTemperature(): Float {
        // 将更新频率控制在>5秒
        if (lastCheckResult && System.currentTimeMillis() - 5000 >= batteryTempTime) {
            // 更新电池温度
            val temperature = BatteryUtils.getBatteryTemperature().temperature
            if (temperature > 10 && temperature < 100) {
                setBatteryTemperature(temperature)
            }
        }
        return temperatureCurrent
    }

    var batteryCapacity = -1
    var batteryCurrentNow: Long = -1
    var batteryStatus = BatteryManager.BATTERY_STATUS_UNKNOWN
    var lastPackageName = ""
    var homeMessage: String? = null
}