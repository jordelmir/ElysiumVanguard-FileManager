package com.elysium.vanguard.core.runtime.distros.gui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File

/**
 * PHASE 9.6.5 — VNC session abstraction.
 *
 * Real VNC requires `libvncclient.so` JNI-loaded; this stub renders
 * the *interface* so the rest of the app can wire its UI today and
 * we can drop in a real native implementation by changing a single
 * factory.
 *
 * The stub draws a placeholder frame: the distro's pretty name (or
 * generic "Linux desktop" when introspector finds nothing) plus the
 * VNC-style "1920x1080x32" card. Useful for visual debugging of the
 * window pipeline before proot launches.
 *
 * Phase 9.6.5 — first build; intentionally minimal.
 */
interface VncSession {
    val frameWidth: Int
    val frameHeight: Int
    fun captureFrame(): Bitmap
    fun close()
}

/**
 * PHASE 9.6.5 — Placeholder frame generator (no native lib required).
 *
 * Renders:
 *   - Background gradient (dark to slightly less dark).
 *   - Title text "[distro] · desktop ready".
 *   - Card outlining the would-be VNC frame (1920x1080 @ 32 bpp).
 *   - Status text "libvncclient.so pending — install to enable real VNC".
 */
class StubVncSession(
    private val distroDisplayName: String,
    private val width: Int = 1024,
    private val height: Int = 720
) : VncSession {
    override val frameWidth: Int = width
    override val frameHeight: Int = height

    override fun captureFrame(): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bg = Paint().apply {
            color = Color.parseColor("#FF0B0D10")
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        val stripe = Paint().apply {
            color = Color.parseColor("#FF1F2A1F")
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, width.toFloat(), (height / 4).toFloat(), stripe)
        val title = Paint().apply {
            color = Color.parseColor("#FFE4E7EB")
            textSize = 38f
            isAntiAlias = true
        }
        canvas.drawText(
            "$distroDisplayName · desktop ready",
            32f,
            96f,
            title
        )
        val subtitle = Paint().apply {
            color = Color.parseColor("#FF8B949E")
            textSize = 22f
            isAntiAlias = true
        }
        canvas.drawText(
            "1920x1080x32 · stub frame (libvncclient.so pending)",
            32f,
            144f,
            subtitle
        )
        val card = Paint().apply {
            color = Color.parseColor("#FF61AFEF")
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawRect(
            64f,
            192f,
            (width - 64).toFloat(),
            (height - 64).toFloat(),
            card
        )
        return bmp
    }

    override fun close() {
        // No-op for the stub; the real VNC session will release the
        // socket and the framebuffer pixel buffer.
    }
}

/**
 * PHASE 9.6.5 — VNC session factory.
 *
 * Returns the stub today; will switch to a native-backed
 * implementation when `libvncclient.so` lands. The factory exists so
 * the UI does not depend on the concrete class.
 */
object VncSessionFactory {
    fun forDistro(distroId: String, displayName: String, rootfsDir: File?): VncSession {
        val name = if (displayName.isNotBlank()) displayName else distroId
        return StubVncSession(distroDisplayName = name)
    }
}
