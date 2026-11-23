package com.omarea.scene_mode

import android.content.Context
import android.util.Base64
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.RootFile
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

data class FasModeParams(
    var marginFpsBase: Double = 1.0,
    val marginOverrides: LinkedHashMap<String, Double> = linkedMapOf(),
    var coreTempThreshMilliC: Long? = 90000L
)

data class FasRsConfig(
    var keepStd: Boolean = false,
    var sceneGameList: Boolean = false,
    val modes: LinkedHashMap<String, FasModeParams> = linkedMapOf(),
    val gameList: LinkedHashMap<String, String> = linkedMapOf()
)

/**
 * Coordinates SCENE4R with the hardened FAS-RS Magisk/KernelSU/APatch module (GPL-3.0).
 * All runtime state, mode node, mount masks, logs, and config reside strictly under Root-only /data/adb/fas-rs (0700).
 */
object FasRsManager {
    const val RUNTIME_DIR = "/data/adb/fas-rs"
    const val NODE_DIR = "/data/adb/fas-rs/node"
    const val MODE_NODE = "/data/adb/fas-rs/node/mode"
    const val GAMES_CONFIG = "/data/adb/fas-rs/games.toml"
    const val LOG_FILE = "/data/adb/fas-rs/fas_log.txt"
    const val MODULE_DIR = "/data/adb/modules/fas_rs"
    const val MODULE_UPDATE_DIR = "/data/adb/modules_update/fas_rs"
    const val BUNDLED_MODULE_ASSET = "fas-rs/fas-rs-r.zip"

    val MODE_ORDER = listOf("powersave", "balance", "performance", "fast")
    private val ALLOWED_MODES = MODE_ORDER.toSet()
    private val SAFE_PKG_REGEX = Regex("^[a-zA-Z0-9._:]{1,192}$")

    fun isModulePresent(): Boolean {
        return (RootFile.fileExists("$MODULE_DIR/module.prop") && !RootFile.fileExists("$MODULE_DIR/remove")) ||
            RootFile.fileExists("$MODULE_UPDATE_DIR/module.prop")
    }

    fun isModuleInstalled(): Boolean {
        return RootFile.fileExists("$MODULE_DIR/module.prop") &&
            !RootFile.fileExists("$MODULE_DIR/disable") &&
            !RootFile.fileExists("$MODULE_DIR/remove")
    }

    fun isModuleEnabled(): Boolean = isModuleInstalled()

    fun isDaemonRunning(): Boolean {
        val out = KeepShellPublic.doCmdSync(
            "expected=\$(readlink -f '$MODULE_DIR/bin/fas-rs' 2>/dev/null); " +
                "for p in \$(pidof fas-rs 2>/dev/null || pgrep -x fas-rs 2>/dev/null); do " +
                "if [ -n \"\$expected\" ] && [ \"\$(readlink -f \"/proc/\$p/exe\" 2>/dev/null)\" = \"\$expected\" ]; then echo FAS_RS_ALIVE; break; fi; done"
        ).trim()
        return out == "FAS_RS_ALIVE"
    }

    fun isReady(): Boolean {
        return isModuleEnabled() &&
            isDaemonRunning() &&
            RootFile.fileExists(GAMES_CONFIG) &&
            RootFile.fileExists(MODE_NODE)
    }

    fun isFasRsActive(): Boolean {
        return isModuleEnabled() && (isDaemonRunning() || RootFile.fileExists(GAMES_CONFIG))
    }

    fun isKernelEbpfSupported(): Boolean {
        val out = KeepShellPublic.doCmdSync(
            "if [ -d /sys/fs/bpf ] && [ \"\$(uname -m)\" = 'aarch64' ]; then echo EBPF_OK; fi"
        ).trim()
        return out == "EBPF_OK"
    }

    fun getCurrentMode(): String {
        if (!RootFile.fileExists(MODE_NODE)) {
            return ""
        }
        val mode = KeepShellPublic.doCmdSync("cat '$MODE_NODE' 2>/dev/null").trim()
        return if (mode in ALLOWED_MODES) mode else ""
    }

