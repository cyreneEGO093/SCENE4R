package com.omarea.vtools.activities

import android.app.TimePickerDialog
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Checkable
import android.widget.CompoundButton
import android.widget.Toast
import com.omarea.common.model.SelectItem
import com.omarea.common.shared.FileWrite
import com.omarea.common.ui.DialogItemChooser2
import com.omarea.data.EventType
import com.omarea.krscript.executor.ExtractAssets
import com.omarea.model.CustomTaskAction
import com.omarea.model.TaskAction
import com.omarea.model.TriggerInfo
import com.omarea.scene_mode.TriggerManager
import com.omarea.store.TriggerStorage
import com.omarea.vtools.R
import java.io.File
import java.io.FilenameFilter
import java.net.URLDecoder
import java.util.*
import kotlin.collections.ArrayList
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Switch
import android.widget.TextView

class ActivityTrigger : ActivityBase() {
    private val system_scene_task_enable: Switch
        get() = findViewById(R.id.system_scene_task_enable)
    private val task_custom_actions: TextView
        get() = findViewById(R.id.task_custom_actions)
    private val task_custom_edit: ImageButton
        get() = findViewById(R.id.task_custom_edit)
    private val task_standby_mode: LinearLayout
        get() = findViewById(R.id.task_standby_mode)
    private val task_standby_off: RadioButton
        get() = findViewById(R.id.task_standby_off)
    private val task_standby_on: RadioButton
        get() = findViewById(R.id.task_standby_on)
    private val task_zen_mode: LinearLayout
        get() = findViewById(R.id.task_zen_mode)
    private val task_zen_mode_off: RadioButton
        get() = findViewById(R.id.task_zen_mode_off)
    private val task_zen_mode_on: RadioButton
        get() = findViewById(R.id.task_zen_mode_on)
    private val trigger_battery_low: CheckBox
        get() = findViewById(R.id.trigger_battery_low)
    private val trigger_boot_completed: CheckBox
        get() = findViewById(R.id.trigger_boot_completed)
    private val trigger_power_connected: RadioButton
        get() = findViewById(R.id.trigger_power_connected)
    private val trigger_power_disconnected: RadioButton
        get() = findViewById(R.id.trigger_power_disconnected)
    private val trigger_screen_off: RadioButton
        get() = findViewById(R.id.trigger_screen_off)
    private val trigger_screen_on: RadioButton
        get() = findViewById(R.id.trigger_screen_on)
    private val trigger_time_end: TextView
        get() = findViewById(R.id.trigger_time_end)
    private val trigger_time_limit: Switch
        get() = findViewById(R.id.trigger_time_limit)
    private val trigger_time_start: TextView
        get() = findViewById(R.id.trigger_time_start)

