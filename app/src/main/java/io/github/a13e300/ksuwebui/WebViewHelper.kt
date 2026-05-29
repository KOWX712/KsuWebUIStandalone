package io.github.a13e300.ksuwebui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.webkit.WebViewAssetLoader
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.topjohnwu.superuser.nio.FileSystemManager
import java.io.File


private const val WEB_DOMAIN = "mui.kernelsu.org"
private const val KSU_SCHEME = "ksu"
private const val ICON_HOST = "icon"
private const val DOWNLOAD_JS = """
    (function() {
        if (window.ksu_download_enabled) return;
        window.ksu_download_enabled = true;
        const blobMap = new Map();
        const orgCreate = URL.createObjectURL;
        URL.createObjectURL = (obj) => {
            const url = orgCreate(obj);
            if (obj instanceof Blob) blobMap.set(url, obj);
            return url;
        };
        const orgRevoke = URL.revokeObjectURL;
        URL.revokeObjectURL = (url) => {
            setTimeout(() => blobMap.delete(url), 10000);
            orgRevoke(url);
        };
        const handleDownload = async (a) => {
            const urlParsed = new URL(a.href, location.href);
            const url = urlParsed.href;
            const fileName = a.download || url.split("/").pop().split("?")[0] || "download.bin";
            const isInternal = urlParsed.hostname === 'mui.kernelsu.org';
            if (url.startsWith('blob:') || url.startsWith('data:') || isInternal) {
                const blob = (url.startsWith('blob:') && blobMap.has(url)) ? blobMap.get(url) : await (await fetch(url)).blob();
                if (blob.size > 16 * 1024 * 1024) {
                    console.error("File too large, please use FileOutputStreamInterface instead.");
                    return;
                }
                const reader = new FileReader();
                reader.onload = () => {
                    ksu_download.save(reader.result.split(',')[1], fileName, blob.type);
                };
                reader.readAsDataURL(blob);
            } else {
                ksu_download.download(url, null, null);
            }
        };
        document.addEventListener("click", (e) => {
            const a = e.target.closest("a[download]");
            if (a) {
                e.preventDefault();
                handleDownload(a);
            }
        }, true);
        const orgClick = HTMLAnchorElement.prototype.click;
        HTMLAnchorElement.prototype.click = function () {
            this.hasAttribute("download") ? handleDownload(this) : orgClick.apply(this, arguments);
        };
    })();
"""

fun WebUIActivity.prepareWebView(state: WebUIState): Boolean {
    val moduleId = intent.getStringExtra("id")
    if (moduleId == null) {
        finish()
        return false
    }
    state.moduleName = intent.getStringExtra("name") ?: moduleId
    state.moduleDir = "/data/adb/modules/$moduleId"
    return true
}

fun WebUIActivity.initWebView(fs: FileSystemManager, state: WebUIState) {
    val webRoot = File("${state.moduleDir}/webroot")
    val webViewAssetLoader = WebViewAssetLoader.Builder()
        .setDomain("mui.kernelsu.org")
        .addPathHandler(
            "/",
            RemoteFsPathHandler(
                this,
                webRoot,
                fs,
                { state.insets },
                { enable ->
                    enableEdgeToEdge(enable) 
                }
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
                    val icon = AppIconUtil.loadAppIconSync(this@initWebView, packageName, 512)
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
            val prefs = getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            if (prefs.getBoolean("enable_web_debugging", BuildConfig.DEBUG)) {
                view?.evaluateJavascript(assets.open("eruda.min.js").bufferedReader().use { it.readText() }, null)
                view?.evaluateJavascript("eruda.init();", null)
            }
            view?.evaluateJavascript(DOWNLOAD_JS, null)
        }
    }

    state.webView?.apply {
        val webviewInterface = WebViewInterface(state)
        state.webviewInterface = webviewInterface
        addJavascriptInterface(webviewInterface, "ksu")

        val downloadInterface = DownloadInterface(state)
        addJavascriptInterface(downloadInterface, "ksu_download")

        setDownloadListener { url, _, contentDisposition, mimetype, _ ->
            downloadInterface.download(url, contentDisposition, mimetype)
        }

        setWebViewClient(webViewClient)
        webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                state.filePathCallback?.onReceiveValue(null)
                state.filePathCallback = filePathCallback
                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }

                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                    if (intent.type?.startsWith(".") == true) {
                        intent.type = "*/*"
                    }
                }

                if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                try {
                    fileChooserLauncher.launch(intent)
                } catch (_: ActivityNotFoundException) {
                    state.filePathCallback?.onReceiveValue(null)
                    state.filePathCallback = null
                    return false
                }
                return true
            }

            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                MaterialAlertDialogBuilder(this@initWebView)
                    .setTitle("Alert")
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                MaterialAlertDialogBuilder(this@initWebView)
                    .setTitle("Confirm")
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
                val input = EditText(this@initWebView).apply {
                    if (defaultValue != null) setText(defaultValue)
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                input.layoutParams = lp
                val container = FrameLayout(this@initWebView)
                val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                params.leftMargin = resources.getDimensionPixelSize(androidx.appcompat.R.dimen.abc_dialog_padding_material)
                params.rightMargin = resources.getDimensionPixelSize(androidx.appcompat.R.dimen.abc_dialog_padding_material)
                input.layoutParams = params
                container.addView(input)

                MaterialAlertDialogBuilder(this@initWebView)
                    .setTitle("Prompt")
                    .setMessage(message)
                    .setView(container)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm(input.text.toString()) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }
        }
        loadUrl("https://mui.kernelsu.org/index.html")
    }
}
