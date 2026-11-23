package com.omarea.permissions

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.omarea.common.shared.FileWrite
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.ShellExecutor
import com.omarea.common.ui.DialogHelper
import com.omarea.vtools.R
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 检查并安装Busybox
 * Created by helloklf on 2017/6/3.
 */
class Busybox(private var context: Context) {
    companion object {
        private const val SHA256_BUSYBOX32 = "29b8083206a76bde2314a84d16694263c678d6baf69422e707e70b756e34b93d"
        private const val SHA256_BUSYBOX64 = "f03b1cda5b19da390d96befc1e26661bcf6570fd940ff55537ff113825b38dc9"
        private const val PROBE_TIMEOUT_MS = 5000L
        private const val ROOT_CMD_TIMEOUT_MS = 8000L
        private val SKIP_APPLETS = setOf("sh", "busybox", "shell", "swapon", "swapoff", "mkswap")
        private val INSTALL_LOCK = Any()

        private fun sha256Hex(file: File): String {
            return try {
                val md = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        md.update(buf, 0, n)
                    }
                }
                md.digest().joinToString("") { "%02x".format(it) }
            } catch (_: Exception) {
                ""
            }
        }

        private fun destroyProcess(proc: java.lang.Process) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    proc.destroyForcibly()
                } else {
                    proc.destroy()
                }
            } catch (_: Exception) {
            }
        }

        private fun runCommandBounded(cmd: Array<String>, timeoutMs: Long = PROBE_TIMEOUT_MS): Pair<Int, List<String>>? {
            return try {
                val proc = ProcessBuilder(*cmd)
                    .redirectErrorStream(true)
                    .start()
                val linesRef = AtomicReference<List<String>>(emptyList())
                val readerLatch = CountDownLatch(1)
                val readerThread = Thread {
                    try {
                        linesRef.set(proc.inputStream.bufferedReader().use { it.readLines() })
                    } catch (_: Exception) {
                    } finally {
                        readerLatch.countDown()
                    }
                }
                readerThread.isDaemon = true
                readerThread.start()

                val finished = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                } else {
                    val waitLatch = CountDownLatch(1)
                    val waitThread = Thread {
                        try {
                            proc.waitFor()
                        } catch (_: InterruptedException) {
                        } finally {
                            waitLatch.countDown()
                        }
                    }
                    waitThread.isDaemon = true
                    waitThread.start()
                    waitLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
                }

                if (!finished) {
                    destroyProcess(proc)
                    readerThread.interrupt()
                    return null
                }
                readerLatch.await(1000L, TimeUnit.MILLISECONDS)
                Pair(proc.exitValue(), linesRef.get())
            } catch (_: Exception) {
                null
            }
        }

        /** Requires [binPath] basename to be `busybox` so BusyBox enters multiplexer `--list` mode. */
        private fun queryBusyboxApplets(binPath: String): List<String>? {
            val res = runCommandBounded(arrayOf(binPath, "--list")) ?: return null
            if (res.first != 0) {
                return null
            }
            val applets = res.second.map { it.trim() }
                .filter { it.isNotEmpty() && !it.contains('/') && !it.contains('\u0000') && it != "." && it != ".." }
            return if (applets.isEmpty()) null else applets
        }

        private fun runRootCmdBounded(cmd: String, timeoutMs: Long = ROOT_CMD_TIMEOUT_MS): String? {
            var proc: java.lang.Process? = null
            return try {
                proc = ProcessBuilder("su")
                    .redirectErrorStream(true)
                    .start()
                val process = proc
                val outputRef = AtomicReference<String?>(null)
                val done = CountDownLatch(1)
                val writerThread = Thread {
                    try {
                        process.outputStream.use { out ->
                            out.write(cmd.toByteArray(Charsets.UTF_8))
                            out.write("\nexit \$?\n".toByteArray(Charsets.UTF_8))
                            out.flush()
                        }
                    } catch (_: Exception) {
                    }
                }
                val readerThread = Thread {
                    try {
                        val text = process.inputStream.bufferedReader().use { it.readText() }
                        process.waitFor()
                        outputRef.set(text)
                    } catch (_: Exception) {
                    } finally {
                        done.countDown()
                    }
                }
                writerThread.isDaemon = true
                readerThread.isDaemon = true
                writerThread.start()
                readerThread.start()

                val finished = done.await(timeoutMs, TimeUnit.MILLISECONDS)
                if (!finished) {
                    destroyProcess(process)
                    writerThread.interrupt()
                    readerThread.interrupt()
                    null
                } else {
                    outputRef.get()
                }
            } catch (_: Exception) {
                proc?.let { destroyProcess(it) }
                null
            }
        }

        private fun deleteTreeNoFollow(entry: File) {
            try {
                val st = Os.lstat(entry.absolutePath)
                if (!OsConstants.S_ISLNK(st.st_mode) && OsConstants.S_ISDIR(st.st_mode)) {
                    entry.listFiles()?.forEach { child ->
                        deleteTreeNoFollow(child)
                    }
                }
                try {
                    Os.remove(entry.absolutePath)
                } catch (_: Exception) {
                    entry.delete()
                }
            } catch (_: ErrnoException) {
                // Path does not exist.
            }
        }

        private fun ensureCleanDirectory(dir: File): Boolean {
            return try {
                try {
                    val st = Os.lstat(dir.absolutePath)
                    if (OsConstants.S_ISLNK(st.st_mode) || !OsConstants.S_ISDIR(st.st_mode)) {
                        Os.remove(dir.absolutePath)
                    }
                } catch (_: ErrnoException) {
                    // Directory does not exist yet.
                }
                if (!dir.exists() && !dir.mkdirs()) {
                    return false
                }
                val st = Os.lstat(dir.absolutePath)
                !OsConstants.S_ISLNK(st.st_mode) && OsConstants.S_ISDIR(st.st_mode)
            } catch (_: Exception) {
                false
            }
        }

        private fun replaceSymlink(targetPath: String, linkFile: File): Boolean {
            return try {
                val parent = linkFile.parentFile ?: return false
                val tmp = File(parent, ".${linkFile.name}.tmp.${Process.myTid()}")
                deleteTreeNoFollow(tmp)
                Os.symlink(targetPath, tmp.absolutePath)
                try {
                    Os.rename(tmp.absolutePath, linkFile.absolutePath)
                } catch (e: ErrnoException) {
                    deleteTreeNoFollow(tmp)
                    throw e
                }
                true
            } catch (_: Exception) {
                false
            }
        }

        fun systemBusyboxInstalled(): Boolean {
            val candidates = listOf(
                "/sbin/busybox",
                "/system/xbin/busybox",
                "/system/sbin/busybox",
                "/system/bin/busybox",
                "/vendor/bin/busybox",
                "/vendor/xbin/busybox",
                "/odm/bin/busybox"
            )
            for (path in candidates) {
                if (File(path).canExecute()) {
                    val res = runCommandBounded(arrayOf(path, "--list"), 2000L)
                    if (res != null && res.first == 0) return true
                }
            }
            val shRes = runCommandBounded(
                arrayOf("sh", "-c", "command -v busybox >/dev/null 2>&1 && busybox --list >/dev/null 2>&1"),
                2000L
            )
            return shRes != null && shRes.first == 0
        }
    }

    private fun isArm64Device(): Boolean = Build.SUPPORTED_64_BIT_ABIS.any { it.contains("arm64") }

    private fun isArmDevice(): Boolean = Build.SUPPORTED_ABIS.any { it.lowercase(Locale.ROOT).contains("arm") }

    private fun busyboxAssetPath(): String {
        return if (isArm64Device()) "toolkit/busybox64" else "toolkit/busybox"
    }

    private fun expectedBusyboxSha256(): String {
        return if (isArm64Device()) SHA256_BUSYBOX64 else SHA256_BUSYBOX32
    }

    private fun nativeExpectedSha256(): String {
        return if (Process.is64Bit()) SHA256_BUSYBOX64 else SHA256_BUSYBOX32
    }

    private fun nativeBusyboxFile(): File {
        return File(context.applicationInfo.nativeLibraryDir, "libbusybox.so")
    }

    private fun isCleanToolkitSymlink(entry: File, expectedCanonical: String, expectedSha: String): Boolean {
        return try {
            val st = Os.lstat(entry.absolutePath)
            OsConstants.S_ISLNK(st.st_mode) &&
                entry.canonicalPath == expectedCanonical &&
                sha256Hex(entry) == expectedSha
        } catch (_: Exception) {
            false
        }
    }

    private fun installFromNativeLibrary(absInstallPath: String, nativeLib: File, expectedSha: String): Boolean {
        val nativeCanonical = try {
            nativeLib.canonicalPath
        } catch (_: Exception) {
            return false
        }
        val filesDir = context.filesDir
        val stageDir = File(filesDir, ".toolkit_stage")
        val oldDir = File(filesDir, ".toolkit_old")
        val toolkitDir = File(absInstallPath)

        deleteTreeNoFollow(stageDir)
        if (!ensureCleanDirectory(stageDir)) {
            return false
        }

        // Probe through a symlink literally named `busybox` so argv[0] triggers `--list`.
        val stageBusybox = File(stageDir, "busybox")
        if (!replaceSymlink(nativeLib.absolutePath, stageBusybox)) {
            deleteTreeNoFollow(stageDir)
            return false
        }
        val applets = queryBusyboxApplets(stageBusybox.absolutePath)
        if (applets == null || !applets.contains("md5sum")) {
            deleteTreeNoFollow(stageDir)
            return false
        }

        for (applet in applets) {
            if (applet in SKIP_APPLETS) continue
            val link = File(stageDir, applet)
            if (!replaceSymlink(nativeLib.absolutePath, link) ||
                !isCleanToolkitSymlink(link, nativeCanonical, expectedSha)
            ) {
                deleteTreeNoFollow(stageDir)
                return false
            }
        }
        val stageMarker = File(stageDir, "busybox_1_30_1")
        if (!replaceSymlink(nativeLib.absolutePath, stageMarker) ||
            !isCleanToolkitSymlink(stageMarker, nativeCanonical, expectedSha) ||
            !isCleanToolkitSymlink(stageBusybox, nativeCanonical, expectedSha)
        ) {
            deleteTreeNoFollow(stageDir)
            return false
        }

        deleteTreeNoFollow(oldDir)
        var movedOld = false
        try {
            val toolkitParent = toolkitDir.parentFile
            if (toolkitParent != null && !ensureCleanDirectory(toolkitParent)) {
                deleteTreeNoFollow(stageDir)
                return false
            }
            try {
                Os.lstat(toolkitDir.absolutePath)
                Os.rename(toolkitDir.absolutePath, oldDir.absolutePath)
                movedOld = true
            } catch (_: ErrnoException) {
                // Target toolkit directory does not exist yet.
            }
            try {
                Os.rename(stageDir.absolutePath, toolkitDir.absolutePath)
            } catch (e: ErrnoException) {
                if (movedOld) {
                    try {
                        Os.rename(oldDir.absolutePath, toolkitDir.absolutePath)
                    } catch (_: Exception) {
                    }
                }
                deleteTreeNoFollow(stageDir)
                return false
            }
        } catch (_: Exception) {
            deleteTreeNoFollow(stageDir)
            return false
        }

        val rootBusybox = File(FileWrite.getPrivateFilePath(context, "busybox"))
        val finalBusybox = File(toolkitDir, "busybox")
        val verified = replaceSymlink(nativeLib.absolutePath, rootBusybox) &&
            isCleanToolkitSymlink(rootBusybox, nativeCanonical, expectedSha) &&
            File(toolkitDir, "md5sum").exists() &&
            File(toolkitDir, "busybox_1_30_1").exists() &&
            isCleanToolkitSymlink(finalBusybox, nativeCanonical, expectedSha) &&
            queryBusyboxApplets(finalBusybox.absolutePath) != null &&
            queryBusyboxApplets(rootBusybox.absolutePath) != null

        if (!verified) {
            deleteTreeNoFollow(toolkitDir)
            if (movedOld) {
                try {
                    Os.rename(oldDir.absolutePath, toolkitDir.absolutePath)
                } catch (_: Exception) {
                }
            }
            return false
        }
        if (movedOld) {
            deleteTreeNoFollow(oldDir)
        }
        return true
    }

    private fun isRegularFile(file: File): Boolean {
        return try {
            val st = Os.lstat(file.absolutePath)
            !OsConstants.S_ISLNK(st.st_mode) && OsConstants.S_ISREG(st.st_mode)
        } catch (_: Exception) {
            false
        }
    }

    private fun verifyNonNativeToolkit(
        toolkitDir: File,
        bin: File,
        rootBusybox: File,
        expectedSha: String
    ): Boolean {
        val marker = File(toolkitDir, "busybox_1_30_1")
        val md5 = File(toolkitDir, "md5sum")
        if (!(marker.exists() && md5.exists() && bin.exists() && rootBusybox.exists())) {
            return false
        }
        if (!isRegularFile(bin) ||
            !isRegularFile(rootBusybox) ||
            sha256Hex(bin) != expectedSha ||
            sha256Hex(rootBusybox) != expectedSha
        ) {
            return false
        }
        val binCanonical = try {
            bin.canonicalPath
        } catch (_: Exception) {
            return false
        }
        val entries = toolkitDir.listFiles() ?: return false
        for (entry in entries) {
            when (entry.name) {
                "busybox" -> if (!isRegularFile(entry) || sha256Hex(entry) != expectedSha) return false
                else -> if (!isCleanToolkitSymlink(entry, binCanonical, expectedSha)) return false
            }
        }
        return true
    }

    private fun privateBusyboxInstalled(): Boolean {
        val installPath = context.getString(R.string.toolkit_install_path)
        val absInstallPath = FileWrite.getPrivateFilePath(context, installPath)
        val toolkitDir = File(absInstallPath)
        try {
            val dirStat = Os.lstat(toolkitDir.absolutePath)
            if (OsConstants.S_ISLNK(dirStat.st_mode) || !OsConstants.S_ISDIR(dirStat.st_mode)) {
                return false
            }
        } catch (_: ErrnoException) {
            return false
        }

        val marker = File("$absInstallPath/busybox_1_30_1")
        val md5 = File("$absInstallPath/md5sum")
        val bin = File("$absInstallPath/busybox")
        val rootBusybox = File(FileWrite.getPrivateFilePath(context, "busybox"))
        if (!(marker.exists() && md5.exists() && bin.exists() && rootBusybox.exists())) {
            return false
        }

        val nativeLib = nativeBusyboxFile()
        val nativeExpected = nativeExpectedSha256()
        if (nativeLib.exists() && sha256Hex(nativeLib) == nativeExpected) {
            val nativeCanonical = try {
                nativeLib.canonicalPath
            } catch (_: Exception) {
                return false
            }
            if (!isCleanToolkitSymlink(bin, nativeCanonical, nativeExpected) ||
                !isCleanToolkitSymlink(rootBusybox, nativeCanonical, nativeExpected) ||
                !isCleanToolkitSymlink(md5, nativeCanonical, nativeExpected) ||
                !isCleanToolkitSymlink(marker, nativeCanonical, nativeExpected)
            ) {
                return false
            }
            // Enforce clean toolkit directory: every entry must symlink to verified libbusybox.so.
            val entries = toolkitDir.listFiles() ?: return false
            for (entry in entries) {
                if (!isCleanToolkitSymlink(entry, nativeCanonical, nativeExpected)) {
                    return false
                }
            }
            return queryBusyboxApplets(bin.absolutePath) != null
        }

        val expectedSha = expectedBusyboxSha256()
        if (!verifyNonNativeToolkit(toolkitDir, bin, rootBusybox, expectedSha)) {
            return false
        }
        if (queryBusyboxApplets(bin.absolutePath) != null) {
            return true
        }
        if (CheckRootStatus.lastCheckResult) {
            val check = runRootCmdBounded("'$absInstallPath/busybox' --list >/dev/null 2>&1; rc=\$?; echo \"BB_RC=\$rc\"")
            return check?.lines()?.lastOrNull()?.trim() == "BB_RC=0"
        }
        return false
    }

    private fun installPrivateBusybox(): Boolean = synchronized(INSTALL_LOCK) {
        if (privateBusyboxInstalled()) {
            return@synchronized true
        }
        if (!isArmDevice()) {
            return@synchronized false
        }
        val installPath = context.getString(R.string.toolkit_install_path)
        val absInstallPath = FileWrite.getPrivateFilePath(context, installPath)

        // Prefer nativeLibraryDir executable (apk_data_file 0755): works with or without root on Android 10-16.
        val nativeLib = nativeBusyboxFile()
        val nativeExpected = nativeExpectedSha256()
        if (nativeLib.exists() && sha256Hex(nativeLib) == nativeExpected) {
            if (installFromNativeLibrary(absInstallPath, nativeLib, nativeExpected)) {
                return@synchronized true
            }
        }

        // Fallback: extract verified static binary from assets into clean private toolkit dir.
        val expectedSha = expectedBusyboxSha256()
        val toolkitDir = File(absInstallPath)
        deleteTreeNoFollow(toolkitDir)
        if (!ensureCleanDirectory(toolkitDir)) {
            return@synchronized false
        }
        val rootBusyboxPath = FileWrite.getPrivateFilePath(context, "busybox")
        val rootBusyboxFile = File(rootBusyboxPath)
        deleteTreeNoFollow(rootBusyboxFile)
        if (FileWrite.writePrivateFile(context.assets, busyboxAssetPath(), "busybox", context) != rootBusyboxPath) {
            return@synchronized false
        }
        if (sha256Hex(rootBusyboxFile) != expectedSha) {
            return@synchronized false
        }

        val busyboxInstallPath = "$installPath/busybox"
        val privateBusybox = FileWrite.getPrivateFilePath(context, busyboxInstallPath)
        val binFile = File(privateBusybox)
        if (FileWrite.writePrivateFile(context.assets, busyboxAssetPath(), busyboxInstallPath, context) != privateBusybox) {
            return@synchronized false
        }
        if (sha256Hex(binFile) != expectedSha) {
            return@synchronized false
        }

        val directApplets = queryBusyboxApplets(privateBusybox)
        if (directApplets != null) {
            for (applet in directApplets) {
                if (applet in SKIP_APPLETS) continue
                if (!replaceSymlink("busybox", File(toolkitDir, applet))) {
                    return@synchronized false
                }
            }
            val marker = File(toolkitDir, "busybox_1_30_1")
            if (replaceSymlink("busybox", marker) &&
                verifyNonNativeToolkit(toolkitDir, binFile, rootBusyboxFile, expectedSha) &&
                queryBusyboxApplets(privateBusybox) != null
            ) {
                return@synchronized true
            }
        }

        val absInstallerPath = FileWrite.writePrivateShellFile(
            "addin/install_busybox.sh",
            "$installPath/install_busybox.sh",
            context
        ) ?: return@synchronized false

        if (CheckRootStatus.lastCheckResult) {
            val res = runRootCmdBounded(
                "sh '$absInstallerPath' '$absInstallPath' && '$absInstallPath/busybox' --list >/dev/null 2>&1; rc=\$?; echo \"BB_RC=\$rc\""
            )
            deleteTreeNoFollow(File(absInstallerPath))
            return@synchronized res?.lines()?.lastOrNull()?.trim() == "BB_RC=0" &&
                verifyNonNativeToolkit(toolkitDir, binFile, rootBusyboxFile, expectedSha)
        }
        deleteTreeNoFollow(File(absInstallerPath))
        return@synchronized false
    }

    fun isPrivateBusyboxReady(): Boolean = synchronized(INSTALL_LOCK) {
        privateBusyboxInstalled()
    }

    fun forceInstall(next: Runnable) {
        val mainHandler = Handler(Looper.getMainLooper())
        Thread {
            val privateOk = try {
                installPrivateBusybox()
            } catch (_: Exception) {
                false
            }
            if (privateOk && !systemBusyboxInstalled()) {
                val absInstallPath = FileWrite.getPrivateFilePath(
                    context,
                    context.getString(R.string.toolkit_install_path)
                )
                if (ShellExecutor.getExtraEnvPath() != absInstallPath) {
                    ShellExecutor.setExtraEnvPath(absInstallPath)
                    KeepShellPublic.tryExit()
                }
            }
            val ok = privateOk || try {
                systemBusyboxInstalled()
            } catch (_: Exception) {
                false
            }
            mainHandler.post {
                val activity = context as? Activity
                if (activity != null && (activity.isFinishing || activity.isDestroyed)) {
                    return@post
                }
                if (ok) {
                    next.run()
                } else {
                    DialogHelper.alert(
                        context,
                        "",
                        context.getString(R.string.busybox_nonsupport)
                    ) {
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }.setCancelable(false)
                }
            }
        }.start()
    }
}
