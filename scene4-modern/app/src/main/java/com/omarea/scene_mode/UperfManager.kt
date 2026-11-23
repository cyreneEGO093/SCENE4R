package com.omarea.scene_mode

import android.content.Context
import android.util.Base64
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.RootFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.math.BigDecimal

/**
 * Coordinates SCENE4R with the hardened Uperf-R Magisk/KernelSU/APatch module.
 * All active Uperf state/config files reside under Root-exclusive /data/adb/uperf (0700).
 */
object UperfManager {
    const val RUNTIME_DIR = "/data/adb/uperf"
    const val POWERMODE_NODE = "/data/adb/uperf/cur_powermode.txt"
    const val PERAPP_FILE = "/data/adb/uperf/perapp_powermode.txt"
    const val ACTIVE_CONFIG = "/data/adb/uperf/uperf.json"
    const val ORIG_CONFIG_BACKUP = "/data/adb/uperf/uperf.json.orig"
    const val MODULE_DIR = "/data/adb/modules/uperf"
    const val BUNDLED_MODULE_ASSET = "uperf/uperf-r.zip"

    // Note: "auto" is bin/uperf's built-in internal perapp_powermode.txt switcher;
    // "pedestal" maps to "crazy" in powercfg_main.sh.
    private val ALLOWED_MODES = setOf("powersave", "balance", "performance", "fast", "auto", "pedestal")
    private val SAFE_PKG_REGEX = Regex("^[a-zA-Z0-9._:]+$")

    fun isModulePresent(): Boolean {
        return RootFile.fileExists("$MODULE_DIR/module.prop") &&
            !RootFile.fileExists("$MODULE_DIR/remove")
    }

    fun isModuleInstalled(): Boolean {
        return isModulePresent() && !RootFile.fileExists("$MODULE_DIR/disable")
    }

    fun isDaemonRunning(): Boolean {
        val out = KeepShellPublic.doCmdSync(
            "expected=\$(readlink -f '$MODULE_DIR/bin/uperf' 2>/dev/null); " +
                "for p in \$(pidof uperf 2>/dev/null || pgrep -x uperf 2>/dev/null); do " +
                "if [ -n \"\$expected\" ] && [ \"\$(readlink -f \"/proc/\$p/exe\" 2>/dev/null)\" = \"\$expected\" ]; then echo UPERF_ALIVE; break; fi; done"
        ).trim()
        return out == "UPERF_ALIVE"
    }

    fun isReady(): Boolean {
        return isModuleInstalled() &&
            isDaemonRunning() &&
            RootFile.fileExists(ACTIVE_CONFIG) &&
            RootFile.fileExists(POWERMODE_NODE)
    }

    fun isUperfActive(): Boolean {
        return isModuleInstalled() && RootFile.fileExists(ACTIVE_CONFIG)
    }

    fun isUperfPowerCfgScript(): Boolean {
        val out = KeepShellPublic.doCmdSync(
            "if [ -f /data/powercfg.sh ] && grep -q '/data/adb/modules/uperf/' /data/powercfg.sh 2>/dev/null; then echo UPERF_BRIDGE; fi"
        ).trim()
        return out == "UPERF_BRIDGE"
    }

    fun getCurrentMode(): String {
        if (!RootFile.fileExists(POWERMODE_NODE)) {
            return ""
        }
        val mode = KeepShellPublic.doCmdSync("cat '$POWERMODE_NODE' 2>/dev/null").trim()
        return if (mode in ALLOWED_MODES || mode == "crazy") mode else ""
    }

    fun switchMode(mode: String): Boolean {
        if (mode !in ALLOWED_MODES || !isReady()) {
            return false
        }
        val targetVal = if (mode == "pedestal") "crazy" else mode
        val cmd = """
            if [ -d "$RUNTIME_DIR" ] && [ ! -L "$RUNTIME_DIR" ] && [ ! -L "$POWERMODE_NODE" ]; then
                chown 0:0 "$RUNTIME_DIR" && chmod 0700 "$RUNTIME_DIR" && \
                printf '%s\n' "$targetVal" > "$POWERMODE_NODE" && \
                chown 0:0 "$POWERMODE_NODE" && chmod 0600 "$POWERMODE_NODE" && \
                echo "UPERF_SWITCH_OK"
            fi
        """.trimIndent()
        return KeepShellPublic.doCmdSync(cmd).trim().lines().lastOrNull() == "UPERF_SWITCH_OK"
    }

