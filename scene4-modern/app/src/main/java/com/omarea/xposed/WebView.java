package com.omarea.xposed;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Created by helloklf on 2017/11/30.
 */

public class WebView {
    public void allowDebug() {
        try {
            XposedBridge.hookAllConstructors(android.webkit.WebView.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        XposedHelpers.callStaticMethod(android.webkit.WebView.class, "setWebContentsDebuggingEnabled", true);
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(android.webkit.WebView.class, "setWebContentsDebuggingEnabled", boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.args[0] = true;
                }
            });
        } catch (Throwable ignored) {
        }
    }
}
