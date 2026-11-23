package com.omarea.utils

import android.content.Context
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Build
import com.omarea.data.GlobalStatus
import com.omarea.store.SpfConfig
import java.util.*
import kotlin.math.abs

class ElectricityUnit {
    companion object {
        fun calcCurrentMa(rawCurrentNow: Long, unit: Int, dualCell: Boolean): Long {
            val safeUnit = if (unit == 0) SpfConfig.GLOBAL_SPF_CURRENT_NOW_UNIT_DEFAULT else unit
            val ma = rawCurrentNow / safeUnit
            return if (dualCell) ma * 2L else ma
        }

        fun calcCurrentMa(rawCurrentNow: Long, globalSPF: SharedPreferences): Long {
            val unit = globalSPF.getInt(
                SpfConfig.GLOBAL_SPF_CURRENT_NOW_UNIT,
                SpfConfig.GLOBAL_SPF_CURRENT_NOW_UNIT_DEFAULT
            )
            val dualCell = globalSPF.getBoolean(SpfConfig.GLOBAL_SPF_DUAL_CELL, false)
            return calcCurrentMa(rawCurrentNow, unit, dualCell)
        }

        fun normalizeVoltageMv(rawVoltage: Int): Int {
            return when {
                rawVoltage <= 0 -> -1
                rawVoltage > 100000 -> rawVoltage / 1000
                rawVoltage < 100 -> rawVoltage * 1000
                else -> rawVoltage
            }
        }

        fun calcPowerWatts(currentMa: Long, voltageMv: Int, dualCell: Boolean): Float? {
            if (currentMa <= -50000L || currentMa >= 50000L) {
                return null
            }
            val normMv = normalizeVoltageMv(voltageMv)
            if (normMv <= 0) {
                return null
            }
            val effectiveMv = if (dualCell && normMv >= 6000) normMv / 2.0 else normMv.toDouble()
            return ((currentMa.toDouble() * effectiveMv) / 1_000_000.0).toFloat()
        }

        fun calcPowerWatts(context: Context, currentMa: Long, globalSPF: SharedPreferences): Float? {
            val dualCell = globalSPF.getBoolean(SpfConfig.GLOBAL_SPF_DUAL_CELL, false)
            val voltageMv = GlobalStatus.updateBatteryVoltageMv(context)
            return calcPowerWatts(currentMa, voltageMv, dualCell)
        }

        fun formatPowerWatts(watts: Float?, decimals: Int = 2, showPlus: Boolean = false): String {
            if (watts == null || watts.isNaN() || watts.isInfinite()) {
                return ""
            }
            val zeroThreshold = if (decimals == 1) 0.05f else 0.005f
            val safeWatts = if (abs(watts) < zeroThreshold) 0f else watts
            val fmt = if (decimals == 1) "%.1fW" else "%.2fW"
            val formatted = String.format(Locale.US, fmt, safeWatts)
            return if (showPlus && safeWatts > 0f) "+$formatted" else formatted
        }
    }

    public fun getDefaultElectricityUnit(context: Context): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val currentNow = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        return if (Build.MANUFACTURER.toUpperCase(Locale.getDefault()) == "XIAOMI") {
            SpfConfig.GLOBAL_SPF_CURRENT_NOW_UNIT_DEFAULT
        } else {
            if (GlobalStatus.batteryStatus == BatteryManager.BATTERY_STATUS_DISCHARGING) {
                if (currentNow > 20000) {
                    -1000
                } else if (currentNow < -20000) {
                    1000
                } else if (currentNow > 0) {
                    -1
                } else {
                    1
                }
            } else if (GlobalStatus.batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING) {
                if (currentNow > 20000) {
                    1000
                } else if (currentNow < -20000) {
                    -1000
                } else if (currentNow > 0) {
                    1
                } else {
                    -1
                }
            } else {
                SpfConfig.GLOBAL_SPF_CURRENT_NOW_UNIT_DEFAULT
            }
        }
    }
}