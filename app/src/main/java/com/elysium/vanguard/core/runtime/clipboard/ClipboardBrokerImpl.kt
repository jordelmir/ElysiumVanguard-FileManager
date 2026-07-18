package com.elysium.vanguard.core.runtime.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

class ClipboardBrokerImpl(
    private val appContext: Context,
    private val maxTextSize: Int = MAX_TEXT_SIZE,
    private val maxImageSize: Int = MAX_IMAGE_SIZE
) : ClipboardBroker, Closeable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<ClipboardBrokerState>(ClipboardBrokerState.Idle)
    override val state: StateFlow<ClipboardBrokerState> = _state.asStateFlow()

    private val _policy = MutableStateFlow(ClipboardPolicy.TEXT_ONLY)
    override val policy: StateFlow<ClipboardPolicy> = _policy.asStateFlow()

    private val _lastAccess = MutableStateFlow<ClipboardAccessEvent?>(null)
    override val lastAccess: StateFlow<ClipboardAccessEvent?> = _lastAccess.asStateFlow()

    private val sessionPolicies = ConcurrentHashMap<String, ClipboardPolicy>()
    private val guestClipboards = ConcurrentHashMap<String, String>()

    private val androidClipboard: ClipboardManager? =
        appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    private var primaryClipListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    private val previousClipData = AtomicReference<ClipData?>(null)

    override suspend fun setPolicy(sessionId: String, policy: ClipboardPolicy): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (sessionId.isBlank()) {
                return@withContext Result.failure(
                    IllegalArgumentException("sessionId must not be blank")
                )
            }
            sessionPolicies[sessionId] = policy
            val resolved = resolveEffectivePolicy()
            _policy.value = resolved
            _state.value = ClipboardBrokerState.Active
            reattachClipboardListener(resolved)
            Result.success(Unit)
        }

    override suspend fun pushText(sessionId: String, text: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val effective = resolveSessionPolicy(sessionId)
            if (effective == ClipboardPolicy.DISABLED) {
                return@withContext Result.failure(
                    IllegalStateException("Clipboard is disabled for session $sessionId")
                )
            }
            if (text.length > maxTextSize) {
                return@withContext Result.failure(
                    IllegalArgumentException(
                        "Text exceeds max size ($maxTextSize bytes)"
                    )
                )
            }
            val label = "Elysium: $sessionId"
            val clip = ClipData.newPlainText(label, text)
            androidClipboard?.setPrimaryClip(clip)
            val bytes = text.toByteArray(Charsets.UTF_8).size
            recordAccess(sessionId, Direction.PUSH, ClipboardType.TEXT, bytes)
            previousClipData.set(clip)
            Result.success(Unit)
        }

    override suspend fun pullText(sessionId: String): Result<String?> =
        withContext(Dispatchers.IO) {
            val effective = resolveSessionPolicy(sessionId)
            if (effective == ClipboardPolicy.DISABLED) {
                return@withContext Result.failure(
                    IllegalStateException("Clipboard is disabled for session $sessionId")
                )
            }
            val clip = androidClipboard?.primaryClip ?: run {
                return@withContext Result.success(null)
            }
            if (clip.itemCount == 0) {
                return@withContext Result.success(null)
            }
            val text = clip.getItemAt(0)?.text?.toString()
            if (text != null) {
                recordAccess(sessionId, Direction.PULL, ClipboardType.TEXT, text.length)
            }
            Result.success(text)
        }

    override suspend fun pushImage(
        sessionId: String,
        data: ByteArray,
        mimeType: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val effective = resolveSessionPolicy(sessionId)
        if (effective != ClipboardPolicy.TEXT_AND_IMAGE &&
            effective != ClipboardPolicy.FULL
        ) {
            return@withContext Result.failure(
                IllegalStateException(
                    "Image clipboard not allowed by policy $effective"
                )
            )
        }
        if (data.size > maxImageSize) {
            return@withContext Result.failure(
                IllegalArgumentException("Image data exceeds max size ($maxImageSize bytes)")
            )
        }
        // PHASE 8 — actually persist the bytes. The previous version built
        // a ClipData with a content:// URI that was never populated, so the
        // guest pulled an empty clipboard. We now write to app-private
        // storage under filesDir/clipboard/<sessionId>/img.<ext> and let
        // the Linux-side transport (a future file-bridge or OSC 52) read
        // from there. The MIME type is preserved in a sidecar file so
        // pullImage can report it back to the runtime.
        val imageDir = File(appContext.filesDir, "clipboard/$sessionId")
        if (!imageDir.exists() && !imageDir.mkdirs()) {
            return@withContext Result.failure(
                IOException("Cannot create clipboard image directory: $imageDir")
            )
        }
        val extension = mimeType.substringAfter("/", "bin")
            .substringBefore(";")
            .ifBlank { "bin" }
            .filter { it.isLetterOrDigit() }
        val target = File(imageDir, "img.${extension.ifBlank { "bin" }}")
        val sidecar = File(imageDir, "mime.txt")
        try {
            val staging = File(imageDir, "img.${extension.ifBlank { "bin" }}.part")
            staging.writeBytes(data)
            if (!staging.renameTo(target)) {
                target.writeBytes(data)
                staging.delete()
            }
            sidecar.writeText(mimeType)
        } catch (e: IOException) {
            return@withContext Result.failure(e)
        }
        val label = "Elysium image: $sessionId"
        // The Android-side ClipData now carries the file URI we just wrote,
        // so any side-channel reader (drag-and-drop, accessibility) sees
        // the same content. The Linux guest reads the file directly
        // through the broker transport (wired in a later commit).
        val clip = ClipData.newUri(
            appContext.contentResolver,
            label,
            Uri.fromFile(target)
        )
        androidClipboard?.setPrimaryClip(clip)
        recordAccess(sessionId, Direction.PUSH, ClipboardType.IMAGE, data.size)
        Result.success(Unit)
    }

    override suspend fun pullImage(sessionId: String): Result<ClipboardImage?> =
        withContext(Dispatchers.IO) {
            val effective = resolveSessionPolicy(sessionId)
            if (effective != ClipboardPolicy.TEXT_AND_IMAGE &&
                effective != ClipboardPolicy.FULL
            ) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "Image clipboard not allowed by policy $effective"
                    )
                )
            }
            // PHASE 8 — read the bytes that pushImage wrote. We look at
            // app-private storage first; the Android-side ClipData is a
            // side channel and may have been replaced by the user in
            // another app.
            val imageDir = File(appContext.filesDir, "clipboard/$sessionId")
            val candidates = imageDir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("img.") && it.extension != "part" }
                ?.sortedBy { it.lastModified() }
                ?: emptyList()
            val target = candidates.lastOrNull()
            if (target == null) {
                return@withContext Result.success(null)
            }
            try {
                val imageBytes = target.readBytes()
                val sidecar = File(imageDir, "mime.txt")
                val mime = if (sidecar.exists()) sidecar.readText() else "image/*"
                recordAccess(sessionId, Direction.PULL, ClipboardType.IMAGE, imageBytes.size)
                return@withContext Result.success(
                    ClipboardImage(
                        data = imageBytes,
                        mimeType = mime,
                        width = 0,
                        height = 0
                    )
                )
            } catch (e: IOException) {
                Result.failure(e)
            }
        }

    override fun registerGuestClipboard(sessionId: String, path: String) {
        guestClipboards[sessionId] = path
    }

    override suspend fun clear(sessionId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val clip = ClipData.newPlainText("", "")
            androidClipboard?.setPrimaryClip(clip)
            guestClipboards.remove(sessionId)
            sessionPolicies.remove(sessionId)
            // Remove the app-private clipboard image directory so cleared
            // sessions do not leak bytes between unrelated sessions.
            val imageDir = File(appContext.filesDir, "clipboard/$sessionId")
            if (imageDir.exists()) {
                imageDir.deleteRecursively()
            }
            recordAccess(sessionId, Direction.PUSH, ClipboardType.TEXT, 0)
            Result.success(Unit)
        }

    override fun close() {
        scope.cancel()
        detachClipboardListener()
        sessionPolicies.clear()
        guestClipboards.clear()
        // Wipe the app-private clipboard directory on close. This is a
        // best-effort cleanup; production callers should call clear() per
        // session before close() so individual audit events are recorded.
        val clipboardRoot = File(appContext.filesDir, "clipboard")
        if (clipboardRoot.exists()) {
            clipboardRoot.deleteRecursively()
        }
        _state.value = ClipboardBrokerState.Idle
    }

    private fun resolveSessionPolicy(sessionId: String): ClipboardPolicy {
        return sessionPolicies[sessionId] ?: _policy.value
    }

    private fun resolveEffectivePolicy(): ClipboardPolicy {
        if (sessionPolicies.isEmpty()) return ClipboardPolicy.TEXT_ONLY
        var mostPermissive = ClipboardPolicy.DISABLED
        for (p in sessionPolicies.values) {
            if (p.ordinal > mostPermissive.ordinal) {
                mostPermissive = p
            }
        }
        return mostPermissive
    }

    private fun recordAccess(
        sessionId: String,
        direction: Direction,
        type: ClipboardType,
        sizeBytes: Int
    ) {
        _lastAccess.value = ClipboardAccessEvent(
            sessionId = sessionId,
            direction = direction,
            type = type,
            timestamp = System.currentTimeMillis(),
            sizeBytes = sizeBytes
        )
    }

    private fun reattachClipboardListener(policy: ClipboardPolicy) {
        if (policy == ClipboardPolicy.DISABLED) {
            detachClipboardListener()
            return
        }
        if (primaryClipListener != null) return
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            onPrimaryClipChanged()
        }
        primaryClipListener = listener
        androidClipboard?.addPrimaryClipChangedListener(listener)
    }

    private fun detachClipboardListener() {
        primaryClipListener?.let {
            androidClipboard?.removePrimaryClipChangedListener(it)
        }
        primaryClipListener = null
    }

    private fun onPrimaryClipChanged() {
        val clip = androidClipboard?.primaryClip ?: return
        if (clip == previousClipData.get()) return
        previousClipData.set(clip)
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0)?.text?.toString()
        if (text != null && sessionPolicies.isNotEmpty()) {
            scope.launch {
                val textLen = text.length
                sessionPolicies.keys.forEach { sid ->
                    recordAccess(sid, Direction.PULL, ClipboardType.TEXT, textLen)
                }
            }
        }
    }

    private companion object {
        private const val MAX_TEXT_SIZE = 1_048_576
        private const val MAX_IMAGE_SIZE = 10_485_760
    }
}