    fun stopDaemonOnly(): Boolean {
        val cmd = """
            real_bin=${'$'}(readlink -f '$MODULE_DIR/bin/uperf' 2>/dev/null)
            for p in ${'$'}(pidof uperf 2>/dev/null || pgrep -x uperf 2>/dev/null); do
                exe=${'$'}(readlink -f "/proc/${'$'}p/exe" 2>/dev/null)
                if [ -n "${'$'}real_bin" ] && { [ "${'$'}exe" = "${'$'}real_bin" ] || [ "${'$'}exe" = "${'$'}real_bin (deleted)" ]; }; then
                    kill -TERM "${'$'}p" 2>/dev/null
                elif [ -z "${'$'}real_bin" ] && { [ "${'$'}{exe##*/}" = "uperf" ] || [ "${'$'}{exe##*/}" = "uperf (deleted)" ]; }; then
                    kill -TERM "${'$'}p" 2>/dev/null
                fi
            done
            i=0
            while [ "${'$'}i" -lt 10 ]; do
                alive=0
                for p in ${'$'}(pidof uperf 2>/dev/null || pgrep -x uperf 2>/dev/null); do
                    exe=${'$'}(readlink -f "/proc/${'$'}p/exe" 2>/dev/null)
                    if [ -z "${'$'}real_bin" ] || [ "${'$'}exe" = "${'$'}real_bin" ] || [ "${'$'}exe" = "${'$'}real_bin (deleted)" ]; then
                        alive=1
                        break
                    fi
                done
                [ "${'$'}alive" -eq 0 ] && break
                sleep 0.2 2>/dev/null || sleep 1
                i=${'$'}((i + 1))
            done
            for p in ${'$'}(pidof uperf 2>/dev/null || pgrep -x uperf 2>/dev/null); do
                exe=${'$'}(readlink -f "/proc/${'$'}p/exe" 2>/dev/null)
                if [ -n "${'$'}real_bin" ] && { [ "${'$'}exe" = "${'$'}real_bin" ] || [ "${'$'}exe" = "${'$'}real_bin (deleted)" ]; }; then
                    kill -KILL "${'$'}p" 2>/dev/null
                fi
            done
            for policy in /sys/devices/system/cpu/cpufreq/policy*; do
                [ -d "${'$'}policy" ] || continue
                if [ -r "${'$'}policy/cpuinfo_min_freq" ] && [ -e "${'$'}policy/scaling_min_freq" ]; then
                    chmod 0644 "${'$'}policy/scaling_min_freq" 2>/dev/null
                    cat "${'$'}policy/cpuinfo_min_freq" >"${'$'}policy/scaling_min_freq" 2>/dev/null
                fi
                if [ -r "${'$'}policy/cpuinfo_max_freq" ] && [ -e "${'$'}policy/scaling_max_freq" ]; then
                    chmod 0644 "${'$'}policy/scaling_max_freq" 2>/dev/null
                    cat "${'$'}policy/cpuinfo_max_freq" >"${'$'}policy/scaling_max_freq" 2>/dev/null
                fi
            done
            echo "UPERF_STOP_OK"
        """.trimIndent()
        return KeepShellPublic.doCmdSync(cmd).trim().lines().lastOrNull() == "UPERF_STOP_OK" && !isDaemonRunning()
    }

    fun stopDaemonAndDisable(): Boolean {
        if (!stopDaemonOnly()) {
            return false
        }
        val out = KeepShellPublic.doCmdSync(
            "if [ -d '$MODULE_DIR' ] && [ ! -L '$MODULE_DIR' ]; then " +
                "touch '$MODULE_DIR/disable' && chown 0:0 '$MODULE_DIR/disable' && chmod 0644 '$MODULE_DIR/disable'; fi; " +
                "echo DISABLE_OK"
        )
        return out.trim().lines().lastOrNull() == "DISABLE_OK"
    }

