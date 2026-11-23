package com.omarea.vtools.activities

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.omarea.common.ui.DialogHelper
import com.omarea.common.ui.ProgressBarDialog
import com.omarea.data.EventBus
import com.omarea.data.EventType
import com.omarea.scene_mode.FasModeParams
import com.omarea.scene_mode.FasRsConfig
import com.omarea.scene_mode.FasRsManager
import com.omarea.scene_mode.UperfManager
import com.omarea.utils.AppListHelper
import com.omarea.vtools.R
import org.json.JSONObject
import java.math.BigDecimal
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class ActivitySchedulerTuning : ActivityBase() {
    companion object {
        const val EXTRA_SCHEDULER = "scheduler"
        const val EXTRA_INITIAL_TAB = "initial_tab"
        const val SCHEDULER_UPERF = "uperf"
        const val SCHEDULER_FAS_RS = "fas_rs"
    }

    private lateinit var statusBanner: TextView
    private lateinit var tabsContainer: LinearLayout
    private lateinit var contentContainer: LinearLayout
    private lateinit var btnReset: Button
    private lateinit var btnSave: Button
    private lateinit var progressDialog: ProgressBarDialog

    private var schedulerType: String = SCHEDULER_UPERF
    private var currentTab: String = "global"
    private val tabKeys = ArrayList<String>()

    private var fasConfig: FasRsConfig? = null
    private var uperfRoot: JSONObject? = null
    private val validationErrors = LinkedHashMap<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scheduler_tuning)
        setBackArrow()

        statusBanner = findViewById(R.id.tuning_status_banner)
        tabsContainer = findViewById(R.id.tuning_tabs_container)
        contentContainer = findViewById(R.id.tuning_content_container)
        btnReset = findViewById(R.id.tuning_btn_reset)
        btnSave = findViewById(R.id.tuning_btn_save)
        progressDialog = ProgressBarDialog(this)

        schedulerType = intent?.getStringExtra(EXTRA_SCHEDULER)
            ?: if (FasRsManager.isFasRsActive()) SCHEDULER_FAS_RS else SCHEDULER_UPERF
        currentTab = intent?.getStringExtra(EXTRA_INITIAL_TAB)?.takeIf { it.isNotEmpty() } ?: "balance"

        title = if (schedulerType == SCHEDULER_FAS_RS) "FAS-RS 参数微调" else "Uperf-R 参数微调"

        btnReset.setOnClickListener { confirmResetDefault() }
        btnSave.setOnClickListener { saveAndApply() }

        loadConfigurationAsync()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    private fun resolveAccentColor(): Int {
        val tv = TypedValue()
        return if (theme.resolveAttribute(android.R.attr.colorAccent, tv, true)) tv.data else Color.parseColor("#BC8DE4")
    }

    private fun resolveTextPrimary(): Int {
        val tv = TypedValue()
        return if (theme.resolveAttribute(android.R.attr.textColorPrimary, tv, true)) {
            if (tv.resourceId != 0) getColor(tv.resourceId) else tv.data
        } else {
            if (themeMode.isDarkMode) Color.WHITE else Color.BLACK
        }
    }

    private fun resolveTextSecondary(): Int {
        val tv = TypedValue()
        return if (theme.resolveAttribute(android.R.attr.textColorSecondary, tv, true)) {
            if (tv.resourceId != 0) getColor(tv.resourceId) else tv.data
        } else {
            Color.GRAY
        }
    }

    private fun resolveCardBg(): Int {
        val tv = TypedValue()
        return if (theme.resolveAttribute(R.attr.cardBg, tv, true)) {
            if (tv.resourceId != 0) getColor(tv.resourceId) else tv.data
        } else {
            if (themeMode.isDarkMode) Color.parseColor("#181818") else Color.WHITE
        }
    }

    private fun loadConfigurationAsync() {
        progressDialog.showDialog("正在加载配置...")
        Thread {
            if (schedulerType == SCHEDULER_FAS_RS) {
                val cfg = FasRsManager.loadConfig()
                val running = FasRsManager.isDaemonRunning()
                val curMode = FasRsManager.getCurrentMode().ifEmpty { "balance" }
                runOnUiThread {
                    progressDialog.hideDialog()
                    if (cfg == null) {
                        DialogHelper.alert(this, "读取失败", "未能读取 /data/adb/fas-rs/games.toml，请确认模块已正确安装。") {
                            finish()
                        }
                        return@runOnUiThread
                    }
                    fasConfig = cfg
                    statusBanner.text = "FAS-RS | 状态：${if (running) "运行中" else "未运行"} | 当前模式：$curMode | 边界：${FasRsManager.GAMES_CONFIG} (0600，保存后热重载)"
                    buildTabsForFasRs()
                    renderCurrentTab()
                }
            } else {
                val root = UperfManager.loadConfigJson()
                val running = UperfManager.isDaemonRunning()
                val curMode = UperfManager.getCurrentMode().ifEmpty { "balance" }
                runOnUiThread {
                    progressDialog.hideDialog()
                    if (root == null) {
                        DialogHelper.alert(this, "读取失败", "未能读取 /data/adb/uperf/uperf.json，请确认 Uperf-R 模块已正确安装。") {
                            finish()
                        }
                        return@runOnUiThread
                    }
                    uperfRoot = root
                    val soc = root.optJSONObject("meta")?.optString("name", "") ?: ""
                    statusBanner.text = "Uperf-R ($soc) | 状态：${if (running) "运行中" else "未运行"} | 模式：$curMode | 边界：${UperfManager.ACTIVE_CONFIG} (0600，validate_config.awk 校验)"
                    buildTabsForUperf(root)
                    renderCurrentTab()
                }
            }
        }.start()
    }

    private fun tabDisplayName(key: String): String = when (key) {
        "global" -> if (schedulerType == SCHEDULER_FAS_RS) "全局" else "全局触发"
        "powersave" -> "省电"
        "balance" -> "均衡"
        "performance" -> "性能"
        "fast" -> "极速"
        "crazy" -> "极客"
        "games" -> "游戏列表"
        else -> key
    }

    private fun buildTabsForFasRs() {
        tabKeys.clear()
        tabKeys.addAll(listOf("global", "powersave", "balance", "performance", "fast", "games"))
        if (currentTab !in tabKeys) currentTab = "balance"
        rebuildTabStrip()
    }

    private fun buildTabsForUperf(root: JSONObject) {
        tabKeys.clear()
        tabKeys.add("global")
        val presets = root.optJSONObject("presets")
        val preferred = listOf("powersave", "balance", "performance", "fast", "crazy")
        if (presets != null) {
            for (k in preferred) {
                if (presets.has(k)) tabKeys.add(k)
            }
            val iter = presets.keys()
            while (iter.hasNext()) {
                val k = iter.next()
                if (k !in tabKeys) tabKeys.add(k)
            }
        }
        if (currentTab !in tabKeys) {
            currentTab = if ("balance" in tabKeys) "balance" else tabKeys.first()
        }
        rebuildTabStrip()
    }

    private fun rebuildTabStrip() {
        tabsContainer.removeAllViews()
        val accent = resolveAccentColor()
        val normalBg = Color.argb(28, Color.red(accent), Color.green(accent), Color.blue(accent))
        val textPrimary = resolveTextPrimary()

        for (key in tabKeys) {
            val selected = (key == currentTab)
            val btn = TextView(this).apply {
                text = tabDisplayName(key)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (selected) Color.WHITE else textPrimary)
                setPadding(dp(14), dp(7), dp(14), dp(7))
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(if (selected) accent else normalBg)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
                setOnClickListener {
                    if (currentTab != key) {
                        currentTab = key
                        rebuildTabStrip()
                        renderCurrentTab()
                    }
                }
            }
            tabsContainer.addView(btn)
        }
    }

    private fun renderCurrentTab() {
        contentContainer.removeAllViews()
        validationErrors.clear()

        if (schedulerType == SCHEDULER_FAS_RS) {
            val cfg = fasConfig ?: return
            when (currentTab) {
                "global" -> renderFasGlobalTab(cfg)
                "games" -> renderFasGamesTab(cfg)
                else -> renderFasModeTab(cfg, currentTab)
            }
        } else {
            val root = uperfRoot ?: return
            if (currentTab == "global") {
                renderUperfGlobalTab(root)
            } else {
                renderUperfPresetTab(root, currentTab)
            }
        }
    }

    private fun createCard(titleText: String, subtitleText: String? = null): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(resolveCardBg())
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(4)
            }
        }
        card.addView(TextView(this).apply {
            text = titleText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(resolveAccentColor())
        })
        if (!subtitleText.isNullOrEmpty()) {
            card.addView(TextView(this).apply {
                text = subtitleText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(resolveTextSecondary())
                setPadding(0, dp(2), 0, dp(8))
            })
        }
        contentContainer.addView(card)
        return card
    }

    private fun addBooleanRow(
        parent: LinearLayout,
        label: String,
        subText: String,
        initial: Boolean,
        onChange: (Boolean) -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(12)
            }
        }
        textCol.addView(TextView(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(resolveTextPrimary())
        })
        if (subText.isNotEmpty()) {
            textCol.addView(TextView(this).apply {
                text = subText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(resolveTextSecondary())
            })
        }
        val sw = Switch(this).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
        }
        row.addView(textCol)
        row.addView(sw)
        parent.addView(row)
    }

    private fun formatNumberDisplay(value: Double, integerMode: Boolean): String {
        if (integerMode) return value.roundToLong().toString()
        val s = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
        return if (s == "-0") "0" else s
    }

    private fun addNumericRow(
        parent: LinearLayout,
        fieldId: String,
        label: String,
        keyHint: String,
        description: String,
        initialValue: Double,
        minVal: Double,
        maxVal: Double,
        step: Double,
        unit: String = "",
        integerMode: Boolean = false,
        sliderMaxVal: Double? = null,
        onValueChanged: (Double) -> Unit
    ) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(10))
        }

        val header = TextView(this).apply {
            text = if (unit.isNotEmpty()) "$label ($unit)" else label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(resolveTextPrimary())
        }
        val sub = TextView(this).apply {
            text = if (description.isNotEmpty()) "$keyHint · $description" else keyHint
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(resolveTextSecondary())
            setPadding(0, dp(1), 0, dp(6))
        }
        container.addView(header)
        container.addView(sub)

        var currentVal = initialValue
        var syncing = false

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val minusBtn = Button(this).apply {
            text = "-"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            minimumWidth = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(36))
        }

        val edit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or
                (if (!integerMode) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0) or
                (if (minVal < 0) InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(resolveTextPrimary())
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            }
            setText(formatNumberDisplay(initialValue, integerMode))
        }

        val plusBtn = Button(this).apply {
            text = "+"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            minimumWidth = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(36))
        }

        val rangeHint = TextView(this).apply {
            text = "范围 ${formatNumberDisplay(minVal, integerMode)}~${formatNumberDisplay(maxVal, integerMode)}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(resolveTextSecondary())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
            }
        }

        controls.addView(minusBtn)
        controls.addView(edit)
        controls.addView(plusBtn)
        controls.addView(rangeHint)
        container.addView(controls)

        val effectiveSeekMax = sliderMaxVal ?: maxVal
        val stepsCount = if (effectiveSeekMax > minVal && step > 0.0) {
            ((effectiveSeekMax - minVal) / step).roundToInt().coerceIn(1, 2000)
        } else 0

        var seekBar: SeekBar? = null
        if (stepsCount in 2..1000) {
            seekBar = SeekBar(this).apply {
                max = stepsCount
                progress = ((initialValue - minVal) / step).roundToInt().coerceIn(0, stepsCount)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        if (!fromUser || syncing) return
                        syncing = true
                        val newVal = (minVal + p * step).coerceIn(minVal, maxVal)
                        currentVal = if (integerMode) newVal.roundToLong().toDouble() else newVal
                        validationErrors.remove(fieldId)
                        edit.error = null
                        edit.setText(formatNumberDisplay(currentVal, integerMode))
                        onValueChanged(currentVal)
                        syncing = false
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            container.addView(seekBar)
        }

        fun applyStep(delta: Double) {
            val base = edit.text.toString().trim().toDoubleOrNull() ?: currentVal
            val next = (base + delta).coerceIn(minVal, maxVal)
            val rounded = if (integerMode) {
                next.roundToLong().toDouble()
            } else {
                (next * 1000.0).roundToLong() / 1000.0
            }
            syncing = true
            currentVal = rounded
            validationErrors.remove(fieldId)
            edit.error = null
            edit.setText(formatNumberDisplay(rounded, integerMode))
            seekBar?.let { sb ->
                sb.progress = ((rounded - minVal) / step).roundToInt().coerceIn(0, sb.max)
            }
            onValueChanged(rounded)
            syncing = false
        }

        minusBtn.setOnClickListener { applyStep(-step) }
        plusBtn.setOnClickListener { applyStep(step) }

        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (syncing) return
                val raw = s?.toString()?.trim().orEmpty()
                val parsed = raw.toDoubleOrNull()
                if (parsed == null || !parsed.isFinite() || parsed < minVal || parsed > maxVal) {
                    val err = "$label 需在 ${formatNumberDisplay(minVal, integerMode)}~${formatNumberDisplay(maxVal, integerMode)} 之间"
                    validationErrors[fieldId] = err
                    edit.error = err
                    return
                }
                val norm = if (integerMode) parsed.roundToLong().toDouble() else parsed
                validationErrors.remove(fieldId)
                edit.error = null
                currentVal = norm
                syncing = true
                seekBar?.let { sb ->
                    sb.progress = ((norm - minVal) / step).roundToInt().coerceIn(0, sb.max)
                }
                onValueChanged(norm)
                syncing = false
            }
        })

        parent.addView(container)
    }

    private fun renderFasGlobalTab(cfg: FasRsConfig) {
        val card = createCard("FAS-RS 全局选项", "对应 /data/adb/fas-rs/games.toml -> [config]")
        addBooleanRow(
            card,
            "模块升级时重置为默认模式参数 (keep_std)",
            "关闭以保留你的微调数值；开启则在覆盖安装模块时用默认参数覆盖 4 个模式参数",
            cfg.keepStd
        ) { cfg.keepStd = it }

        addBooleanRow(
            card,
            "从 /data/adb/fas-rs/scene_games.xml 读取附加游戏名单 (scene_game_list)",
            "modify 版仅读取 Root 专属目录中的名单，不再读取应用可写的 /data/data 或 /sdcard 路径",
            cfg.sceneGameList
        ) { cfg.sceneGameList = it }

        val info = createCard("运行机理与温控说明", "FAS-RS 与系统原厂温控协作")
        info.addView(TextView(this).apply {
            text = "• FAS-RS 使用内核 eBPF 挂载前台应用帧耗时，按目标帧率实时收敛各 CPU 簇频率。\n" +
                "• 保留系统原厂温控服务不被破坏，仅通过各模式 core_temp_thresh 做柔和掉帧前瞻降档。\n" +
                "• 已彻底移除 Root LuaJIT 扩展插件与 /sdcard/Android/fas-rs 明文目录。"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(resolveTextSecondary())
        })
    }

    private fun renderFasModeTab(cfg: FasRsConfig, mode: String) {
        val params = cfg.modes.getOrPut(mode) { FasModeParams() }
        val card = createCard("${tabDisplayName(mode)} 模式参数 ($mode)", "修改后点击“保存并应用”将由 fas-rs 二进制校验并热重载")

        addNumericRow(
            card,
            "fas_${mode}_margin_fps",
            "帧率余量 margin_fps",
            "[$mode].margin_fps（以 60 FPS 为基准按比例缩放）",
            "适当增大余量可进一步稳帧但略增功耗；设为 0 则贴近目标帧率极限省电",
            params.marginFpsBase,
            0.0,
            20.0,
            0.1,
            unit = "FPS",
            integerMode = false,
            sliderMaxVal = 10.0
        ) { params.marginFpsBase = it }

        val tempHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tempEnabled = (params.coreTempThreshMilliC != null)

        fun rebuildTempRow() {
            tempHolder.removeAllViews()
            if (params.coreTempThreshMilliC != null) {
                val curC = (params.coreTempThreshMilliC!! / 1000.0)
                addNumericRow(
                    tempHolder,
                    "fas_${mode}_temp",
                    "软温控目标温度阈值 core_temp_thresh",
                    "[$mode].core_temp_thresh（将以毫摄氏度写入，如 90°C -> 90000）",
                    "超过该核心温度阈值时逐步温和下调目标帧率，避免触发原厂硬限频降档",
                    curC,
                    40.0,
                    115.0,
                    1.0,
                    unit = "°C",
                    integerMode = true,
                    sliderMaxVal = 110.0
                ) { v -> params.coreTempThreshMilliC = v.roundToLong() * 1000L }
            }
        }

        addBooleanRow(
            card,
            "启用模式级核心温度柔控 (core_temp_thresh)",
            "关闭则设为 \"disabled\"（仍保留手机系统原厂底层温控保护）",
            tempEnabled
        ) { checked ->
            params.coreTempThreshMilliC = if (checked) 90000L else null
            validationErrors.remove("fas_${mode}_temp")
            rebuildTempRow()
        }
        card.addView(tempHolder)
        rebuildTempRow()

        if (params.marginOverrides.isNotEmpty()) {
            val ovCard = createCard("高级帧率挡位覆写 (margin_fps 档位表)", "当前配置包含按目标帧率单独覆写的 margin_fps")
            for (fpsKey in params.marginOverrides.keys.toList()) {
                val cur = params.marginOverrides[fpsKey] ?: 1.0
                addNumericRow(
                    ovCard,
                    "fas_${mode}_ov_$fpsKey",
                    "${fpsKey}Hz 目标帧率专用余量",
                    "[$mode].margin_fps.\"$fpsKey\"",
                    "仅当目标帧率为 $fpsKey FPS 时生效",
                    cur,
                    0.0,
                    20.0,
                    0.1,
                    unit = "FPS",
                    integerMode = false,
                    sliderMaxVal = 10.0
                ) { params.marginOverrides[fpsKey] = it }
            }
        }
    }

    private fun renderFasGamesTab(cfg: FasRsConfig) {
        val actionCard = createCard("游戏目标帧率列表 ([game_list])", "只有列入此处（或 scene_game_list）的应用在前台运行时才会启用 FAS-RS 帧调度")

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        val pickBtn = Button(this).apply {
            text = "从已安装应用添加"
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(8) }
        }
        val manualBtn = Button(this).apply {
            text = "手动输入包名"
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
        }
        pickBtn.setOnClickListener { pickInstalledAppForGameList(cfg) }
        manualBtn.setOnClickListener { showEditGameDialog(cfg, null, "[30, 60, 90, 120]") }
        btnRow.addView(pickBtn)
        btnRow.addView(manualBtn)
        actionCard.addView(btnRow)

        if (cfg.gameList.isEmpty()) {
            actionCard.addView(TextView(this).apply {
                text = "当前游戏列表为空，点击上方按钮添加需要 FAS-RS 调度的游戏。"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(resolveTextSecondary())
                setPadding(0, dp(10), 0, dp(4))
            })
            return
        }

        val pm = packageManager
        for ((pkg, fpsSpec) in cfg.gameList.entries.toList()) {
            val rowCard = createCard(pkg, null)
            val appLabel = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) {
                ""
            }
            val metaView = TextView(this).apply {
                text = if (appLabel.isNotEmpty()) "$appLabel | 目标帧率：$fpsSpec" else "目标帧率：$fpsSpec"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(resolveTextSecondary())
                setPadding(0, dp(2), 0, dp(8))
            }
            rowCard.addView(metaView)

            val ops = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val editBtn = Button(this).apply {
                text = "修改帧率"
                layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(8) }
                setOnClickListener { showEditGameDialog(cfg, pkg, fpsSpec) }
            }
            val delBtn = Button(this).apply {
                text = "移除"
                layoutParams = LinearLayout.LayoutParams(dp(84), dp(36))
                setOnClickListener {
                    cfg.gameList.remove(pkg)
                    renderCurrentTab()
                }
            }
            ops.addView(editBtn)
            ops.addView(delBtn)
            rowCard.addView(ops)
        }
    }

    private fun pickInstalledAppForGameList(cfg: FasRsConfig) {
        progressDialog.showDialog("正在扫描已安装应用...")
        Thread {
            val apps = AppListHelper(this, false).getBootableApps(null, true)
                .filter { it.packageName != packageName }
                .sortedWith(compareBy({ it.appType.name }, { it.appName.toString().lowercase(Locale.getDefault()) }))
            runOnUiThread {
                progressDialog.hideDialog()
                if (apps.isEmpty()) {
                    Toast.makeText(this, "未找到可选应用", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val labels = apps.map { app ->
                    val prefix = if (cfg.gameList.containsKey(app.packageName)) "✓ " else ""
                    "$prefix${app.appName} (${app.packageName})"
                }.toTypedArray()

                DialogHelper.animDialog(
                    AlertDialog.Builder(this)
                        .setTitle("选择要加入 FAS-RS 调度的应用")
                        .setItems(labels) { d, which ->
                            d.dismiss()
                            val chosen = apps[which]
                            val existing = cfg.gameList[chosen.packageName] ?: "[30, 60, 90, 120]"
                            showEditGameDialog(cfg, chosen.packageName, existing)
                        }
                        .setNegativeButton("取消", null)
                )
            }
        }.start()
    }

    private fun showEditGameDialog(cfg: FasRsConfig, existingPkg: String?, initialSpec: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(6))
        }
        val pkgInput = EditText(this).apply {
            hint = "应用包名，如 com.miHoYo.Yuanshen"
            setText(existingPkg ?: "")
            isEnabled = (existingPkg == null)
        }
        val specInput = EditText(this).apply {
            hint = "帧率档位：auto / 60 / [30, 60, 90, 120]"
            setText(initialSpec)
        }
        val presetsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("auto", "60", "[30, 60]", "[30, 60, 90, 120]").forEach { preset ->
            val b = Button(this).apply {
                text = preset
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(dp(6), 0, dp(6), 0)
                minimumWidth = 0
                setOnClickListener { specInput.setText(preset) }
            }
            presetsRow.addView(b)
        }
        box.addView(TextView(this).apply { text = "包名" })
        box.addView(pkgInput)
        box.addView(TextView(this).apply {
            text = "目标帧率（点击快捷填入或自定义，如 [30, 45, 60]）："
            setPadding(0, dp(8), 0, dp(4))
        })
        box.addView(presetsRow)
        box.addView(specInput)

        DialogHelper.animDialog(
            AlertDialog.Builder(this)
                .setTitle(if (existingPkg == null) "添加 FAS-RS 调度应用" else "修改目标帧率")
                .setView(box)
                .setPositiveButton("确定") { _, _ ->
                    val safePkg = FasRsManager.sanitizePackageName(pkgInput.text.toString().trim())
                    val spec = specInput.text.toString().trim()
                    if (safePkg.isEmpty()) {
                        Toast.makeText(this, "包名格式无效（仅支持字母/数字/./_/:）", Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                    if (!FasRsManager.isValidFpsSpec(spec)) {
                        Toast.makeText(this, "帧率格式无效：请输入 auto、10~240 整数或 [30, 60, 120]", Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                    cfg.gameList[safePkg] = spec
                    renderCurrentTab()
                }
                .setNegativeButton("取消", null)
        )
    }

    private fun renderUperfGlobalTab(root: JSONObject) {
        val guardCard = createCard("安全信任边界锁定", "为防止提权篡改，以下路径节点受 SCENE4R 锁定保护且禁止在界面修改")
        val modules = root.optJSONObject("modules") ?: JSONObject().also { root.put("modules", it) }
        val switcher = modules.optJSONObject("switcher")
        guardCard.addView(TextView(this).apply {
            text = "• switchInode: ${switcher?.optString("switchInode", "-")}\n" +
                "• perapp: ${switcher?.optString("perapp", "-")}\n" +
                "• sysfs.knob 路径白名单受 validate_config.awk 强制校验"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(resolveTextSecondary())
        })

        val hintDur = switcher?.optJSONObject("hintDuration")
        if (hintDur != null) {
            val hintCard = createCard("交互升频持续时间 (hintDuration)", "控制触摸、滑动手势、应用切换与掉帧时保持提频的时间（秒）")
            renderDynamicJsonScalars(hintCard, "hintDur", hintDur)
        }

        val inputObj = modules.optJSONObject("input")
        if (inputObj != null) {
            val inputCard = createCard("输入手势检测 (modules.input)", "调整滑动、手势触发阈值与进入闲置/长按判定时长")
            renderDynamicJsonScalars(inputCard, "input", inputObj)
        }

        val sfObj = modules.optJSONObject("sfanalysis")
        if (sfObj != null) {
            val sfCard = createCard("SurfaceFlinger 帧分析 (modules.sfanalysis)", "配置 UI 渲染状态机松弛判断参数")
            renderDynamicJsonScalars(sfCard, "sfanalysis", sfObj)
        }
    }

    private fun renderUperfPresetTab(root: JSONObject, modeKey: String) {
        val presets = root.optJSONObject("presets") ?: return
        val modeObj = presets.optJSONObject(modeKey) ?: return

        val orderedScenes = listOf("*", "idle", "touch", "trigger", "gesture", "junk", "switch")
        val seen = HashSet<String>()
        for (sceneKey in orderedScenes) {
            if (modeObj.has(sceneKey)) {
                seen.add(sceneKey)
                val obj = modeObj.optJSONObject(sceneKey) ?: continue
                renderUperfSceneCard(modeKey, sceneKey, obj)
            }
        }
        val extraIter = modeObj.keys()
        while (extraIter.hasNext()) {
            val k = extraIter.next()
            if (k !in seen) {
                val obj = modeObj.optJSONObject(k) ?: continue
                renderUperfSceneCard(modeKey, k, obj)
            }
        }
    }

    private fun sceneDisplayName(sceneKey: String): Pair<String, String> = when (sceneKey) {
        "*" -> Pair("基础默认参数 (*)", "该模式下所有场景共享的基底调频与功耗上限")
        "idle" -> Pair("空闲状态 (idle)", "无输入且界面静止时的采样与节能约束")
        "touch" -> Pair("触摸交互 (touch)", "点击或滑动屏幕期间的瞬态调度覆写")
        "trigger" -> Pair("瞬时触发 (trigger)", "初始触摸瞬间的短时提频覆写")
        "gesture" -> Pair("连续手势 (gesture)", "全面屏手势或持续滑屏期间的覆写")
        "junk" -> Pair("掉帧响应 (junk)", "SurfaceFlinger 检测到渲染超时/掉帧时的爆发补偿")
        "switch" -> Pair("应用切换/跳转 (switch)", "冷启动与界面跳转时的瞬态爆发参数")
        else -> Pair("场景 ($sceneKey)", "Uperf 预设场景覆写")
    }

    private fun renderUperfSceneCard(modeKey: String, sceneKey: String, sceneObj: JSONObject) {
        if (sceneObj.length() == 0) {
            createCard(sceneDisplayName(sceneKey).first, "当前场景未设置覆写参数（沿用基础参数 *）")
            return
        }
        val (titleText, subText) = sceneDisplayName(sceneKey)
        val card = createCard(titleText, subText)
        renderDynamicJsonScalars(card, "preset_${modeKey}_${sceneKey}", sceneObj)
    }

    private fun uperfParamMeta(key: String): Triple<String, String, DoubleArray> {
        // Triple(中文名称, 说明, [min, max, step, integerMode(0/1), sliderMax])
        return when (key) {
            "cpu.margin" -> Triple("CPU 裕度倍率 (margin)", "升高可更积极升频保流畅，降低更省电", doubleArrayOf(0.0, 2.0, 0.05, 0.0, 1.5))
            "cpu.burst" -> Triple("瞬时爆发系数 (burst)", "升高增强触控/卡顿突发性能拉升", doubleArrayOf(0.0, 2.0, 0.05, 0.0, 1.5))
            "cpu.latencyTime" -> Triple("升频延迟阈值 (latencyTime)", "0=零延迟响应；>0 可过滤极短毛刺省电 (秒)", doubleArrayOf(0.0, 3.0, 0.05, 0.0, 1.5))
            "cpu.slowLimitPower" -> Triple("长时功耗上限 (slowLimitPower)", "平均功耗软限制；999 表示不限制", doubleArrayOf(0.2, 999.0, 0.2, 0.0, 15.0))
            "cpu.fastLimitPower" -> Triple("短时功耗上限 (fastLimitPower)", "峰值功耗软限制；999 表示不限制", doubleArrayOf(0.2, 999.0, 0.2, 0.0, 20.0))
            "cpu.fastLimitCapacity" -> Triple("爆发能量池容量 (fastLimitCapacity)", "允许短时突破长时功耗的累计能量预算", doubleArrayOf(0.2, 999.0, 0.2, 0.0, 20.0))
            "cpu.fastLimitRecoverScale" -> Triple("能量恢复比例 (fastLimitRecoverScale)", "高负载后恢复快速预算的回充系数", doubleArrayOf(0.0, 5.0, 0.05, 0.0, 1.0))
            "cpu.baseSampleTime" -> Triple("基础采样周期 (baseSampleTime)", "空闲状态采样周期 (秒)", doubleArrayOf(0.005, 1.0, 0.005, 0.0, 0.2))
            "cpu.baseSlackTime" -> Triple("空闲松弛周期 (baseSlackTime)", "空闲态最大延迟检查周期 (秒)", doubleArrayOf(0.005, 1.0, 0.01, 0.0, 0.5))
            "cpu.predictThd" -> Triple("负载预测阈值 (predictThd)", "触发负载跃升预测的阈值倍率", doubleArrayOf(0.0, 5.0, 0.1, 0.0, 3.0))
            "touch" -> Triple("触摸提频时长", "秒", doubleArrayOf(0.0, 30.0, 0.2, 0.0, 10.0))
            "trigger" -> Triple("触发提频时长", "秒", doubleArrayOf(0.0, 5.0, 0.01, 0.0, 0.5))
            "gesture" -> Triple("手势提频时长", "秒", doubleArrayOf(0.0, 10.0, 0.05, 0.0, 2.0))
            "switch" -> Triple("应用切换提频时长", "秒", doubleArrayOf(0.0, 10.0, 0.05, 0.0, 3.0))
            "junk" -> Triple("掉帧补偿时长", "秒", doubleArrayOf(0.0, 5.0, 0.01, 0.0, 0.5))
            "idle" -> Triple("闲置等待时长", "秒", doubleArrayOf(0.0, 10.0, 0.1, 0.0, 5.0))
            "swipeThd" -> Triple("滑动识别阈值", "屏幕归一化距离", doubleArrayOf(0.001, 0.5, 0.005, 0.0, 0.2))
            "gestureThdX" -> Triple("横向手势阈值", "屏幕归一化距离", doubleArrayOf(0.001, 0.5, 0.005, 0.0, 0.2))
            "gestureThdY" -> Triple("纵向手势阈值", "屏幕归一化距离", doubleArrayOf(0.001, 0.5, 0.005, 0.0, 0.2))
            "gestureDelayTime" -> Triple("手势延迟退出时长", "秒", doubleArrayOf(0.0, 10.0, 0.2, 0.0, 5.0))
            "holdEnterTime" -> Triple("长按进入阈值", "秒", doubleArrayOf(0.0, 10.0, 0.1, 0.0, 5.0))
            "renderIdleSlackTime" -> Triple("渲染空闲松弛窗口", "秒", doubleArrayOf(0.01, 5.0, 0.05, 0.0, 1.0))
            else -> Triple(key, "", doubleArrayOf(0.0, 10000000.0, if (key.startsWith("sysfs.")) 1.0 else 0.1, if (key.startsWith("sysfs.")) 1.0 else 0.0, 100.0))
        }
    }

    private fun renderDynamicJsonScalars(parent: LinearLayout, idPrefix: String, obj: JSONObject) {
        val keys = ArrayList<String>()
        val iter = obj.keys()
        while (iter.hasNext()) keys.add(iter.next())

        for (key in keys) {
            val value = obj.opt(key)
            when (value) {
                is Boolean -> {
                    val label = when (key) {
                        "enable" -> "启用模块开关 (enable)"
                        "cpu.limitEfficiency" -> "限制能效比核心过高频率 (cpu.limitEfficiency)"
                        else -> key
                    }
                    addBooleanRow(parent, label, key, value) { checked ->
                        obj.put(key, checked)
                    }
                }
                is Number -> {
                    val (label, desc, bounds) = uperfParamMeta(key)
                    val isInt = (bounds[3] >= 0.5) || (key.startsWith("sysfs.") && value is Int || value is Long)
                    val maxB = if (key.startsWith("sysfs.")) 100000000.0 else bounds[1]
                    val sliderMax = if (bounds[4] >= bounds[0]) bounds[4] else maxB
                    addNumericRow(
                        parent,
                        "${idPrefix}_$key",
                        label,
                        key,
                        desc,
                        value.toDouble(),
                        bounds[0],
                        maxB,
                        bounds[2],
                        integerMode = isInt,
                        sliderMaxVal = sliderMax
                    ) { newVal ->
                        if (isInt) {
                            obj.put(key, newVal.roundToLong())
                        } else {
                            obj.put(key, BigDecimal.valueOf(newVal).stripTrailingZeros().toDouble())
                        }
                    }
                }
                else -> {
                    // Preserve read-only string descriptors like sched.scene safely
                    parent.addView(TextView(this).apply {
                        text = "$key = $value"
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setTextColor(resolveTextSecondary())
                        setPadding(0, dp(4), 0, dp(4))
                    })
                }
            }
        }
    }

    private fun confirmResetDefault() {
        val targetName = if (schedulerType == SCHEDULER_FAS_RS) "FAS-RS" else "Uperf-R"
        DialogHelper.confirm(
            this,
            "恢复 $targetName 默认参数",
            "确定要还原 $targetName 的原始默认参数并应用吗？当前微调将被重置。",
            {
                progressDialog.showDialog("正在恢复默认参数...")
                Thread {
                    val (ok, msg) = if (schedulerType == SCHEDULER_FAS_RS) {
                        FasRsManager.resetConfigToDefault()
                    } else {
                        UperfManager.resetConfigToDefault(restartNow = UperfManager.isModuleInstalled())
                    }
                    runOnUiThread {
                        progressDialog.hideDialog()
                        if (ok) {
                            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                            loadConfigurationAsync()
                        } else {
                            DialogHelper.alert(this, "恢复提示", msg)
                        }
                    }
                }.start()
            }
        )
    }

    private fun saveAndApply() {
        if (validationErrors.isNotEmpty()) {
            val firstErr = validationErrors.values.first()
            DialogHelper.alert(this, "参数范围无效", "请先修正输入错误：\n$firstErr")
            return
        }

        progressDialog.showDialog("正在校验并写入安全边界...")
        Thread {
            val (ok, msg) = if (schedulerType == SCHEDULER_FAS_RS) {
                val cfg = fasConfig
                if (cfg == null) Pair(false, "配置未加载") else FasRsManager.saveConfig(cfg)
            } else {
                val root = uperfRoot
                if (root == null) Pair(false, "配置未加载") else UperfManager.saveTunedConfig(root, restartNow = UperfManager.isModuleInstalled())
            }
            runOnUiThread {
                progressDialog.hideDialog()
                if (ok) {
                    EventBus.publish(EventType.SCENE_CONFIG)
                    DialogHelper.alert(this, "保存成功", msg) {
                        loadConfigurationAsync()
                    }
                } else {
                    DialogHelper.alert(this, "保存失败", msg)
                }
            }
        }.start()
    }
}
