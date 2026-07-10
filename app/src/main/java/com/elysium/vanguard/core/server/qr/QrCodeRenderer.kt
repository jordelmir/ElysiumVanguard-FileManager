package com.elysium.vanguard.core.server.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * PHASE 3.7 — QR code generator.
 *
 * Thin wrapper over ZXing core. We need QR generation only (the laptop's browser
 * scans the code via its built-in camera UI; we don't need an in-app scanner yet).
 * ZXing core is ~600 KB, well-tested, and the smallest reasonable QR dependency.
 *
 * The single function we expose is [renderBitmap] because all callers care about is
 * a Bitmap they can paint on screen. Higher-level screens (LocalServerScreen) own the
 * surrounding UI — sizing, padding, error states.
 */
object QrCodeRenderer {

    /**
     * Render [content] as a square [Bitmap] of side [sizePx] pixels.
     *
     * The bitmap is pure black on white with a 4-module quiet zone around the code
     * (the spec-mandated white border that scanners need to lock on).
     *
     * @param content the URL or text to encode. Keep it short — anything over ~1 KB
     *                will produce a dense code that laptops struggle with in low light.
     * @param sizePx output bitmap side length in pixels.
     * @param foreground dark modules color (default black).
     * @param background light modules + quiet zone color (default white).
     */
    fun renderBitmap(
        content: String,
        sizePx: Int,
        foreground: Int = Color.BLACK,
        background: Int = Color.WHITE
    ): Bitmap {
        require(sizePx > 0) { "sizePx must be > 0" }
        require(content.isNotEmpty()) { "content must not be empty" }

        val hints = mapOf<EncodeHintType, Any>(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2,  // quiet zone in modules
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )

        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            val rowOffset = y * sizePx
            for (x in 0 until sizePx) {
                pixels[rowOffset + x] = if (matrix[x, y]) foreground else background
            }
        }
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
        return bitmap
    }
}