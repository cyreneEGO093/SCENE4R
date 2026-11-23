package com.omarea.vtools.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.PowerManager.PARTIAL_WAKE_LOCK
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.omarea.common.shell.KeepShell
import com.omarea.vtools.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.ArrayList

/**
 * 后台编译应用
 */
class CompileService : Service() {
    companion object {
        @Volatile
        var compiling = false
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var activeJob: Job? = null
    @Volatile
    private var compileCanceled = false
    @Volatile
    private var latestStartId = 0
    private var keepShell = KeepShell(true)
    private lateinit var nm: NotificationManager
    private var compileMethod = "speed"
    private var channelCreated = false
    private var mWakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val initial = buildNotification(getString(R.string.dex2oat_compiling), "", 0, 0, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(990, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(990, initial)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        synchronized(this) {
            latestStartId = startId
            if (compiling && activeJob?.isActive == true) {
                compileCanceled = true
                activeJob?.cancel()
                return START_NOT_STICKY
            }
            compileCanceled = false
            compiling = true
            activeJob = serviceScope.launch {
                try {
                    handleCompile(intent)
                } finally {
                    synchronized(this@CompileService) {
                        compiling = false
                        ServiceCompat.stopForeground(this@CompileService, ServiceCompat.STOP_FOREGROUND_DETACH)
                        stopSelf(latestStartId)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun getAllPackageNames(): ArrayList<String> {
        val packageInfos = packageManager.getInstalledApplications(0)
        val list = ArrayList<String>()
        for (i in packageInfos.indices) {
            list.add(packageInfos[i].packageName)
        }
        list.remove(packageName)
        list.remove("com.google.android.gms")
        return list
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !channelCreated) {
            nm.createNotificationChannel(NotificationChannel("vtool-compile", "后台编译", NotificationManager.IMPORTANCE_LOW))
            channelCreated = true
        }
    }

    private fun buildNotification(title: String, text: String, total: Int, current: Int, autoCancel: Boolean): Notification {
        ensureChannel()
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationCompat.Builder(this, "vtool-compile")
        } else {
            NotificationCompat.Builder(this)
        }
        builder.setSmallIcon(R.drawable.process)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(autoCancel)
        if (total > 0) {
            builder.setProgress(total, current, false)
        }
        return builder.build()
    }

    private fun updateNotification(title: String, text: String, total: Int = 0, current: Int = 0, autoCancel: Boolean = true) {
        nm.notify(990, buildNotification(title, text, total, current, autoCancel))
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.handleCompile(intent: Intent?) {
        val mPowerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        mWakeLock = mPowerManager.newWakeLock(PARTIAL_WAKE_LOCK, "scene:CompileService").apply {
            acquire(60 * 60 * 1000L)
        }

        if (intent != null) {
            when (intent.action) {
                getString(R.string.scene_speed_compile) -> compileMethod = "speed"
                getString(R.string.scene_speed_profile_compile) -> compileMethod = "speed-profile"
                getString(R.string.scene_everything_compile) -> compileMethod = "everything"
                getString(R.string.scene_reset_compile) -> compileMethod = "reset"
            }
        }

        val timeoutPrefix = when {
            keepShell.doCmdSync("timeout 1 true >/dev/null 2>&1 && echo HAS_TIMEOUT").trim() == "HAS_TIMEOUT" -> "timeout 120 "
            keepShell.doCmdSync("busybox timeout -t 1 true >/dev/null 2>&1 && echo HAS_BB_TIMEOUT").trim() == "HAS_BB_TIMEOUT" -> "busybox timeout -t 120 "
            else -> ""
        }

        val packageNames = getAllPackageNames()
        val total = packageNames.size
        var current = 0
        if (compileMethod == "reset") {
            for (pkg in packageNames) {
                if (!isActive || compileCanceled) break
                updateNotification(getString(R.string.dex2oat_reset_running), pkg, total, current)
                val res = withTimeoutOrNull(125_000L) {
                    keepShell.doCmdSync(
                        "am broadcast -n com.omarea.vtools/com.omarea.vtools.ReceiverCompileState --ei current $current --ei total $total --es packageName $pkg\n" +
                            "${timeoutPrefix}cmd package compile --reset $pkg"
                    )
                }
                if (res == null) {
                    keepShell.tryExit()
                    break
                }
                current++
            }
            if (!compileCanceled) {
                withTimeoutOrNull(30_000L) {
                    keepShell.doCmdSync("am broadcast -n com.omarea.vtools/com.omarea.vtools.ReceiverCompileState --ei current $total --ei total $total --es packageName OK")
                }
            }
        } else {
            for (pkg in packageNames) {
                if (!isActive || compileCanceled) break
                updateNotification("${getString(R.string.dex2oat_compiling)}[$compileMethod]", "[$current/$total]$pkg", total, current)
                val res = withTimeoutOrNull(125_000L) {
                    keepShell.doCmdSync("${timeoutPrefix}cmd package compile -m $compileMethod $pkg")
                }
                if (res == null) {
                    keepShell.tryExit()
                    break
                }
                current++
            }
            if (!compileCanceled) {
                withTimeoutOrNull(125_000L) {
                    keepShell.doCmdSync("${timeoutPrefix}cmd package compile -m $compileMethod $packageName")
                } ?: keepShell.tryExit()
            }
        }
        hideNotification()
        keepShell.tryExit()
        try {
            if (mWakeLock?.isHeld == true) {
                mWakeLock?.release()
            }
        } catch (_: Exception) {
        }
    }

    private fun hideNotification() {
        if (compileCanceled) {
            nm.cancel(990)
        } else {
            updateNotification("complete!", getString(R.string.dex2oat_completed), 100, 100, true)
        }
    }

    override fun onDestroy() {
        compileCanceled = true
        activeJob?.cancel()
        hideNotification()
        try {
            if (mWakeLock?.isHeld == true) {
                mWakeLock?.release()
            }
        } catch (_: Exception) {
        }
        serviceScope.cancel()
        super.onDestroy()
    }
}
