package io.github.a13e300.ksuwebui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.topjohnwu.superuser.nio.FileSystemManager
import java.io.File
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

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
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var downloadFilename: String? = null

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

        fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val data = result.data
                var uris: Array<Uri>? = null
                data?.dataString?.let { uris = arrayOf(it.toUri()) }
                data?.clipData?.let { clipData ->
                    uris = Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                }
                filePathCallback?.onReceiveValue(uris)
                filePathCallback = null
            } else {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = null
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
                view?.evaluateJavascript("""
                    (function () {
                        document.addEventListener("click", async (e) => {
                            if (e.target.tagName !== "A" || !e.target.hasAttribute("download")) return;
                            const filename = e.target.getAttribute("download") || "download";
                            downloadInterface.setDownloadFilename(filename);

                            if (!e.target.href.startsWith("blob:")) return;
                            e.preventDefault();
                            const blob = await fetch(e.target.href).then((r) => r.blob());
                            const reader = new FileReader();
                            reader.onload = () => {
                                const temp = document.createElement("a");
                                temp.href = reader.result;
                                temp.download = filename;
                                temp.click();
                            };
                            reader.readAsDataURL(blob);
                        });
                    })();
                    """,null
                )
            }
        }
        webView?.apply {
            addJavascriptInterface(webviewInterface, "ksu")
            addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun setDownloadFilename(filename: String) {
                        downloadFilename = filename
                    }
                },
                "downloadInterface"
            )
            setWebViewClient(webViewClient)
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    this@WebUIActivity.filePathCallback = filePathCallback
                    val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                    }
                    if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    try {
                        fileChooserLauncher.launch(intent)
                    } catch (_: Exception) {
                        this@WebUIActivity.filePathCallback?.onReceiveValue(null)
                        this@WebUIActivity.filePathCallback = null
                        return false
                    }
                    return true
                }
            }
            setDownloadListener { url, _, contentDisposition, mimetype, _ ->
                if (url.startsWith("data:")) {
                    // Parse data URL
                    val parts = url.substring(5).split(",", limit = 2)
                    if (parts.size == 2) {
                        val header = parts[0]
                        val data = parts[1]
                        val mimeType = header.split(";")[0]
                        val base64 = header.contains("base64")
                        var filename = downloadFilename ?: "download"
                        if (contentDisposition.isNotEmpty()) {
                            filename = WebUIFileHelper.getFilename(contentDisposition)
                        }
                        if (!filename.contains(".")) {
                            val ext = MimeUtil.getExtensionFromMime(mimeType)
                            filename += ".$ext"
                        }
                        filename = WebUIFileHelper.sanitizeFilename(filename)
                        try {
                            when {
                                base64 -> WebUIFileHelper.saveContent(
                                    this@WebUIActivity,
                                    android.util.Base64.decode(
                                        data,
                                        android.util.Base64.DEFAULT
                                    ),
                                    filename
                                )
                                else -> WebUIFileHelper.saveContent(
                                    this@WebUIActivity,
                                    java.net.URLDecoder.decode(data, "UTF-8"),
                                    filename
                                )
                            }
                            downloadFilename = null
                        } catch (e: Exception) {
                            Toast.makeText(this@WebUIActivity, "Failed to decode data: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    // Use DownloadManager for regular URLs
                    val request = DownloadManager.Request(url.toUri())
                    if (mimetype.isNotEmpty()) {
                        request.setMimeType(mimetype)
                    }
                    if (contentDisposition.isNotEmpty()) {
                        request.setTitle(contentDisposition)
                    }
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    request.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        WebUIFileHelper.getFilename(contentDisposition)
                    )
                    val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                    dm.enqueue(request)
                    Toast.makeText(this@WebUIActivity, "Download started", Toast.LENGTH_SHORT).show()
                }
            }
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
