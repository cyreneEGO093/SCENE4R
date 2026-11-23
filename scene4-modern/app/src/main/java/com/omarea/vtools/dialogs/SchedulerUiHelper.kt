package com.omarea.vtools.dialogs

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.widget.ScrollView
import android.widget.TextView
import com.omarea.common.ui.DialogHelper
import com.omarea.common.ui.ProgressBarDialog
import com.omarea.data.EventBus
import com.omarea.data.EventType
import com.omarea.scene_mode.FasRsManager
import com.omarea.scene_mode.UperfManager
import com.omarea.vtools.R
import com.omarea.vtools.activities.ActivitySchedulerTuning

/**
 * Shared UI coordinator for mutual-exclusive scheduler switching (Uperf-R <-> FAS-RS)
 * and launching fine-grained scheduler parameter tuning from FragmentHome & ActivityCpuModes.
 */
object SchedulerUiHelper {
    fun updateChooserCardStates(view: android.view.View) {
        val uperfTitle = view.findViewById<TextView?>(R.id.source_uperf_title)
        val uperfDesc = view.findViewById<TextView?>(R.id.source_uperf_desc)
        val fasTitle = view.findViewById<TextView?>(R.id.source_fas_rs_title)
        val fasDesc = view.findViewById<TextView?>(R.id.source_fas_rs_desc)

        val uperfActive = UperfManager.isUperfActive()
        val uperfPresent = UperfManager.isModulePresent()
        val fasActive = FasRsManager.isFasRsActive()
        val fasPresent = FasRsManager.isModulePresent()

        uperfTitle?.text = when {
            uperfActive -> "Uperf-R 调度（当前启用 · 运行中${if (UperfManager.isDaemonRunning()) "" else "/未运行"}）"
            uperfPresent -> "Uperf-R 调度（已安装 · 未启用）"
            else -> "Uperf-R 调度（保留原厂温控）"
        }
        uperfDesc?.text = when {
            uperfActive -> "当前激活调度器 · 点击管理、微调 uperf.json 或切换其它调度"
            uperfPresent -> "点击立即停止当前调度并切换为 Uperf-R（无需重启）"
            else -> "未安装 · 点击离线刷入内置 Uperf-R（支持 120+ SoC，隔离于 /data/adb/uperf）"
        }

        fasTitle?.text = when {
            fasActive -> "FAS-RS 游戏帧率调度（当前启用 · ${if (FasRsManager.isDaemonRunning()) "运行中" else "未运行"}）"
            fasPresent -> "FAS-RS 游戏帧率调度（已安装 · 未启用）"
            else -> "FAS-RS 游戏帧率调度（保留原厂温控）"
        }
        fasDesc?.text = when {
            fasActive -> "当前激活调度器 · 点击微调参数、管理游戏列表或查看运行日志"
            fasPresent -> "点击立即停止当前调度并切换为 FAS-RS（eBPF 帧感知，无需重启）"
            else -> "未安装 · 点击离线刷入内置 FAS-RS modify（${if (FasRsManager.isKernelEbpfSupported()) "内核支持 BPF" else "需内核 eBPF"}）"
        }
    }

    fun openTuningActivity(activity: Activity, scheduler: String, initialTab: String = "balance") {
        activity.startActivity(Intent(activity, ActivitySchedulerTuning::class.java).apply {
            putExtra(ActivitySchedulerTuning.EXTRA_SCHEDULER, scheduler)
            putExtra(ActivitySchedulerTuning.EXTRA_INITIAL_TAB, initialTab)
        })
    }

    fun handleUperfCardClick(activity: Activity, onStateUpdated: () -> Unit) {
        val socSummary = UperfManager.getDetectedSocSummary()
        when {
            UperfManager.isUperfActive() -> {
                val running = if (UperfManager.isDaemonRunning()) "运行中" else "未运行"
                val curMode = UperfManager.getCurrentMode().ifEmpty { "balance" }
                val options = arrayOf(
                    "微调 Uperf-R 调度参数 (uperf.json)",
                    "重启 Uperf-R 守护进程",
                    if (FasRsManager.isModulePresent()) "切换到 FAS-RS 帧感知调度（无需重启）" else "安装并切换到 FAS-RS 调度",
                    "重新部署内置 Uperf-R 模块"
                )
                DialogHelper.animDialog(
                    AlertDialog.Builder(activity)
                        .setTitle("Uperf-R ($running · $curMode · $socSummary)")
                        .setItems(options) { d, which ->
                            d.dismiss()
                            when (which) {
                                0 -> openTuningActivity(activity, ActivitySchedulerTuning.SCHEDULER_UPERF, curMode)
                                1 -> runAsyncSwitch(activity, "正在重启 Uperf-R 守护进程...", onStateUpdated) {
                                    UperfManager.enableAndStartDaemon()
                                }
                                2 -> if (FasRsManager.isModulePresent()) {
                                    confirmSwitchToFasRs(activity, onStateUpdated)
                                } else {
                                    confirmInstallFasRs(activity, onStateUpdated)
                                }
                                3 -> confirmInstallUperf(activity, onStateUpdated)
                            }
                        }
                        .setNegativeButton("取消", null)
                )
            }
            UperfManager.isModulePresent() -> {
                DialogHelper.confirm(
                    activity,
                    "切换为 Uperf-R 调度",
                    "检测到 Uperf-R 已安装但当前未启用（平台：$socSummary）。\n\n确认将停用其它调度进程并立即启动 Uperf-R（无需重启）？",
                    {
                        runAsyncSwitch(activity, "正在停用旧调度并启动 Uperf-R...", onStateUpdated) {
                            UperfManager.enableAndStartDaemon()
                        }
                    }
                )
            }
            else -> confirmInstallUperf(activity, onStateUpdated)
        }
    }

