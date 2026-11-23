package com.omarea.vtools.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.omarea.common.shell.KeepShellPublic
import com.omarea.permissions.CheckRootStatus
import com.omarea.vtools.R
import com.omarea.vtools.activities.ActivityStartSplash


class FragmentNotRoot : androidx.fragment.app.Fragment() {
    private val btn_retry: Button
        get() = requireView().findViewById(R.id.btn_retry)


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? =
            inflater.inflate(R.layout.fragment_not_root, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        btn_retry.setOnClickListener {
            val hostActivity = activity ?: return@setOnClickListener
            btn_retry.isEnabled = false
            KeepShellPublic.tryExit()
            CheckRootStatus(hostActivity, Runnable {
                val currentActivity = activity ?: return@Runnable
                if (currentActivity.isFinishing || currentActivity.isDestroyed) {
                    return@Runnable
                }
                ActivityStartSplash.finished = false
                val intent = Intent(currentActivity.applicationContext, ActivityStartSplash::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                currentActivity.startActivity(intent)
                currentActivity.finish()
            }, false, Runnable {
                if (isAdded && getView() != null) {
                    btn_retry.isEnabled = true
                }
            }).forceGetRoot()
        }
    }

    companion object {
        fun createPage(): androidx.fragment.app.Fragment {
            val fragment = FragmentNotRoot()
            return fragment
        }
    }
}
