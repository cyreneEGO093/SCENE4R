package com.omarea.store

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

public class XposedExtension(private val context: Context) {
    companion object {
        const val PREFS_NAME = "xposed"
    }

    open class AppConfig(val packageName: String) {
        public var dpi = -1
        public var excludeRecent = false
        public var smoothScroll = false
        public var webDebug = false
    }

    open class GlobalConfig {
        public var androidScroll = false
        public var globalWebDebug = false
        public var freezeAutoUnfreeze = true
    }

    @Suppress("DEPRECATION")
    private fun getPrefs(): SharedPreferences? {
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE)
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    public fun isAvailable(): Boolean = getPrefs() != null

    public fun bindService(onCompleted: Runnable): Boolean {
        if (!isAvailable()) {
            return false
        }
        onCompleted.run()
        return true
    }

    public fun unbindService() {
    }

    // 获取某个应用的xposed配置
    public fun getAppConfig(packageName: String): AppConfig? {
        return getAppConfig(AppConfig(packageName))
    }

    public fun getAppConfig(appConfig: AppConfig): AppConfig? {
        val prefs = getPrefs() ?: return null
        return try {
            val configJson = prefs.getString(appConfig.packageName, "{}") ?: "{}"
            val config = JSONObject(configJson)
            for (key in config.keys()) {
                when (key) {
                    "dpi" -> appConfig.dpi = config.getInt(key)
                    "excludeRecent" -> appConfig.excludeRecent = config.getBoolean(key)
                    "smoothScroll" -> appConfig.smoothScroll = config.getBoolean(key)
                    "webDebug" -> appConfig.webDebug = config.getBoolean(key)
                }
            }
            appConfig
        } catch (_: Exception) {
            null
        }
    }

    public fun setAppConfig(appConfig: AppConfig): Boolean {
        val prefs = getPrefs() ?: return false
        return try {
            val config = JSONObject().apply {
                put("dpi", appConfig.dpi)
                put("excludeRecent", appConfig.excludeRecent)
                put("smoothScroll", appConfig.smoothScroll)
                put("webDebug", appConfig.webDebug)
            }.toString(0)

            prefs.edit().putString(appConfig.packageName, config).commit()
        } catch (_: Exception) {
            false
        }
    }

    public fun getGlobalConfig(): GlobalConfig? {
        val prefs = getPrefs() ?: return null
        return try {
            GlobalConfig().apply {
                androidScroll = prefs.getBoolean("android_scroll", false)
                globalWebDebug = prefs.getBoolean("global_web_debug", false)
                freezeAutoUnfreeze = prefs.getBoolean("freeze_xposed_open", true)
            }
        } catch (_: Exception) {
            null
        }
    }

    public fun setGlobalConfig(globalConfig: GlobalConfig): Boolean {
        val prefs = getPrefs() ?: return false
        return try {
            prefs.edit()
                .putBoolean("android_scroll", globalConfig.androidScroll)
                .putBoolean("global_web_debug", globalConfig.globalWebDebug)
                .putBoolean("freeze_xposed_open", globalConfig.freezeAutoUnfreeze)
                .commit()
        } catch (_: Exception) {
            false
        }
    }
}