package com.omarea.vtools.activities

import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.PersistableBundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.omarea.Scene
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.ui.ThemeMode
import com.omarea.vtools.R

open class ActivityBase : AppCompatActivity() {
    public lateinit var themeMode: ThemeMode
    private var backCallback: OnBackPressedCallback? = null
    private var appliedThemeKey: String = ""

    override fun onCreate(savedInstanceState: Bundle?, persistentState: PersistableBundle?) {
        this.themeMode = ThemeSwitch.switchTheme(this)
        this.appliedThemeKey = ThemeSwitch.getEffectiveThemeKey(this)
        super.onCreate(savedInstanceState, persistentState)
        applyEdgeToEdgeBars()
        installDefaultBackCallback()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        this.themeMode = ThemeSwitch.switchTheme(this)
        this.appliedThemeKey = ThemeSwitch.getEffectiveThemeKey(this)
        super.onCreate(savedInstanceState)
        applyEdgeToEdgeBars()
        installDefaultBackCallback()
    }

    private fun applyEdgeToEdgeBars() {
        val barStyle = if (themeMode.isLightStatusBar) {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        } else {
            SystemBarStyle.dark(Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
        ThemeSwitch.applyWindowBackground(this, themeMode)
        ThemeSwitch.applySystemBarAppearance(this, themeMode.isLightStatusBar)
    }

    override fun onResume() {
        super.onResume()
        if (appliedThemeKey.isNotEmpty() && ThemeSwitch.getEffectiveThemeKey(this) != appliedThemeKey) {
            recreate()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (appliedThemeKey.isNotEmpty() && ThemeSwitch.getEffectiveThemeKey(this, newConfig) != appliedThemeKey) {
            ThemeSwitch.syncDefaultNightMode(this)
            recreate()
        }
    }

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        applyEdgeToEdgeInsets()
        if (::themeMode.isInitialized) {
            ThemeSwitch.applySystemBarAppearance(this, themeMode.isLightStatusBar)
        }
    }

    override fun setContentView(view: View?) {
        super.setContentView(view)
        applyEdgeToEdgeInsets()
        if (::themeMode.isInitialized) {
            ThemeSwitch.applySystemBarAppearance(this, themeMode.isLightStatusBar)
        }
    }

    private fun applyEdgeToEdgeInsets() {
        val content = findViewById<View>(android.R.id.content) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, windowInsets ->
            val systemInsets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            v.updatePadding(
                left = systemInsets.left,
                top = systemInsets.top,
                right = systemInsets.right,
                bottom = maxOf(systemInsets.bottom, imeInsets.bottom)
            )
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(content)
    }

    private fun installDefaultBackCallback() {
        val cb = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                onModernBackPressed()
            }
        }
        backCallback = cb
        onBackPressedDispatcher.addCallback(this, cb)
    }

    protected open fun onModernBackPressed() {
        backCallback?.isEnabled = false
        onBackPressedDispatcher.onBackPressed()
        finish()
    }

    protected val context: Context
        get() {
            return this
        }

    protected fun setBackArrow() {
        val toolbar = findViewById<View>(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)

        supportActionBar!!.setHomeButtonEnabled(true)
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    protected fun excludeFromRecent() {
        try {
            val service = this.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            for (task in service.appTasks) {
                if (task.taskInfo.id == this.taskId) {
                    task.setExcludeFromRecents(true)
                }
            }
        } catch (ex: Exception) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Scene.postDelayed({
            System.gc()
        }, 500)
        if (isTaskRoot) {
            Scene.postDelayed({
                KeepShellPublic.doCmdSync("dumpsys meminfo " + context.packageName + " > /dev/null")
            }, 100)
        }
    }
}