    private lateinit var triggerInfo: TriggerInfo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_trigger)
        setBackArrow()

        // 读取或初始化任务模型
        var id: String = "SCENE_TRIGGER_" + UUID.randomUUID().toString()
        intent?.run {
            if (hasExtra("id")) {
                intent.getStringExtra("id")?.run {
                    id = this
                }
            }
        }
        val task = TriggerStorage(this@ActivityTrigger).load(id)
        triggerInfo = if (task == null) TriggerInfo(id) else task

        // 时间选择
        trigger_time_start.setOnClickListener {
            TimePickerDialog(this, TimePickerDialog.OnTimeSetListener { view, hourOfDay, minute ->
                trigger_time_start.setText(String.format(getString(R.string.format_hh_mm), hourOfDay, minute))
                triggerInfo.timeStart = hourOfDay * 60 + minute
            }, triggerInfo.timeStart / 60, triggerInfo.timeStart % 60, true).show()
        }
        trigger_time_end.setOnClickListener {
            TimePickerDialog(this, TimePickerDialog.OnTimeSetListener { view, hourOfDay, minute ->
                trigger_time_end.setText(String.format(getString(R.string.format_hh_mm), hourOfDay, minute))
                triggerInfo.timeEnd = hourOfDay * 60 + minute
            }, triggerInfo.timeEnd / 60, triggerInfo.timeEnd % 60, true).show()
        }
        trigger_time_limit.setOnClickListener {
            triggerInfo.timeLimited = (it as Checkable).isChecked
        }

        // 设定单选关系
        oneOf(trigger_screen_on, trigger_screen_off)
        oneOf(trigger_power_connected, trigger_power_disconnected)

        oneOf(task_standby_on, task_standby_off)
        oneOf(task_zen_mode_on, task_zen_mode_off)

        // 更新选中状态
        updateUI()
        // 勿扰模式
        task_zen_mode.visibility = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) View.VISIBLE else View.GONE
        // 待机模式
        task_standby_mode.visibility = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) View.VISIBLE else View.GONE

        // 自定义动作点击
        task_custom_edit.setOnClickListener {
            customEditClick()
        }
    }

    private fun customEditClick() {
        ExtractAssets(this).extractResources("custom-command")

        val dirPath = FileWrite.getPrivateFilePath(this, "custom-command")
        val dir = File(dirPath)
        if (dir.exists()) {
            val files = dir.listFiles(object : FilenameFilter {
                override fun accept(dir: File?, name: String?): Boolean {
                    return name?.endsWith(".sh") == true
                }
            })

            val fileNames = files?.map {
                SelectItem().apply {
                    val name = URLDecoder.decode(it.name)
                    title = name
                    value = it.absolutePath
                    selected = triggerInfo.customTaskActions?.find { it.Name == name } != null
                }
            }?.sortedBy { it.title }
            val selectedItems = ArrayList<SelectItem>()
            triggerInfo.customTaskActions?.forEach { item ->
                val name = item.Name
                val r = fileNames?.find { it.title == name }
                if (r != null) {
                    selectedItems.add(r)
                }
            }

            if (fileNames != null && fileNames.size > 0) {
                DialogItemChooser2(themeMode.isDarkMode, ArrayList(fileNames), ArrayList(selectedItems), true, object : DialogItemChooser2.Callback {
                    override fun onConfirm(selected: List<SelectItem>, status: BooleanArray) {
                        triggerInfo.customTaskActions = ArrayList(selected.map {
                            CustomTaskAction().apply {
                                Name = it.title
                                Command = "sh '" + it.value + "'"
                            }
                        })
                        updateUI()
                    }
                }).setTitle("选择要执行的命令").show(supportFragmentManager, "custom-action-picker")
            } else {
                Toast.makeText(this, "你还没创建自定义命令", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "你还没创建自定义命令", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUI() {
        triggerInfo.run {
            system_scene_task_enable.isChecked = enabled
            trigger_time_limit.isChecked = triggerInfo.timeLimited
            // 触发时间
            trigger_time_start.setText(String.format(getString(R.string.format_hh_mm), triggerInfo.timeStart / 60, triggerInfo.timeStart % 60))
            trigger_time_end.setText(String.format(getString(R.string.format_hh_mm), triggerInfo.timeEnd / 60, triggerInfo.timeEnd % 60))

            // 触发事件
            events?.run {
                trigger_boot_completed.isChecked = contains(EventType.BOOT_COMPLETED)
                trigger_screen_on.isChecked = contains(EventType.SCREEN_ON)
                trigger_screen_off.isChecked = contains(EventType.SCREEN_OFF)
                trigger_battery_low.isChecked = contains(EventType.BATTERY_LOW)
                trigger_power_connected.isChecked = contains(EventType.POWER_CONNECTED)
                trigger_power_disconnected.isChecked = contains(EventType.POWER_DISCONNECTED)
            }

            // 功能动作
            taskActions?.run {
                task_standby_on.isChecked = contains(TaskAction.STANDBY_MODE_ON)
                task_standby_off.isChecked = contains(TaskAction.STANDBY_MODE_OFF)
                task_zen_mode_on.isChecked = contains(TaskAction.ZEN_MODE_ON)
                task_zen_mode_off.isChecked = contains(TaskAction.ZEN_MODE_OFF)
            }

            customTaskActions?.run {
                val str = this.map { it.Name }.toTypedArray().joinToString("\n\n").trim()
                task_custom_actions.text = str
            }
        }
    }

    private fun oneOf(radioButton1: CompoundButton, radioButton2: CompoundButton) {
        radioButton1.setOnClickListener {
            if ((it as CompoundButton).isChecked && it.tag == true) {
                it.tag = false
                it.isChecked = false
            } else {
                it.tag = it.isChecked
                radioButton2.tag = false
                radioButton2.isChecked = false
            }
        }
        radioButton2.setOnClickListener {
            if ((it as CompoundButton).isChecked && it.tag == true) {
                it.tag = false
                it.isChecked = false
            } else {
                it.tag = it.isChecked
                radioButton1.tag = false
                radioButton1.isChecked = false
            }
        }
    }


    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.save, menu)
        return true
    }

    //右上角菜单
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_save -> {
                saveConfigAndFinish()
            }
        }
        return super.onOptionsItemSelected(item)
    }

    // 保存并关闭界面
    private fun saveConfigAndFinish() {
        triggerInfo.enabled = system_scene_task_enable.isChecked

        // triggerInfo.expireDate = if (taks_repeat.isChecked) 0 else (GetUpTime(timingTaskInfo.triggerTimeMinutes).nextGetUpTime)
        // triggerInfo.afterScreenOff = task_after_screen_off.isChecked
        // triggerInfo.beforeExecuteConfirm = task_before_execute_confirm.isChecked
        // triggerInfo.chargeOnly = task_charge_only.isChecked
        // triggerInfo.batteryCapacityRequire = if(task_battery_capacity_require.isChecked) (task_battery_capacity.text).toString().toInt() else 0

        triggerInfo.taskActions = ArrayList<TaskAction>().apply {
            task_standby_on.isChecked && add(TaskAction.STANDBY_MODE_ON)
            task_standby_off.isChecked && add(TaskAction.STANDBY_MODE_OFF)
            task_zen_mode_on.isChecked && add(TaskAction.ZEN_MODE_ON)
            task_zen_mode_off.isChecked && add(TaskAction.ZEN_MODE_OFF)
        }

        triggerInfo.events = ArrayList<EventType>().apply {
            trigger_boot_completed.isChecked && add(EventType.BOOT_COMPLETED)
            trigger_screen_on.isChecked && add(EventType.SCREEN_ON)
            trigger_screen_off.isChecked && add(EventType.SCREEN_OFF)
            trigger_battery_low.isChecked && add(EventType.BATTERY_LOW)
            trigger_power_connected.isChecked && add(EventType.POWER_CONNECTED)
            trigger_power_disconnected.isChecked && add(EventType.POWER_DISCONNECTED)
        }

        // timingTaskInfo.taskId = taskId

        TriggerManager(this).setTriggerAndSave(triggerInfo)

        finish()
    }

    override fun onPause() {
        super.onPause()
    }
}