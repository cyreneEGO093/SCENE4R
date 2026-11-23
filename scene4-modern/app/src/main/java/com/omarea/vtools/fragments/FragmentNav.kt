package com.omarea.vtools.fragments

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.omarea.Scene
import com.omarea.common.ui.DialogHelper
import com.omarea.common.ui.ThemeMode
import com.omarea.kr.KrScriptConfig
import com.omarea.library.shell.BatteryUtils
import com.omarea.permissions.CheckRootStatus
import com.omarea.utils.AccessibleServiceHelper
import com.omarea.vtools.R
import com.omarea.store.XposedExtension
import com.omarea.vtools.activities.*
import com.omarea.vtools.dialogs.DialogXposedGlobalConfig
import com.projectkr.shell.OpenPageHelper
import com.omarea.ui.NavItem

class FragmentNav : Fragment(), View.OnClickListener {
    private val nav: LinearLayout
        get() = requireView().findViewById(R.id.nav)
    private val nav_additional_all: NavItem
        get() = requireView().findViewById(R.id.nav_additional_all)
    private val nav_app_magisk: NavItem
        get() = requireView().findViewById(R.id.nav_app_magisk)
    private val nav_app_scene: NavItem
        get() = requireView().findViewById(R.id.nav_app_scene)
    private val nav_applictions: NavItem
        get() = requireView().findViewById(R.id.nav_applictions)
    private val nav_auto_click: NavItem
        get() = requireView().findViewById(R.id.nav_auto_click)
    private val nav_battery: NavItem
        get() = requireView().findViewById(R.id.nav_battery)
    private val nav_battery_stats: NavItem
        get() = requireView().findViewById(R.id.nav_battery_stats)
    private val nav_charge: NavItem
        get() = requireView().findViewById(R.id.nav_charge)
    private val nav_core_control: NavItem
        get() = requireView().findViewById(R.id.nav_core_control)
    private val nav_fps_chart: NavItem
        get() = requireView().findViewById(R.id.nav_fps_chart)
    private val nav_freeze: NavItem
        get() = requireView().findViewById(R.id.nav_freeze)
    private val nav_processes: NavItem
        get() = requireView().findViewById(R.id.nav_processes)
    private val nav_scene_service_not_active: LinearLayout
        get() = requireView().findViewById(R.id.nav_scene_service_not_active)
    private val nav_swap: NavItem
        get() = requireView().findViewById(R.id.nav_swap)
    private val nav_system_scene: NavItem
        get() = requireView().findViewById(R.id.nav_system_scene)
    private val nav_xposed_app: NavItem
        get() = requireView().findViewById(R.id.nav_xposed_app)
    private val nav_xposed_global: NavItem
        get() = requireView().findViewById(R.id.nav_xposed_global)

    private lateinit var themeMode: ThemeMode

