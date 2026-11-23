package com.omarea.vtools.fragments

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Context.ACTIVITY_SERVICE
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.*
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.CompoundButton
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.Switch
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.omarea.Scene
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.ui.DialogHelper
import com.omarea.common.ui.ProgressBarDialog
import com.omarea.data.EventBus
import com.omarea.data.EventType
import com.omarea.data.GlobalStatus
import com.omarea.library.shell.*
import com.omarea.model.CpuCoreInfo
import com.omarea.scene_mode.CpuConfigInstaller
import com.omarea.scene_mode.FasRsManager
import com.omarea.scene_mode.ModeSwitcher
import com.omarea.scene_mode.UperfManager
import com.omarea.store.SpfConfig
import com.omarea.ui.AdapterCpuCores
import com.omarea.utils.AccessibleServiceHelper
import com.omarea.utils.ElectricityUnit
import com.omarea.vtools.R
import com.omarea.vtools.activities.ActivityCpuControl
import com.omarea.vtools.activities.ActivityFileSelector
import com.omarea.vtools.activities.ActivitySchedulerTuning
import com.omarea.vtools.dialogs.DialogElectricityUnit
import com.omarea.vtools.dialogs.SchedulerUiHelper
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.Charset
import java.util.*
import kotlin.collections.HashMap
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.omarea.common.ui.OverScrollView
import com.omarea.common.ui.OverScrollGridView
import com.omarea.ui.CpuChartView
import com.omarea.ui.RamChartView

class FragmentHome : androidx.fragment.app.Fragment() {
    private val config_author: TextView
        get() = requireView().findViewById(R.id.config_author)
    private val config_author_icon: ImageButton
        get() = requireView().findViewById(R.id.config_author_icon)
    private val cpu_config_p0: LinearLayout
        get() = requireView().findViewById(R.id.cpu_config_p0)
    private val cpu_config_p1: LinearLayout
        get() = requireView().findViewById(R.id.cpu_config_p1)
    private val cpu_config_p2: LinearLayout
        get() = requireView().findViewById(R.id.cpu_config_p2)
    private val cpu_config_p3: LinearLayout
        get() = requireView().findViewById(R.id.cpu_config_p3)
    private val cpu_mode_delete_outside: ImageButton
        get() = requireView().findViewById(R.id.cpu_mode_delete_outside)
    private val cpu_mode_outside: LinearLayout
        get() = requireView().findViewById(R.id.cpu_mode_outside)
    private val delay_switch: Switch
        get() = requireView().findViewById(R.id.delay_switch)
    private val dynamic_control: Switch
        get() = requireView().findViewById(R.id.dynamic_control)
    private val dynamic_control_opts: LinearLayout
        get() = requireView().findViewById(R.id.dynamic_control_opts)
    private val first_mode: Spinner
        get() = requireView().findViewById(R.id.first_mode)
    private val sleep_mode: Spinner
        get() = requireView().findViewById(R.id.sleep_mode)
    private val cpu_core_count: TextView
        get() = requireView().findViewById(R.id.cpu_core_count)
    private val cpu_core_list: OverScrollGridView
        get() = requireView().findViewById(R.id.cpu_core_list)
    private val cpu_core_total_load: TextView
        get() = requireView().findViewById(R.id.cpu_core_total_load)
    private val home_battery_capacity: TextView
        get() = requireView().findViewById(R.id.home_battery_capacity)
    private val home_battery_edit: ImageView
        get() = requireView().findViewById(R.id.home_battery_edit)
    private val home_battery_now: TextView
        get() = requireView().findViewById(R.id.home_battery_now)
    private val home_battery_temperature: TextView
        get() = requireView().findViewById(R.id.home_battery_temperature)
    private val home_buffers: TextView
        get() = requireView().findViewById(R.id.home_buffers)
    private val home_clear_ram: LinearLayout
        get() = requireView().findViewById(R.id.home_clear_ram)
    private val home_clear_swap: LinearLayout
        get() = requireView().findViewById(R.id.home_clear_swap)
    private val home_cpu_chat: CpuChartView
        get() = requireView().findViewById(R.id.home_cpu_chat)
    private val home_device_name: TextView
        get() = requireView().findViewById(R.id.home_device_name)
    private val home_dirty: TextView
        get() = requireView().findViewById(R.id.home_dirty)
    private val home_gpu_chat: CpuChartView
        get() = requireView().findViewById(R.id.home_gpu_chat)
    private val home_gpu_freq: TextView
        get() = requireView().findViewById(R.id.home_gpu_freq)
    private val home_gpu_load: TextView
        get() = requireView().findViewById(R.id.home_gpu_load)
    private val home_message: TextView
        get() = requireView().findViewById(R.id.home_message)
    private val home_raminfo: RamChartView?
        get() = view?.findViewById(R.id.home_raminfo)
    private val home_raminfo_text: TextView?
        get() = view?.findViewById(R.id.home_raminfo_text)
    private val home_running_time: TextView
        get() = requireView().findViewById(R.id.home_running_time)
    private val home_swap_cached: TextView
        get() = requireView().findViewById(R.id.home_swap_cached)
    private val home_swapstate_chat: RamChartView?
        get() = view?.findViewById(R.id.home_swapstate_chat)
    private val home_zramsize_text: TextView?
        get() = view?.findViewById(R.id.home_zramsize_text)

