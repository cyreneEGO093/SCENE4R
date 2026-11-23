package com.omarea.vtools.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.omarea.vtools.R
import com.omarea.vtools.activities.ActivityHiddenApps
import android.widget.Button

class FragmentAppHelp : androidx.fragment.app.Fragment() {
    private val app_btn_hide2: Button
        get() = requireView().findViewById(R.id.app_btn_hide2)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_app_help, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        app_btn_hide2.setOnClickListener {
            val intent = Intent(context, ActivityHiddenApps::class.java)
            startActivity(intent)
        }
    }
}
