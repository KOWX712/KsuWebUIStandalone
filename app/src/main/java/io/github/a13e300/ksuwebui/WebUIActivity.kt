package io.github.a13e300.ksuwebui

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.topjohnwu.superuser.nio.FileSystemManager
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File

@SuppressLint("SetJavaScriptEnabled")
class WebUIActivity : ComponentActivity(), FileSystemService.Listener {
    private lateinit var webviewInterface: WebViewInterface
    private var webView: WebView? = null
    private lateinit var container: FrameLayout
    private lateinit var insets: Insets
    private var insetsContinuation: CancellableContinuation<Unit>? = null
    private var isInsetsEnabled = false
    private lateinit var moduleDir: String
    private var enableWebDebugging = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Enable edge to edge
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        super.onCreate(savedInstanceState)

        MonetColorsProvider.updateCss(this)

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

        lifecycleScope.launch(Dispatchers.IO) {
            if (AppList.getApplist().isEmpty()) {
                AppList.getApps(this@WebUIActivity)
            }
            withContext(Dispatchers.Main) {
                setupWebView()
            }
        }
    }

    private fun erudaConsole(context: Context): String {
        return context.assets.open("eruda.min.js").bufferedReader().use { it.readText() }
    }

    private suspend fun setupWebView() {
        val moduleId = intent.getStringExtra("id")
        if (moduleId == null) {
            finish()
            return
        }
        val name = intent.getStringExtra("name") ?: moduleId
        if (name.isNotEmpty()) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                setTaskDescription(ActivityManager.TaskDescription(name))
            } else {
                val taskDescription = ActivityManager.TaskDescription.Builder().setLabel(name).build()
                setTaskDescription(taskDescription)
            }
        }

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        enableWebDebugging = prefs.getBoolean("enable_web_debugging", BuildConfig.DEBUG)
        WebView.setWebContentsDebuggingEnabled(enableWebDebugging)

        moduleDir = "/data/adb/modules/$moduleId"
        insets = Insets(0, 0, 0, 0)

        container = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        this.webView = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            val density = resources.displayMetrics.density

            ViewCompat.setOnApplyWindowInsetsListener(container) { view, windowInsets ->
                val inset = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                insets = Insets(
                    top = (inset.top / density).toInt(),
                    bottom = (inset.bottom / density).toInt(),
                    left = (inset.left / density).toInt(),
                    right = (inset.right / density).toInt()
                )
                if (isInsetsEnabled) {
                    view.setPadding(0, 0, 0, 0)
                } else {
                    view.setPadding(inset.left, inset.top, inset.right, inset.bottom)
                }
                insetsContinuation?.resumeWith(Result.success(Unit))
                insetsContinuation = null
                WindowInsetsCompat.CONSUMED
            }
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            webviewInterface = WebViewInterface(this@WebUIActivity, this, moduleDir)
        }
        container.addView(this.webView)
        setContentView(container)

        if (insets == Insets(0, 0, 0, 0)) {
            suspendCancellableCoroutine { cont ->
                insetsContinuation = cont
                cont.invokeOnCancellation {
                    insetsContinuation = null
                }
            }
        }

        FileSystemService.start(this)
    }

    private fun setupWebview(fs: FileSystemManager) {
        val webRoot = File("$moduleDir/webroot")
        val webViewAssetLoader = WebViewAssetLoader.Builder()
            .setDomain("mui.kernelsu.org")
            .addPathHandler(
                "/",
                RemoteFsPathHandler(
                    this,
                    webRoot,
                    fs,
                    { insets },
                    { enable -> enableInsets(enable) }
                )
            )
            .build()
        val webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url

                // Handle ksu://icon/[packageName] to serve app icon via WebView
                if (url.scheme.equals("ksu", ignoreCase = true) && url.host.equals("icon", ignoreCase = true)) {
                    val packageName = url.path?.substring(1)
                    if (!packageName.isNullOrEmpty()) {
                        val icon = AppIconUtil.loadAppIconSync(this@WebUIActivity, packageName, 512)
                        if (icon != null) {
                            val stream = java.io.ByteArrayOutputStream()
                            icon.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
                            val inputStream = java.io.ByteArrayInputStream(stream.toByteArray())
                            return WebResourceResponse("image/png", null, inputStream)
                        }
                    }
                }

                return webViewAssetLoader.shouldInterceptRequest(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (enableWebDebugging) {
                    view?.evaluateJavascript(erudaConsole(this@WebUIActivity), null)
                    view?.evaluateJavascript("eruda.init();", null)
                }
            }
        }
        webView?.apply {
            addJavascriptInterface(webviewInterface, "ksu")
            setWebViewClient(webViewClient)
            loadUrl("https://mui.kernelsu.org/index.html")
        }
    }

    override fun onServiceAvailable(fs: FileSystemManager) {
        setupWebview(fs)
    }

    override fun onLaunchFailed() {
        Toast.makeText(this, R.string.please_grant_root, Toast.LENGTH_SHORT).show()
        finish()
    }

    fun enableInsets(enable: Boolean = true) {
        runOnUiThread {
            if (isInsetsEnabled != enable) {
                isInsetsEnabled = enable
                ViewCompat.requestApplyInsets(container)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        FileSystemService.removeListener(this)
    }
}