    private val modeSwitcher = ModeSwitcher()
    private val configInstaller = CpuConfigInstaller()
    private var author: String = ""
    private var configFileInstalled: Boolean = false

    private val powercfgFileLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val data = result.data!!
                if (Build.VERSION.SDK_INT >= 30) {
                    val uri = data.data
                    if (uri != null) {
                        installLocalConfigFromUri(uri)
                    } else {
                        Toast.makeText(requireContext(), "所选的文件没找到！", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    if (data.extras?.containsKey("file") == true) {
                        val path = data.extras!!.getString("file")
                        if (!path.isNullOrEmpty()) {
                            installLocalConfig(path)
                        }
                    }
                }
            }
        }

    private val powercfgOnlineLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                configInstalled()
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    private var CpuFrequencyUtil = CpuFrequencyUtils()
    private lateinit var globalSPF: SharedPreferences
    private var timer: Timer? = null

    private lateinit var spf: SharedPreferences
    private var myHandler = Handler(Looper.getMainLooper())
    private var cpuLoadUtils = CpuLoadUtils()
    private val memoryUtils = MemoryUtils()

    private suspend fun forceKSWAPD(mode: Int): String {
        return withContext(Dispatchers.Default) {
            SwapUtils(context!!).forceKswapd(mode)
        }
    }

    private suspend fun dropCaches() {
        return withContext(Dispatchers.Default) {
            KeepShellPublic.doCmdSync(
                    "sync\n" +
                            "echo 3 > /proc/sys/vm/drop_caches\n" +
                            "echo 1 > /proc/sys/vm/compact_memory")
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activityManager = context!!.getSystemService(ACTIVITY_SERVICE) as ActivityManager
        batteryManager = context!!.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

        globalSPF = requireContext().getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)

        initCpuModesPanel()

        if (!GlobalStatus.homeMessage.isNullOrEmpty()) {
            home_message.visibility = View.VISIBLE
            home_message.text = GlobalStatus.homeMessage
        }

        spf = context!!.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)

        home_clear_ram.setOnClickListener {
            home_raminfo_text?.text = getString(R.string.please_wait)
            GlobalScope.launch(Dispatchers.Main) {
                dropCaches()
                Scene.toast("缓存已清理...", Toast.LENGTH_SHORT)
            }
        }

        home_clear_swap.setOnClickListener {
            home_zramsize_text?.text = getText(R.string.please_wait)
            GlobalScope.launch(Dispatchers.Main) {
                Scene.toast("开始回收少量内存(长按回收更多~)", Toast.LENGTH_SHORT)
                val result = forceKSWAPD(1)
                Scene.toast(result, Toast.LENGTH_SHORT)
            }
        }

        home_clear_swap.setOnLongClickListener {
            home_zramsize_text?.text = getText(R.string.please_wait)
            GlobalScope.launch(Dispatchers.Main) {
                val result = forceKSWAPD(2)
                Scene.toast(result, Toast.LENGTH_SHORT)
            }
            true
        }

        home_battery_edit.setOnClickListener {
            DialogElectricityUnit().showDialog(context!!)
        }

        // 点击CPU核心 查看详细参数
        cpu_core_list.setOnItemClickListener { _, _, position, _ ->
            CpuFrequencyUtil.getCoregGovernorParams(position)?.run {
                val msg = StringBuilder()
                for (param in this) {
                    msg.append("\n")
                    msg.append(param.key)
                    msg.append("：")
                    msg.append(param.value)
                    msg.append("\n")
                }
                DialogHelper.alert(activity!!, "调度器参数", msg.toString())
            }
        }

