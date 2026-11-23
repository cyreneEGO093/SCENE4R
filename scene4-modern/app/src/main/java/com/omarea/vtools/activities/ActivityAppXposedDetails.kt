package com.omarea.vtools.activities

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import com.omarea.common.ui.DialogHelper
import com.omarea.store.SpfConfig
import com.omarea.store.XposedExtension
import com.omarea.ui.IntInputFilter
import com.omarea.vtools.R
import com.omarea.xposed.XposedCheck

class ActivityAppXposedDetails : ActivityBase() {
    private val app_details_dpi: TextView
        get() = findViewById(R.id.app_details_dpi)
    private val app_details_excludetask: Switch
        get() = findViewById(R.id.app_details_excludetask)
    private val app_details_icon: ImageView
        get() = findViewById(R.id.app_details_icon)
    private val app_details_name: TextView
        get() = findViewById(R.id.app_details_name)
    private val app_details_packagename: TextView
        get() = findViewById(R.id.app_details_packagename)
    private val app_details_scrollopt: Switch
        get() = findViewById(R.id.app_details_scrollopt)
    private val app_details_vaddins_notactive: TextView
        get() = findViewById(R.id.app_details_vaddins_notactive)
    private val app_details_vaddins_notinstall: TextView
        get() = findViewById(R.id.app_details_vaddins_notinstall)
    private val app_details_web_debug: Switch
        get() = findViewById(R.id.app_details_web_debug)
    private val toolbar: Toolbar
        get() = findViewById(R.id.toolbar)

    var app = ""
    lateinit var sceneConfigInfo: XposedExtension.AppConfig
    lateinit var originConfig: XposedExtension.AppConfig
    private var dynamicCpu: Boolean = false
    private var _result = RESULT_CANCELED
    private lateinit var xposedExtension: XposedExtension
    private lateinit var spfGlobal: SharedPreferences

    private fun updateXposedConfigFromBuiltIn() {
        val loaded = xposedExtension.getAppConfig(app)
        if (loaded != null) {
            sceneConfigInfo.dpi = loaded.dpi
            originConfig.dpi = loaded.dpi
            sceneConfigInfo.excludeRecent = loaded.excludeRecent
            originConfig.excludeRecent = loaded.excludeRecent
            sceneConfigInfo.smoothScroll = loaded.smoothScroll
            originConfig.smoothScroll = loaded.smoothScroll
            sceneConfigInfo.webDebug = loaded.webDebug
            originConfig.webDebug = loaded.webDebug

            app_details_scrollopt.isChecked = sceneConfigInfo.smoothScroll
            app_details_excludetask.isChecked = sceneConfigInfo.excludeRecent
            app_details_web_debug.isChecked = sceneConfigInfo.webDebug
            app_details_dpi.text = if (sceneConfigInfo.dpi >= 96) sceneConfigInfo.dpi.toString() else "默认"
        }
    }

    /**
     * 检查内置 Xposed 状态
     */
    private fun checkXposedState() {
        val available = xposedExtension.isAvailable()
        val xposedActive = available || XposedCheck.xposedIsRunning()
        app_details_vaddins_notinstall.visibility = View.GONE
        app_details_vaddins_notactive.visibility = if (available) View.GONE else View.VISIBLE

        if (available) {
            updateXposedConfigFromBuiltIn()
        }

        val allowXposedConfig = available && xposedActive
        app_details_dpi.isEnabled = allowXposedConfig
        app_details_excludetask.isEnabled = allowXposedConfig
        app_details_scrollopt.isEnabled = allowXposedConfig
        app_details_web_debug.isEnabled = allowXposedConfig
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_xposed_details)

        val toolbar = findViewById<View>(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)

        // 显示返回按钮
        supportActionBar!!.setHomeButtonEnabled(true)
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener {
            saveConfigAndFinish()
        }

        spfGlobal = getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
        xposedExtension = XposedExtension(this)

        val intent = this.intent
        if (intent == null) {
            setResult(_result, this.intent)
            finish()
            return
        }
        val extras = this.intent.extras
        if (extras == null || !extras.containsKey("app")) {
            setResult(_result, this.intent)
            finish()
            return
        }

        app = extras.getString("app")!!

