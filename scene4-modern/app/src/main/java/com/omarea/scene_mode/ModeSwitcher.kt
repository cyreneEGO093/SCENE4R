package com.omarea.scene_mode

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.omarea.Scene
import com.omarea.common.shared.FileWrite
import com.omarea.common.shell.KeepShellPublic
import com.omarea.library.shell.PropsUtils
import com.omarea.store.CpuConfigStorage
import com.omarea.store.SpfConfig
import com.omarea.vtools.R

/**
 * Created by Hello on 2018/06/03.
 * Modernized to coordinate mutually-exclusive Uperf-R and hardened FAS-RS scheduling cleanly.
 */
open class ModeSwitcher {
    companion object {
        const val SOURCE_UNKNOWN = "UNKNOWN"
        const val SOURCE_SCENE_ACTIVE = "SOURCE_SCENE_ACTIVE"
        const val SOURCE_SCENE_CONSERVATIVE = "SOURCE_SCENE_CONSERVATIVE"
        const val SOURCE_SCENE_CUSTOM = "SOURCE_SCENE_CUSTOM"
        const val SOURCE_SCENE_IMPORT = "SOURCE_SCENE_IMPORT"
        const val SOURCE_SCENE_ONLINE = "SOURCE_SCENE_ONLINE"
        const val SOURCE_OUTSIDE = "SOURCE_OUTSIDE"
        const val SOURCE_UPERF = "SOURCE_UPERF"
        const val SOURCE_FAS_RS = "SOURCE_FAS_RS"
        const val SOURCE_NONE = "SOURCE_NONE"

        const val PROVIDER_INSIDE = "PROVIDER_INSIDE"
        const val PROVIDER_OUTSIDE = "PROVIDER_OUTSIDE"
        const val PROVIDER_UPERF = "PROVIDER_UPERF"
        const val PROVIDER_FAS_RS = "PROVIDER_FAS_RS"
        const val PROVIDER_NONE = "PROVIDER_NONE"

        private var inited = false
        var lastInitProvider = PROVIDER_NONE
        private var configProvider: String = ""

        fun getCurrentSource(): String {
            if (FasRsManager.isFasRsActive()) {
                return SOURCE_FAS_RS
            }
            if (UperfManager.isUperfActive()) {
                return SOURCE_UPERF
            }
            if (CpuConfigInstaller().outsideConfigInstalled() && !UperfManager.isUperfPowerCfgScript()) {
                return SOURCE_OUTSIDE
            }
            val config = Scene.context
                .getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
                .getString(SpfConfig.GLOBAL_SPF_PROFILE_SOURCE, SOURCE_UNKNOWN)
            if (config == SOURCE_SCENE_CUSTOM || CpuConfigInstaller().insideConfigInstalled()) {
                return config!!
            }
            return SOURCE_NONE
        }

        fun getCurrentSourceName(): String {
            return when (getCurrentSource()) {
                SOURCE_FAS_RS -> "FAS-RS"
                SOURCE_UPERF -> "Uperf-R"
                SOURCE_OUTSIDE -> "外部来源"
                SOURCE_SCENE_CONSERVATIVE -> "Scene-经典"
                SOURCE_SCENE_ACTIVE -> "Scene-性能"
                SOURCE_SCENE_CUSTOM -> "自定义"
                SOURCE_SCENE_IMPORT -> "文件导入"
                SOURCE_SCENE_ONLINE -> "在线下载"
                SOURCE_NONE -> "未定义"
                else -> "未知"
            }
        }

        private var innerConfigUpdated = false

        const val OUTSIDE_POWER_CFG_PATH = "/data/powercfg.sh"
        const val OUTSIDE_POWER_CFG_BASE = "/data/powercfg-base.sh"

        internal var POWERSAVE = "powersave"
        internal var PERFORMANCE = "performance"
        internal var FAST = "fast"
        internal var BALANCE = "balance"
        internal var IGONED = "igoned"
        internal var DEFAULT = BALANCE
        private var INIT = "init"

        private val VALID_MODES = setOf(POWERSAVE, BALANCE, PERFORMANCE, FAST, INIT, "auto", "pedestal")

        internal fun getModName(mode: String): String {
            return when (mode) {
                POWERSAVE -> "省电模式"
                PERFORMANCE -> "性能模式"
                FAST -> "极速模式"
                BALANCE -> "均衡模式"
                IGONED -> "保持状态"
                "" -> "全局默认"
                else -> "未知模式"
            }
        }

        private var currentPowercfg: String = ""
        private var currentPowercfgApp: String = ""

        fun getCurrentPowerMode(): String {
            if (FasRsManager.isFasRsActive()) {
                val fasMode = FasRsManager.getCurrentMode()
                if (fasMode.isNotEmpty()) {
                    return fasMode
                }
            }
            if (UperfManager.isUperfActive()) {
                val uperfMode = UperfManager.getCurrentMode()
                if (uperfMode.isNotEmpty()) {
                    return uperfMode
                }
            }
            if (currentPowercfg.isNotEmpty()) {
                return currentPowercfg
            }
            return PropsUtils.getProp("vtools.powercfg")
        }

        fun getCurrentPowermodeApp(): String {
            if (currentPowercfgApp.isNotEmpty()) {
                return currentPowercfgApp
            }
            return PropsUtils.getProp("vtools.powercfg_app")
        }
    }

