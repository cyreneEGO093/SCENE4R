package com.omarea.common.shared

import android.content.Context
import android.content.res.AssetManager
import android.os.Environment
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/**
 * 提供公共方法，向外置存储读写文件
 * Created by helloklf on 2016/8/27.
 */
object FileWrite {
    val SDCardDir: String = Environment.getExternalStorageDirectory().absolutePath

    fun writeFile(context: Context, file: String, hasExtName: Boolean): String? {
        val baseUrl = "${SDCardDir}/Android/data/${context.packageName}/"

        try {
            val inputStream = if (file.startsWith("file:///android_asset/")) {
                context.assets.open(file.substring("file:///android_asset/".length))
            } else {
                context.assets.open(file)
            }

            val dir = File(baseUrl)
            if (!dir.exists())
                dir.mkdirs()
            val filePath = baseUrl + if (hasExtName)
                file
            else
                file.substring(0, if (file.lastIndexOf(".") > 0) file.lastIndexOf(".") else file.length)

            val fileOutputStream = FileOutputStream(filePath)

            val datas = ByteArray(20480)
            while (true) {
                val len = inputStream.read(datas)
                if (len > 0) {
                    fileOutputStream.write(datas, 0, len)
                } else {
                    break
                }
            }

            fileOutputStream.close()
            inputStream.close()
            val writedFile = File(filePath)
            writedFile.setWritable(true, true)
            writedFile.setExecutable(true, false)
            writedFile.setReadable(true, false)
            return filePath
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return null
    }

    fun getPrivateFileDir(context: Context): String {
        return context.filesDir.absolutePath + "/"
    }

    fun getPrivateFilePath(context: Context, outName: String): String {
        return getPrivateFileDir(context) + (if (outName.startsWith("/")) outName.substring(1, outName.length) else outName)
    }
    fun writePrivateFile(file: String, outName: String, context: Context): String? {
        return writePrivateFile(context.assets, file, outName, context);
    }

    private fun removeSymlinkIfPresent(path: String): Boolean {
        return try {
            val st = Os.lstat(path)
            if (OsConstants.S_ISLNK(st.st_mode)) {
                Os.remove(path)
            }
            true
        } catch (ex: ErrnoException) {
            ex.errno == OsConstants.ENOENT
        }
    }

    fun writePrivateFile(assetManager: AssetManager, file: String, outName: String, context: Context): String? {
        var tempFile: File? = null
        try {
            val inputStream = if (file.startsWith("file:///android_asset/")) {
                assetManager.open(file.substring("file:///android_asset/".length))
            } else {
                assetManager.open(file)
            }

            val dir = File(getPrivateFileDir(context))
            if (!dir.exists())
                dir.mkdirs()
            val filePath = getPrivateFilePath(context, outName)
            val targetFile = File(filePath)
            val fileDir = targetFile.parentFile ?: dir
            if (!fileDir.exists())
                fileDir.mkdirs()

            if (!removeSymlinkIfPresent(filePath)) {
                inputStream.close()
                return null
            }

            tempFile = File.createTempFile(".fw_", ".tmp", fileDir)
            FileOutputStream(tempFile).use { fileOutputStream ->
                inputStream.use { stream ->
                    val datas = ByteArray(20480)
                    while (true) {
                        val len = stream.read(datas)
                        if (len > 0) {
                            fileOutputStream.write(datas, 0, len)
                        } else {
                            break
                        }
                    }
                }
            }
            tempFile.setWritable(true, true)
            tempFile.setExecutable(true, false)
            tempFile.setReadable(true, false)
            Os.rename(tempFile.absolutePath, filePath)
            tempFile = null
            return filePath
            //getApplicationContext().getClassLoader().getResourceAsStream("");
        } catch (e: Exception) {
            Log.e("writePrivateFile", "" + e.message)
            e.printStackTrace()
        } finally {
            tempFile?.delete()
        }
        return null
    }

    fun writePrivateFile(bytes: ByteArray, outName: String, context: Context): Boolean {
        var tempFile: File? = null
        try {
            val dir = File(getPrivateFileDir(context))
            if (!dir.exists())
                dir.mkdirs()
            val filePath = getPrivateFilePath(context, outName)
            val targetFile = File(filePath)
            val fileDir = targetFile.parentFile ?: dir
            if (!fileDir.exists())
                fileDir.mkdirs()

            if (!removeSymlinkIfPresent(filePath)) {
                return false
            }

            tempFile = File.createTempFile(".fw_", ".tmp", fileDir)
            FileOutputStream(tempFile).use { fileOutputStream ->
                fileOutputStream.write(bytes, 0, bytes.size)
            }
            tempFile.setWritable(true, true)
            tempFile.setExecutable(true, false)
            tempFile.setReadable(true, false)
            Os.rename(tempFile.absolutePath, filePath)
            tempFile = null
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            tempFile?.delete()
        }
    }

    fun writePrivateShellFile(file: String, outName: String, context: Context): String? {
        val data = parseText(context, file)
        if (data.size > 0 && writePrivateFile(data, outName, context)) {
            return getPrivateFilePath(context, outName)
        }
        return null
    }

    //Dos转Unix，避免\r\n导致的脚本无法解析
    private fun parseText(context: Context, fileName: String): ByteArray {
        return try {
            context.assets.open(fileName).use { inputStream ->
                val codes = inputStream.readBytes()
                    .toString(Charsets.UTF_8)
                    .replace(Regex("\r\n"), "\n")
                    .replace(Regex("\r\t"), "\t")
                codes.toByteArray(Charsets.UTF_8)
            }
        } catch (ex: Exception) {
            Log.e("script-parse", "" + ex.message)
            ByteArray(0)
        }
    }
}
