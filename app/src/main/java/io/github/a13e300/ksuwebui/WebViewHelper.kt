package io.github.a13e300.ksuwebui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

private const val WEB_DOMAIN = "mui.kernelsu.org"
private const val KSU_SCHEME = "ksu"
private const val ICON_HOST = "icon"

fun WebUIActivity.prepareWebView(state: WebUIState): Boolean {
    val remoteUrl = intent.getStringExtra("url")
    if (remoteUrl != null) {
        state.remoteUrl = remoteUrl
        state.moduleName = remoteUrl
        return true
    }

    val moduleId = intent.getStringExtra("id")
    if (moduleId == null) {
        finish()
        return false
    }
    state.moduleName = intent.getStringExtra("name") ?: moduleId
    state.moduleDir = "/data/adb/modules/$moduleId"
    return true
}

fun WebUIActivity.initWebView(fs: FileSystemManager?, state: WebUIState) {
    val assetLoader = if (state.remoteUrl == null && fs != null) {
        val webRoot = File("${state.moduleDir}/webroot")
        WebViewAssetLoader.Builder()
            .setDomain(WEB_DOMAIN)
            .addPathHandler(
                "/",
                RemoteFsPathHandler(
                    this,
                    webRoot,
                    fs,
                    { state.insets },
                    { enable -> enableEdgeToEdge(enable) }
                )
            )
            .build()
    } else {
        null
    }

    state.webView?.apply {
        addJavascriptInterface(WebViewInterface(state), "ksu")
        setWebViewClient(createWebViewClient(state, assetLoader))
        webChromeClient = createWebChromeClient(state)
        loadUrl(state.remoteUrl ?: "https://$WEB_DOMAIN/index.html")
    }
}

private fun WebUIActivity.createWebViewClient(
    state: WebUIState,
    assetLoader: WebViewAssetLoader?,
): WebViewClient {
    return object : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            val url = request.url

            // Handle ksu://icon/[packageName] to serve app icon via WebView
            if (url.scheme.equals(KSU_SCHEME, ignoreCase = true) && url.host.equals(ICON_HOST, ignoreCase = true)) {
                val packageName = url.path?.substring(1)
                if (!packageName.isNullOrEmpty()) {
                    val icon = AppIconUtil.loadAppIconSync(this@createWebViewClient, packageName, 512)
                    if (icon != null) {
                        val stream = ByteArrayOutputStream()
                        icon.compress(Bitmap.CompressFormat.PNG, 100, stream)
                        return WebResourceResponse(
                            "image/png", null, 200, "OK",
                            mapOf("Access-Control-Allow-Origin" to "*"),
                            ByteArrayInputStream(stream.toByteArray())
                        )
                    }
                }
            }

            if (assetLoader != null) {
                return assetLoader.shouldInterceptRequest(url)
            }

            if (url.host == WEB_DOMAIN) {
                val path = url.path?.removePrefix("/") ?: ""
                if (path == "internal/insets.css") {
                    MonetColorsProvider.updateCss(this@createWebViewClient)
                    enableEdgeToEdge(true)
                    val css = state.insets.css
                    return WebResourceResponse(
                        "text/css", "utf-8",
                        ByteArrayInputStream(css.toByteArray(Charsets.UTF_8))
                    )
                }
                if (path == "internal/colors.css") {
                    val prefs = getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                    val enableMonet = prefs.getBoolean("enable_monet", true)
                    val css = if (enableMonet) MonetColorsProvider.getColorsCss() else ""
                    return WebResourceResponse(
                        "text/css", "utf-8",
                        ByteArrayInputStream(css.toByteArray(Charsets.UTF_8))
                    )
                }
            }

            return null
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            val prefs = getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            if (prefs.getBoolean("enable_web_debugging", BuildConfig.DEBUG)) {
                view?.evaluateJavascript(assets.open("eruda.min.js").bufferedReader().use { it.readText() }, null)
                view?.evaluateJavascript("eruda.init();", null)
            }
        }
    }
}

private fun WebUIActivity.createWebChromeClient(state: WebUIState): WebChromeClient {
    return object : WebChromeClient() {
        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            state.filePathCallback?.onReceiveValue(null)
            state.filePathCallback = filePathCallback
            val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
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
            MaterialAlertDialogBuilder(this@createWebChromeClient)
                .setTitle("Alert")
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                .setOnCancelListener { result?.cancel() }
                .show()
            return true
        }

        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            MaterialAlertDialogBuilder(this@createWebChromeClient)
                .setTitle("Confirm")
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                .setOnCancelListener { result?.cancel() }
                .show()
            return true
        }

        override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
            val input = EditText(this@createWebChromeClient).apply {
                if (defaultValue != null) setText(defaultValue)
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            input.layoutParams = lp
            val container = FrameLayout(this@createWebChromeClient)
            val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.leftMargin = resources.getDimensionPixelSize(androidx.appcompat.R.dimen.abc_dialog_padding_material)
            params.rightMargin = resources.getDimensionPixelSize(androidx.appcompat.R.dimen.abc_dialog_padding_material)
            input.layoutParams = params
            container.addView(input)

            MaterialAlertDialogBuilder(this@createWebChromeClient)
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
}
