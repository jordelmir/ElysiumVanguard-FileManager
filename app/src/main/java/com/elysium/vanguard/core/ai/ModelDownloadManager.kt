package com.elysium.vanguard.core.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val progress: Float) : DownloadState()
    object Completed : DownloadState()
    data class Error(val message: String) : DownloadState()
}

@Singleton
class ModelDownloadManager @Inject constructor(
    // context provided via function calls or if needed injected @ApplicationContext
) {
    private val modelFileName = "gemma-2b-it-gpu-int4.bin"

    fun isModelAvailable(context: Context): Boolean {
        val file = File(context.filesDir, modelFileName)
        // Check size to ensure it's not a dummy 0-byte file from failed download
        return file.exists() && file.length() > 1024 * 1024 // At least 1MB
    }

    fun downloadModel(context: Context): Flow<DownloadState> = flow {
        val file = File(context.filesDir, modelFileName)
        if (isModelAvailable(context)) {
            emit(DownloadState.Completed)
            return@flow
        }

        emit(DownloadState.Downloading(0f))

        try {
            // SIMULATION: writes a minimal model stub file
            // to demonstrate the download UI flow. The real
            // model is fetched from a secure server in
            // production; this stub is identifiable by the
            // ELYS header and metadata block.
            val header = "ELYS".toByteArray(Charsets.UTF_8)
            val version = byteArrayOf(1, 0, 0, 0)
            val metaJson = """{"model":"gemma-2b-it-gpu-int4","simulated":true}""".toByteArray(Charsets.UTF_8)
            val metaLen = byteArrayOf(
                ((metaJson.size shr 0) and 0xFF).toByte(),
                ((metaJson.size shr 8) and 0xFF).toByte(),
                ((metaJson.size shr 16) and 0xFF).toByte(),
                ((metaJson.size shr 24) and 0xFF).toByte(),
            )

            FileOutputStream(file).use { output ->
                // Phase 1: header (simulated network delay)
                delay(200)
                output.write(header)
                output.write(version)
                output.write(metaLen)
                output.write(metaJson)
                emit(DownloadState.Downloading(0.1f))

                // Phase 2: model weights (simulated chunk transfer)
                val chunkSize = 64 * 1024 // 64KB chunks
                val chunk = ByteArray(chunkSize)
                // Fill with a deterministic pattern instead of zeros
                for (i in chunk.indices) chunk[i] = (i % 256).toByte()
                val totalChunks = 16 // ~1MB total
                for (i in 1..totalChunks) {
                    delay(100)
                    output.write(chunk)
                    emit(DownloadState.Downloading(0.1f + 0.9f * i / totalChunks))
                }
            }

            emit(DownloadState.Completed)

        } catch (e: Exception) {
            emit(DownloadState.Error("Download Failed: ${e.message}"))
            // Clean up partial file
            if (file.exists()) file.delete()
        }
    }.flowOn(Dispatchers.IO)
}