    internal fun getModIcon(mode: String): Int {
        return when (mode) {
            POWERSAVE -> R.drawable.p1
            BALANCE -> R.drawable.p2
            PERFORMANCE -> R.drawable.p3
            FAST -> R.drawable.p4
            else -> R.drawable.p3
        }
    }

    internal fun getModImage(mode: String): Int {
        return when (mode) {
            POWERSAVE -> R.drawable.shortcut_p1
            BALANCE -> R.drawable.shortcut_p2
            PERFORMANCE -> R.drawable.shortcut_p3
            FAST -> R.drawable.shortcut_p4
            else -> R.drawable.shortcut_p3
        }
    }

    internal fun setCurrent(powerCfg: String, app: String): ModeSwitcher {
        setCurrentPowercfg(powerCfg)
        setCurrentPowercfgApp(app)
        return this
    }

    internal fun setCurrentPowercfg(powerCfg: String): ModeSwitcher {
        currentPowercfg = powerCfg
        PropsUtils.setPorp("vtools.powercfg", powerCfg)
        return this
    }

    internal fun setCurrentPowercfgApp(app: String): ModeSwitcher {
        currentPowercfgApp = app
        PropsUtils.setPorp("vtools.powercfg_app", app)
        return this
    }

    private fun keepShellExec(cmd: String): Boolean {
        val out = KeepShellPublic.secondaryKeepShell.doCmdSync("($cmd) && echo __SCENE_SH_OK__")
        return out != "error" && out.trim().lines().lastOrNull() == "__SCENE_SH_OK__"
    }

    internal fun initPowerCfg(): ModeSwitcher {
        if (FasRsManager.isFasRsActive()) {
            lastInitProvider = PROVIDER_FAS_RS
            configProvider = FasRsManager.MODE_NODE
            inited = true
            return this
        }
        if (UperfManager.isUperfActive()) {
            lastInitProvider = PROVIDER_UPERF
            configProvider = OUTSIDE_POWER_CFG_PATH
            inited = true
            return this
        }

        val installer = CpuConfigInstaller()
        if (installer.outsideConfigInstalled() && !UperfManager.isUperfPowerCfgScript()) {
            if (!installer.verifyOutsideConfigSafe()) {
                Log.e("Scene", "Refusing unsafe outside powercfg script (must be root-owned non-symlink non-group/world-writable)")
                configProvider = ""
                inited = false
                return this
            }
            configProvider = OUTSIDE_POWER_CFG_PATH
            lastInitProvider = PROVIDER_OUTSIDE
        } else {
            if (!innerConfigUpdated) {
                installer.applyConfigNewVersion(Scene.context)
                innerConfigUpdated = true
            }
            lastInitProvider = PROVIDER_INSIDE
            configProvider = FileWrite.getPrivateFilePath(Scene.context, "powercfg.sh")
        }

        if (configProvider.isNotEmpty()) {
            if (keepShellExec("sh '$configProvider' $INIT > /dev/null 2>&1")) {
                setCurrentPowercfg("")
                inited = true
            }
        }
        return this
    }

