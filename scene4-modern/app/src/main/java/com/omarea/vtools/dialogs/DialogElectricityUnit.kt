package com.omarea.vtools.dialogs

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.TextView
import com.omarea.common.ui.DialogHelper
import com.omarea.data.EventBus
import com.omarea.data.EventType
import com.omarea.data.GlobalStatus
import com.omarea.store.ChargeSpeedStore
import com.omarea.store.SpfConfig
import com.omarea.utils.ElectricityUnit
import com.omarea.vtools.R
import java.util.*

class DialogElectricityUnit {
    fun showDialog(context: Context) {
        val globalSPF = context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)

        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        var currentNow = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        var defaultUnit = if (Build.MANUFACTURER.toUpperCase() == "XIAOMI") {
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
        var unit = globalSPF.getInt(SpfConfig.GLOBAL_SPF_CURRENT_NOW_UNIT, defaultUnit)
        var dualCell = globalSPF.getBoolean(SpfConfig.GLOBAL_SPF_DUAL_CELL, false)
        var alertDialog: DialogHelper.DialogWrap? = null
        val dialog = LayoutInflater.from(context).inflate(R.layout.dialog_electricity_unit, null)
        val electricity_adj_unit = dialog.findViewById<TextView>(R.id.electricity_adj_unit)
        val electricity_adj_sample = dialog.findViewById<TextView>(R.id.electricity_adj_sample)
        val dualCellCheck = dialog.findViewById<CheckBox>(R.id.electricity_adj_dual_cell)

        val updateSample = {
            electricity_adj_unit.text = unit.toString()
            val currentMA = ElectricityUnit.calcCurrentMa(currentNow, unit, dualCell)
            val powerWatts = ElectricityUnit.calcPowerWatts(
                currentMA,
                GlobalStatus.updateBatteryVoltageMv(context),
                dualCell
            )
            val powerStr = ElectricityUnit.formatPowerWatts(powerWatts, 2, true)
            electricity_adj_sample.text =
                (if (currentMA >= 0) "+" else "") + currentMA + "mA" +
                    (if (powerStr.isNotEmpty()) " ($powerStr)" else "")
        }

        dualCellCheck.isChecked = dualCell
        dualCellCheck.setOnCheckedChangeListener { _, isChecked ->
            dualCell = isChecked
            updateSample()
        }

        dialog.findViewById<ImageButton>(R.id.electricity_adj_minus).setOnClickListener {
            if (unit == -1) {
                unit = -10
            } else if (unit == 1) {
                unit = -1
            } else if (unit > 0) {
                unit /= 10
            } else if (unit > -1000 * 1000 * 100) {
                unit *= 10
            }
            updateSample()
        }
        dialog.findViewById<ImageButton>(R.id.electricity_adj_plus).setOnClickListener {
            if (unit == -1) {
                unit = 1
            } else if (unit < 0) {
                unit /= 10
            } else if (unit < 1000 * 1000 * 100) {
                unit *= 10
            }
            updateSample()
        }
        dialog.findViewById<Button>(R.id.btn_confirm).setOnClickListener {
            globalSPF.edit()
                .putInt(SpfConfig.GLOBAL_SPF_CURRENT_NOW_UNIT, unit)
                .putBoolean(SpfConfig.GLOBAL_SPF_DUAL_CELL, dualCell)
                .apply()
            EventBus.publish(EventType.SCENE_MODE_ACTION)
            alertDialog?.dismiss()
        }
        updateSample()
        val handler = Handler(Looper.getMainLooper())
        val timer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    handler.post {
                        currentNow = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                        try {
                            updateSample()
                        } catch (ex: Exception) {
                        }
                    }
                }
            }, 10, 1000)
        }

        alertDialog = DialogHelper.customDialog(context, dialog, false).setOnDismissListener {
            ChargeSpeedStore(context).clearAll()
            timer.cancel()
        }
    }
}