    fun switchMode(mode: String): Boolean {
        if (mode !in ALLOWED_MODES || !isModuleEnabled() || !isDaemonRunning()) {
            return false
        }
        val cmd = """
            if [ -d "$RUNTIME_DIR" ] && [ ! -L "$RUNTIME_DIR" ] && [ -d "$NODE_DIR" ] && [ ! -L "$NODE_DIR" ] && [ ! -L "$MODE_NODE" ]; then
                chown 0:0 "$RUNTIME_DIR" "$NODE_DIR" && chmod 0700 "$RUNTIME_DIR" "$NODE_DIR" && \
                tmp="$NODE_DIR/.mode_tmp.${'$'}${'$'}" && \
                printf '%s\n' "$mode" > "${'$'}tmp" && \
                chown 0:0 "${'$'}tmp" && chmod 0600 "${'$'}tmp" && \
                mv -f "${'$'}tmp" "$MODE_NODE" && \
                echo "FAS_SWITCH_OK"
            fi
        """.trimIndent()
        return KeepShellPublic.doCmdSync(cmd).trim().lines().lastOrNull() == "FAS_SWITCH_OK"
    }

    fun stopDaemonAndDisable(): Boolean {
        val cmd = """
            real_bin=${'$'}(readlink -f '$MODULE_DIR/bin/fas-rs' 2>/dev/null)
            for p in ${'$'}(pidof fas-rs 2>/dev/null || pgrep -x fas-rs 2>/dev/null); do
                exe=${'$'}(readlink -f "/proc/${'$'}p/exe" 2>/dev/null)
                if [ -n "${'$'}real_bin" ] && { [ "${'$'}exe" = "${'$'}real_bin" ] || [ "${'$'}exe" = "${'$'}real_bin (deleted)" ]; }; then
                    kill -TERM "${'$'}p" 2>/dev/null
                elif [ -z "${'$'}real_bin" ] && { [ "${'$'}{exe##*/}" = "fas-rs" ] || [ "${'$'}{exe##*/}" = "fas-rs (deleted)" ]; }; then
                    kill -TERM "${'$'}p" 2>/dev/null
                fi
            done
            i=0
            while [ "${'$'}i" -lt 12 ]; do
                alive=0
                for p in ${'$'}(pidof fas-rs 2>/dev/null || pgrep -x fas-rs 2>/dev/null); do
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
            for p in ${'$'}(pidof fas-rs 2>/dev/null || pgrep -x fas-rs 2>/dev/null); do
                exe=${'$'}(readlink -f "/proc/${'$'}p/exe" 2>/dev/null)
                if [ -n "${'$'}real_bin" ] && { [ "${'$'}exe" = "${'$'}real_bin" ] || [ "${'$'}exe" = "${'$'}real_bin (deleted)" ]; }; then
                    kill -KILL "${'$'}p" 2>/dev/null
                fi
            done
            for target in \
                /sys/module/mtk_fpsgo/parameters/perfmgr_enable \
                /sys/module/perfmgr/parameters/perfmgr_enable \
                /sys/module/perfmgr_policy/parameters/perfmgr_enable \
                /sys/module/perfmgr_mtk/parameters/perfmgr_enable \
                /sys/module/migt/parameters/glk_fbreak_enable \
                /sys/module/migt/parameters/glk_disable \
                /proc/game_opt/disable_cpufreq_limit; do
                if grep -q " ${'$'}target " /proc/mounts 2>/dev/null; then
                    umount -l "${'$'}target" 2>/dev/null || umount "${'$'}target" 2>/dev/null
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
            if [ -d '$MODULE_DIR' ] && [ ! -L '$MODULE_DIR' ]; then
                rm -f '$MODULE_DIR/remove' 2>/dev/null
                touch '$MODULE_DIR/disable' && chown 0:0 '$MODULE_DIR/disable' && chmod 0644 '$MODULE_DIR/disable'
            fi
            echo "FAS_STOP_OK"
        """.trimIndent()
        return KeepShellPublic.doCmdSync(cmd).trim().lines().lastOrNull() == "FAS_STOP_OK" && !isDaemonRunning()
    }

