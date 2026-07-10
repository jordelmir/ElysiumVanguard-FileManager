package com.elysium.vanguard.core.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * PHASE 3.11 — On-device OCR via ML Kit.
 *
 * Uses the Latin-script text recognizer (works for English, Spanish, French,
 * German, Italian, Portuguese, plus a long tail of Latin-alphabet languages).
 * For CJK we'd add a separate model (`text-recognition-chinese`, etc.) — those
 * live in separate artifacts and download on demand.
 *
 * Why ML Kit (vs. Tesseract):
 *   - Tesseract 5 ships its own native binary (~30 MB after lang data) and
 *     needs NDK build work. ML Kit is a Play Services dynamic module that
 *     downloads the model on first use, ~10 MB, and is auto-updated.
 *   - Tesseract accuracy for "OCR a phone photo of a contract" is actually
 *     slightly better, but ML Kit is fast enough on real devices and the
 *     dep cost is way lower. We can swap in Tesseract later if a user reports
 *     quality issues.
 */
@Singleton
class OcrEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Extract text from the image at [uri]. The URI must be readable by the
     * caller; the recognizer opens its own stream from it.
     */
    suspend fun extract(uri: Uri): OcrResult = suspendCancellableCoroutine { cont ->
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val image = InputImage.fromFilePath(context, uri)
            recognizer.process(image)
                .addOnSuccessListener { vision ->
                    cont.resume(OcrResult(
                        fullText = vision.text,
                        blocks = vision.textBlocks.map { block ->
                            Block(
                                text = block.text,
                                boundingBox = block.boundingBox,
                                lines = block.lines.map { line ->
                                    Line(
                                        text = line.text,
                                        boundingBox = line.boundingBox,
                                        confidence = line.confidence ?: 0f
                                    )
                                }
                            )
                        }
                    ))
                }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        } catch (e: Exception) {
            cont.resumeWithException(e)
        } finally {
            cont.invokeOnCancellation { recognizer.close() }
        }
    }

    data class OcrResult(
        val fullText: String,
        val blocks: List<Block>
    )

    data class Block(
        val text: String,
        val boundingBox: android.graphics.Rect?,
        val lines: List<Line>
    )

    data class Line(
        val text: String,
        val boundingBox: android.graphics.Rect?,
        val confidence: Float
    )
}