    fun enableAndStartDaemon(): Pair<Boolean, String> {
        if (!isModulePresent()) {
            return Pair(false, "未安装 Uperf-R 模块，请先点击刷入。")
        }

        // Strict mutual exclusion: always stop & disable FAS-RS first
        if (FasRsManager.isModulePresent() || FasRsManager.isDaemonRunning()) {
            if (!FasRsManager.stopDaemonAndDisable()) {
                return Pair(false, "无法停止正在运行的 FAS-RS 守护进程，已中止切换以避免冲突。")
            }
        }

        stopDaemonOnly()
        val out = KeepShellPublic.doCmdSync(
            """
                rm -f "$MODULE_DIR/disable" "$MODULE_DIR/remove" 2>/dev/null
                sh "$MODULE_DIR/script/initsvc.sh" 2>&1
                echo "INIT_RC=${'$'}?"
            """.trimIndent()
        )
        val rcLine = out.lines().lastOrNull { it.startsWith("INIT_RC=") }
        val rc = rcLine?.substringAfter("INIT_RC=")?.toIntOrNull() ?: -1

        if (rc == 0 && isReady()) {
            val mode = getCurrentMode().ifEmpty { "balance" }
            return Pair(true, "已切换到 Uperf-R 并立即启动（当前模式：$mode，进程身份与配置边界已验证）。")
        }
        return Pair(false, "Uperf-R 启动未就绪 (rc=$rc)。\n$out")
    }

