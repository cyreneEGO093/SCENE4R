package com.omarea.library.shell

import android.content.Context
import android.content.SharedPreferences
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.RootFile
import com.omarea.store.SpfConfig
import java.io.File
import java.io.FileOutputStream

/*
# 配置示例

# 是否启用swap
swap=true
# swap大小(MB)，部分设备超过2047会开启失败
swap_size=1536
# swap使用顺序（0:与zram同时使用，-1:用完zram后再使用，5:优先于zram使用）
swap_priority=0
# 是否挂载为回环设备(如非必要，不建议开启)
swap_use_loop=false

# 是否启用zram
zram=true
# zram大小(MB)，部分设备超过2047会开启失败
zram_size=1536
# zram压缩算法(可设置的值取决于内核支持)
comp_algorithm=lzo

# 使用zram、swap的积极性
swappiness=100
# extra_free_kbytes(kbytes)
extra_free_kbytes=98304
*/

/**
 * Created by Hello on 2017/11/01.
 */

class SwapModuleUtils {
    companion object {
        const val BUNDLED_MODULE_ASSET = "swap/scene-swap-controller.zip"
        const val MODULE_DIR = "/data/adb/modules/scene_swap_controller"
        const val MODULE_UPDATE_DIR = "/data/adb/modules_update/scene_swap_controller"
        private const val CONFIG_FILE = "/data/swap_config.conf"
    }

    private var installed: Boolean? = null

    // 是否已经安装用于处理自启动的magisk模块
    val magiskModuleInstalled: Boolean
        get () {
            if (installed == null) {
                installed = RootFile.fileExists(CONFIG_FILE) && PropsUtils.getProp("vtools.swap.controller") == "magisk"
            }

            return installed!!
        }

    fun isRootModuleManagerAvailable(): Boolean {
        val out = KeepShellPublic.doCmdSync(
            "if command -v ksud >/dev/null 2>&1 || command -v apd >/dev/null 2>&1 || command -v magisk >/dev/null 2>&1; then echo HAS_MANAGER; fi"
        ).trim()
        return out == "HAS_MANAGER"
    }

    private val swapEnable = "swap"
    private val swapSize = "swap_size"
    private val swapPriority = "swap_priority"
    private val swapUseLoop = "swap_use_loop"

    private val zramEnable = "zram"
    private val zramSize = "zram_size"
    private val zramCompAlgorithm = "comp_algorithm"

    private val swappiness = "swappiness"
    private val extraFreeKbytes = "extra_free_kbytes"
    private val watermarkScaleFactor = "watermark_scale_factor"

    private fun getProp(prop: String): String {
        return KeepShellPublic.doCmdSync("cat /data/swap_config.conf | grep -v '^#' | grep \"^${prop}=\" | cut -f2 -d '='")
    }

    private fun getProp(config: List<String>, prop: String): String {
        val result = config.find { it.startsWith("$prop=") }
        if (result != null) {
            return result.subSequence(prop.length + 1, result.length).toString()
        }
        return ""
    }

    private fun setProp(prop: String, value: Any) {
        val cmd = """
            if [ -f "$CONFIG_FILE" ]; then
                if grep -q '^$prop=' "$CONFIG_FILE" 2>/dev/null; then
                    if command -v busybox >/dev/null 2>&1; then
                        busybox sed -i 's|^$prop=.*|$prop=$value|' "$CONFIG_FILE"
                    else
                        sed -i 's|^$prop=.*|$prop=$value|' "$CONFIG_FILE"
                    fi
                else
                    printf '%s=%s\n' '$prop' '$value' >> "$CONFIG_FILE"
                fi
            fi
        """.trimIndent()
        KeepShellPublic.doCmdSync(cmd)
    }

    // 保存模块配置
    fun saveModuleConfig(spf: SharedPreferences) {
        setProp(swapEnable, spf.getBoolean(SpfConfig.SWAP_SPF_SWAP, false))
        setProp(swapSize, spf.getInt(SpfConfig.SWAP_SPF_SWAP_SWAPSIZE, 0))
        setProp(swapPriority, spf.getInt(SpfConfig.SWAP_SPF_SWAP_PRIORITY, 0))
        setProp(swapUseLoop, spf.getBoolean(SpfConfig.SWAP_SPF_SWAP_USE_LOOP, false))

        setProp(zramEnable, spf.getBoolean(SpfConfig.SWAP_SPF_ZRAM, false))
        setProp(zramSize, spf.getInt(SpfConfig.SWAP_SPF_ZRAM_SIZE, 0))
        setProp(zramCompAlgorithm, "" + spf.getString(SpfConfig.SWAP_SPF_ALGORITHM, "lzo"))

        setProp(swappiness, spf.getInt(SpfConfig.SWAP_SPF_SWAPPINESS, 65))
        setProp(extraFreeKbytes, spf.getInt(SpfConfig.SWAP_SPF_EXTRA_FREE_KBYTES, 29615))
        setProp(watermarkScaleFactor, spf.getInt(SpfConfig.SWAP_SPF_WATERMARK_SCALE, 100))
    }