    companion object {
        fun createPage(themeMode: ThemeMode): Fragment {
            val fragment = FragmentNav()
            fragment.themeMode = themeMode;
            return fragment
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? =
            inflater.inflate(R.layout.fragment_nav, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val nav = view.findViewById<LinearLayout>(R.id.nav)
        for (index in 1..nav.childCount) {
            val ele = nav.getChildAt(index)
            if (ele is GridLayout) {
                for (index2 in 0 until ele.childCount) {
                    bindClickEvent(ele.getChildAt(index2))
                }
            }
        }

        // 激活辅助服务按钮
        nav_scene_service_not_active.setOnClickListener {
            startService()
        }
    }

    private fun startService() {
        AccessibleServiceHelper().stopSceneModeService(activity!!.applicationContext)

        /* 使用ROOT权限激活辅助服务会导致某些授权拿不到，导致事件触发不完整 */
        /*

        val dialog = ProgressBarDialog(context!!)
        dialog.showDialog("尝试使用ROOT权限开启服务...")
        Thread(Runnable {
            if (!AccessibleServiceHelper().startSceneModeService(context!!)) {
                try {
                    myHandler.post {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        // intent.addFlags(Intent.FLAG_ACTIVITY_TASK_ON_HOME)
                        startActivity(intent)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    myHandler.post {
                        dialog.hideDialog()
                    }
                }
            } else {
                myHandler.post {
                    dialog.hideDialog()
                    btn_config_service_not_active.visibility = if (AccessibleServiceHelper().serviceRunning(context!!)) View.GONE else View.VISIBLE
                }
            }
        }).start()
        */
        Scene.toast("请在系统设置里激活[SCENE4R - 场景模式]选项", Toast.LENGTH_SHORT)
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // intent.addFlags(Intent.FLAG_ACTIVITY_TASK_ON_HOME)
            startActivity(intent)
        } catch (e: Exception) {
        }
    }

    private fun bindClickEvent(view: View) {
        view.setOnClickListener(this)
        if ("root" == view.tag) {
            view.isEnabled = CheckRootStatus.lastCheckResult
        }
    }

    private fun syncRootEnabledState() {
        val rootView = view?.findViewById<LinearLayout>(R.id.nav) ?: return
        for (index in 0 until rootView.childCount) {
            val ele = rootView.getChildAt(index)
            if (ele is GridLayout) {
                for (index2 in 0 until ele.childCount) {
                    val child = ele.getChildAt(index2)
                    if ("root" == child.tag) {
                        child.isEnabled = CheckRootStatus.lastCheckResult
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isDetached) {
            return
        }

        syncRootEnabledState()

        // 辅助服务激活状态
        val serviceState = AccessibleServiceHelper().serviceRunning(context!!)
        nav_scene_service_not_active.visibility = if (serviceState) View.GONE else View.VISIBLE

        activity!!.title = getString(R.string.app_name)
    }

    override fun onClick(v: View?) {
        v?.run {
            if (!CheckRootStatus.lastCheckResult && "root".equals(getTag())) {
                Toast.makeText(context, "没有获得ROOT权限，不能使用本功能", Toast.LENGTH_SHORT).show()
                return
            }

            when (id) {
                R.id.nav_freeze -> {
                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.setClassName("com.omarea.vtools", "com.omarea.vtools.activities.ActivityFreezeApps2")
                    startActivity(intent)
                    return
                }
                R.id.nav_applictions -> {
                    val intent = Intent(context, ActivityApplistions::class.java)
                    startActivity(intent)
                    return
                }
                R.id.nav_swap -> {
                    val intent = Intent(context, ActivitySwap::class.java)
                    startActivity(intent)
                    return
                }
                R.id.nav_battery -> {
                    val batteryUtils = BatteryUtils()
                    if (batteryUtils.qcSettingSupport() || batteryUtils.bpSettingSupport()) {
                        val intent = Intent(context, ActivityBattery::class.java)
                        startActivity(intent)
                    } else {
                        Toast.makeText(context, "此功能不支持你的手机", Toast.LENGTH_SHORT).show()
                    }
                    return
                }
                R.id.nav_charge -> {
                    val intent = Intent(context, ActivityCharge::class.java)
                    startActivity(intent)
                    return
                }
                R.id.nav_battery_stats -> {
                    val intent = Intent(context, ActivityBatteryStats::class.java)
                    startActivity(intent)
                    return
                }
                R.id.nav_core_control -> {
                    val intent = Intent(context, ActivityCpuControl::class.java)
                    startActivity(intent)
                    return
                }
                R.id.nav_app_scene -> {
                    val intent = Intent(context, ActivityAppConfig2::class.java)
                    startActivity(intent)
                    return
                }
                R.id.nav_system_scene -> {
                    val intent = Intent(context, ActivitySystemScene::class.java)
                    startActivity(intent)
                    return
                }
                R.id.nav_auto_click -> {
                    val intent = Intent(context, ActivityAutoClick::class.java)
                    startActivity(intent)
                    return
                }
                R.id.nav_app_magisk -> {
                    val intent = Intent(context, ActivityMagisk::class.java)
                    startActivity(intent)
                    return
                }
                R.id.nav_xposed_app -> {
                    xposedCheck {
                        val intent = Intent(context, ActivityAppXposedConfig::class.java)
                        startActivity(intent)
                    }
                    return
                }
                R.id.nav_xposed_global -> {
                    xposedCheck {
                        DialogXposedGlobalConfig(activity!!).show()
                    }
                    return
                }
                R.id.nav_processes -> {
                    val intent = Intent(context, ActivityProcess::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    return
                }
                R.id.nav_fps_chart -> {
                    val serviceState = AccessibleServiceHelper().serviceRunning(context!!)
                    if (serviceState) {
                        val intent = Intent(context, ActivityFpsChart::class.java)
                        startActivity(intent)
                    } else {
                        Scene.toast("请在系统设置里激活[SCENE4R - 场景模式]辅助服务", Toast.LENGTH_SHORT)
                    }
                    return
                }
                R.id.nav_additional_all -> {
                    val krScriptConfig = KrScriptConfig().init(context!!)
                    val activity = activity!!
                    krScriptConfig.pageListConfig?.run {
                        OpenPageHelper(activity).openPage(this.apply {
                            title = getString(R.string.menu_additional)
                        })
                    }
                    return
                }
            }
        }
    }

    private fun xposedCheck(onPass: Runnable) {
        if (XposedExtension(requireContext()).isAvailable()) {
            onPass.run()
        } else {
            DialogHelper.alert(
                requireContext(),
                "内置 Xposed 模块未激活",
                "高级设定已直接内置于 SCENE4R（无需额外下载安装插件）。\n\n请在 LSPosed / Vector 管理器中启用“SCENE4R”，勾选需要生效的目标应用作用域，并重启设备或重启应用后即可使用。"
            )
        }
    }
}
