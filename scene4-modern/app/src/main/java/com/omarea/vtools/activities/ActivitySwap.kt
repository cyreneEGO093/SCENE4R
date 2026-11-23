package com.omarea.vtools.activities

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.omarea.Scene
import com.omarea.common.model.SelectItem
import com.omarea.common.shared.MagiskExtend
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.KernelProrp
import com.omarea.common.shell.RootFile
import com.omarea.common.ui.DialogHelper
import com.omarea.common.ui.DialogItemChooserMini
import com.omarea.common.ui.ProgressBarDialog
import com.omarea.library.basic.RadioGroupSimulator
import com.omarea.library.shell.LMKUtils
import com.omarea.library.shell.PropsUtils
import com.omarea.library.shell.SwapModuleUtils
import com.omarea.library.shell.SwapUtils
import com.omarea.store.SpfConfig
import com.omarea.ui.AdapterSwaplist
import com.omarea.vtools.R
import java.util.*
import kotlin.collections.LinkedHashMap
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import com.omarea.ui.ZRamStateView


class ActivitySwap : ActivityBase() {
    private val btn_swap_close: Button
        get() = findViewById(R.id.btn_swap_close)
    private val btn_swap_create: Button
        get() = findViewById(R.id.btn_swap_create)
    private val btn_zram_resize: Button
        get() = findViewById(R.id.btn_zram_resize)
    private val extra_free_kbytes_display: TextView
        get() = findViewById(R.id.extra_free_kbytes_display)
    private val list_swaps: ListView
        get() = findViewById(R.id.list_swaps)
    private val swap_auto_lmk: Switch
        get() = findViewById(R.id.swap_auto_lmk)
    private val swap_auto_lmk_wrap: LinearLayout
        get() = findViewById(R.id.swap_auto_lmk_wrap)
    private val swap_config_zram: LinearLayout
        get() = findViewById(R.id.swap_config_zram)
    private val swap_lmk_current: TextView
        get() = findViewById(R.id.swap_lmk_current)
    private val swap_module_downloadable: TextView
        get() = findViewById(R.id.swap_module_downloadable)
    private val swap_module_installed: TextView
        get() = findViewById(R.id.swap_module_installed)
    private val swap_module_uninstalled: TextView
        get() = findViewById(R.id.swap_module_uninstalled)
    private val swap_state: TextView
        get() = findViewById(R.id.swap_state)
    private val swap_swappiness_display: TextView
        get() = findViewById(R.id.swap_swappiness_display)
    private val swap_usage: ZRamStateView
        get() = findViewById(R.id.swap_usage)
    private val swap_usage_ratio: TextView
        get() = findViewById(R.id.swap_usage_ratio)
    private val swappiness_adj: Button
        get() = findViewById(R.id.swappiness_adj)
    private val txt_mem: TextView
        get() = findViewById(R.id.txt_mem)
    private val txt_swap_auto_start: TextView
        get() = findViewById(R.id.txt_swap_auto_start)
    private val txt_swap_io: TextView
        get() = findViewById(R.id.txt_swap_io)
    private val txt_swap_size_display: TextView
        get() = findViewById(R.id.txt_swap_size_display)
    private val txt_zram_auto_start: TextView
        get() = findViewById(R.id.txt_zram_auto_start)
    private val txt_zram_size_display: TextView
        get() = findViewById(R.id.txt_zram_size_display)
    private val watermark_scale_factor_display: TextView
        get() = findViewById(R.id.watermark_scale_factor_display)
    private val zram0_stat: TextView
        get() = findViewById(R.id.zram0_stat)
    private val zram_compact_algorithm: TextView
        get() = findViewById(R.id.zram_compact_algorithm)
    private val zram_stat: LinearLayout
        get() = findViewById(R.id.zram_stat)
    private val zram_state: TextView
        get() = findViewById(R.id.zram_state)
    private val zram_usage: ZRamStateView
        get() = findViewById(R.id.zram_usage)
    private val zram_usage_ratio: TextView
        get() = findViewById(R.id.zram_usage_ratio)

