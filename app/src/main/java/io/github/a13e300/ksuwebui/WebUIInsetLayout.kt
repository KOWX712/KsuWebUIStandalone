package io.github.a13e300.ksuwebui

data class PixelInsets(
    val top: Int,
    val bottom: Int,
    val left: Int,
    val right: Int,
)

data class WebUIInsetLayout(
    val reportedInsets: Insets,
    val padding: PixelInsets,
)

fun calculateWebUIInsetLayout(
    density: Float,
    safeDrawing: PixelInsets,
    systemBars: PixelInsets,
    ime: PixelInsets,
    isEdgeToEdgeEnabled: Boolean,
): WebUIInsetLayout {
    require(density > 0f) { "density must be positive" }

    return WebUIInsetLayout(
        reportedInsets = Insets(
            top = (systemBars.top / density).toInt(),
            bottom = (systemBars.bottom / density).toInt(),
            left = (systemBars.left / density).toInt(),
            right = (systemBars.right / density).toInt(),
        ),
        padding = if (isEdgeToEdgeEnabled) ime else safeDrawing,
    )
}