    fun loadConfigJson(): JSONObject? {
        val raw = KeepShellPublic.doCmdSync(
            "if [ -f '$ACTIVE_CONFIG' ] && [ ! -L '$ACTIVE_CONFIG' ]; then cat '$ACTIVE_CONFIG' 2>/dev/null; fi"
        )
        if (raw == "error" || raw.isBlank()) return null
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            null
        }
    }

    fun saveTunedConfig(updatedRoot: JSONObject, restartNow: Boolean = true): Pair<Boolean, String> {
        if (!RootFile.fileExists(ACTIVE_CONFIG)) {
            return Pair(false, "未找到 /data/adb/uperf/uperf.json")
        }
        val currentRoot = loadConfigJson() ?: return Pair(false, "读取当前 uperf.json 失败，无法校验信任边界。")

        // Enforce immutable trust-boundary fields (switchInode, perapp, sysfs.knob)
        val curModules = currentRoot.optJSONObject("modules")
        val updModules = updatedRoot.optJSONObject("modules")
        val curSw = curModules?.optJSONObject("switcher")
        val updSw = updModules?.optJSONObject("switcher")
        val curSysfs = curModules?.optJSONObject("sysfs")?.optJSONObject("knob")
        val updSysfs = updModules?.optJSONObject("sysfs")?.optJSONObject("knob")

        if (curSw?.optString("switchInode") != updSw?.optString("switchInode") ||
            curSw?.optString("perapp") != updSw?.optString("perapp") ||
            !jsonObjectsEqual(curSysfs, updSysfs)
        ) {
            return Pair(false, "安全校验失败：switchInode / perapp / sysfs.knob 受信任边界锁定，不允许修改。")
        }

        val jsonText = try {
            formatCanonicalJson(updatedRoot) + "\n"
        } catch (ex: Exception) {
            return Pair(false, "JSON 序列化失败：${ex.message}")
        }

        val b64 = Base64.encodeToString(jsonText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val cmd = """
            umask 077
            [ -L "$RUNTIME_DIR" ] && exit 1
            [ -L "$ACTIVE_CONFIG" ] && exit 1
            bb="$MODULE_DIR/bin/busybox/busybox"
            awk_script="$MODULE_DIR/script/validate_config.awk"
            [ -x "${'$'}bb" ] && [ -f "${'$'}awk_script" ] || { echo "ERR_VALIDATOR_MISSING"; exit 1; }
            if [ ! -f "$ORIG_CONFIG_BACKUP" ] && [ -f "$ACTIVE_CONFIG" ]; then
                cp -f "$ACTIVE_CONFIG" "$ORIG_CONFIG_BACKUP" && chown 0:0 "$ORIG_CONFIG_BACKUP" && chmod 0600 "$ORIG_CONFIG_BACKUP"
            fi
            tmp="$RUNTIME_DIR/.uperf_edit.${'$'}${'$'}"
            rm -f "${'$'}tmp"
            printf '%s' '$b64' | base64 -d >"${'$'}tmp" || { rm -f "${'$'}tmp"; echo "ERR_WRITE"; exit 1; }
            chown 0:0 "${'$'}tmp" && chmod 0600 "${'$'}tmp" || { rm -f "${'$'}tmp"; exit 1; }
            val_out=${'$'}("${'$'}bb" awk -f "${'$'}awk_script" "${'$'}tmp" 2>&1)
            val_rc=${'$'}?
            if [ "${'$'}val_rc" -ne 0 ]; then
                echo "${'$'}val_out"
                rm -f "${'$'}tmp"
                exit 1
            fi
            mv -f "${'$'}tmp" "$ACTIVE_CONFIG" && chown 0:0 "$ACTIVE_CONFIG" && chmod 0600 "$ACTIVE_CONFIG" && echo "UPERF_CFG_SAVED"
        """.trimIndent()

        val saveOut = KeepShellPublic.doCmdSync(cmd)
        if (saveOut == "error" || saveOut.trim().lines().lastOrNull() != "UPERF_CFG_SAVED") {
            return Pair(false, "uperf.json 未通过校验：\n$saveOut")
        }

        if (restartNow && isModuleInstalled()) {
            val (restarted, msg) = enableAndStartDaemon()
            return if (restarted) {
                Pair(true, "已通过 validate_config.awk 安全校验并写入 0600，Uperf-R 守护进程已重启生效。")
            } else {
                Pair(false, "配置已写入但重启 Uperf-R 失败：\n$msg")
            }
        }
        return Pair(true, "已通过 validate_config.awk 安全校验并写入 /data/adb/uperf/uperf.json (0600)。")
    }

    fun resetConfigToDefault(restartNow: Boolean = true): Pair<Boolean, String> {
        val cmd = """
            umask 077
            bb="$MODULE_DIR/bin/busybox/busybox"
            awk_script="$MODULE_DIR/script/validate_config.awk"
            if [ ! -f "$ORIG_CONFIG_BACKUP" ] || [ -L "$ORIG_CONFIG_BACKUP" ]; then
                echo "NO_ORIG_BACKUP"
                exit 1
            fi
            tmp="$RUNTIME_DIR/.uperf_reset.${'$'}${'$'}"
            cp -f "$ORIG_CONFIG_BACKUP" "${'$'}tmp" && chown 0:0 "${'$'}tmp" && chmod 0600 "${'$'}tmp" || { rm -f "${'$'}tmp"; exit 1; }
            if ! "${'$'}bb" awk -f "${'$'}awk_script" "${'$'}tmp" 2>&1; then
                rm -f "${'$'}tmp"
                exit 1
            fi
            mv -f "${'$'}tmp" "$ACTIVE_CONFIG" && chown 0:0 "$ACTIVE_CONFIG" && chmod 0600 "$ACTIVE_CONFIG" && echo "UPERF_CFG_RESET"
        """.trimIndent()
        val out = KeepShellPublic.doCmdSync(cmd)
        if (out.contains("NO_ORIG_BACKUP")) {
            return Pair(false, "当前已是原始默认配置（尚无修改备份）。")
        }
        if (out == "error" || out.trim().lines().lastOrNull() != "UPERF_CFG_RESET") {
            return Pair(false, "恢复默认配置失败：\n$out")
        }
        if (restartNow && isModuleInstalled()) {
            val (ok, msg) = enableAndStartDaemon()
            return if (ok) Pair(true, "已恢复 Uperf-R 默认配置并重启守护进程生效。") else Pair(false, msg)
        }
        return Pair(true, "已恢复 Uperf-R 默认配置。")
    }

    private fun jsonObjectsEqual(a: JSONObject?, b: JSONObject?): Boolean {
        if (a == null && b == null) return true
        if (a == null || b == null || a.length() != b.length()) return false
        val keys = a.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (!b.has(k) || a.opt(k)?.toString() != b.opt(k)?.toString()) return false
        }
        return true
    }

    /**
     * Strict UTF-8 JSON serializer matching validate_config.awk:
     * no \uXXXX escapes, no '/' escaping, finite plain-decimal numbers, depth <= 16.
     */
    private fun formatCanonicalJson(value: Any?, indent: Int = 0): String {
        if (indent > 32) throw IllegalArgumentException("JSON nesting too deep")
        val pad = "  ".repeat(indent)
        val innerPad = "  ".repeat(indent + 1)
        return when (value) {
            null, JSONObject.NULL -> "null"
            is Boolean -> value.toString()
            is Int, is Long -> value.toString()
            is Number -> {
                val d = value.toDouble()
                if (!d.isFinite() || kotlin.math.abs(d) > 1e10) {
                    throw IllegalArgumentException("Out-of-range numeric value: $value")
                }
                val s = BigDecimal.valueOf(d).stripTrailingZeros().toPlainString()
                if (s.length > 24) String.format(java.util.Locale.US, "%.4f", d) else s
            }
            is String -> escapeJsonString(value)
            is JSONObject -> {
                if (value.length() == 0) return "{}"
                val sb = StringBuilder("{\n")
                val keys = value.keys()
                var first = true
                while (keys.hasNext()) {
                    val k = keys.next()
                    if ('/' in k) throw IllegalArgumentException("Disallowed '/' in JSON key: $k")
                    if (!first) sb.append(",\n")
                    first = false
                    sb.append(innerPad).append(escapeJsonString(k)).append(": ")
                        .append(formatCanonicalJson(value.get(k), indent + 1))
                }
                sb.append('\n').append(pad).append('}')
                sb.toString()
            }
            is JSONArray -> {
                if (value.length() == 0) return "[]"
                val sb = StringBuilder("[\n")
                for (i in 0 until value.length()) {
                    if (i > 0) sb.append(",\n")
                    sb.append(innerPad).append(formatCanonicalJson(value.get(i), indent + 1))
                }
                sb.append('\n').append(pad).append(']')
                sb.toString()
            }
            else -> throw IllegalArgumentException("Unsupported JSON value: ${value::class.java}")
        }
    }

    private fun escapeJsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 32) {
                        throw IllegalArgumentException("Disallowed control char U+${ch.code}")
                    }
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    fun sanitizePackageName(pkg: String?): String {
        if (pkg.isNullOrEmpty()) return ""
        return if (SAFE_PKG_REGEX.matches(pkg)) pkg else ""
    }

    fun getDetectedSocSummary(): String {
        val platform = KeepShellPublic.doCmdSync("getprop ro.board.platform").trim()
        val socModel = KeepShellPublic.doCmdSync("getprop ro.soc.model").trim()
        return when {
            socModel.startsWith("SM8650", ignoreCase = true) || platform == "pineapple" ->
                "骁龙 8 Gen 3 ($platform / $socModel)"
            platform.isNotEmpty() || socModel.isNotEmpty() ->
                "$platform / $socModel".trim(' ', '/')
            else -> "未知平台"
        }
    }

    /**
     * Extracts the bundled hardened Uperf module ZIP into app-private files and installs it
     * via the active root manager (Magisk, KernelSU, or APatch).
     */
    fun installBundledModule(context: Context): Pair<Boolean, String> {
        val outFile = File(context.filesDir, "uperf-r.zip")
        try {
            context.assets.open(BUNDLED_MODULE_ASSET).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (ex: Exception) {
            return Pair(false, "释放内置 Uperf-R 模块失败: ${ex.message}")
        }

        val fasWasActive = FasRsManager.isModuleEnabled() || FasRsManager.isDaemonRunning()
        val result = try {
            val escapedPath = outFile.absolutePath.replace("'", "'\\''")
            val cmd = """
                ZIP='$escapedPath'
                if command -v ksud >/dev/null 2>&1; then
                    ksud module install "${'$'}ZIP" 2>&1
                    rc=${'$'}?
                elif command -v apd >/dev/null 2>&1; then
                    apd module install "${'$'}ZIP" 2>&1
                    rc=${'$'}?
                elif command -v magisk >/dev/null 2>&1; then
                    magisk --install-module "${'$'}ZIP" 2>&1
                    rc=${'$'}?
                else
                    echo "NO_ROOT_MANAGER"
                    rc=2
                fi
                if [ "${'$'}rc" -eq 0 ] && [ "${if (fasWasActive) "1" else "0"}" = "1" ] && [ -d "$MODULE_DIR" ]; then
                    touch "$MODULE_DIR/disable" && chown 0:0 "$MODULE_DIR/disable" && chmod 0644 "$MODULE_DIR/disable"
                fi
                echo "INSTALL_RC=${'$'}rc"
            """.trimIndent()
            KeepShellPublic.doCmdSync(cmd)
        } finally {
            outFile.delete()
        }

        val rcLine = result.lines().lastOrNull { it.startsWith("INSTALL_RC=") }
        val rc = rcLine?.substringAfter("INSTALL_RC=")?.toIntOrNull() ?: -1
        if (result == "error" || rc != 0) {
            return Pair(false, "模块安装失败 (exit=$rc)，请在 Magisk / KernelSU / APatch 中手动刷入。\n$result")
        }
        return Pair(true, "已安装 Uperf-R（保留原厂温控）。请重启设备使调度守护进程生效。\n$result")
    }
}