    fun handleFasRsCardClick(activity: Activity, onStateUpdated: () -> Unit) {
        when {
            FasRsManager.isFasRsActive() -> {
                val running = if (FasRsManager.isDaemonRunning()) "运行中" else "未运行"
                val curMode = FasRsManager.getCurrentMode().ifEmpty { "balance" }
                val options = arrayOf(
                    "微调 FAS-RS 参数与游戏列表",
                    "查看运行日志 (/data/adb/fas-rs/fas_log.txt)",
                    "重启 FAS-RS 守护进程",
                    if (UperfManager.isModulePresent()) "切换到 Uperf-R 调度（无需重启）" else "安装并切换到 Uperf-R 调度",
                    "重新部署内置 FAS-RS 模块"
                )
                DialogHelper.animDialog(
                    AlertDialog.Builder(activity)
                        .setTitle("FAS-RS ($running · $curMode)")
                        .setItems(options) { d, which ->
                            d.dismiss()
                            when (which) {
                                0 -> openTuningActivity(activity, ActivitySchedulerTuning.SCHEDULER_FAS_RS, curMode)
                                1 -> showFasRsLogs(activity)
                                2 -> runAsyncSwitch(activity, "正在重启 FAS-RS 守护进程...", onStateUpdated) {
                                    FasRsManager.enableAndStartDaemon()
                                }
                                3 -> if (UperfManager.isModulePresent()) {
                                    confirmSwitchToUperf(activity, onStateUpdated)
                                } else {
                                    confirmInstallUperf(activity, onStateUpdated)
                                }
                                4 -> confirmInstallFasRs(activity, onStateUpdated)
                            }
                        }
                        .setNegativeButton("取消", null)
                )
            }
            FasRsManager.isModulePresent() -> confirmSwitchToFasRs(activity, onStateUpdated)
            else -> confirmInstallFasRs(activity, onStateUpdated)
        }
    }

    private fun confirmSwitchToFasRs(activity: Activity, onStateUpdated: () -> Unit) {
        DialogHelper.confirm(
            activity,
            "切换为 FAS-RS 调度",
            "将立即停用并禁用 Uperf-R（避免双方同时读写 CPU 调频节点），随后启动 FAS-RS 帧感知调度（无需重启）。\n\n安全边界：/data/adb/fas-rs (0700)，已禁用 Lua 扩展并保留系统原厂温控。\n\n确认切换？",
            {
                runAsyncSwitch(activity, "正在停用旧调度并启动 FAS-RS...", onStateUpdated) {
                    FasRsManager.enableAndStartDaemon()
                }
            }
        )
    }

    private fun confirmSwitchToUperf(activity: Activity, onStateUpdated: () -> Unit) {
        DialogHelper.confirm(
            activity,
            "切换为 Uperf-R 调度",
            "将向 FAS-RS 发送优雅退出信号并恢复频率边界，随后启动 Uperf-R 守护进程（无需重启）。\n\n确认切换？",
            {
                runAsyncSwitch(activity, "正在停用 FAS-RS 并启动 Uperf-R...", onStateUpdated) {
                    UperfManager.enableAndStartDaemon()
                }
            }
        )
    }

    private fun confirmInstallUperf(activity: Activity, onStateUpdated: () -> Unit) {
        val socSummary = UperfManager.getDetectedSocSummary()
        DialogHelper.confirm(
            activity,
            "安装 Uperf-R 调度模块",
            "已内置 Uperf-R，基于 yc9559/uperf 完整支持多平台 120 种 SoC 配置：\n" +
                "• 运行目录隔离至 Root 专属 /data/adb/uperf (0700)\n" +
                "• 完整保留系统原厂温控与电池保护\n" +
                "• 已移除第三方闭源注入模块\n\n检测到平台：$socSummary\n是否立即刷入模块？",
            {
                val progress = ProgressBarDialog(activity)
                progress.showDialog("正在刷入 Uperf-R 模块...")
                Thread {
                    val (ok, msg) = UperfManager.installBundledModule(activity)
                    activity.runOnUiThread {
                        progress.hideDialog()
                        DialogHelper.alert(activity, if (ok) "安装成功" else "安装提示", msg)
                        onStateUpdated()
                    }
                }.start()
            }
        )
    }