    private fun executeMode(mode: String, packageName: String): ModeSwitcher {
        if (mode == IGONED || mode !in VALID_MODES) {
            return this
        }

        val safePkg = UperfManager.sanitizePackageName(packageName)
        when (getCurrentSource()) {
            SOURCE_FAS_RS -> {
                if (FasRsManager.switchMode(mode)) {
                    setCurrentPowercfg(mode)
                } else {
                    Log.e("Scene", "FAS-RS mode switch failed for $mode")
                }
            }
            SOURCE_UPERF -> {
                if (UperfManager.switchMode(mode)) {
                    setCurrentPowercfg(mode)
                } else {
                    Log.e("Scene", "Uperf mode switch failed for $mode")
                }
            }
            SOURCE_SCENE_CUSTOM -> {
                val cpuConfigStorage = CpuConfigStorage(Scene.context)
                if (cpuConfigStorage.exists(mode)) {
                    cpuConfigStorage.applyCpuConfig(Scene.context, mode)
                    setCurrentPowercfg(mode)
                } else {
                    Log.e("Scene", "${mode}Profile lost!")
                }
            }
            SOURCE_OUTSIDE -> {
                if (!inited || lastInitProvider != PROVIDER_OUTSIDE) {
                    initPowerCfg()
                }
                if (configProvider.isNotEmpty() && CpuConfigInstaller().verifyOutsideConfigSafe()) {
                    val dynamic = Scene.getBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL, SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL_DEFAULT)
                    val strictMode = Scene.getBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL_STRICT, false)
                    val ok = if (dynamic && strictMode && safePkg.isNotEmpty()) {
                        keepShellExec("export top_app='$safePkg'\nsh '$configProvider' '$mode' > /dev/null 2>&1")
                    } else {
                        keepShellExec("export top_app=''\nsh '$configProvider' '$mode' > /dev/null 2>&1")
                    }
                    if (ok) {
                        setCurrentPowercfg(mode)
                    } else {
                        Log.e("Scene", "Outside powercfg execution failed for $mode")
                    }
                } else {
                    Log.e("Scene", "${mode}Profile lost!")
                }
            }
            else -> {
                if (!inited || lastInitProvider != PROVIDER_INSIDE) {
                    initPowerCfg()
                }
                if (configProvider.isNotEmpty()) {
                    val dynamic = Scene.getBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL, SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL_DEFAULT)
                    val strictMode = Scene.getBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL_STRICT, false)
                    val ok = if (dynamic && strictMode && safePkg.isNotEmpty()) {
                        val currentTime = SystemClock.elapsedRealtime()
                        keepShellExec("export top_app='$safePkg'\nsh '$configProvider' '$mode' 'task$currentTime' > /dev/null 2>&1")
                    } else {
                        keepShellExec("export top_app=''\nsh '$configProvider' '$mode' > /dev/null 2>&1")
                    }
                    if (ok) {
                        setCurrentPowercfg(mode)
                    } else {
                        Log.e("Scene", "Powercfg execution failed for $mode")
                    }
                } else {
                    Log.e("Scene", "${mode}Profile lost!")
                }
            }
        }
        return this
    }

    internal fun executePowercfgMode(mode: String, app: String): ModeSwitcher {
        if (app != Scene.thisPackageName) {
            executeMode(mode, app)
            setCurrentPowercfgApp(app)
        } else {
            executeMode(mode, "")
            setCurrentPowercfgApp("")
        }
        return this
    }

    fun modeReplaced(mode: String): Boolean {
        return CpuConfigStorage(Scene.context).exists(mode)
    }

    fun modeConfigCompleted(): Boolean {
        if (FasRsManager.isFasRsActive() || UperfManager.isUperfActive() ||
            (CpuConfigInstaller().outsideConfigInstalled() && !UperfManager.isUperfPowerCfgScript())
        ) {
            return true
        }
        return when (getCurrentSource()) {
            SOURCE_SCENE_CUSTOM -> allModeReplaced()
            SOURCE_SCENE_ACTIVE,
            SOURCE_SCENE_CONSERVATIVE,
            SOURCE_SCENE_IMPORT,
            SOURCE_SCENE_ONLINE -> CpuConfigInstaller().insideConfigInstalled()
            else -> false
        }
    }

    fun allModeReplaced(): Boolean {
        val storage = CpuConfigStorage(Scene.context)
        return storage.exists(POWERSAVE) &&
            storage.exists(BALANCE) &&
            storage.exists(PERFORMANCE) &&
            storage.exists(FAST)
    }

    fun clearInitedState() {
        inited = false
    }
}
