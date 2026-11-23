package com.omarea.vtools.activities

import android.Manifest
import android.app.Activity
import android.app.WallpaperManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Environment
import android.util.TypedValue
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.PermissionChecker
import androidx.core.view.WindowInsetsControllerCompat
import com.omarea.Scene
import com.omarea.common.ui.ThemeMode
import com.omarea.store.SpfConfig
import com.omarea.vtools.R

object ThemeSwitch {
    const val NIGHT_FOLLOW_SYSTEM = 0
    const val NIGHT_LIGHT = 1
    const val NIGHT_DARK = 2

    private val themeMap = arrayListOf(
        R.style.AppThemeBlue,
        R.style.AppThemeCyan,
        R.style.AppThemeGreen,
        R.style.AppThemeOrange,
        R.style.AppThemeRed,
        R.style.AppThemePink,
        R.style.AppThemePretty,
        R.style.AppThemeViolet
    )

    private fun getGlobalSpf(context: Context): SharedPreferences =
        (context.applicationContext ?: context).getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)

    private fun checkPermission(context: Context, permission: String): Boolean =
        PermissionChecker.checkSelfPermission(context, permission) == PermissionChecker.PERMISSION_GRANTED

    internal fun hasWallpaperStorageAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) &&
                checkPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    internal fun applySystemBarAppearance(activity: Activity, light: Boolean) {
        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        controller.isAppearanceLightStatusBars = light
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            controller.isAppearanceLightNavigationBars = light
        }
    }

    internal fun applyWindowBackground(activity: Activity, themeMode: ThemeMode) {
        val theme = getGlobalSpf(activity).getInt(SpfConfig.GLOBAL_SPF_THEME, -1)
        if (theme == 10) return
        if (themeMode.isDarkMode) {
            activity.window.setBackgroundDrawableResource(R.color.window_background_dark)
            return
        }
        val tv = TypedValue()
        if (activity.theme.resolveAttribute(android.R.attr.windowBackground, tv, true)) {
            if (tv.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
                activity.window.setBackgroundDrawable(ColorDrawable(tv.data))
                return
            } else if (tv.resourceId != 0) {
                activity.window.setBackgroundDrawableResource(tv.resourceId)
                return
            }
        }
        activity.window.setBackgroundDrawableResource(R.color.window_background_light)
    }

    internal fun isSystemNightMode(context: Context): Boolean {
        val sysNight = Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (sysNight == Configuration.UI_MODE_NIGHT_YES) return true
        if (sysNight == Configuration.UI_MODE_NIGHT_NO) return false
        val appNight = (context.applicationContext ?: context).resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return appNight == Configuration.UI_MODE_NIGHT_YES
    }

    internal fun getNightModePolicy(context: Context): Int {
        val spf = getGlobalSpf(context)
        if (spf.contains(SpfConfig.GLOBAL_SPF_NIGHT_MODE_POLICY)) {
            val saved = spf.getInt(SpfConfig.GLOBAL_SPF_NIGHT_MODE_POLICY, NIGHT_FOLLOW_SYSTEM)
            return if (saved in NIGHT_FOLLOW_SYSTEM..NIGHT_DARK) saved else NIGHT_FOLLOW_SYSTEM
        }
        return when (spf.getInt(SpfConfig.GLOBAL_SPF_THEME, -1)) {
            -2 -> NIGHT_DARK
            -3 -> NIGHT_LIGHT
            else -> NIGHT_FOLLOW_SYSTEM
        }
    }

    internal fun isNightModeActive(context: Context, configOverride: Configuration? = null): Boolean {
        return when (getNightModePolicy(context)) {
            NIGHT_DARK -> true
            NIGHT_LIGHT -> false
            else -> {
                if (configOverride != null) {
                    (configOverride.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                } else {
                    isSystemNightMode(context)
                }
            }
        }
    }

    internal fun syncDefaultNightMode(context: Context): Int {
        val delegateMode = when (getNightModePolicy(context)) {
            NIGHT_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            NIGHT_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != delegateMode) {
            AppCompatDelegate.setDefaultNightMode(delegateMode)
        }
        return delegateMode
    }

    internal fun getEffectiveThemeKey(context: Context, configOverride: Configuration? = null): String {
        val spf = getGlobalSpf(context)
        val rawTheme = spf.getInt(SpfConfig.GLOBAL_SPF_THEME, -1)
        val normalizedTheme = if (rawTheme == -2 || rawTheme == -3) -1 else rawTheme
        val policy = getNightModePolicy(context)
        val isDark = isNightModeActive(context, configOverride)
        return "$normalizedTheme:$policy:$isDark"
    }

    internal fun switchTheme(activity: Activity): ThemeMode {
        val themeMode = ThemeMode()
        val spf = getGlobalSpf(activity)

        var theme = spf.getInt(SpfConfig.GLOBAL_SPF_THEME, -1)
        if (!spf.contains(SpfConfig.GLOBAL_SPF_NIGHT_MODE_POLICY) && (theme == -2 || theme == -3)) {
            val migratedPolicy = if (theme == -2) NIGHT_DARK else NIGHT_LIGHT
            spf.edit()
                .putInt(SpfConfig.GLOBAL_SPF_NIGHT_MODE_POLICY, migratedPolicy)
                .putInt(SpfConfig.GLOBAL_SPF_THEME, -1)
                .apply()
            theme = -1
        } else if (theme == -2 || theme == -3) {
            spf.edit().putInt(SpfConfig.GLOBAL_SPF_THEME, -1).apply()
            theme = -1
        }

        if (theme == 10 && !hasWallpaperStorageAccess(activity)) {
            spf.edit().remove(SpfConfig.GLOBAL_SPF_THEME).apply()
            return switchTheme(activity)
        }

        if (theme < themeMap.size) {
            val delegateMode = syncDefaultNightMode(activity)
            val isDark = isNightModeActive(activity)
            themeMode.isDarkMode = isDark
            themeMode.isLightStatusBar = !isDark && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

            val themeId = when {
                theme in themeMap.indices -> themeMap[theme]
                isDark -> R.style.AppThemeNoActionBarNight
                else -> R.style.AppThemeWhite
            }

            if (activity is AppCompatActivity) {
                val targetLocalNight = delegateMode
                if (activity.delegate.localNightMode != targetLocalNight) {
                    activity.delegate.localNightMode = targetLocalNight
                }
            }
            activity.setTheme(themeId)
        } else if (theme == 10) {
            try {
                val wallpaper = WallpaperManager.getInstance(activity)
                val wallpaperInfo = wallpaper.wallpaperInfo
                activity.setTheme(R.style.AppThemeWallpaper)

                if (wallpaperInfo != null && wallpaperInfo.packageName != null) {
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                    themeMode.isDarkMode = true
                } else {
                    val wallpaperDrawable = wallpaper.drawable
                    if (wallpaperDrawable == null || isDarkColor(wallpaperDrawable)) {
                        themeMode.isDarkMode = true
                    } else {
                        themeMode.isDarkMode = false
                        themeMode.isLightStatusBar = true
                        applySystemBarAppearance(activity, true)
                    }
                    if (wallpaperDrawable != null) {
                        activity.window.setBackgroundDrawable(wallpaperDrawable)
                    }
                }
            } catch (_: SecurityException) {
                // On scoped-storage/Android 13+ without wallpaper permission, fall back gracefully to live wallpaper flag
                activity.setTheme(R.style.AppThemeWallpaper)
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                themeMode.isDarkMode = true
            }

            if (themeMode.isDarkMode) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
        }
        Scene.updateNightMode(themeMode.isDarkMode)
        return themeMode
    }

    private fun isDarkColor(wallPaper: Drawable): Boolean {
        val bitmap = (wallPaper as? BitmapDrawable)?.bitmap ?: return true
        val h = bitmap.height - 1
        val w = bitmap.width - 1

        var darkPoint = 0
        var lightPoint = 0
        val pointCount = if (h > 24 && w > 24) 24 else 1

        for (i in 0..pointCount) {
            val y = h / pointCount * i
            val x = w / pointCount * i
            val pixel = bitmap.getPixel(x, y)

            val redValue = Color.red(pixel)
            val blueValue = Color.blue(pixel)
            val greenValue = Color.green(pixel)

            if (redValue > 150 && blueValue > 150 && greenValue > 150) {
                lightPoint += 1
            } else {
                darkPoint += 1
            }
        }
        return darkPoint > lightPoint
    }
}