    private fun confirmInstallFasRs(activity: Activity, onStateUpdated: () -> Unit) {
        val ebpfNote = if (FasRsManager.isKernelEbpfSupported()) "内核已挂载 /sys/fs/bpf (aarch64)" else "警告：未检测到 /sys/fs/bpf，部分内核可能无法挂载 eBPF"
        DialogHelper.confirm(
            activity,
            "安装 FAS-RS 调度模块（SCENE4R modify）",
            "基于 shadow3aaa/fas-rs (GNU GPL v3.0) 源码编译与安全收敛：\n" +
                "• 运行目录与节点严格隔离至 Root 专属 /data/adb/fas-rs (0700)\n" +
                "• 已禁用 Root LuaJIT 扩展、移除 /sdcard 读取及全局渲染 prop 篡改\n" +
                "• 完整保留系统原厂温控与游戏列表参数微调\n" +
                "• $ebpfNote\n\n是否立即离线刷入？",
            {
                val progress = ProgressBarDialog(activity)
                progress.showDialog("正在离线刷入 FAS-RS 模块...")
                Thread {
                    val (ok, msg) = FasRsManager.installBundledModule(activity)
                    activity.runOnUiThread {
                        progress.hideDialog()
                        onStateUpdated()
                        if (ok && FasRsManager.isModulePresent()) {
                            DialogHelper.confirm(
                                activity,
                                "FAS-RS 已就绪",
                                "$msg\n\n是否立即停用其它调度并切换启用 FAS-RS？（无需重启）",
                                {
                                    runAsyncSwitch(activity, "正在启用 FAS-RS...", onStateUpdated) {
                                        FasRsManager.enableAndStartDaemon()
                                    }
                                }
                            )
                        } else {
                            DialogHelper.alert(activity, if (ok) "安装成功" else "安装提示", msg)
                        }
                    }
                }.start()
            }
        )
    }

    fun confirmLeaveDaemonSchedulers(activity: Activity, targetDesc: String, onReady: () -> Unit) {
        if (!FasRsManager.isFasRsActive() && !UperfManager.isUperfActive()) {
            onReady()
            return
        }
        val activeName = if (FasRsManager.isFasRsActive()) "FAS-RS" else "Uperf-R"
        DialogHelper.confirm(
            activity,
            "停用 $activeName",
            "你选择了「$targetDesc」。需要先停止并禁用当前正在运行的 $activeName 守护进程以避免调频冲突。\n\n确认停用 $activeName 并继续？",
            {
                val progress = ProgressBarDialog(activity)
                progress.showDialog("正在停用 $activeName 守护进程...")
                Thread {
                    val stoppedUperf = if (UperfManager.isModuleInstalled() || UperfManager.isDaemonRunning()) {
                        UperfManager.stopDaemonAndDisable()
                    } else true
                    val stoppedFas = if (FasRsManager.isModuleEnabled() || FasRsManager.isDaemonRunning()) {
                        FasRsManager.stopDaemonAndDisable()
                    } else true
                    activity.runOnUiThread {
                        progress.hideDialog()
                        if (stoppedUperf && stoppedFas) {
                            onReady()
                        } else {
                            DialogHelper.alert(activity, "操作失败", "未能完全停止当前调度守护进程，请重试。")
                        }
                    }
                }.start()
            }
        )
    }

    private fun showFasRsLogs(activity: Activity) {
        val progress = ProgressBarDialog(activity)
        progress.showDialog("读取 FAS-RS 日志...")
        Thread {
            val logs = FasRsManager.readRecentLogs(120)
            activity.runOnUiThread {
                progress.hideDialog()
                val tv = TextView(activity).apply {
                    text = logs
                    typeface = Typeface.MONOSPACE
                    textSize = 11.5f
                    setPadding(24, 16, 24, 16)
                    setTextIsSelectable(true)
                }
                val scroll = ScrollView(activity).apply { addView(tv) }
                DialogHelper.animDialog(
                    AlertDialog.Builder(activity)
                        .setTitle("FAS-RS 运行日志 (/data/adb/fas-rs/fas_log.txt)")
                        .setView(scroll)
                        .setPositiveButton("确定", null)
                )
            }
        }.start()
    }

    private fun runAsyncSwitch(activity: Activity, waitText: String, onStateUpdated: () -> Unit, work: () -> Pair<Boolean, String>) {
        val progress = ProgressBarDialog(activity)
        progress.showDialog(waitText)
        Thread {
            val (ok, msg) = work()
            activity.runOnUiThread {
                progress.hideDialog()
                onStateUpdated()
                EventBus.publish(EventType.SCENE_CONFIG)
                DialogHelper.alert(activity, if (ok) "切换成功" else "切换提示", msg)
            }
        }.start()
    }
}