    fun enableAndStartDaemon(): Pair<Boolean, String> {
        if (!isModulePresent()) {
            return Pair(false, "未安装 FAS-RS 模块，请先点击刷入。")
        }

        // Strict mutual exclusion: always stop & disable Uperf-R first
        if (UperfManager.isModulePresent() || UperfManager.isDaemonRunning()) {
            if (!UperfManager.stopDaemonAndDisable()) {
                return Pair(false, "无法停止正在运行的 Uperf-R 守护进程，已中止切换以避免冲突。")
            }
        }

        val promoteAndStartCmd = """
            if [ -f "$MODULE_UPDATE_DIR/bin/fas-rs" ] && [ ! -e "$MODULE_UPDATE_DIR/system" ] && [ ! -L "/data/adb/modules" ]; then
                mkdir -p "$MODULE_DIR" && \
                cp -af "$MODULE_UPDATE_DIR/." "$MODULE_DIR/" && \
                rm -rf "$MODULE_DIR/update" "$MODULE_UPDATE_DIR" && \
                chown -R 0:0 "$MODULE_DIR" && \
                chmod 0755 "$MODULE_DIR/bin/fas-rs" "$MODULE_DIR/service.sh" "$MODULE_DIR/uninstall.sh" 2>/dev/null
            fi
            if [ ! -f "$MODULE_DIR/bin/fas-rs" ]; then
                echo "NO_FAS_RS_BIN"
                exit 1
            fi
            rm -f "$MODULE_DIR/disable" "$MODULE_DIR/remove" 2>/dev/null
            sh "$MODULE_DIR/service.sh"
            rc=${'$'}?
            echo "START_RC=${'$'}rc"
        """.trimIndent()
        val out = KeepShellPublic.doCmdSync(promoteAndStartCmd)
        val rcLine = out.lines().lastOrNull { it.startsWith("START_RC=") }
        val rc = rcLine?.substringAfter("START_RC=")?.toIntOrNull() ?: -1

        if (rc == 0 && isReady()) {
            val mode = getCurrentMode().ifEmpty { "balance" }
            return Pair(true, "已切换到 FAS-RS 并立即启动（当前模式：$mode，eBPF 与进程身份已验证）。")
        }

        val logTail = readRecentLogs(20)
        return Pair(false, "FAS-RS 启动未就绪 (rc=$rc)。\n$out\n\n最近日志：\n$logTail")
    }