        home_device_name.text = when (Build.VERSION.SDK_INT) {
            36 -> "Android 16"
            35 -> "Android 15"
            34 -> "Android 14"
            33 -> "Android 13"
            32 -> "Android 12L"
            31 -> "Android 12"
            30 -> "Android 11"
            29 -> "Android 10"
            28 -> "Android 9"
            27 -> "Android 8.1"
            26 -> "Android 8.0"
            25 -> "Android 7.1"
            24 -> "Android 7.0"
            23 -> "Android 6.0"
            else -> if (Build.VERSION.SDK_INT > 36) "Android 16+ (API ${Build.VERSION.SDK_INT})" else "SDK(${Build.VERSION.SDK_INT})"
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onResume() {
        super.onResume()
        if (isDetached) {
            return
        }
        activity!!.title = getString(R.string.app_name)

        updateCpuModesState()
        if (dynamic_control.isChecked && author.isNotEmpty()) {
            // keep latest author state synced when returning from config activities
        }

        maxFreqs.clear()
        minFreqs.clear()
        stopTimer()
        timer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    updateInfo()
                }
            }, 0, 1500)
        }
    }

    private var coreCount = -1
    private lateinit var batteryManager: BatteryManager
    private lateinit var activityManager: ActivityManager

    private var minFreqs = HashMap<Int, String>()
    private var maxFreqs = HashMap<Int, String>()
    fun format1(value: Double): String {
        var bd = BigDecimal(value)
        bd = bd.setScale(1, RoundingMode.HALF_UP)
        return bd.toString()
    }

    @SuppressLint("SetTextI18n")
    private fun updateRamInfo() {
        try {
            val info = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(info)
            val totalMem = (info.totalMem / 1024 / 1024f).toInt()
            val availMem = (info.availMem / 1024 / 1024f).toInt()

            val swapInfo = KeepShellPublic.doCmdSync("free -m | grep Swap")
            var swapTotal = 0
            var swaoUse = 0
            if (swapInfo.contains("Swap")) {
                try {
                    val swapInfos = swapInfo.substring(swapInfo.indexOf(" "), swapInfo.lastIndexOf(" ")).trim()
                    if (Regex("[\\d]+[\\s]{1,}[\\d]{1,}").matches(swapInfos)) {
                        swapTotal = swapInfos.substring(0, swapInfos.indexOf(" ")).trim().toInt()
                        swaoUse = swapInfos.substring(swapInfos.indexOf(" ")).trim().toInt()
                    }
                } catch (ex: java.lang.Exception) {
                }
                // home_swapstate.text = swapInfo.substring(swapInfo.indexOf(" "), swapInfo.lastIndexOf(" ")).trim()
            }

            myHandler.post {
                home_raminfo_text?.text = "${((totalMem - availMem) * 100 / totalMem)}% (${totalMem / 1024 + 1}GB)"
                home_raminfo?.setData(totalMem.toFloat(), availMem.toFloat())
                home_swapstate_chat?.setData(swapTotal.toFloat(), (swapTotal - swaoUse).toFloat())
                home_zramsize_text?.text = (
                        if (swapTotal > 99) {
                            "${(swaoUse * 100.0 / swapTotal).toInt()}% (${format1(swapTotal / 1024.0)}GB)"
                        } else {
                            "${(swaoUse * 100.0 / swapTotal).toInt()}% (${swapTotal}MB)"
                        }
                        )
            }
        } catch (ex: Exception) {
        }
    }

    /**
     * dp转换成px
     */
    private fun dp2px(dpValue: Float): Int {
        val scale = context!!.resources.displayMetrics.density
        return (dpValue * scale + 0.5f).toInt()
    }

    private fun elapsedRealtimeStr(): String {
        val timer = SystemClock.elapsedRealtime() / 1000
        return String.format("%02d:%02d:%02d", timer / 3600, timer % 3600 / 60, timer % 60)
    }

    private var updateTick = 0

    private var batteryCurrentNow = 0L

    @SuppressLint("SetTextI18n")
    private fun updateInfo() {
        if (coreCount < 1) {
            coreCount = CpuFrequencyUtil.getCoreCount()
            myHandler.post {
                try {
                    cpu_core_count.text = "$coreCount 核心"
                } catch (ex: Exception) {
                }
            }
        }
        val cores = ArrayList<CpuCoreInfo>()
        for (coreIndex in 0 until coreCount) {
            val core = CpuCoreInfo(coreIndex)

            core.currentFreq = CpuFrequencyUtil.getCurrentFrequency("cpu$coreIndex")
            if (!maxFreqs.containsKey(coreIndex) || (core.currentFreq != "" && maxFreqs[coreIndex].isNullOrEmpty())) {
                maxFreqs[coreIndex] = CpuFrequencyUtil.getCurrentMaxFrequency("cpu$coreIndex")
            }
            core.maxFreq = maxFreqs[coreIndex]

            if (!minFreqs.containsKey(coreIndex) || (core.currentFreq != "" && minFreqs[coreIndex].isNullOrEmpty())) {
                minFreqs.put(coreIndex, CpuFrequencyUtil.getCurrentMinFrequency("cpu$coreIndex"))
            }
            core.minFreq = minFreqs[coreIndex]
            cores.add(core)
        }
        val loads = cpuLoadUtils.cpuLoad
        for (core in cores) {
            if (loads.containsKey(core.coreIndex)) {
                core.loadRatio = loads[core.coreIndex]!!
            }
        }

        val gpuFreq = GpuUtils.getGpuFreq() + "Mhz"
        val gpuLoad = GpuUtils.getGpuLoad()

        // 电池电流
        batteryCurrentNow = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        // 电量
        val batteryCapacity = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        // 电池温度
        val temperature = GlobalStatus.updateBatteryTemperature()

        updateRamInfo()
        val memInfo = memoryUtils.memoryInfo

        myHandler.post {
            try {
                home_swap_cached.text = "" + (memInfo.swapCached / 1024) + "MB"
                home_buffers.text = "" + (memInfo.buffers / 1024) + "MB"
                home_dirty.text = "" + (memInfo.dirty / 1024) + "MB"

                home_running_time.text = elapsedRealtimeStr()
                if (batteryCurrentNow != Long.MIN_VALUE && batteryCurrentNow != Long.MAX_VALUE) {
                    val currentMa = ElectricityUnit.calcCurrentMa(batteryCurrentNow, globalSPF)
                    val powerStr = context?.let { ctx ->
                        ElectricityUnit.formatPowerWatts(
                            ElectricityUnit.calcPowerWatts(ctx, currentMa, globalSPF),
                            2,
                            false
                        )
                    } ?: ""
                    home_battery_now.text = if (powerStr.isNotEmpty()) "${currentMa}mA  $powerStr" else "${currentMa}mA"
                } else {
                    home_battery_now.text = "--"
                }
                home_battery_capacity.text = "$batteryCapacity%"
                home_battery_temperature.text = "${temperature}°C"

                home_gpu_freq.text = gpuFreq
                home_gpu_load.text = "负载：$gpuLoad%"
                if (gpuLoad > -1) {
                    home_gpu_chat.setData(100.toFloat(), (100 - gpuLoad).toFloat())
                }
                if (loads.containsKey(-1)) {
                    cpu_core_total_load.text = "负载：" + loads[-1]!!.toInt().toString() + "%"
                    home_cpu_chat.setData(100.toFloat(), (100 - loads[-1]!!.toInt()).toFloat())
                }
                if (cpu_core_list.adapter == null) {
                    val layoutParams = cpu_core_list.layoutParams
                    if (cores.size < 6) {
                        layoutParams.height = dp2px(105 * 2F)
                        cpu_core_list.numColumns = 2
                    } else if (cores.size > 12) {
                        layoutParams.height = dp2px(105 * 4F)
                    } else if (cores.size > 8) {
                        layoutParams.height = dp2px(105 * 3F)
                    } else {
                        layoutParams.height = dp2px(105 * 2F)
                    }
                    cpu_core_list.layoutParams = layoutParams
                    cpu_core_list.adapter = AdapterCpuCores(context!!, cores)
                } else {
                    (cpu_core_list.adapter as AdapterCpuCores).setData(cores)
                }

            } catch (ex: Exception) {

            }
        }
        updateTick++
        if (updateTick > 5) {
            updateTick = 0
            minFreqs.clear()
            maxFreqs.clear()
        }
    }

    private fun setModeState() {
        updateCpuModesButtons()
    }

    private fun initCpuModesPanel() {
        bindCpuModeCard(cpu_config_p0, ModeSwitcher.POWERSAVE)
        bindCpuModeCard(cpu_config_p1, ModeSwitcher.BALANCE)
        bindCpuModeCard(cpu_config_p2, ModeSwitcher.PERFORMANCE)
        bindCpuModeCard(cpu_config_p3, ModeSwitcher.FAST)

        dynamic_control.isChecked = globalSPF.getBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL, SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL_DEFAULT)
        dynamic_control_opts.visibility = if (dynamic_control.isChecked) View.VISIBLE else View.GONE
        dynamic_control.setOnClickListener {
            val value = (it as Switch).isChecked
            if (value && !(modeSwitcher.modeConfigCompleted())) {
                it.isChecked = false
                DialogHelper.helpInfo(requireContext(),
                    "设置向导",
                    "在使用此功能前，你需要先完成一些配置~") {
                    chooseConfigSource()
                }
            } else {
                globalSPF.edit().putBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL, value).apply()
                reStartService()
            }
        }
        dynamic_control.setOnCheckedChangeListener { _, isChecked ->
            dynamic_control_opts.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        globalSPF.edit().putBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL_STRICT, false).apply()

        delay_switch.isChecked = globalSPF.getBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL_DELAY, false)
        delay_switch.setOnClickListener {
            val checked = (it as CompoundButton).isChecked
            globalSPF.edit().putBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL_DELAY, checked).apply()
        }

        cpu_mode_delete_outside.setOnClickListener {
            DialogHelper.confirm(requireActivity(), "确定删除?",
                "确定删除安装在 /data/powercfg.sh 的外部配置脚本吗？\n它可能是Scene2遗留下来的，也可能是其它优化模块创建的\n（删除后建议重启手机一次）",
                {
                    configInstaller.removeOutsideConfig()
                    cpu_mode_outside.visibility = View.GONE
                    reStartService()
                    updateCpuModesState()
                })
        }

        first_mode.run {
            when (globalSPF.getString(SpfConfig.GLOBAL_SPF_POWERCFG_FIRST_MODE, ModeSwitcher.BALANCE)) {
                ModeSwitcher.POWERSAVE -> setSelection(0)
                ModeSwitcher.BALANCE -> setSelection(1)
                ModeSwitcher.PERFORMANCE -> setSelection(2)
                ModeSwitcher.FAST -> setSelection(3)
                ModeSwitcher.IGONED -> setSelection(4)
            }
            onItemSelectedListener = ModeOnItemSelectedListener(globalSPF) {
                reStartService()
            }
        }

        sleep_mode.run {
            when (globalSPF.getString(SpfConfig.GLOBAL_SPF_POWERCFG_SLEEP_MODE, ModeSwitcher.POWERSAVE)) {
                ModeSwitcher.POWERSAVE -> setSelection(0)
                ModeSwitcher.BALANCE -> setSelection(1)
                ModeSwitcher.PERFORMANCE -> setSelection(2)
                ModeSwitcher.IGONED -> setSelection(3)
            }
            onItemSelectedListener = ModeOnItemSelectedListener2(globalSPF) {
            }
        }

        val sourceClick = View.OnClickListener { v ->
            if (FasRsManager.isFasRsActive() || UperfManager.isUperfActive()) {
                chooseConfigSource()
            } else if (configInstaller.outsideConfigInstalled() && !UperfManager.isUperfPowerCfgScript()) {
                Snackbar.make(v, "你需要删除外部配置，才能选择其它配置源", Snackbar.LENGTH_LONG).show()
            } else {
                chooseConfigSource()
            }
        }
        config_author_icon.setOnClickListener(sourceClick)
        config_author.setOnClickListener(sourceClick)
    }

    private fun bindCpuModeCard(button: View, mode: String) {
        button.setOnClickListener {
            if (author == ModeSwitcher.SOURCE_SCENE_CUSTOM && !modeSwitcher.modeReplaced(mode)) {
                modifyCpuConfig(mode)
            } else if (!configFileInstalled && author == ModeSwitcher.SOURCE_NONE) {
                DialogHelper.helpInfo(requireContext(), "提示", "尚未选择性能配置源，请先点击右上角选择配置源或安装内置 Uperf-R / FAS-RS 模块。") {
                    chooseConfigSource()
                }
            } else {
                installConfig(mode)
            }
        }
        button.setOnLongClickListener {
            when (author) {
                ModeSwitcher.SOURCE_SCENE_CUSTOM -> {
                    modifyCpuConfig(mode)
                    true
                }
                ModeSwitcher.SOURCE_UPERF -> {
                    SchedulerUiHelper.openTuningActivity(requireActivity(), ActivitySchedulerTuning.SCHEDULER_UPERF, mode)
                    true
                }
                ModeSwitcher.SOURCE_FAS_RS -> {
                    SchedulerUiHelper.openTuningActivity(requireActivity(), ActivitySchedulerTuning.SCHEDULER_FAS_RS, mode)
                    true
                }
                else -> false
            }
        }
    }

    private fun updateCpuModesState() {
        val prevAuthor = author
        val fasRsActive = FasRsManager.isFasRsActive()
        val uperfActive = UperfManager.isUperfActive()
        val realOutside = configInstaller.outsideConfigInstalled() && !UperfManager.isUperfPowerCfgScript()
        configFileInstalled = fasRsActive || uperfActive || realOutside || configInstaller.insideConfigInstalled()
        author = ModeSwitcher.getCurrentSource()

        cpu_mode_outside.visibility = if (realOutside && !uperfActive && !fasRsActive) View.VISIBLE else View.GONE
        config_author.text = ModeSwitcher.getCurrentSourceName()

        val dynamicEnabled = globalSPF.getBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL, SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL_DEFAULT)
        if (dynamicEnabled && !modeSwitcher.modeConfigCompleted()) {
            globalSPF.edit().putBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL, false).apply()
            dynamic_control.isChecked = false
            reStartService()
        } else {
            dynamic_control.isChecked = dynamicEnabled
        }
        dynamic_control_opts.visibility = if (dynamic_control.isChecked) View.VISIBLE else View.GONE
        delay_switch.isChecked = globalSPF.getBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL_DELAY, false)

        updateCpuModesButtons()

        if (dynamic_control.isChecked && prevAuthor.isNotEmpty() && prevAuthor != author) {
            reStartService()
        }
    }

    private fun updateCpuModesButtons() {
        val currentMode = ModeSwitcher.getCurrentPowerMode()
        updateCpuModeCardAlpha(cpu_config_p0, ModeSwitcher.POWERSAVE, currentMode)
        updateCpuModeCardAlpha(cpu_config_p1, ModeSwitcher.BALANCE, currentMode)
        updateCpuModeCardAlpha(cpu_config_p2, ModeSwitcher.PERFORMANCE, currentMode)
        updateCpuModeCardAlpha(cpu_config_p3, ModeSwitcher.FAST, currentMode)
    }

    private fun updateCpuModeCardAlpha(button: View, mode: String, currentMode: String) {
        button.alpha = when {
            author == ModeSwitcher.SOURCE_SCENE_CUSTOM -> {
                if (!modeSwitcher.modeReplaced(mode)) {
                    0.4f
                } else if (currentMode == mode) {
                    1f
                } else {
                    0.65f
                }
            }
            configFileInstalled && author != ModeSwitcher.SOURCE_NONE -> {
                if (currentMode == mode) 1f else 0.45f
            }
            else -> if (modeSwitcher.modeReplaced(mode)) 0.6f else 0.4f
        }
    }

    private fun modifyCpuConfig(mode: String) {
        val intent = Intent(requireContext(), ActivityCpuControl::class.java)
        intent.putExtra("cpuModeName", mode)
        startActivity(intent)
    }

    private fun outsideOverrided(): Boolean {
        if (configInstaller.outsideConfigInstalled() && !UperfManager.isUperfPowerCfgScript()) {
            DialogHelper.helpInfo(requireContext(), "你需要先删除外部配置，因为Scene会优先使用它！")
            return true
        }
        return false
    }

    private fun chooseConfigSource() {
        val view = layoutInflater.inflate(R.layout.dialog_powercfg_source, null)
        SchedulerUiHelper.updateChooserCardStates(view)
        val dialog = DialogHelper.customDialog(requireActivity(), view)

        val conservative = view.findViewById<View>(R.id.source_official_conservative)
        val active = view.findViewById<View>(R.id.source_official_active)

        val cpuConfigInstaller = CpuConfigInstaller()
        if (cpuConfigInstaller.dynamicSupport(requireContext())) {
            conservative.setOnClickListener {
                dialog.dismiss()
                SchedulerUiHelper.confirmLeaveDaemonSchedulers(requireActivity(), "经典配置") {
                    if (outsideOverrided()) {
                        configInstaller.removeOutsideConfig()
                    }
                    installOfficialConfig(false)
                }
            }
            active.setOnClickListener {
                dialog.dismiss()
                SchedulerUiHelper.confirmLeaveDaemonSchedulers(requireActivity(), "偏重性能") {
                    if (outsideOverrided()) {
                        configInstaller.removeOutsideConfig()
                    }
                    installOfficialConfig(true)
                }
            }
        } else {
            conservative.visibility = View.GONE
            active.visibility = View.GONE
        }

        view.findViewById<View>(R.id.source_import).setOnClickListener {
            dialog.dismiss()
            SchedulerUiHelper.confirmLeaveDaemonSchedulers(requireActivity(), "选取配置文件") {
                chooseLocalConfig()
            }
        }
        view.findViewById<View>(R.id.source_download).setOnClickListener {
            dialog.dismiss()
            SchedulerUiHelper.handleUperfCardClick(requireActivity()) { updateCpuModesState() }
        }
        view.findViewById<View>(R.id.source_fas_rs)?.setOnClickListener {
            dialog.dismiss()
            SchedulerUiHelper.handleFasRsCardClick(requireActivity()) { updateCpuModesState() }
        }
        view.findViewById<View>(R.id.source_custom).setOnClickListener {
            dialog.dismiss()
            SchedulerUiHelper.confirmLeaveDaemonSchedulers(requireActivity(), "自定义") {
                if (outsideOverrided()) {
                    configInstaller.removeOutsideConfig()
                }
                globalSPF.edit().putString(SpfConfig.GLOBAL_SPF_PROFILE_SOURCE, ModeSwitcher.SOURCE_SCENE_CUSTOM).apply()
                updateCpuModesState()
            }
        }
    }

    private fun chooseLocalConfig() {
        if (Build.VERSION.SDK_INT >= 30) {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "*/*"
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            powercfgFileLauncher.launch(intent)
        } else {
            try {
                val intent = Intent(requireContext(), ActivityFileSelector::class.java)
                intent.putExtra("extension", "sh")
                powercfgFileLauncher.launch(intent)
            } catch (ex: Exception) {
                Toast.makeText(requireContext(), "启动内置文件选择器失败！", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private sealed class ScriptReadResult {
        class Ok(val content: String) : ScriptReadResult()
        object Oversize : ScriptReadResult()
        class Error(val message: String) : ScriptReadResult()
    }

    private fun readBoundedStream(input: java.io.InputStream, maxBytes: Int): ScriptReadResult {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            total += n
            if (total > maxBytes) {
                return ScriptReadResult.Oversize
            }
            out.write(buf, 0, n)
        }
        return ScriptReadResult.Ok(String(out.toByteArray(), Charset.forName("UTF-8")).trimStart().replace("\r", ""))
    }

    private fun confirmAndInstallScript(lines: String) {
        val configStar = lines.split("\n").firstOrNull()
        if (configStar == null || (!configStar.startsWith("#!/") && !lines.contains("echo "))) {
            Toast.makeText(requireContext(), "这似乎是个无效的脚本文件！", Toast.LENGTH_LONG).show()
            return
        }
        DialogHelper.confirm(
            requireActivity(),
            "导入外部调度脚本安全提示",
            "自定义 .sh 脚本将在 Root 权限下执行。请确认脚本来源可信，且未包含关闭温控或破坏性命令。\n\n推荐使用内置的 Uperf-R。是否继续安装此脚本？",
            {
                if (configInstaller.installCustomConfig(requireContext(), lines, ModeSwitcher.SOURCE_SCENE_IMPORT)) {
                    configInstalled()
                } else {
                    Toast.makeText(requireContext(), "安装配置脚本失败，请重试！", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun installLocalConfigFromUri(uri: Uri) {
        if (uri.scheme != android.content.ContentResolver.SCHEME_CONTENT) {
            Toast.makeText(requireContext(), "仅支持通过系统文档选择器导入 content:// 配置！", Toast.LENGTH_LONG).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val maxBytes = 200 * 1024
            val result = withContext(Dispatchers.IO) {
                try {
                    requireContext().contentResolver.openInputStream(uri)?.use { input ->
                        readBoundedStream(input, maxBytes)
                    } ?: ScriptReadResult.Error("Scene无法读取此文件！")
                } catch (ex: Exception) {
                    ScriptReadResult.Error("读取所选文件失败: ${ex.message}")
                }
            }
            when (result) {
                is ScriptReadResult.Ok -> confirmAndInstallScript(result.content)
                is ScriptReadResult.Oversize -> Toast.makeText(requireContext(), "这个文件也太大了，配置脚本大小不能超过200KB！", Toast.LENGTH_LONG).show()
                is ScriptReadResult.Error -> Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun installLocalConfig(path: String) {
        if (!path.endsWith(".sh")) {
            Toast.makeText(requireContext(), "这似乎是个无效的脚本文件！", Toast.LENGTH_LONG).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val maxBytes = 200 * 1024
            val result = withContext(Dispatchers.IO) {
                val file = File(path)
                if (!file.exists()) {
                    return@withContext ScriptReadResult.Error("所选的文件没找到！")
                }
                if (!file.canRead()) {
                    return@withContext ScriptReadResult.Error("Scene无法读取此文件！")
                }
                try {
                    file.inputStream().use { input ->
                        readBoundedStream(input, maxBytes)
                    }
                } catch (ex: Exception) {
                    ScriptReadResult.Error("读取所选文件失败: ${ex.message}")
                }
            }
            when (result) {
                is ScriptReadResult.Ok -> confirmAndInstallScript(result.content)
                is ScriptReadResult.Oversize -> Toast.makeText(requireContext(), "这个文件也太大了，配置脚本大小不能超过200KB！", Toast.LENGTH_LONG).show()
                is ScriptReadResult.Error -> Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun installOfficialConfig(active: Boolean) {
        if (!configInstaller.dynamicSupport(requireContext())) {
            Scene.toast(R.string.not_support_config, Toast.LENGTH_LONG)
            return
        }

        configInstaller.installOfficialConfig(requireContext(), "", active)
        configInstalled()
    }

    private fun configInstalled() {
        updateCpuModesState()

        if (globalSPF.getBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL, SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL_DEFAULT)) {
            Scene.toast(getString(R.string.config_installed), Toast.LENGTH_LONG)
            reStartService()
        } else {
            DialogHelper.confirm(
                requireActivity(),
                "",
                "配置脚本已安装，是否开启 [动态响应] ？",
                {
                    dynamic_control.isChecked = true
                    globalSPF.edit().putBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL, true).apply()
                    reStartService()
                })
        }
    }

    private fun reStartService() {
        EventBus.publish(EventType.SCENE_CONFIG)
    }

    private class ModeOnItemSelectedListener(private var globalSPF: SharedPreferences, private var runnable: Runnable) : AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: AdapterView<*>?) {
        }

        @SuppressLint("ApplySharedPref")
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            var mode = ModeSwitcher.DEFAULT
            when (position) {
                0 -> mode = ModeSwitcher.POWERSAVE
                1 -> mode = ModeSwitcher.BALANCE
                2 -> mode = ModeSwitcher.PERFORMANCE
                3 -> mode = ModeSwitcher.FAST
                4 -> mode = ModeSwitcher.IGONED
            }
            if (globalSPF.getString(SpfConfig.GLOBAL_SPF_POWERCFG_FIRST_MODE, ModeSwitcher.DEFAULT) != mode) {
                globalSPF.edit().putString(SpfConfig.GLOBAL_SPF_POWERCFG_FIRST_MODE, mode).commit()
                runnable.run()
            }
        }
    }

    private class ModeOnItemSelectedListener2(private var globalSPF: SharedPreferences, private var runnable: Runnable) : AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: AdapterView<*>?) {
        }

        @SuppressLint("ApplySharedPref")
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            var mode = ModeSwitcher.POWERSAVE
            when (position) {
                0 -> mode = ModeSwitcher.POWERSAVE
                1 -> mode = ModeSwitcher.BALANCE
                2 -> mode = ModeSwitcher.PERFORMANCE
                3 -> mode = ModeSwitcher.IGONED
            }
            if (globalSPF.getString(SpfConfig.GLOBAL_SPF_POWERCFG_SLEEP_MODE, ModeSwitcher.POWERSAVE) != mode) {
                globalSPF.edit().putString(SpfConfig.GLOBAL_SPF_POWERCFG_SLEEP_MODE, mode).commit()
                runnable.run()
            }
        }
    }

    private fun stopTimer() {
        if (this.timer != null) {
            timer!!.cancel()
            timer = null
        }
    }

    override fun onPause() {
        stopTimer()
        super.onPause()
    }

    private fun toggleMode(modeSwitcher: ModeSwitcher, mode: String): Deferred<Unit> {
        return GlobalScope.async {
            context?.run {
                if (modeSwitcher.modeConfigCompleted()) {
                    modeSwitcher.executePowercfgMode(mode, packageName)
                } else {
                    CpuConfigInstaller().installOfficialConfig(context!!)
                    modeSwitcher.executePowercfgMode(mode, packageName)
                }
            }
        }
    }

    private fun installConfig(toMode: String) {
        val dynamic = AccessibleServiceHelper().serviceRunning(context!!) && spf.getBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL, SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL_DEFAULT)
        if (!dynamic && ModeSwitcher.getCurrentPowerMode() == toMode) {
            if (author == ModeSwitcher.SOURCE_UPERF || author == ModeSwitcher.SOURCE_FAS_RS) {
                Scene.toast("当前已是 ${ModeSwitcher.getModName(toMode)}（长按模式卡片可微调调度参数）", Toast.LENGTH_SHORT)
                return
            }
            modeSwitcher.setCurrent("", "")
            globalSPF.edit().putString(SpfConfig.GLOBAL_SPF_POWERCFG, "").apply()
            myHandler.post {
                DialogHelper.confirmBlur(this.activity!!,
                        "提示",
                        "需要重启手机才能恢复默认调度，是否立即重启？",
                        {
                            KeepShellPublic.doCmdSync("sync\nsleep 1\nsvc power reboot || reboot")
                        },
                        null)
                setModeState()
            }
            return
        }

        val progressBarDialog = ProgressBarDialog(this.activity!!, "home-mode-switch")
        progressBarDialog.showDialog(getString(R.string.please_wait))

        GlobalScope.launch(Dispatchers.Main) {
            toggleMode(modeSwitcher, toMode).await()

            setModeState()
            maxFreqs.clear()
            minFreqs.clear()
            updateInfo()
            if (dynamic) {
                globalSPF.edit().putString(SpfConfig.GLOBAL_SPF_POWERCFG, "").apply()
                DialogHelper.alert(activity!!,
                        "提示",
                        "“场景模式-动态响应”已被激活，你手动选择的模式随时可能被覆盖。\n\n如果你需要长期使用手动控制，请在当前概览页顶部关闭“动态响应”！")
            } else {
                globalSPF.edit().putString(SpfConfig.GLOBAL_SPF_POWERCFG, toMode).apply()
                if (author != ModeSwitcher.SOURCE_UPERF && author != ModeSwitcher.SOURCE_FAS_RS &&
                    !globalSPF.getBoolean(SpfConfig.GLOBAL_SPF_POWERCFG_FRIST_NOTIFY, false)
                ) {
                    DialogHelper.confirm(activity!!,
                            "提示",
                            "如果你已允许Scene自启动，手机重启后，Scene还会自动激活刚刚选择的模式。\n\n如果需要恢复系统默认调度，请再次点击，然后重启手机！",
                            DialogHelper.DialogButton(getString(R.string.btn_confirm)),
                            DialogHelper.DialogButton(getString(R.string.btn_dontshow), {
                                globalSPF.edit().putBoolean(SpfConfig.GLOBAL_SPF_POWERCFG_FRIST_NOTIFY, true).apply()
                            })).setCancelable(false)
                }
            }

            progressBarDialog.hideDialog()
        }
    }
}
