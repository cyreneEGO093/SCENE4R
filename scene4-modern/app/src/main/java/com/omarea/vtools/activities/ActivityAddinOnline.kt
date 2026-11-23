package com.omarea.vtools.activities

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowInsetsControllerCompat
import com.omarea.common.ui.DialogHelper
import com.omarea.common.ui.ProgressBarDialog
import com.omarea.vtools.R

class ActivityAddinOnline : ActivityBase() {
    private val vtools_online: WebView
        get() = findViewById(R.id.vtools_online)

    override fun onPostResume() {
        super.onPostResume()
        delegate.onPostResume()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_addin_online)

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            controller.isAppearanceLightNavigationBars = true
        }

        val initialUrl = intent?.extras?.getString("url")
            ?.takeIf { it.startsWith("https://") }
            ?: "https://helloklf.github.io/vtools-online.html#/scripts"
        vtools_online.loadUrl(initialUrl)

        val context = this@ActivityAddinOnline
        val progressBarDialog = ProgressBarDialog(context)

        vtools_online.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                DialogHelper.animDialog(
                    AlertDialog.Builder(context)
                        .setMessage(message)
                        .setPositiveButton(R.string.btn_confirm) { _, _ -> }
                        .setOnDismissListener { result?.confirm() }
                        .create()
                )?.setCancelable(false)
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                DialogHelper.animDialog(
                    AlertDialog.Builder(context)
                        .setMessage(message)
                        .setPositiveButton(R.string.btn_confirm) { _, _ -> result?.confirm() }
                        .setNeutralButton(R.string.btn_cancel) { _, _ -> result?.cancel() }
                        .create()
                )?.setCancelable(false)
                return true
            }
        }

        vtools_online.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBarDialog.hideDialog()
                view?.let { title = it.title }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBarDialog.showDialog(getString(R.string.please_wait))
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (!url.startsWith("https://")) {
                    Toast.makeText(context, "已拦截非 HTTPS 页面跳转", Toast.LENGTH_SHORT).show()
                    return true
                }
                return false
            }
        }

        vtools_online.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        // Network WebView: no KrScriptCore JS->root bridge and no remote shell download auto-install
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (vtools_online.canGoBack()) {
                    vtools_online.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onDestroy() {
        vtools_online.clearCache(true)
        vtools_online.removeAllViews()
        vtools_online.destroy()
        super.onDestroy()
    }
}