    /**
     * Extracts bundled fas-rs-r.zip and installs it via Magisk / KernelSU / APatch.
     * Pure daemon module (no /system overlays) is safely promoted to /data/adb/modules/fas_rs so users
     * can switch live without rebooting; kept disabled initially if Uperf-R is active.
     */
    fun installBundledModule(context: Context): Pair<Boolean, String> {
        val outFile = File(context.filesDir, "fas-rs-r.zip")
        try {
            context.assets.open(BUNDLED_MODULE_ASSET).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (ex: Exception) {
            return Pair(false, "释放内置 FAS-RS 模块失败: ${ex.message}")
        }

        val uperfWasEnabled = UperfManager.isModuleInstalled() || UperfManager.isDaemonRunning()
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
                if [ "${'$'}rc" -eq 0 ] && [ -f "$MODULE_UPDATE_DIR/bin/fas-rs" ] && [ ! -e "$MODULE_UPDATE_DIR/system" ] && [ ! -L "/data/adb/modules" ]; then
                    mkdir -p "$MODULE_DIR" && \
                    cp -af "$MODULE_UPDATE_DIR/." "$MODULE_DIR/" && \
                    rm -rf "$MODULE_DIR/update" "$MODULE_UPDATE_DIR" && \
                    chown -R 0:0 "$MODULE_DIR" && \
                    chmod 0755 "$MODULE_DIR/bin/fas-rs" "$MODULE_DIR/service.sh" "$MODULE_DIR/uninstall.sh" 2>/dev/null
                fi
                if [ "${'$'}rc" -eq 0 ] && [ "${if (uperfWasEnabled) "1" else "0"}" = "1" ] && [ -d "$MODULE_DIR" ]; then
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
        return Pair(true, "已安装 FAS-RS（保留原厂温控，Lua 扩展已禁用，运行目录 /data/adb/fas-rs）。\n$result")
    }

    fun sanitizePackageName(pkg: String?): String {
        if (pkg.isNullOrEmpty()) return ""
        return if (SAFE_PKG_REGEX.matches(pkg)) pkg else ""
    }

    fun isValidFpsSpec(spec: String): Boolean {
        val t = unquoteTomlKey(spec.trim())
        if (t == "auto") return true
        t.toIntOrNull()?.let { return it in 10..240 }
        if (t.startsWith("[") && t.endsWith("]")) {
            val inner = t.substring(1, t.length - 1).trim()
            if (inner.isEmpty()) return false
            return inner.split(",").all { part ->
                val v = part.trim().toIntOrNull()
                v != null && v in 10..240
            }
        }
        return false
    }

    fun readRecentLogs(maxLines: Int = 80): String {
        if (!RootFile.fileExists(LOG_FILE)) {
            return "(暂无运行日志: $LOG_FILE)"
        }
        val out = KeepShellPublic.doCmdSync("tail -n $maxLines '$LOG_FILE' 2>/dev/null")
        return if (out == "error" || out.isBlank()) "(日志为空)" else out.trimEnd()
    }

    fun loadConfig(): FasRsConfig? {
        val raw = KeepShellPublic.doCmdSync(
            "if [ -f '$GAMES_CONFIG' ] && [ ! -L '$GAMES_CONFIG' ]; then cat '$GAMES_CONFIG' 2>/dev/null; " +
                "elif [ -f '$MODULE_DIR/games.toml' ] && [ ! -L '$MODULE_DIR/games.toml' ]; then cat '$MODULE_DIR/games.toml' 2>/dev/null; fi"
        )
        if (raw == "error" || raw.isBlank()) return null
        return parseTomlConfig(raw)
    }

    fun parseTomlConfig(raw: String): FasRsConfig {
        val config = FasRsConfig()
        MODE_ORDER.forEach { m -> config.modes[m] = defaultModeParams(m) }

        var section = ""
        for (rawLine in raw.lines()) {
            val stripped = stripTomlComment(rawLine).trim()
            if (stripped.isEmpty()) continue
            if (stripped.startsWith("[") && stripped.endsWith("]")) {
                section = stripped.substring(1, stripped.length - 1).trim()
                continue
            }
            val eqIdx = findTopLevelEquals(stripped) ?: continue
            val key = unquoteTomlKey(stripped.substring(0, eqIdx).trim())
            val valStr = stripped.substring(eqIdx + 1).trim()

            when (section) {
                "config" -> when (key) {
                    "keep_std" -> config.keepStd = (valStr == "true")
                    "scene_game_list" -> config.sceneGameList = (valStr == "true")
                }
                "game_list" -> {
                    val safePkg = sanitizePackageName(key)
                    if (safePkg.isNotEmpty() && valStr.isNotEmpty()) {
                        val normVal = if (unquoteTomlKey(valStr) == "auto") "auto" else valStr
                        config.gameList[safePkg] = normVal
                    }
                }
                in MODE_ORDER -> {
                    val modeParams = config.modes.getOrPut(section) { defaultModeParams(section) }
                    when (key) {
                        "margin_fps" -> parseMarginValueInto(valStr, modeParams)
                        "core_temp_thresh" -> {
                            modeParams.coreTempThreshMilliC = if (unquoteTomlKey(valStr) == "disabled") {
                                null
                            } else {
                                valStr.toLongOrNull() ?: modeParams.coreTempThreshMilliC
                            }
                        }
                    }
                }
                else -> {
                    val dot = section.indexOf('.')
                    if (dot > 0) {
                        val modeName = section.substring(0, dot).trim()
                        val subName = section.substring(dot + 1).trim()
                        if (modeName in MODE_ORDER && subName == "margin_fps") {
                            val modeParams = config.modes.getOrPut(modeName) { defaultModeParams(modeName) }
                            val num = valStr.toDoubleOrNull()
                            if (num != null && num.isFinite() && num >= 0.0) {
                                if (key == "base") {
                                    modeParams.marginFpsBase = num
                                } else {
                                    modeParams.marginOverrides[key] = num
                                }
                            }
                        }
                    }
                }
            }
        }
        return config
    }

    private fun defaultModeParams(mode: String): FasModeParams = when (mode) {
        "powersave" -> FasModeParams(3.0, linkedMapOf(), 80000L)
        "balance" -> FasModeParams(1.0, linkedMapOf(), 90000L)
        "performance" -> FasModeParams(0.3, linkedMapOf(), 95000L)
        else -> FasModeParams(0.0, linkedMapOf(), 95000L)
    }

    private fun stripTomlComment(line: String): String {
        var inQuotes = false
        var escape = false
        for (i in line.indices) {
            val ch = line[i]
            if (escape) {
                escape = false
                continue
            }
            if (ch == '\\' && inQuotes) {
                escape = true
                continue
            }
            if (ch == '"') {
                inQuotes = !inQuotes
            } else if (ch == '#' && !inQuotes) {
                return line.substring(0, i)
            }
        }
        return line
    }

    private fun findTopLevelEquals(line: String): Int? {
        var inQuotes = false
        for (i in line.indices) {
            val ch = line[i]
            if (ch == '"') inQuotes = !inQuotes
            else if (ch == '=' && !inQuotes) return i
        }
        return null
    }

    private fun unquoteTomlKey(raw: String): String {
        return if (raw.length >= 2 && ((raw.startsWith("\"") && raw.endsWith("\"")) || (raw.startsWith("'") && raw.endsWith("'")))) {
            raw.substring(1, raw.length - 1)
        } else {
            raw
        }
    }

    private fun parseMarginValueInto(valStr: String, target: FasModeParams) {
        val scalar = valStr.toDoubleOrNull()
        if (scalar != null && scalar.isFinite() && scalar >= 0.0) {
            target.marginFpsBase = scalar
            target.marginOverrides.clear()
            return
        }
        if (valStr.startsWith("{") && valStr.endsWith("}")) {
            val inner = valStr.substring(1, valStr.length - 1)
            target.marginOverrides.clear()
            for (part in inner.split(",")) {
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                val k = unquoteTomlKey(part.substring(0, eq).trim())
                val v = part.substring(eq + 1).trim().toDoubleOrNull() ?: continue
                if (!v.isFinite() || v < 0.0) continue
                if (k == "base") target.marginFpsBase = v else target.marginOverrides[k] = v
            }
        }
    }

    private fun formatTomlFloat(v: Double): String {
        val safe = v.coerceIn(0.0, 60.0)
        val raw = String.format(Locale.US, "%.2f", safe)
        return if (raw.endsWith("0") && !raw.endsWith(".0")) raw.dropLast(1) else raw
    }

    fun serializeToml(config: FasRsConfig): String {
        val sb = StringBuilder()
        sb.append("[config]\n")
        sb.append("keep_std = ${config.keepStd}\n")
        sb.append("scene_game_list = ${config.sceneGameList}\n\n")

        sb.append("[game_list]\n")
        for ((pkg, spec) in config.gameList) {
            val safePkg = sanitizePackageName(pkg)
            if (safePkg.isEmpty() || !isValidFpsSpec(spec)) continue
            val cleanSpec = unquoteTomlKey(spec.trim())
            val normSpec = if (cleanSpec == "auto") "\"auto\"" else cleanSpec
            sb.append("\"$safePkg\" = $normSpec\n")
        }
        sb.append('\n')

        for (mode in MODE_ORDER) {
            val p = config.modes[mode] ?: defaultModeParams(mode)
            sb.append("[$mode]\n")
            if (p.marginOverrides.isEmpty()) {
                sb.append("margin_fps = ${formatTomlFloat(p.marginFpsBase)}\n")
            } else {
                val ovParts = p.marginOverrides.entries.joinToString(", ") { (k, v) ->
                    "\"$k\" = ${formatTomlFloat(v)}"
                }
                sb.append("margin_fps = { base = ${formatTomlFloat(p.marginFpsBase)}, $ovParts }\n")
            }
            val tempStr = p.coreTempThreshMilliC?.coerceIn(30000L, 120000L)?.toString() ?: "\"disabled\""
            sb.append("core_temp_thresh = $tempStr\n\n")
        }
        return sb.toString()
    }

    fun saveConfig(config: FasRsConfig): Pair<Boolean, String> {
        if (!isModulePresent()) {
            return Pair(false, "未安装 FAS-RS 模块")
        }
        val tomlText = serializeToml(config)
        val b64 = Base64.encodeToString(tomlText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val cmd = """
            umask 077
            [ -L "$RUNTIME_DIR" ] && { echo "ERR_SYMLINK_DIR"; exit 1; }
            mkdir -p "$RUNTIME_DIR" && chown 0:0 "$RUNTIME_DIR" && chmod 0700 "$RUNTIME_DIR" || exit 1
            [ -L "$GAMES_CONFIG" ] && { echo "ERR_SYMLINK_CFG"; exit 1; }
            bin="$MODULE_DIR/bin/fas-rs"
            [ -x "${'$'}bin" ] && [ ! -L "${'$'}bin" ] || { echo "ERR_MISSING_BIN"; exit 1; }
            tmp="$RUNTIME_DIR/.games_edit.${'$'}${'$'}"
            rm -f "${'$'}tmp"
            printf '%s' '$b64' | base64 -d >"${'$'}tmp" || { rm -f "${'$'}tmp"; echo "ERR_WRITE"; exit 1; }
            chown 0:0 "${'$'}tmp" && chmod 0600 "${'$'}tmp" || { rm -f "${'$'}tmp"; exit 1; }
            val_out=${'$'}("${'$'}bin" validate "${'$'}tmp" 2>&1)
            val_rc=${'$'}?
            if [ "${'$'}val_rc" -ne 0 ]; then
                echo "${'$'}val_out"
                rm -f "${'$'}tmp"
                exit 1
            fi
            mv -f "${'$'}tmp" "$GAMES_CONFIG" && chown 0:0 "$GAMES_CONFIG" && chmod 0600 "$GAMES_CONFIG" && echo "FAS_CFG_SAVED"
        """.trimIndent()
        val out = KeepShellPublic.doCmdSync(cmd)
        val ok = out != "error" && out.trim().lines().lastOrNull() == "FAS_CFG_SAVED"
        if (!ok) {
            return Pair(false, "校验或保存 FAS-RS 配置失败：\n$out")
        }
        val tail = if (isDaemonRunning()) "（运行中的 FAS-RS 将通过 inotify 自动热重载）" else "（将在下次启动 FAS-RS 时生效）"
        return Pair(true, "已验证并保存 /data/adb/fas-rs/games.toml (0600)$tail")
    }

    fun resetConfigToDefault(): Pair<Boolean, String> {
        val cmd = """
            umask 077
            std="$MODULE_DIR/games.toml"
            bin="$MODULE_DIR/bin/fas-rs"
            [ -f "${'$'}std" ] && [ ! -L "${'$'}std" ] && [ -x "${'$'}bin" ] || { echo "ERR_MISSING_STD"; exit 1; }
            tmp="$RUNTIME_DIR/.games_reset.${'$'}${'$'}"
            cp -f "${'$'}std" "${'$'}tmp" && chown 0:0 "${'$'}tmp" && chmod 0600 "${'$'}tmp" || { rm -f "${'$'}tmp"; exit 1; }
            if ! "${'$'}bin" validate "${'$'}tmp" 2>&1; then
                rm -f "${'$'}tmp"
                exit 1
            fi
            mv -f "${'$'}tmp" "$GAMES_CONFIG" && chown 0:0 "$GAMES_CONFIG" && chmod 0600 "$GAMES_CONFIG" && echo "FAS_CFG_RESET"
        """.trimIndent()
        val out = KeepShellPublic.doCmdSync(cmd)
        return if (out != "error" && out.trim().lines().lastOrNull() == "FAS_CFG_RESET") {
            Pair(true, "已恢复 FAS-RS 默认参数配置")
        } else {
            Pair(false, "恢复默认配置失败：\n$out")
        }
    }
}
