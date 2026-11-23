package com.omarea.xposed;

import android.app.Activity;
import android.app.ActivityManager;
import android.os.Bundle;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static android.content.Context.ACTIVITY_SERVICE;

public class ExcludeRecent {
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam loadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "android.app.Activity",
                    loadPackageParam.classLoader,
                    "onCreate",
                    Bundle.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Activity activity = (Activity) param.thisObject;
                                if (activity == null) return;
                                ActivityManager service = (ActivityManager) activity.getSystemService(ACTIVITY_SERVICE);
                                if (service == null) return;
                                for (ActivityManager.AppTask task : service.getAppTasks()) {
                                    int id = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
                                            ? task.getTaskInfo().taskId
                                            : task.getTaskInfo().id;
                                    if (id == activity.getTaskId()) {
                                        task.setExcludeFromRecents(true);
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        } catch (Throwable ignored) {
        }
    }
}
