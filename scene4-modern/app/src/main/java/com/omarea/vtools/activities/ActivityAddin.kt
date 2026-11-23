package com.omarea.vtools.activities

import android.os.Bundle
import com.omarea.kr.KrScriptConfig
import com.omarea.vtools.R
import com.projectkr.shell.OpenPageHelper

/**
 * Legacy sundry tools entry preserved only as a redirect into the unified 附加功能 catalog.
 */
class ActivityAddin : ActivityBase() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val krScriptConfig = KrScriptConfig().init(this)
        krScriptConfig.pageListConfig?.let { page ->
            OpenPageHelper(this).openPage(page.apply {
                title = getString(R.string.menu_additional)
            })
        }
        finish()
    }
}
