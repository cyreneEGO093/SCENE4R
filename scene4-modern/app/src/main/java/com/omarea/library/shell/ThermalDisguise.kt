package com.omarea.library.shell

import com.omarea.common.shell.KeepShellPublic

/**
 * Thermal preservation policy:
 * System thermal daemon (mi_thermald / vendor.thermal-hal / msm_thermal) is preserved.
 * Disabling thermal spoofing by default; resumeMessage() remains available to restore
 * permissions if an older Scene installation previously locked board_sensor_temp.
 */
class ThermalDisguise {
    private val boardSensorTemp = "/sys/class/thermal/thermal_message/board_sensor_temp"
    private val migtMaxFreq = "/sys/module/migt/parameters/glk_maxfreq"
    private val gameService = "com.xiaomi.gamecenter.sdk.service/.PidService"
    private val vtoolsStorage = "vtools.thermal.disguise"

    fun supported(): Boolean {
        // Thermal spoofing is intentionally disabled to preserve OEM temperature control
        return false
    }

    fun disableMessage() {
        // No-op: preserve OEM thermal protection
    }

    fun resumeMessage() {
        KeepShellPublic.doCmdSync(
            "chmod 644 $boardSensorTemp 2>/dev/null\n" +
                "chmod 644 $migtMaxFreq 2>/dev/null\n" +
                "pm enable $gameService 2>/dev/null\n" +
                "setprop $vtoolsStorage 0"
        )
    }

    fun isDisabled(): Boolean {
        return false
    }
}
