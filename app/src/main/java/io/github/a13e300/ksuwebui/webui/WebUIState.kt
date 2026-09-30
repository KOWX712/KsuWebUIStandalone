package io.github.a13e300.ksuwebui.webui

import android.webkit.WebView
import io.github.a13e300.ksuwebui.ui.Insets

class WebUIState {
    var webView: WebView? = null
    lateinit var moduleDir: String
    var moduleName: String = ""
    var insets: Insets = Insets(0, 0, 0, 0)
    var isEdgeToEdgeEnabled = false
    var filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>? = null
    var remoteUrl: String? = null
}
