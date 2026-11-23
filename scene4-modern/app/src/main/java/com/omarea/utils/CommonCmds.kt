package com.omarea.utils

import android.os.Environment

/**
 * Created by Hello on 2017/2/22.
 */

object CommonCmds {
    val SDCardDir: String = Environment.getExternalStorageDirectory().absolutePath

    val AbsBackUpDir = "$SDCardDir/backups/apps/"

    const val MountSystemRW =
            "busybox mount -o rw,remount / 2>/dev/null\n" +
                    "mount -o rw,remount /system 2>/dev/null\n" +
                    "busybox mount -o rw,remount / 2>/dev/null\n" +
                    "mount -o rw,remount /system 2>/dev/null\n" +
                    "busybox mount -o remount,rw /dev/block/bootdevice/by-name/system /system 2>/dev/null\n" +
                    "mount -o remount,rw /dev/block/bootdevice/by-name/system /system 2>/dev/null\n" +
                    "busybox mount -o rw,remount /vendor 2>/dev/null\n" +
                    "mount -o rw,remount /vendor 2>/dev/null\n"

    // Security hardening: never disable SELinux globally (setenforce 0 removed).
    const val ResumeSELinux = "setenforce 1;\n"
}
