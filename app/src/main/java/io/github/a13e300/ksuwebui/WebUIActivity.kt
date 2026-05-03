package io.github.a13e300.ksuwebui

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.topjohnwu.superuser.nio.FileSystemManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("SetJavaScriptEnabled")
class WebUIActivity : ComponentActivity(), FileSystemService.Listener {
    
    private val webUIState = WebUIState()
    internal lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val webView = webUIState.webView
                if (webView?.canGoBack() == true) {
                    webView.goBack()
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })

        MonetColorsProvider.updateCss(this)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)

        val progressLayout = FrameLayout(this).apply {
            addView(CircularProgressIndicator(this@WebUIActivity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            })
        }
        setContentView(progressLayout)

        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val uris: Array<Uri>? = when (result.resultCode) {
                RESULT_OK -> result.data?.let { data ->
                    when {
                        data.clipData != null -> {
                            Array(data.clipData!!.itemCount) { i ->
                                data.clipData!!.getItemAt(i).uri // Multiple files
                            }
                        }
                        data.data != null -> { arrayOf(data.data!!) } // Single file
                        else -> null
                    }
                }
                else -> null
            }
            webUIState.filePathCallback?.onReceiveValue(uris)
            webUIState.filePathCallback = null
        }

        lifecycleScope.launch(Dispatchers.IO) {
            if (AppList.getApplist().isEmpty()) {
                AppList.getApps(this@WebUIActivity)
            }
            withContext(Dispatchers.Main) {
                if (prepareWebView(webUIState)) {
                    val container = FrameLayout(this@WebUIActivity)
                    setupWebUIScreen(webUIState, container)
                    updateTaskDescription()
                    FileSystemService.start(this@WebUIActivity)
                }
            }
        }
    }

    private fun updateTaskDescription() {
        val name = webUIState.moduleName
        if (name.isNotEmpty()) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                setTaskDescription(ActivityManager.TaskDescription(name))
            } else {
                val taskDescription = ActivityManager.TaskDescription.Builder().setLabel(name).build()
                setTaskDescription(taskDescription)
            }
        }
    }

    override fun onServiceAvailable(fs: FileSystemManager) {
        initWebView(fs, webUIState)
    }

    override fun onLaunchFailed() {
        Toast.makeText(this, R.string.please_grant_root, Toast.LENGTH_SHORT).show()
        finish()
    }

    fun enableEdgeToEdge(enable: Boolean = true) {
        runOnUiThread {
            if (enable) {
                (this as ComponentActivity).enableEdgeToEdge()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            webUIState.isEdgeToEdgeEnabled = enable
            val container = webUIState.webView?.parent as? android.view.View
            container?.let { ViewCompat.requestApplyInsets(it) }

            if (enable) {
                webUIState.webView?.evaluateJavascript(webUIState.insets.js, null)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        webUIState.webView?.settings?.textZoom = (newConfig.fontScale * 100).toInt()
        MonetColorsProvider.updateCss(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        FileSystemService.removeListener(this)
        webUIState.webView?.destroy()
    }
}