    private lateinit var processBarDialog: ProgressBarDialog
    private val myHandler = Handler(Looper.getMainLooper())
    private lateinit var swapConfig: SharedPreferences
    private var totalMem = 2048
    private val swapUtils = SwapUtils(Scene.context)
    private val swapModuleUtils = SwapModuleUtils()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_swap)
        setBackArrow()

        swapConfig = getSharedPreferences(SpfConfig.SWAP_SPF, Context.MODE_PRIVATE)

        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)

        totalMem = (info.totalMem / 1024 / 1024f).toInt()

        // 进入界面时 加载Magisk模块的配置
        swapModuleUtils.loadModuleConfig(swapConfig)

        setView()
    }

    private fun swapOffAwait(): Timer {
        val timer = Timer()
        val totalUsed = swapUtils.swapUsedSize
        val startTime = System.currentTimeMillis()
        timer.schedule(object : TimerTask() {
            override fun run() {
                val currentUsed = swapUtils.swapUsedSize
                val avgSpeed = (totalUsed - currentUsed).toFloat() / (System.currentTimeMillis() - startTime) * 1000
                val tipStr = StringBuilder()
                tipStr.append(String.format("回收Swapfile ${currentUsed}/${totalUsed}MB (%.1fMB/s)\n", avgSpeed))
                if (avgSpeed > 0) {
                    tipStr.append("大约还需 " + (currentUsed / avgSpeed).toInt() + "秒")
                } else {
                    tipStr.append("请耐心等待~")
                }
                myHandler.post {
                    processBarDialog.showDialog(tipStr.toString())
                }
            }
        }, 0, 1000)
        return timer
    }

    private fun zramOffAwait(): Timer {
        val timer = Timer()
        val totalUsed = swapUtils.zramUsedSize
        val startTime = System.currentTimeMillis()
        timer.schedule(object : TimerTask() {
            override fun run() {
                val currentUsed = swapUtils.zramUsedSize
                val avgSpeed = (totalUsed - currentUsed).toFloat() / (System.currentTimeMillis() - startTime) * 1000
                val tipStr = StringBuilder()
                tipStr.append(String.format("回收ZRAM ${currentUsed}/${totalUsed}MB (%.1fMB/s)\n", avgSpeed))
                if (avgSpeed > 0) {
                    tipStr.append("大约还需 " + (currentUsed / avgSpeed).toInt() + "秒")
                } else {
                    tipStr.append("请耐心等待~")
                }
                myHandler.post {
                    processBarDialog.showDialog(tipStr.toString())
                }
            }
        }, 0, 1000)
        return timer
    }

    private fun setView() {
        val context = this
        processBarDialog = ProgressBarDialog(context)

        refreshModuleCard()

        // 关闭swap
        btn_swap_close.setOnClickListener {
            val usedSize = swapUtils.swapUsedSize
            if (usedSize > 500) {
                DialogHelper.confirm(this,
                        "确认重启手机？",
                        "Swap被大量使用(${usedSize}MB)，短时间内很难完成回收。\n因此需要重启手机来完成此操作，请确保你的重要数据都已保存！！！", {
                    swapConfig.edit().putBoolean(SpfConfig.SWAP_SPF_SWAP, false).apply()
                    swapModuleUtils.saveModuleConfig(swapConfig)
                    KeepShellPublic.doCmdSync("sync\nsleep 2\nsvc power reboot || reboot")
                })
            } else {
                swapOffDialog()
            }
        }

        // 自动lmk调节
        swap_auto_lmk.setOnClickListener {
            val checked = (it as CompoundButton).isChecked
            swapConfig.edit().putBoolean(SpfConfig.SWAP_SPF_AUTO_LMK, checked).apply()
            if (checked) {
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val info = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(info)
                val utils = LMKUtils()
                utils.autoSetLMK(info.totalMem)
                swap_lmk_current.text = utils.getCurrent()
            } else {
                Toast.makeText(context, "需要重启手机才会恢复默认的LMK参数！", Toast.LENGTH_SHORT).show()
            }
        }

        // 是否支持zram
        if (!swapUtils.zramSupport) {
            swap_config_zram.visibility = View.GONE
            zram_stat.visibility = View.GONE
        }

        // swap启动
        btn_swap_create.setOnClickListener {
            swapCreateDialog()
        }

        // 调整zram大小操作
        btn_zram_resize.setOnClickListener {
            zramResizeDialog()
        }

        swappiness_adj.setOnClickListener {
            swappinessAdjDialog()
        }
    }

    private fun refreshModuleCard() {
        val targetVersion = getString(R.string.swap_module_target_version).toInt()
        val runningVersion = swapModuleUtils.getModuleVersion()
        val pendingUpgrade = swapModuleUtils.isPendingRebootUpgrade(targetVersion)
        val hasManager = swapModuleUtils.isRootModuleManagerAvailable()

        when {
            pendingUpgrade -> {
                swap_module_installed.text = "已安装 v4.0.0（重启生效）"
                swap_module_installed.visibility = View.VISIBLE
                swap_module_uninstalled.visibility = View.GONE
                swap_module_downloadable.visibility = View.GONE
            }
            swapModuleUtils.magiskModuleInstalled -> {
                swap_module_installed.text = if (runningVersion > 0) "已激活 (v$runningVersion)" else "已激活"
                swap_module_installed.visibility = View.VISIBLE
                swap_module_uninstalled.visibility = View.GONE
                if (hasManager && runningVersion < targetVersion) {
                    swap_module_downloadable.text = "升级内置模块"
                    swap_module_downloadable.visibility = View.VISIBLE
                    swap_module_downloadable.setOnClickListener {
                        swapModuleUpdateDialog()
                    }
                } else {
                    swap_module_downloadable.visibility = View.GONE
                }
            }
            else -> {
                swap_module_installed.visibility = View.GONE
                swap_module_uninstalled.text = "未激活"
                swap_module_uninstalled.visibility = View.VISIBLE
                if (hasManager) {
                    swap_module_downloadable.text = "安装内置模块"
                    swap_module_downloadable.visibility = View.VISIBLE
                    swap_module_downloadable.setOnClickListener {
                        swapModuleUpdateDialog()
                    }
                } else {
                    swap_module_downloadable.visibility = View.GONE
                }
            }
        }
    }

    // 离线安装 / 升级内置 SWAP 控制器模块
    private fun swapModuleUpdateDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_swap_module, null)
        val dialog = DialogHelper.customDialog(this, view)

        view.findViewById<View>(R.id.btn_cancel).setOnClickListener {
            dialog.dismiss()
        }
        view.findViewById<View>(R.id.btn_confirm).setOnClickListener {
            dialog.dismiss()
            processBarDialog.showDialog("正在离线安装内置 SWAP 控制器模块...")
            Thread {
                val (ok, msg) = swapModuleUtils.installBundledModule(applicationContext)
                myHandler.post {
                    processBarDialog.hideDialog()
                    refreshModuleCard()
                    DialogHelper.helpInfo(
                        this@ActivitySwap,
                        if (ok) "模块安装成功" else "模块安装失败",
                        msg
                    )
                }
            }.start()
        }
    }

    private fun swapOffDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_swap_delete, null)
        val deleteFile = view.findViewById<CompoundButton>(R.id.swap_delete_file)
        val dialog = DialogHelper.customDialog(this, view)

        view.findViewById<View>(R.id.btn_cancel).setOnClickListener {
            dialog.dismiss()
        }
        view.findViewById<View>(R.id.btn_confirm).setOnClickListener {
            dialog.dismiss()
            val delete = deleteFile.isChecked

            processBarDialog.showDialog(getString(R.string.swap_on_close))
            val run = Runnable {
                val timer = swapOffAwait()

                if (delete) {
                    swapUtils.swapDelete()
                } else {
                    swapUtils.swapOff()
                }

                timer.cancel()
                myHandler.post {
                    swapConfig.edit().putBoolean(SpfConfig.SWAP_SPF_SWAP, false).apply()
                    processBarDialog.hideDialog()
                    getSwaps()
                }
            }
            Thread(run).start()
        }
    }

    private var timer: Timer? = null
    private fun startTimer() {
        stopTimer()

        timer = Timer()
        timer!!.schedule(object : TimerTask() {
            override fun run() {
                getSwaps()
            }
        }, 0, 5000)
    }

    private fun stopTimer() {
        timer?.cancel()
        timer = null
    }

    private fun enableDialogKeyboard(dialog: DialogHelper.DialogWrap) {
        dialog.dialog.window?.clearFlags(
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
    }

    /**
     * Synchronize a SeekBar and numeric EditText without rounding/clamping away manual input.
     */
    private fun bindSliderAndInput(
        seekBar: SeekBar,
        input: EditText,
        label: TextView,
        step: Int = 1,
        seekMin: Int = 0,
        formatter: (Int) -> String
    ) {
        var syncing = false
        val updateInputText = { value: Int ->
            val str = value.toString()
            if (input.text.toString() != str) {
                input.setText(str)
                input.setSelection(str.length)
            }
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress * step
                label.text = formatter(value)
                if (!syncing && fromUser) {
                    syncing = true
                    updateInputText(value)
                    syncing = false
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (syncing) return
                val parsed = s?.toString()?.trim()?.toIntOrNull() ?: return
                syncing = true
                label.text = formatter(parsed)
                val targetProgress = (parsed / step).coerceIn(seekMin, seekBar.max)
                if (seekBar.progress != targetProgress) {
                    seekBar.progress = targetProgress
                }
                syncing = false
            }
        })
    }

    private fun swappinessAdjDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_swappines, null)

        val swappinessSeekBar = view.findViewById<SeekBar>(R.id.seekbar_swap_swappiness)
        val swappinessText = view.findViewById<TextView>(R.id.txt_zramstus_swappiness)
        val swappinessInput = view.findViewById<EditText>(R.id.input_swap_swappiness)
        val extraFreeSeekBar = view.findViewById<SeekBar>(R.id.seekbar_extra_free_kbytes)
        val extraFreeText = view.findViewById<TextView>(R.id.text_extra_free_kbytes)
        val extraFreeInput = view.findViewById<EditText>(R.id.input_extra_free_kbytes)
        val watermarkScaleSeekBar = view.findViewById<SeekBar>(R.id.seekbar_watermark_scale_factor)
        val watermarkScaleText = view.findViewById<TextView>(R.id.text_watermark_scale_factor)
        val watermarkScaleInput = view.findViewById<EditText>(R.id.input_watermark_scale_factor)

        val initSwappiness = swapConfig.getInt(SpfConfig.SWAP_SPF_SWAPPINESS, 65).coerceIn(0, 200)
        swappinessSeekBar.max = 200
        swappinessSeekBar.progress = initSwappiness
        swappinessText.text = initSwappiness.toString()
        swappinessInput.setText(initSwappiness.toString())

        val extraFreeKbytes = KernelProrp.getProp("/proc/sys/vm/extra_free_kbytes")
        val defaultExtraFree = extraFreeKbytes.trim().toIntOrNull() ?: 29615
        val initExtraFree = swapConfig.getInt(SpfConfig.SWAP_SPF_EXTRA_FREE_KBYTES, defaultExtraFree).coerceIn(0, 2097152)
        extraFreeSeekBar.progress = initExtraFree.coerceIn(0, extraFreeSeekBar.max)
        extraFreeText.text = "$initExtraFree(${initExtraFree / 1024}MB)"
        extraFreeInput.setText(initExtraFree.toString())

        val initWatermark = swapConfig.getInt(SpfConfig.SWAP_SPF_WATERMARK_SCALE, 100).coerceIn(1, 1000)
        watermarkScaleSeekBar.progress = initWatermark
        watermarkScaleText.text = "$initWatermark(${initWatermark / 100F}%)"
        watermarkScaleInput.setText(initWatermark.toString())

        val watermarkSupported = RootFile.fileExists("/proc/sys/vm/watermark_scale_factor")
        watermarkScaleSeekBar.isEnabled = watermarkSupported
        watermarkScaleInput.isEnabled = watermarkSupported

        bindSliderAndInput(swappinessSeekBar, swappinessInput, swappinessText) { it.toString() }
        bindSliderAndInput(extraFreeSeekBar, extraFreeInput, extraFreeText) { "$it(${it / 1024}MB)" }
        bindSliderAndInput(watermarkScaleSeekBar, watermarkScaleInput, watermarkScaleText, seekMin = 1) { "$it(${it / 100F}%)" }

        val dialog = DialogHelper.customDialog(this, view)
        enableDialogKeyboard(dialog)
        view.findViewById<View>(R.id.btn_cancel).setOnClickListener {
            dialog.dismiss()
        }
        view.findViewById<View>(R.id.btn_confirm).setOnClickListener {
            val swappiness = swappinessInput.text.toString().trim().toIntOrNull()
            if (swappiness == null || swappiness !in 0..200) {
                Toast.makeText(this, "请输入 0 ~ 200 之间的 swappiness 数值", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val extraFree = extraFreeInput.text.toString().trim().toIntOrNull()
            if (extraFree == null || extraFree !in 0..2097152) {
                Toast.makeText(this, "请输入 0 ~ 2097152 之间的 extra_free_kbytes 数值", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val watermarkScale = if (watermarkSupported) {
                val wm = watermarkScaleInput.text.toString().trim().toIntOrNull()
                if (wm == null || wm !in 1..1000) {
                    Toast.makeText(this, "请输入 1 ~ 1000 之间的 watermark_scale_factor 数值", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                wm
            } else {
                watermarkScaleSeekBar.progress
            }
            dialog.dismiss()

            val config = swapConfig.edit()
                    .putInt(SpfConfig.SWAP_SPF_SWAPPINESS, swappiness)
                    .putInt(SpfConfig.SWAP_SPF_EXTRA_FREE_KBYTES, extraFree)

            KeepShellPublic.doCmdSync("echo $swappiness > /proc/sys/vm/swappiness")
            KeepShellPublic.doCmdSync("echo $extraFree > /proc/sys/vm/extra_free_kbytes")
            if (watermarkSupported) {
                KeepShellPublic.doCmdSync("echo $watermarkScale > /proc/sys/vm/watermark_scale_factor")

                config.putInt(SpfConfig.SWAP_SPF_WATERMARK_SCALE, watermarkScale)
            }
            config.apply()

            myHandler.post {
                getSwaps()
            }
        }
    }

    private fun zramResizeDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_zram_resize, null)
        val zramSizeBar = view.findViewById<SeekBar>(R.id.zram_size)
        val zramAutoStart = view.findViewById<CompoundButton>(R.id.zram_auto_start)
        val compactAlgorithm = view.findViewById<TextView>(R.id.zram_compact_algorithm)
        val zramSizeText = view.findViewById<TextView>(R.id.zram_size_text)
        val zramSizeInput = view.findViewById<EditText>(R.id.zram_size_input)

        zramAutoStart.isChecked = swapConfig.getBoolean(SpfConfig.SWAP_SPF_ZRAM, false)
        val compAlgorithmOptions = swapUtils.compAlgorithmOptions
        var currentAlgorithm = swapConfig.getString(SpfConfig.SWAP_SPF_ALGORITHM, swapUtils.compAlgorithm)
        if (!compAlgorithmOptions.contains(currentAlgorithm!!)) {
            currentAlgorithm = swapUtils.compAlgorithm
            swapConfig.edit().putString(SpfConfig.SWAP_SPF_ALGORITHM, currentAlgorithm).apply()
        }
        compactAlgorithm.text = currentAlgorithm
        compactAlgorithm.setOnClickListener {
            DialogItemChooserMini
                    .singleChooser(context, compAlgorithmOptions, compAlgorithmOptions.indexOf(currentAlgorithm))
                    .setTitle(R.string.swap_zram_comp_options)
                    .setCallback(object : DialogItemChooserMini.Callback {
                        override fun onConfirm(selected: List<SelectItem>, status: BooleanArray) {
                            val algorithm = selected.firstOrNull()?.value
                            algorithm?.run {
                                (it as TextView).text = algorithm
                            }
                        }
                    })
                    .show()
        }

        val maxZramInputMb = maxOf(totalMem * 2, 32768)
        zramSizeBar.max = totalMem / 128
        var zramSize = swapConfig.getInt(SpfConfig.SWAP_SPF_ZRAM_SIZE, 0)
        if (zramSize > maxZramInputMb) {
            zramSize = maxZramInputMb
        }
        zramSizeBar.progress = (zramSize / 128).coerceIn(0, zramSizeBar.max)
        zramSizeText.text = "${zramSize}MB"
        zramSizeInput.setText(zramSize.toString())
        bindSliderAndInput(zramSizeBar, zramSizeInput, zramSizeText, step = 128) { "${it}MB" }

        val dialog = DialogHelper.customDialog(this, view)
        enableDialogKeyboard(dialog)
        view.findViewById<View>(R.id.btn_cancel).setOnClickListener {
            dialog.dismiss()
        }
        view.findViewById<View>(R.id.btn_confirm).setOnClickListener {
            val sizeVal = zramSizeInput.text.toString().trim().toIntOrNull()
            if (sizeVal == null || sizeVal !in 0..maxZramInputMb) {
                Toast.makeText(this, "请输入 0 ~ $maxZramInputMb 之间的有效大小(MB)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            val autoStart = zramAutoStart.isChecked
            val algorithm = "" + compactAlgorithm.text

            processBarDialog.showDialog(getString(R.string.zram_resizing))
            swapConfig.edit()
                    .putInt(SpfConfig.SWAP_SPF_ZRAM_SIZE, sizeVal)
                    .putBoolean(SpfConfig.SWAP_SPF_ZRAM, autoStart)
                    .putString(SpfConfig.SWAP_SPF_ALGORITHM, algorithm)
                    .apply()

            val run = Thread {
                if (swapUtils.zramEnabled && algorithm != swapUtils.compAlgorithm || sizeVal != swapUtils.zramCurrentSizeMB) {
                    val timer = zramOffAwait()
                    swapUtils.zramOff()
                    timer.cancel()
                }
                myHandler.post {
                    processBarDialog.showDialog(getString(R.string.zram_resizing))
                }
                swapUtils.resizeZram(sizeVal, algorithm)

                getSwaps()
                myHandler.post {
                    processBarDialog.hideDialog()
                }
            }
            Thread(run).start()
        }
    }

    private fun swapCreateDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_swap_create, null)
        val swapSize = view.findViewById<SeekBar>(R.id.swap_size)
        val swapSizeText = view.findViewById<TextView>(R.id.swap_size_text)
        val swapSizeInput = view.findViewById<EditText>(R.id.swap_size_input)

        val dialog = DialogHelper.customDialog(this, view)
        enableDialogKeyboard(dialog)

        val swapCurrentSize = if (swapUtils.swapExists) {
            swapUtils.swapFileSize
        } else {
            swapConfig.getInt(SpfConfig.SWAP_SPF_SWAP_SWAPSIZE, 0)
        }.coerceAtLeast(0)

        swapSize.progress = (swapCurrentSize / 128).coerceIn(0, swapSize.max)
        swapSizeText.text = "${swapCurrentSize}MB"
        swapSizeInput.setText(swapCurrentSize.toString())
        bindSliderAndInput(swapSize, swapSizeInput, swapSizeText, step = 128) { "${it}MB" }

        view.findViewById<View>(R.id.btn_cancel).setOnClickListener {
            dialog.dismiss()
        }
        view.findViewById<View>(R.id.btn_confirm).setOnClickListener {
            val size = swapSizeInput.text.toString().trim().toIntOrNull()
            if (size == null || size !in 1..65536) {
                Toast.makeText(this, "请输入 1 ~ 65536 之间的有效大小(MB)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            if (size == swapUtils.swapFileSize) {
                // 如果大小和已经创建的文件一致，跳过创建

                // 保存设置
                swapConfig.edit().putInt(SpfConfig.SWAP_SPF_SWAP_SWAPSIZE, size).apply()

                swapActiveDialog()
            } else {
                val run = Runnable {
                    val startTime = System.currentTimeMillis()
                    myHandler.post {
                        processBarDialog.showDialog(getString(R.string.file_creating))
                    }
                    swapUtils.mkswap(size)

                    // 保存设置
                    swapConfig.edit().putInt(SpfConfig.SWAP_SPF_SWAP_SWAPSIZE, size).apply()

                    getSwaps()
                    val time = System.currentTimeMillis() - startTime
                    myHandler.post {
                        processBarDialog.hideDialog()
                        val speed = (size * 1000.0 / time).toInt()
                        Toast.makeText(
                                context,
                                "Swapfile创建完毕，耗时${time / 1000}s，平均写入速度：${speed}MB/s",
                                Toast.LENGTH_LONG
                        ).show()
                        swapActiveDialog()
                    }
                }
                Thread(run).start()
            }
        }
    }

    private fun swapActiveDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_swap_active, null)
        val dialog = DialogHelper.customDialog(this, view)

        val priorityHight = view.findViewById<CompoundButton>(R.id.swap_priority_high)
        val priorityMiddle = view.findViewById<CompoundButton>(R.id.swap_priority_middle)
        val priorityLow = view.findViewById<CompoundButton>(R.id.swap_priority_low)
        val autoStart = view.findViewById<CompoundButton>(R.id.swap_auto_start)
        val mountLoop = view.findViewById<CompoundButton>(R.id.swap_mount_loop)

        // 设置选中状态
        val radioGroupSimulator = RadioGroupSimulator(priorityHight, priorityMiddle, priorityLow)
        when (swapConfig.getInt(SpfConfig.SWAP_SPF_SWAP_PRIORITY, -2)) {
            5 -> priorityHight.isChecked = true
            0 -> priorityMiddle.isChecked = true
            else -> priorityLow.isChecked = true
        }
        mountLoop.isChecked = swapConfig.getBoolean(SpfConfig.SWAP_SPF_SWAP_USE_LOOP, false)
        autoStart.isChecked = swapConfig.getBoolean(SpfConfig.SWAP_SPF_SWAP, false)

        view.findViewById<View>(R.id.btn_cancel).setOnClickListener {
            dialog.dismiss()
        }
        view.findViewById<View>(R.id.btn_confirm).setOnClickListener {
            dialog.dismiss()

            val priority: Int
            when (radioGroupSimulator.checked) {
                priorityHight -> {
                    priority = 5
                }
                priorityMiddle -> {
                    priority = 0
                }
                priorityLow -> {
                    priority = -2
                }
                else -> {
                    return@setOnClickListener
                }
            }
            // 保存配置
            swapConfig.edit()
                    .putBoolean(SpfConfig.SWAP_SPF_SWAP_USE_LOOP, mountLoop.isChecked)
                    .putInt(SpfConfig.SWAP_SPF_SWAP_PRIORITY, priority)
                    .putBoolean(SpfConfig.SWAP_SPF_SWAP, autoStart.isChecked)
                    .apply()

            processBarDialog.showDialog("稍等...")
            Thread {
                val swapPriority = swapConfig.getInt(SpfConfig.SWAP_SPF_SWAP_PRIORITY, -2)
                if (swapPriority == 0) {
                    val zramPriority = swapUtils.zramPriority
                    if (zramPriority != null && zramPriority < 0) {
                        val timer = zramOffAwait()
                        swapUtils.zramOff()
                        timer.cancel()
                    }
                }
                val result = swapUtils.swapOn(
                        swapPriority,
                        swapConfig.getBoolean(SpfConfig.SWAP_SPF_SWAP_USE_LOOP, false)
                )
                if (result.isNotEmpty()) {
                    Scene.toast(result, Toast.LENGTH_LONG)
                }

                getSwaps()

                myHandler.post(showSwapOpened)
                processBarDialog.hideDialog()
            }.start()
        }
    }

    private var swapsTHRowCache: LinkedHashMap<String, String>? = null
    private val swapsTHRow: LinkedHashMap<String, String>
        get() {
            if (swapsTHRowCache == null) {
                swapsTHRowCache = LinkedHashMap<String, String>().apply {
                    put("path", getString(R.string.path))
                    put("type", getString(R.string.type))
                    put("size", getString(R.string.size))
                    put("used", getString(R.string.used))
                    put("priority", getString(R.string.order)) // put("priority", getString(R.string.priority))
                }
            }
            return swapsTHRowCache!!
        }

    internal val getSwaps = {
        val zramEnabled = swapUtils.zramEnabled
        val swappiness = KernelProrp.getProp("/proc/sys/vm/swappiness")
        val watermarkScale = KernelProrp.getProp("/proc/sys/vm/watermark_scale_factor")
        val memInfo = KernelProrp.getProp("/proc/meminfo")
        val swapFileExists = swapUtils.swapExists
        val currentSwap = swapUtils.sceneSwaps
        val rows = swapUtils.procSwaps
        val extraFreeKbytes = KernelProrp.getProp("/proc/sys/vm/extra_free_kbytes")
        // 压缩算法
        val compAlgorithm = swapUtils.compAlgorithm
        // zram统计
        val zramStatus = getZRamStatus(compAlgorithm)
        val swapFileSize = swapUtils.swapFileSize

        var loopName: String? = PropsUtils.getProp("vtools.swap.loop").split("/").lastOrNull()
        if (loopName != null && !loopName.contains("loop")) {
            loopName = null
        }

        val list = ArrayList<HashMap<String, String>>()
        list.add(swapsTHRow)

        var swapSize = 0f
        var swapFree = 0f
        // 按理说ZRAM的虚拟磁盘大小不取决于是否启用，但是为了避免引起误会，未启用还是刻意显示为0比较好
        val zramSize = if (zramEnabled) swapUtils.zramCurrentSizeMB else 0
        var zramFree = 0f
        for (i in 1 until rows.size) {
            val tr = LinkedHashMap<String, String>()
            val params = rows[i].split(" ").toMutableList()
            val path = params[0]
            tr["path"] = path
            tr["type"] = params[1].replace("file", "文件").replace("partition", "分区")

            val size = swapUsedSizeParseMB(params[2])
            // tr.put("size", if (size.length > 3) (size.substring(0, size.length - 3) + "m") else "0")
            tr["size"] = size

            val used = swapUsedSizeParseMB(params[3])
            // tr.put("used", if (used.length > 3) (used.substring(0, used.length - 3) + "m") else "0")
            tr["used"] = used

            tr["priority"] = params[4]
            list.add(tr)

            if (path.startsWith("/swapfile") || path.equals("/data/swapfile") || (loopName != null && path.contains(loopName))) {
                try {
                    swapSize = size.toFloat()
                    swapFree = size.toFloat() - used.toFloat()
                } catch (ex: java.lang.Exception) {
                }
            } else if (path.startsWith("/block/zram0") || path.startsWith("/dev/block/zram0")) {
                try {
                    // zramSize = size.toFloat()
                    zramFree = size.toFloat() - used.toFloat()
                } catch (ex: java.lang.Exception) {
                }
            }
        }
        val swaps = AdapterSwaplist(this, list)

        myHandler.post {
            try {
                txt_swap_size_display.text = swapFileSize.toString() + "MB"
                swap_usage.setData(swapSize, swapFree)
                zram_usage.setData(zramSize.toFloat(), zramFree)
                if (swapSize > 0) {
                    swap_usage_ratio.text = (100 - (swapFree * 100 / swapSize).toInt()).toString() + "%"
                } else {
                    swap_usage_ratio.text = "0%"
                }
                if (zramSize > 0 && zramFree > 0) {
                    zram_usage_ratio.text = (100 - (zramFree * 100 / zramSize).toInt()).toString() + "%"
                } else {
                    zram_usage_ratio.text = "0%"
                }

                swap_swappiness_display.text = swappiness
                watermark_scale_factor_display.text = watermarkScale

                list_swaps.adapter = swaps

                txt_mem.text = memInfo

                if (currentSwap.isNotEmpty()) {
                    btn_swap_close.visibility = View.VISIBLE
                    btn_swap_create.visibility = View.GONE
                    swap_state.text = getString(R.string.swap_state_using)
                } else {
                    btn_swap_close.visibility = View.GONE
                    btn_swap_create.visibility = View.VISIBLE
                    if (swapFileExists) {
                        swap_state.text = getString(R.string.swap_state_created)
                    } else {
                        swap_state.text = getString(R.string.swap_state_undefined)
                    }
                }

                if (zramEnabled) {
                    zram_state.text = getString(R.string.swap_state_using)
                } else {
                    zram_state.text = getString(R.string.swap_state_created)
                }

                swap_auto_lmk.isChecked = swapConfig.getBoolean(SpfConfig.SWAP_SPF_AUTO_LMK, false)
                val lmkUtils = LMKUtils()
                if (lmkUtils.supported() && !swapModuleUtils.magiskModuleInstalled) {
                    swap_lmk_current.text = lmkUtils.getCurrent()
                    swap_auto_lmk_wrap.visibility = View.VISIBLE
                } else {
                    swap_auto_lmk_wrap.visibility = View.GONE
                }

                extra_free_kbytes_display.text = extraFreeKbytes

                zram0_stat.text = zramStatus
                txt_swap_io.text = vmStat

                txt_zram_size_display.text = "${zramSize}MB"

                zram_compact_algorithm.text = compAlgorithm

                if (currentSwap.isNotEmpty()) {
                    txt_swap_auto_start.text = if (swapConfig.getBoolean(SpfConfig.SWAP_SPF_SWAP, false)) "重启后保持当前设置" else "重启后失效"
                } else {
                    txt_swap_auto_start.text = "--"
                }
                txt_zram_auto_start.text = if (swapConfig.getBoolean(SpfConfig.SWAP_SPF_ZRAM, false)) "重启后保持当前设置" else "重启后还原系统设定"
            } catch (ex: java.lang.Exception) {
            }
        }
    }

    private val vmStat: String
        get() {
            val vmstat = KernelProrp.getProp("/proc/vmstat")
            vmstat.run {
                val text = StringBuilder()
                try {
                    var prop = "";
                    var value = "";
                    for (row in split("\n")) {
                        if (row.startsWith("pswpin")) {
                            prop = "从SWAP读出："
                        } else if (row.startsWith("pswpout")) {
                            prop = "写入到SWAP："
                        } else {
                            continue
                        }
                        value = row.split(" ")[1]
                        text.append(prop)
                        val mb = (value.toLong() * 4 / 1024)
                        if (mb > 10240) {
                            text.append(String.format("%.2f", (mb / 1024f)))
                            text.append("GB\n")
                        } else {
                            text.append(mb)
                            text.append("MB\n")
                        }
                    }
                } catch (ex: Exception) {
                }

                return text.toString().trim()
            }
        }

    private fun getZRamStatus(compAlgorithm: String): String {
        return if (RootFile.fileExists("/proc/zraminfo")) {
            KernelProrp.getProp("/proc/zraminfo")
        } else {
            // 最大压缩流
            // val max_comp_streams = KernelProrp.getProp("/sys/block/zram0/max_comp_streams")
            // 存储在此磁盘中的未压缩数据大小
            var origDataSize = KernelProrp.getProp("/sys/block/zram0/orig_data_size")
            // 存储在此磁盘中的压缩数据大小
            var comprDataSize = KernelProrp.getProp("/sys/block/zram0/compr_data_size")
            if (origDataSize.isBlank() || comprDataSize.isBlank()) {
                val mmStat = KernelProrp.getProp("/sys/block/zram0/mm_stat").split("[ ]+".toRegex())
                if (mmStat.size > 1) {
                    origDataSize = mmStat[0]
                    comprDataSize = mmStat[1]
                }
            }

            // 为此磁盘分配的内存量
            val memUsedTotal = KernelProrp.getProp("/sys/block/zram0/mem_used_total")

            val zramWriteBackStat = if (swapUtils.zramWriteBackSupport) swapUtils.writeBackStat else null

            val generalStats = if (memUsedTotal.length > 0) {
                // 可用于存储的最大内存量
                val memLimit = KernelProrp.getProp("/sys/block/zram0/mem_limit")
                // 消耗的最大内存量
                val memUsedMax = KernelProrp.getProp("/sys/block/zram0/mem_used_max")
                // 写入此磁盘的相同元素填充页面的数量 不占用内存
                // val same_pages = KernelProrp.getProp("/sys/block/zram0/same_pages")
                // 压缩期间释放的页数
                // val pages_compacted = KernelProrp.getProp("/sys/block/zram0/pages_compacted")
                // 不可压缩数据
                // val huge_pages = KernelProrp.getProp("/sys/block/zram0/huge_pages")

                String.format(
                        getString(R.string.swap_zram_stat_format),
                        compAlgorithm,
                        zramInfoValueParseMB(origDataSize),
                        zramInfoValueParseMB(comprDataSize),
                        zramInfoValueParseMB(memUsedTotal),
                        zramInfoValueParseMB(memUsedMax),
                        if (memLimit == "0") "" else memLimit,
                        zramCompressionRatio(origDataSize, comprDataSize))
            } else {
                String.format(
                        getString(R.string.swap_zram_stat_format2),
                        compAlgorithm,
                        zramInfoValueParseMB(origDataSize),
                        zramInfoValueParseMB(comprDataSize),
                        zramCompressionRatio(origDataSize, comprDataSize))
            }
            if (zramWriteBackStat != null) {
                return generalStats + "\n\n" + String.format(
                        getString(R.string.swap_zram_writback_stat),
                        zramWriteBackStat.backingDev,
                        zramWriteBackStat.backed / 1024,
                        zramWriteBackStat.backReads / 1024,
                        zramWriteBackStat.backWrites / 1024
                )
            } else {
                return generalStats
            }
        }
    }

    private fun zramInfoValueParseMB(sizeStr: String): String {
        return try {
            (sizeStr.toLong() / 1024 / 1024).toString() + "MB"
        } catch (ex: java.lang.Exception) {
            sizeStr
        }
    }

    private fun zramCompressionRatio(origDataSize: String, comprDataSize: String): String {
        return try {
            (comprDataSize.toLong() * 1000 / origDataSize.toLong() / 10.0).toString() + "%"
        } catch (ex: java.lang.Exception) {
            "$comprDataSize/$origDataSize"
        }
    }

    private fun swapUsedSizeParseMB(sizeStr: String): String {
        return try {
            (sizeStr.toLong() / 1024).toString()
        } catch (ex: java.lang.Exception) {
            sizeStr
        }
    }

    override fun onResume() {
        super.onResume()
        title = getString(R.string.menu_swap)
        startTimer()
    }

    private var showSwapOpened = {
        Toast.makeText(context, getString(R.string.executed), Toast.LENGTH_LONG).show()
        processBarDialog.hideDialog()
    }

    class OnSeekBarChangeListener(
            private var onValueChange: Runnable?,
            private var omCompleted: Runnable?,
            private var spf: SharedPreferences,
            private var spfProp: String,
            private var ratio: Int = 1) : SeekBar.OnSeekBarChangeListener {
        override fun onStopTrackingTouch(seekBar: SeekBar?) {
            omCompleted?.run()
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) {
        }

        @SuppressLint("ApplySharedPref")
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            val value = progress * ratio
            if (spf.getInt(spfProp, Int.MIN_VALUE) == value) {
                return
            }
            spf.edit().putInt(spfProp, value).commit()
            onValueChange?.run()
        }
    }

    // 离开界面时保存配置
    override fun onPause() {
        stopTimer()
        swapModuleUtils.saveModuleConfig(swapConfig)
        super.onPause()
    }

    override fun onDestroy() {
        processBarDialog.hideDialog()
        super.onDestroy()
    }
}
