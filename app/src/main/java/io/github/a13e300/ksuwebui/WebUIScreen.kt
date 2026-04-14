package io.github.a13e300.ksuwebui

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

@SuppressLint("SetJavaScriptEnabled")
fun WebUIActivity.setupWebUIScreen(state: WebUIState, container: FrameLayout) {
    container.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    
    state.webView = WebView(this).apply {
        setBackgroundColor(Color.TRANSPARENT)
        val density = resources.displayMetrics.density

        ViewCompat.setOnApplyWindowInsetsListener(container) { view, windowInsets ->
            val safeDrawing = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout() or
                        WindowInsetsCompat.Type.ime()
            )
            val systemBars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val layout = calculateWebUIInsetLayout(
                density = density,
                safeDrawing = safeDrawing.toPixelInsets(),
                systemBars = systemBars.toPixelInsets(),
                ime = ime.toPixelInsets(),
                isEdgeToEdgeEnabled = state.isEdgeToEdgeEnabled,
            )

            if (state.insets != layout.reportedInsets) {
                state.insets = layout.reportedInsets
                if (state.isEdgeToEdgeEnabled) {
                    evaluateJavascript(state.insets.js, null)
                }
            }

            view.setPadding(layout.padding.left, layout.padding.top, layout.padding.right, layout.padding.bottom)
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

private fun Insets.toPixelInsets() = PixelInsets(
    top = top,
    bottom = bottom,
    left = left,
    right = right,
)
