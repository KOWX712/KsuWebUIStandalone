package io.github.a13e300.ksuwebui

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

@SuppressLint("SetJavaScriptEnabled")
fun WebUIActivity.setupWebUIScreen(state: WebUIState, container: FrameLayout) {
    container.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    
    state.webView = WebView(this).apply {
        setBackgroundColor(Color.TRANSPARENT)
        val density = resources.displayMetrics.density

        ViewCompat.setOnApplyWindowInsetsListener(container) { view, windowInsets ->
            val inset = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout() or
                        WindowInsetsCompat.Type.ime()
            )
            val newInsets = Insets(
                top = (inset.top / density).toInt(),
                bottom = (inset.bottom / density).toInt(),
                left = (inset.left / density).toInt(),
                right = (inset.right / density).toInt()
            )

            if (state.insets != newInsets) {
                state.insets = newInsets
                if (state.isEdgeToEdgeEnabled) {
                    evaluateJavascript(state.insets.js, null)
                }
            }

            if (state.isEdgeToEdgeEnabled) {
                view.setPadding(0, 0, 0, 0)
            } else {
                view.setPadding(inset.left, inset.top, inset.right, inset.bottom)
            }
            WindowInsetsCompat.CONSUMED
        }
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.textZoom = (resources.configuration.fontScale * 100).toInt()
    }
    container.addView(state.webView)
    setContentView(container)
}
