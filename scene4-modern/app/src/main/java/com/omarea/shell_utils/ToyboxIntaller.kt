package com.omarea.shell_utils

import android.content.Context
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.omarea.common.shared.FileWrite.getPrivateFilePath
import com.omarea.common.shared.FileWrite.writePrivateFile
import java.io.File
import java.security.MessageDigest

class ToyboxIntaller(private val context: Context) {
    companion object {
        private const val SHA256_TOYBOX32 = "380f34649adfe16cdd79f6f600f6dd82fa8f147535c7779b50249d50a1c0f0c3"
        private const val SHA256_TOYBOX64 = "c299b85b99b67bf6c33a3c5b15c3b5845bb26863fcdd129ba2e08429d8d145b6"

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

        private fun isVerifiedRegularBinary(file: File, expectedSha: String): Boolean {
            return try {
                val st = Os.lstat(file.absolutePath)
                !OsConstants.S_ISLNK(st.st_mode) &&
                    OsConstants.S_ISREG(st.st_mode) &&
                    sha256Hex(file) == expectedSha
            } catch (_: ErrnoException) {
                false
            } catch (_: Exception) {
                false
            }
        }
    }

    public fun install(): String {
        val isArm64 = Build.SUPPORTED_64_BIT_ABIS.any { it.contains("arm64") }
        val fileName = if (isArm64) "toybox-outside64" else "toybox-outside"
        val assetPath = "toolkit/$fileName"
        val outRelPath = "toybox/$fileName"
        val expectedSha = if (isArm64) SHA256_TOYBOX64 else SHA256_TOYBOX32
        val outsideToybox = getPrivateFilePath(context, outRelPath)
        val outFile = File(outsideToybox)

        if (!isVerifiedRegularBinary(outFile, expectedSha)) {
            writePrivateFile(context.assets, assetPath, outRelPath, context)
            if (!isVerifiedRegularBinary(outFile, expectedSha)) {
                outFile.delete()
                return "/system/bin/toybox"
            }
        }

        return outsideToybox
    }
}