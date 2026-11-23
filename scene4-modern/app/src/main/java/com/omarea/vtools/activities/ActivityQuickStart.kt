package com.omarea.vtools.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.core.view.WindowCompat
import com.omarea.common.shell.KeepShellPublic
import com.omarea.library.shell.GAppsUtilis
import com.omarea.permissions.CheckRootStatus
import com.omarea.scene_mode.SceneMode
import com.omarea.store.SceneConfigStore
import com.omarea.store.SpfConfig
import com.omarea.vtools.R
import java.lang.ref.WeakReference
import java.security.SecureRandom

class ActivityQuickStart : Activity() {
    companion object {
        private const val PREF_SHORTCUT_TOKEN = "scene_freeze_shortcut_token"
        private const val EXTRA_SHORTCUT_TOKEN = "shortcutToken"

        fun getOrCreateShortcutToken(context: Context): String {
            val spf = context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
            val existing = spf.getString(PREF_SHORTCUT_TOKEN, null)
            if (!existing.isNullOrEmpty() && existing.length >= 32) {
                return existing
            }
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            val token = bytes.joinToString("") { "%02x".format(it) }
            spf.edit().putString(PREF_SHORTCUT_TOKEN, token).apply()
            return token
        }
    }

    private val start_state_text: TextView
        get() = findViewById(R.id.start_state_text)

    private var appPackageName: String = ""
    private val safePkgRegex = Regex("^[a-zA-Z0-9._]+$")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_quick_start)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val expectedToken = getOrCreateShortcutToken(this)
        val providedToken = intent?.getStringExtra(EXTRA_SHORTCUT_TOKEN)
        if (providedToken.isNullOrEmpty() || providedToken != expectedToken) {
            start_state_text.text = "未经授权的快捷方式启动请求！"
            return
        }

        val rawPkg = intent?.getStringExtra("packageName")
        if (rawPkg.isNullOrEmpty() || !safePkgRegex.matches(rawPkg)) {
            start_state_text.text = "无效的快捷方式！"
            return
        }
        appPackageName = rawPkg

        val controlledApps = SceneConfigStore(this).freezeAppList
        val appInfo = lookupInstalledPackage(appPackageName)
        if (appInfo == null || !controlledApps.contains(appPackageName)) {
            start_state_text.text = "未受信任或未纳入偏见名单的应用！"
            return
        }

        if (appInfo.enabled && (appInfo.flags and ApplicationInfo.FLAG_SUSPENDED) == 0) {
            startApp()
        } else {
            checkRoot(CheckRootSuccess(this, appPackageName))
        }
    }

    private fun lookupInstalledPackage(pkg: String): ApplicationInfo? {
        return try {
            val flags = PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(pkg, flags)
            }
        } catch (_: Exception) {
            null
        }
    }

    private class CheckRootSuccess(context: ActivityQuickStart, private var appPackageName: String) : Runnable {
        private var context = WeakReference(context)
        override fun run() {
            val act = context.get() ?: return
            act.start_state_text.text = "正在启动应用..."

            if (appPackageName == "com.android.vending") {
                GAppsUtilis().enable(KeepShellPublic.secondaryKeepShell)
            } else {
                KeepShellPublic.doCmdSync(
                    "pm unsuspend '$appPackageName'\n" +
                        "pm unhide '$appPackageName'\n" +
                        "pm enable '$appPackageName'\n"
                )
            }
            act.startApp()
        }
    }

    private fun startApp() {
        val pm = packageManager
        try {
            val appIntent = pm.getLaunchIntentForPackage(appPackageName)
            if (appIntent != null) {
                Thread {
                    appIntent.flags = Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(appIntent)
                    SceneMode.getCurrentInstance()?.setFreezeAppStartTime(appPackageName)
                }.start()
                return
            }
        } catch (_: Exception) {
            start_state_text.text = "启动应用失败！"
        }

        val appInfo = lookupInstalledPackage(appPackageName)
        if (appInfo == null) {
            start_state_text.text = "应用似乎已被卸载！"
        } else {
            start_state_text.text = "启动应用失败！"
        }
    }

    override fun onPause() {
        super.onPause()
        this.finish()
    }

    private fun checkRoot(next: Runnable) {
        CheckRootStatus(this, next, false).forceGetRoot()
    }
}
