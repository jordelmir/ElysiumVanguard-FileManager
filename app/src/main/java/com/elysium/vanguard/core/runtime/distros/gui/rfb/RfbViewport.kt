package com.elysium.vanguard.core.runtime.distros.gui.rfb

/** Pure framebuffer-to-Surface geometry; independent from Android View APIs. */
internal data class RfbViewport(
    val framebufferWidth: Int,
    val framebufferHeight: Int,
    val surfaceWidth: Int,
    val surfaceHeight: Int
) {
    init {
        require(framebufferWidth > 0 && framebufferHeight > 0) { "framebuffer dimensions must be positive" }
        require(surfaceWidth > 0 && surfaceHeight > 0) { "surface dimensions must be positive" }
    }

    private val scale = minOf(
        surfaceWidth.toFloat() / framebufferWidth,
        surfaceHeight.toFloat() / framebufferHeight
    )
    val drawWidth: Float = framebufferWidth * scale
    val drawHeight: Float = framebufferHeight * scale
    val offsetX: Float = (surfaceWidth - drawWidth) / 2f
    val offsetY: Float = (surfaceHeight - drawHeight) / 2f

    /** Converts a touch coordinate only when it is inside the drawn framebuffer. */
    fun map(x: Float, y: Float): RfbPointer? {
        if (x < offsetX || y < offsetY || x >= offsetX + drawWidth || y >= offsetY + drawHeight) return null
        return RfbPointer(
            x = ((x - offsetX) / scale).toInt().coerceIn(0, framebufferWidth - 1),
            y = ((y - offsetY) / scale).toInt().coerceIn(0, framebufferHeight - 1)
        )
    }
}

internal data class RfbPointer(val x: Int, val y: Int)
