package com.omarea.krscript.downloader

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Context.DOWNLOAD_SERVICE
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.URLUtil
import android.widget.Toast
import com.omarea.common.shared.FileWrite
import com.omarea.common.ui.DialogHelper
import com.omarea.krscript.R
import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset

class Downloader(private var context: Context, private var activity: Activity? = null) {
    companion object {
        private val HISTORY_CONFIG = "kr_downloader"

        @Synchronized
        @JvmStatic
        fun isTrackedDownload(context: Context, downloadId: Long): Boolean {
            if (downloadId <= 0) return false
            val historyList = context.getSharedPreferences(HISTORY_CONFIG, Context.MODE_PRIVATE)
            val raw = historyList.getString(downloadId.toString(), null) ?: return false
            return try {
                val obj = JSONObject(raw)
                if (obj.optBoolean("completed", false) || obj.optBoolean("claimed", false)) {
                    false
                } else {
                    obj.put("claimed", true)
                    historyList.edit().putString(downloadId.toString(), obj.toString(2)).commit()
                }
            } catch (_: Exception) {
                false
            }
        }

        @Synchronized
        @JvmStatic
        fun releaseDownloadClaim(context: Context, downloadId: Long) {
            if (downloadId <= 0) return
            val historyList = context.getSharedPreferences(HISTORY_CONFIG, Context.MODE_PRIVATE)
            val raw = historyList.getString(downloadId.toString(), null) ?: return
            try {
                val obj = JSONObject(raw)
                if (!obj.optBoolean("completed", false) && obj.optBoolean("claimed", false)) {
                    obj.put("claimed", false)
                    historyList.edit().putString(downloadId.toString(), obj.toString(2)).commit()
                }
            } catch (_: Exception) {
            }
        }
    }

    fun downloadByBrowser(url: String) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.data = Uri.parse(url)
        activity?.startActivity(intent)
    }

    fun downloadBySystem(url: String, contentDisposition: String?, mimeType: String?, taskAliasId: String): Long? {
        try {
            val request = DownloadManager.Request(Uri.parse(url))
            request.allowScanningByMediaScanner()
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setAllowedOverMetered(true)
            request.setVisibleInDownloadsUi(true)
            request.setAllowedOverRoaming(true)
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            val downloadManager = context.getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = downloadManager.enqueue(request)
            addTaskHisotry(downloadId, taskAliasId, url)
            Toast.makeText(context, context.getString(R.string.kr_download_create_success), Toast.LENGTH_SHORT).show()
            DownloaderReceiver.autoRegister(context.applicationContext)
            return downloadId
        } catch (ex: Exception) {
            DialogHelper.helpInfo(context, context.getString(R.string.kr_download_create_fail), "" + ex.message)
            return null
        }
    }

    // 保存下载记录
    private fun addTaskHisotry(downloadId: Long, taskAliasId: String, url: String) {
        val historyList = context.getSharedPreferences(HISTORY_CONFIG, Context.MODE_PRIVATE)
        val history = JSONObject()
        history.put("url", url)
        history.put("taskAliasId", taskAliasId)
        historyList.edit().putString(downloadId.toString(), history.toString(2)).apply()
    }

    // 保存任务状态、进度
    fun saveTaskStatus(taskAliasId: String, ratio: Int) {
        FileWrite.writePrivateFile(ratio.toString().toByteArray(Charset.defaultCharset()), "downloader/status/" + taskAliasId, context)
    }

    // 保存下载成功后的路径（先写入结果文件，全部成功后再提交 completed=true）
    fun saveTaskCompleted(downloadId: Long, absPath: String): Boolean {
        val historyList = context.getSharedPreferences(HISTORY_CONFIG, Context.MODE_PRIVATE)
        val historyStr = historyList.getString(downloadId.toString(), null)
        var taskAliasId: String? = ""
        if (historyStr != null) {
            val hisotry = JSONObject(historyStr)
            taskAliasId = hisotry.optString("taskAliasId", "")
        }
        return try {
            val file = File(absPath)
            if (file.exists() && file.canRead()) {
                val md5 = FileMD5().getFileMD5(file).lowercase()
                val wroteMd5 = FileWrite.writePrivateFile(absPath.toByteArray(Charset.defaultCharset()), "downloader/path/$md5", context)
                val wroteAlias = if (!taskAliasId.isNullOrEmpty()) {
                    FileWrite.writePrivateFile(absPath.toByteArray(Charset.defaultCharset()), "downloader/result/$taskAliasId", context)
                } else {
                    true
                }
                if (wroteMd5 && wroteAlias && historyStr != null) {
                    val hisotry = JSONObject(historyStr)
                    hisotry.put("absPath", absPath)
                    hisotry.put("completed", true)
                    historyList.edit().putString(downloadId.toString(), hisotry.toString(2)).commit()
                } else {
                    wroteMd5 && wroteAlias
                }
            } else {
                false
            }
        } catch (_: java.lang.Exception) {
            false
        }
    }
}
