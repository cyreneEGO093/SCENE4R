package com.omarea.vtools.activities

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import androidx.core.app.ActivityCompat
import androidx.core.content.PermissionChecker
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.omarea.Scene
import com.omarea.common.ui.DialogHelper
import com.omarea.common.ui.ThemeMode
import com.omarea.permissions.Busybox
import com.omarea.permissions.CheckRootStatus
import com.omarea.permissions.WriteSettings
import com.omarea.store.SpfConfig
import com.omarea.vtools.R
import java.util.*
import android.widget.FrameLayout
import android.widget.TextView

class ActivityStartSplash : Activity() {
    private val splash_root: FrameLayout
        get() = findViewById(R.id.splash_root)
    private val start_state_text: TextView
        get() = findViewById(R.id.start_state_text)

    companion object {
        public var finished = false
    }

    private lateinit var globalSPF: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        globalSPF = getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)

        val themeMode = ThemeSwitch.switchTheme(this)
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_start_splash)
        updateThemeStyle(themeMode)

        checkPermissions()
    }

    /**
     * 协议 同意与否
     */
    private fun initContractAction() {
        val view = layoutInflater.inflate(R.layout.dialog_danger_agreement, null)
        val dialog = DialogHelper.customDialog(this, view, false)
        val btnConfirm = view.findViewById<Button>(R.id.btn_confirm)
        val agreement = view.findViewById<CompoundButton>(R.id.agreement)
        val timer = Timer()
        var timeout = 30
        var clickItems = 0
        timer.schedule(object : TimerTask() {
            override fun run() {
                Scene.post {
                    if (timeout > 0) {
                        timeout --
                        btnConfirm.text = timeout.toString() + "s"
                    } else {
                        timer.cancel()
                        btnConfirm.text = "同意继续"
                    }
                }
            }
        }, 0, 1000)
        view.findViewById<View>(R.id.btn_cancel).setOnClickListener {
            timer.cancel()
            dialog.dismiss()
            finish()
        }
        btnConfirm.setOnClickListener {
            if (!agreement.isChecked) {
                return@setOnClickListener
            }
            if (timeout > 0 && clickItems < 10) { // 连点10次允许跳过倒计时
                clickItems++
                return@setOnClickListener
            }

            timer.cancel()
            dialog.dismiss()
            globalSPF.edit().putBoolean(SpfConfig.GLOBAL_SPF_CONTRACT, true).apply()
            checkPermissions()
        }
    }

    /**
     * 界面主题样式调整
     */
    private fun updateThemeStyle(themeMode: ThemeMode) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (themeMode.isDarkMode) {
            splash_root.setBackgroundColor(Color.argb(255, 0, 0, 0))
            controller.isAppearanceLightStatusBars = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                controller.isAppearanceLightNavigationBars = false
            }
        } else {
            splash_root.setBackgroundColor(Color.argb(255, 255, 255, 255))
            controller.isAppearanceLightStatusBars = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                controller.isAppearanceLightNavigationBars = true
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(splash_root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
    }

    private fun getColorAccent(): Int {
        val typedValue = TypedValue()
        this.theme.resolveAttribute(R.attr.colorAccent, typedValue, true)
        return typedValue.data
    }

    /**
     * 开始检查必需权限
     */
    private fun checkPermissions() {
        checkRoot()
    }

    private class CheckFileWirte(private val context: ActivityStartSplash) : Runnable {
        override fun run() {
            context.start_state_text.text = "检查并获取必需权限……"
            context.hasRoot = true

            context.checkFileWrite(InstallBusybox(context))
        }

    }

    private class InstallBusybox(private val context: ActivityStartSplash) : Runnable {
        override fun run() {
            context.start_state_text.text = "检查Busybox是否安装..."
            Busybox(context).forceInstall(BusyboxInstalled(context))
        }

    }

    private class BusyboxInstalled(private val context: ActivityStartSplash) : Runnable {
        override fun run() {
            context.startToFinish()
        }

    }

    private fun checkPermission(permission: String): Boolean = PermissionChecker.checkSelfPermission(this.applicationContext, permission) == PermissionChecker.PERMISSION_GRANTED

    /**
     * 检查权限 主要是文件读写权限
     */
    private var pendingAfterPermissions: Runnable? = null

    private fun checkFileWrite(next: Runnable) {
        Thread {
            CheckRootStatus.grantPermission(this)
            myHandler.post {
                val writeSettings = WriteSettings()
                if (!writeSettings.getPermission(applicationContext)) {
                    writeSettings.setPermission(applicationContext)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (!checkPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                        pendingAfterPermissions = next
                        ActivityCompat.requestPermissions(
                            this@ActivityStartSplash,
                            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                            0x11
                        )
                        return@post
                    }
                } else if (!(checkPermission(Manifest.permission.READ_EXTERNAL_STORAGE) && checkPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE))) {
                    pendingAfterPermissions = next
                    ActivityCompat.requestPermissions(
                        this@ActivityStartSplash,
                        arrayOf(
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ),
                        0x11
                    )
                    return@post
                }
                next.run()
            }
        }.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 0x11) {
            val next = pendingAfterPermissions
            pendingAfterPermissions = null
            next?.run()
        }
    }

    private var hasRoot = false
    private var myHandler = Handler(Looper.getMainLooper())

    private fun checkRoot() {
        CheckRootStatus(this, Runnable {
            if (globalSPF.getBoolean(SpfConfig.GLOBAL_SPF_CONTRACT, false)) {
                CheckFileWirte(this).run()
            } else {
                initContractAction()
            }
        }, false, InstallBusybox(this)).forceGetRoot()
    }

    /**
     * 启动完成
     */
    private fun startToFinish() {
        start_state_text.text = "启动完成！"

        val intent = Intent(this.applicationContext, ActivityMain::class.java)
        startActivity(intent)
        finished = true
        finish()
    }
}