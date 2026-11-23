package com.omarea.xposed;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Display;

import com.omarea.store.XposedExtension;

import org.json.JSONObject;

import java.util.Iterator;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static de.robv.android.xposed.XposedHelpers.getObjectField;

/**
 * Built-in Xposed module entry using LSPosed xposedsharedprefs.
 */
public class XposedInterface implements IXposedHookLoadPackage {
    private static final String MODULE_PACKAGE = "com.omarea.vtools";
    private static XSharedPreferences prefs;

    private static synchronized XSharedPreferences getPrefs() {
        if (prefs == null) {
            prefs = new XSharedPreferences(MODULE_PACKAGE, XposedExtension.PREFS_NAME);
        }
        try {
            prefs.reload();
        } catch (Throwable ignored) {
        }
        return prefs;
    }

    public XposedExtension.AppConfig getAppConfig(String packageName) {
        XposedExtension.AppConfig appConfig = new XposedExtension.AppConfig(packageName);
        try {
            XSharedPreferences xPrefs = getPrefs();
            String configJson = xPrefs.getString(packageName, "{}");
            JSONObject config = new JSONObject(configJson != null ? configJson : "{}");

            Iterator<String> iter = config.keys();
            while (iter.hasNext()) {
                String key = iter.next();
                switch (key) {
                    case "dpi": {
                        appConfig.setDpi(config.getInt(key));
                        break;
                    }
                    case "excludeRecent": {
                        appConfig.setExcludeRecent(config.getBoolean(key));
                        break;
                    }
                    case "smoothScroll": {
                        appConfig.setSmoothScroll(config.getBoolean(key));
                        break;
                    }
                    case "webDebug": {
                        appConfig.setWebDebug(config.getBoolean(key));
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return appConfig;
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam loadPackageParam) throws Throwable {
        if (!loadPackageParam.isFirstApplication) {
            return;
        }
        final XSharedPreferences xPrefs = getPrefs();
        final String packageName = loadPackageParam.packageName;
        final XposedExtension.AppConfig appConfig = getAppConfig(packageName);

        switch (packageName) {
            case "com.omarea.vtools":
            case "com.omarea.vboot":
                new ActiveCheck().isActive(loadPackageParam);
                break;
        }

        if (xPrefs.getBoolean("freeze_xposed_open", true)) {
            new AppFreezeInjector().appFreezeInject(loadPackageParam);
        }

        if (!"android".equals(packageName) && !"com.android.systemui".equals(packageName)) {
            if (appConfig.getSmoothScroll() || xPrefs.getBoolean("android_scroll", false)) {
                new ViewConfig().handleLoadPackage(loadPackageParam);
            }
        }

        if (appConfig.getExcludeRecent()) {
            new ExcludeRecent().handleLoadPackage(loadPackageParam);
        }

        if (appConfig.getWebDebug() || xPrefs.getBoolean("global_web_debug", false)) {
            new WebView().allowDebug();
        }

        final int dpi = appConfig.getDpi();
        if (dpi >= 96) {
            hookDpi(loadPackageParam, dpi);
        }
    }

    private void hookDpi(final XC_LoadPackage.LoadPackageParam loadPackageParam, final int dpi) {
        try {
            XposedHelpers.findAndHookMethod("android.app.Application", loadPackageParam.classLoader, "attach", Context.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Context context = (Context) param.args[0];
                        if (context == null) return;
                        Configuration origConfig = context.getResources().getConfiguration();
                        origConfig.densityDpi = dpi;
                        context.getResources().updateConfiguration(origConfig, context.getResources().getDisplayMetrics());
                        context.getResources().getDisplayMetrics().density = dpi / 160.0f;
                        context.getResources().getDisplayMetrics().densityDpi = dpi;
                        context.getResources().getDisplayMetrics().scaledDensity = dpi / 160.0f;
                    } catch (Throwable ex) {
                        XposedBridge.log(ex);
                    }
                }
            });
        } catch (Throwable ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(Display.class, "getMetrics", DisplayMetrics.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object mDisplayInfo = XposedHelpers.getObjectField(param.thisObject, "mDisplayInfo");
                        if (mDisplayInfo != null) {
                            XposedHelpers.setIntField(mDisplayInfo, "logicalDensityDpi", dpi);
                        }
                        DisplayMetrics displayMetrics = (DisplayMetrics) param.args[0];
                        if (displayMetrics != null) {
                            displayMetrics.scaledDensity = dpi / 160.0f;
                            displayMetrics.densityDpi = dpi;
                            displayMetrics.density = dpi / 160.0f;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod("android.content.res.ResourcesImpl", loadPackageParam.classLoader, "getDisplayMetrics", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        DisplayMetrics displayMetrics = (DisplayMetrics) getObjectField(param.thisObject, "mMetrics");
                        if (displayMetrics != null) {
                            displayMetrics.scaledDensity = dpi / 160.0f;
                            displayMetrics.densityDpi = dpi;
                            displayMetrics.density = dpi / 160.0f;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }

        try {
            XposedBridge.hookAllMethods(DisplayMetrics.class, "getDeviceDensity", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    param.setResult(dpi);
                }
            });
        } catch (Throwable ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod("android.app.Activity", loadPackageParam.classLoader, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Activity activity = (Activity) param.thisObject;
                        Resources resources = activity.getWindow().getDecorView().getResources();
                        DisplayMetrics displayMetrics = resources.getDisplayMetrics();

                        Configuration origConfig = resources.getConfiguration();
                        origConfig.densityDpi = dpi;
                        resources.updateConfiguration(origConfig, displayMetrics);

                        displayMetrics.density = dpi / 160.0f;
                        displayMetrics.densityDpi = dpi;
                        displayMetrics.scaledDensity = dpi / 160.0f;
                    } catch (Throwable ex) {
                        XposedBridge.log(ex);
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }
}