        dynamicCpu = spfGlobal.getBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL, SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL_DEFAULT)

        app_details_icon.setOnClickListener {
            try {
                saveConfig()
                startActivity(getPackageManager().getLaunchIntentForPackage(app))
            } catch (ex: Exception) {
                Toast.makeText(applicationContext, getString(R.string.start_app_fail), Toast.LENGTH_SHORT).show()
            }
        }

        sceneConfigInfo = XposedExtension.AppConfig(app)
        originConfig = XposedExtension.AppConfig(app)
        if (sceneConfigInfo.dpi >= 96) {
            app_details_dpi.text = sceneConfigInfo.dpi.toString()
        } else {
            app_details_dpi.text = "默认"
        }
        app_details_excludetask.setOnClickListener {
            sceneConfigInfo.excludeRecent = (it as Switch).isChecked
        }
        app_details_scrollopt.setOnClickListener {
            sceneConfigInfo.smoothScroll = (it as Switch).isChecked
        }
        app_details_web_debug.setOnClickListener {
            sceneConfigInfo.webDebug = (it as Switch).isChecked
        }

        app_details_dpi.setOnClickListener {
            if (!xposedExtension.isAvailable()) return@setOnClickListener
            var dialog: DialogHelper.DialogWrap? = null
            val view = layoutInflater.inflate(R.layout.dialog_dpi_input, null)
            val inputDpi = view.findViewById<EditText>(R.id.input_dpi).apply {
                filters = arrayOf(IntInputFilter())
                if (sceneConfigInfo.dpi >= 96) {
                    setText(sceneConfigInfo.dpi.toString())
                }
            }
            dialog = DialogHelper.confirm(this, "请输入DPI", "", view, DialogHelper.DialogButton(getString(R.string.btn_confirm), {
                val dpiText = inputDpi.text.toString()
                if (dpiText.isEmpty()) {
                    sceneConfigInfo.dpi = 0
                    app_details_dpi.text = "默认"
                    dialog?.dismiss()
                } else {
                    try {
                        val dpi = dpiText.toInt()
                        if (dpi < 96 && dpi != 0) {
                            Toast.makeText(applicationContext, "DPI的值必须大于96", Toast.LENGTH_SHORT).show()
                        } else {
                            sceneConfigInfo.dpi = dpi
                            app_details_dpi.text = if (dpi == 0) "默认" else dpi.toString()
                            dialog?.dismiss()
                        }
                    } catch (_: Exception) {
                    }
                }
            }, false), DialogHelper.DialogButton(getString(R.string.btn_cancel)))
            dialog.dialog.window?.clearFlags(
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            )
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.save, menu)
        return true
    }

    //右上角菜单
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_save -> {
                saveConfigAndFinish()
            }
        }
        return super.onOptionsItemSelected(item)
    }

    @SuppressLint("SetTextI18n")
    override fun onResume() {
        super.onResume()
        checkXposedState()

        var packageInfo: PackageInfo? = null
        try {
            packageInfo = packageManager.getPackageInfo(app, 0)
        } catch (ex: Exception) {
            Toast.makeText(applicationContext, "所选的应用已被卸载！", Toast.LENGTH_SHORT).show()
        }
        val applicationInfo = packageInfo?.applicationInfo
        if (packageInfo == null || applicationInfo == null) {
            finish()
            return
        }
        app_details_name.text = applicationInfo.loadLabel(packageManager)
        app_details_packagename.text = packageInfo.packageName
        app_details_icon.setImageDrawable(applicationInfo.loadIcon(packageManager))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            saveConfigAndFinish()
        }
        return false
    }

    private fun saveConfigAndFinish() {
        saveConfig()
        this.finish()
    }

    private fun saveConfig() {
        try {
            if (
                sceneConfigInfo.dpi != originConfig.dpi ||
                sceneConfigInfo.excludeRecent != originConfig.excludeRecent ||
                sceneConfigInfo.smoothScroll != originConfig.smoothScroll ||
                sceneConfigInfo.webDebug != originConfig.webDebug
            ) {
                setResult(RESULT_OK, this.intent)
            } else {
                setResult(_result, this.intent)
            }
            if (xposedExtension.isAvailable()) {
                xposedExtension.setAppConfig(sceneConfigInfo)
            }
        } catch (_: Exception) {
        }
    }

    override fun onPause() {
        super.onPause()
    }
}