    // 加载模块配置
    fun loadModuleConfig(spf: SharedPreferences) {
        // 如果模块没有安装，就不要再去读取配置了
        if (!magiskModuleInstalled) {
            return
        }

        val editor = spf.edit()
        val savedConfig = KeepShellPublic.doCmdSync("cat /data/swap_config.conf").split("\n")

        try {
            editor.putBoolean(SpfConfig.SWAP_SPF_SWAP, getProp(savedConfig, swapEnable) == "true")
            editor.putInt(SpfConfig.SWAP_SPF_SWAP_SWAPSIZE, getProp(savedConfig, swapSize).toInt())
            editor.putInt(SpfConfig.SWAP_SPF_SWAP_PRIORITY, getProp(savedConfig, swapPriority).toInt())
            editor.putBoolean(SpfConfig.SWAP_SPF_SWAP_USE_LOOP, getProp(savedConfig, swapUseLoop) == "true")
        } catch (ex: Exception) {
        }

        try {
            editor.putBoolean(SpfConfig.SWAP_SPF_ZRAM, getProp(savedConfig, zramEnable) == "true")
            editor.putInt(SpfConfig.SWAP_SPF_ZRAM_SIZE, getProp(savedConfig, zramSize).toInt())
            editor.putString(SpfConfig.SWAP_SPF_ALGORITHM, getProp(savedConfig, zramCompAlgorithm))
        } catch (ex: Exception) {
        }

        try {
            editor.putInt(SpfConfig.SWAP_SPF_SWAPPINESS, getProp(savedConfig, swappiness).toInt())
            editor.putInt(SpfConfig.SWAP_SPF_EXTRA_FREE_KBYTES, getProp(savedConfig, extraFreeKbytes).toInt())
        } catch (ex: Exception) {
        }
        try {
            editor.putInt(SpfConfig.SWAP_SPF_WATERMARK_SCALE, getProp(savedConfig, watermarkScaleFactor).toInt())
        } catch (ex: Exception) {
        }

        editor.apply()
    }

    // 当前运行中的模块版本
    fun getModuleVersion(): Int {
        val version = PropsUtils.getProp("vtools.swap.module").trim()
        if (version == "error" || version.isEmpty()) {
            return 0
        }
        return version.toIntOrNull() ?: 0
    }

    // 磁盘上已安装或待重启更新的 module.prop 版本
    fun getInstalledModulePropVersion(): Int {
        val cmd = """
            prop_file=""
            if [ -f "$MODULE_UPDATE_DIR/module.prop" ]; then
                prop_file="$MODULE_UPDATE_DIR/module.prop"
            elif [ -f "$MODULE_DIR/module.prop" ] && [ ! -f "$MODULE_DIR/disable" ] && [ ! -f "$MODULE_DIR/remove" ]; then
                prop_file="$MODULE_DIR/module.prop"
            fi
            if [ -n "${'$'}prop_file" ]; then
                grep '^versionCode=' "${'$'}prop_file" 2>/dev/null | head -n 1 | cut -d'=' -f2 | tr -d '\r '
            fi
        """.trimIndent()
        val out = KeepShellPublic.doCmdSync(cmd).trim()
        return if (out == "error" || out.isEmpty()) 0 else (out.toIntOrNull() ?: 0)
    }

    fun isPendingRebootUpgrade(targetVersion: Int): Boolean {
        return getInstalledModulePropVersion() >= targetVersion && getModuleVersion() < targetVersion
    }

    /**
     * Extracts the bundled offline scene-swap-controller.zip module into app-private files and
     * installs it via Magisk / KernelSU / APatch.
     */
    fun installBundledModule(context: Context): Pair<Boolean, String> {
        val outFile = File(context.filesDir, "scene-swap-controller.zip")
        try {
            context.assets.open(BUNDLED_MODULE_ASSET).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (ex: Exception) {
            return Pair(false, "释放内置 SWAP 控制器模块失败: ${ex.message}")
        }

        val result = try {
            val escapedPath = outFile.absolutePath.replace("'", "'\\''")
            val cmd = """
                ZIP='$escapedPath'
                if command -v ksud >/dev/null 2>&1; then
                    ksud module install "${'$'}ZIP" 2>&1
                    echo "INSTALL_RC=${'$'}?"
                elif command -v apd >/dev/null 2>&1; then
                    apd module install "${'$'}ZIP" 2>&1
                    echo "INSTALL_RC=${'$'}?"
                elif command -v magisk >/dev/null 2>&1; then
                    magisk --install-module "${'$'}ZIP" 2>&1
                    echo "INSTALL_RC=${'$'}?"
                else
                    echo "NO_ROOT_MANAGER"
                    echo "INSTALL_RC=2"
                fi
            """.trimIndent()
            KeepShellPublic.doCmdSync(cmd)
        } finally {
            outFile.delete()
        }

        installed = null
        val rcLine = result.lines().lastOrNull { it.startsWith("INSTALL_RC=") }
        val rc = rcLine?.substringAfter("INSTALL_RC=")?.toIntOrNull() ?: -1
        if (result == "error" || rc != 0) {
            return Pair(false, "模块安装失败 (exit=$rc)，请在 Magisk / KernelSU / APatch 中重试。\n$result")
        }
        return Pair(true, "已离线安装 SCENE4R 内置 SWAP 控制器模块（v4.0.0）。请重启设备使新版模块生效。\n$result")
    }
}