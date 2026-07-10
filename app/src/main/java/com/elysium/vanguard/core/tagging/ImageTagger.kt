package com.elysium.vanguard.core.tagging

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * PHASE 3.10 — On-device image labeling via ML Kit.
 *
 * Returns a list of labels (e.g. "beach", "sunset", "outdoor recreation") for
 * an image, each with a confidence score. We then sanitize and merge them
 * with the existing [FileMetadataEntity.tags] column.
 *
 * Confidence threshold: we drop anything below 0.6 to avoid noise like
 * "indoor" tagging on a sunset. The threshold is a tunable parameter; for
 * the user's "auto-organize my photos" flow we want high precision over
 * recall — false positives in tags are more annoying than false negatives.
 */
@Singleton
class ImageTagger @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Classify the image at [uri] and return labels above [minConfidence].
     * Sorted descending by confidence.
     */
    suspend fun tag(uri: Uri, minConfidence: Float = 0.6f, maxLabels: Int = 12): List<Tag> =
        suspendCancellableCoroutine { cont ->
            val labeler: ImageLabeler = ImageLabeling.getClient(
                ImageLabelerOptions.Builder()
                    .setConfidenceThreshold(minConfidence)
                    .build()
            )
            try {
                val image = InputImage.fromFilePath(context, uri)
                labeler.process(image)
                    .addOnSuccessListener { labels ->
                        cont.resume(
                            labels.take(maxLabels).map { Tag(it.text, it.confidence ?: 0f) }
                        )
                    }
                    .addOnFailureListener { e -> cont.resumeWithException(e) }
            } catch (e: Exception) {
                cont.resumeWithException(e)
            } finally {
                cont.invokeOnCancellation { labeler.close() }
            }
        }

    data class Tag(val label: String, val confidence: Float)
}