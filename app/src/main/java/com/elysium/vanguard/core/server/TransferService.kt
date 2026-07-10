package com.elysium.vanguard.core.server

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/**
 * PHASE 2.3 — Bridge between the [LocalFileServer] and the actual file system.
 *
 * Two execution contexts:
 *   - When [useSaf] is true, paths passed to handlers are treated as SAF tree URIs
 *     (the user picked a folder via OpenDocumentTree). The server validates that
 *     the requested URI is inside the granted tree before doing any IO.
 *   - When [useSaf] is false, paths are absolute filesystem paths under [rootDir]
 *     and traversal (`..`) is rejected.
 *
 * This dual mode is intentional: on Android 11+ scoped storage means raw filesystem
 * paths don't cover everything. SAF is the official escape hatch. The UI offers
 * "Pick a folder" once and we keep the granted tree URI for the session.
 */
class TransferService(
    /**
     * Android context. Required for SAF mode; can be null in unit tests that only
     * exercise the filesystem branch. The [mode] getter falls back to FILESYSTEM
     * when context is null so callers never accidentally hit a NPE.
     */
    private val context: Context?,
    private val safTreeUri: () -> Uri?,
    private val fsRoot: () -> File?
) {

    private fun requireContext(): Context =
        context ?: error("TransferService: Context required for SAF mode but was null")

    private val TAG = "TransferService"

    /** What kind of path the server should expect. */
    enum class Mode { SAF, FILESYSTEM }

    val mode: Mode
        get() = if (safTreeUri() != null && context != null) Mode.SAF else Mode.FILESYSTEM

    // ---- Listing ----

    data class Entry(
        val name: String,
        val path: String,        // server-side identifier (URI string or absolute path)
        val size: Long,
        val lastModified: Long,
        val isDirectory: Boolean,
        val mimeType: String?
    )

    /** List the contents of a directory. Returns null if [path] doesn't exist or is
     *  not accessible from the granted scope. */
    suspend fun list(path: String?): List<Entry>? = withContext(Dispatchers.IO) {
        try {
            when (mode) {
                Mode.SAF -> {
                    val treeUri = safTreeUri() ?: return@withContext null
                    val target = resolveSafDir(treeUri, path) ?: return@withContext null
                    val children = DocumentFile.fromTreeUri(requireContext(), target)
                        ?.listFiles() ?: return@withContext null
                    children.mapNotNull { doc ->
                        try {
                            Entry(
                                name = doc.name ?: return@mapNotNull null,
                                path = doc.uri.toString(),
                                size = if (doc.isDirectory) 0L else doc.length(),
                                lastModified = doc.lastModified(),
                                isDirectory = doc.isDirectory,
                                mimeType = doc.type
                            )
                        } catch (_: Exception) { null }
                    }.sortedWith(compareByDescending<Entry> { it.isDirectory }.thenBy { it.name.lowercase() })
                }
                Mode.FILESYSTEM -> {
                    val root = fsRoot() ?: return@withContext null
                    val dir = if (path.isNullOrEmpty() || path == "/") root
                    else resolveFsPath(root, path) ?: return@withContext null
                    if (!dir.exists() || !dir.isDirectory) return@withContext null
                    dir.listFiles()?.map { f ->
                        Entry(
                            name = f.name,
                            path = f.absolutePath,
                            size = if (f.isDirectory) 0L else f.length(),
                            lastModified = f.lastModified(),
                            isDirectory = f.isDirectory,
                            mimeType = if (f.isDirectory) null else guessMime(f.name)
                        )
                    }?.sortedWith(compareByDescending<Entry> { it.isDirectory }.thenBy { it.name.lowercase() })
                        ?: emptyList()
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "list denied for $path: ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "list failed for $path", e)
            null
        }
    }

    // ---- Downloading ----

    /**
     * Stream the bytes of [path] to [out]. Returns true on full success, false on any
     * IO error. Callers should close [out] themselves (we don't, since we don't own it).
     */
    suspend fun streamDownload(path: String, out: java.io.OutputStream): Boolean =
        withContext(Dispatchers.IO) {
            try {
                when (mode) {
                    Mode.SAF -> {
                        val treeUri = safTreeUri() ?: return@withContext false
                        val uri = Uri.parse(path)
                        if (!isInsideTree(treeUri, uri)) {
                            Log.w(TAG, "download blocked — outside granted tree: $path")
                            return@withContext false
                        }
                        requireContext().contentResolver.openInputStream(uri)?.use { input ->
                            input.copyTo(out, bufferSize = 64 * 1024)
                        } ?: return@withContext false
                    }
                    Mode.FILESYSTEM -> {
                        val root = fsRoot() ?: return@withContext false
                        val file = resolveFsPath(root, path)
                            ?.takeIf { it.exists() && it.isFile }
                            ?: return@withContext false
                        FileInputStream(file).use { input ->
                            input.copyTo(out, bufferSize = 64 * 1024)
                        }
                    }
                }
                true
            } catch (e: FileNotFoundException) {
                Log.w(TAG, "download not found: $path")
                false
            } catch (e: SecurityException) {
                Log.w(TAG, "download denied: $path")
                false
            } catch (e: IOException) {
                Log.e(TAG, "download IO error: $path", e)
                false
            }
        }

    // ---- Uploading ----

    /**
     * Persist [bytes] at [path]. For SAF, [path] is the parent directory URI and we
     * create a new file with [fileName]. For filesystem, [path] is the absolute path
     * including the file name. Returns the resulting URI / path, or null on failure.
     */
    suspend fun writeBytes(parentPath: String, fileName: String, bytes: ByteArray): String? =
        withContext(Dispatchers.IO) {
            try {
                when (mode) {
                    Mode.SAF -> {
                        val treeUri = safTreeUri() ?: return@withContext null
                        val parent = Uri.parse(parentPath)
                        if (!isInsideTree(treeUri, parent)) return@withContext null
                        val target = DocumentFile.fromTreeUri(requireContext(), parent)
                            ?.createFile("application/octet-stream", fileName)
                            ?: return@withContext null
                        requireContext().contentResolver.openOutputStream(target.uri)?.use { out ->
                            out.write(bytes)
                        } ?: return@withContext null
                        target.uri.toString()
                    }
                    Mode.FILESYSTEM -> {
                        val root = fsRoot() ?: return@withContext null
                        val target = resolveFsPath(root, parentPath)?.let {
                            File(it, fileName)
                        } ?: return@withContext null
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { it.write(bytes) }
                        target.absolutePath
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "writeBytes failed for $fileName", e)
                null
            }
        }

    // ---- Path safety ----

    /**
     * For filesystem mode: resolve [relativeOrAbsolute] against [root] while rejecting
     * traversal (`..`). Returns null if the resolved path escapes [root].
     *
     * The whole point of this helper is to make `..`-based escapes impossible. We
     * canonicalize via [File.canonicalFile] after resolving, then check that the
     * result is still under root.
     *
     * Why the absolute-path branch is split: `File(root, "/etc/passwd")` on macOS
     * joins as relative because the JDK resolves the second arg against `root` when
     * it begins with `/` (yes, even though POSIX says it's absolute). To stay
     * portable we explicitly detect absolute paths and resolve them directly.
     */
    fun resolveFsPath(root: File, relativeOrAbsolute: String): File? {
        val candidate = if (File(relativeOrAbsolute).isAbsolute) {
            File(relativeOrAbsolute).canonicalFile
        } else {
            File(root, relativeOrAbsolute).canonicalFile
        }
        val rootCanonical = root.canonicalFile
        return if (candidate.absolutePath.startsWith(rootCanonical.absolutePath)) {
            candidate
        } else {
            Log.w(TAG, "path escape attempt blocked: $relativeOrAbsolute")
            null
        }
    }

    /** For SAF: take a tree URI plus a relative path and return the directory URI, or
     *  null if the relative path would escape the tree. */
    fun resolveSafDir(treeUri: Uri, relative: String?): Uri? {
        if (relative.isNullOrEmpty() || relative == "/") return treeUri
        // Build a child document URI via DocumentFile traversal. We don't have a public
        // API for "tree + path", so we walk the tree from the root. For a local transfer
        // use case the trees are shallow, so this is fine.
        return try {
            val root = DocumentFile.fromTreeUri(requireContext(), treeUri) ?: return null
            val segments = relative.trim('/').split('/').filter { it.isNotEmpty() }
            var current: DocumentFile? = root
            for (seg in segments) {
                current = current?.findFile(seg) ?: return null
            }
            current?.takeIf { it.isDirectory }?.uri
        } catch (e: Exception) {
            Log.w(TAG, "SAF resolve failed for $relative: ${e.message}")
            null
        }
    }

    /** True if [child] lives under [tree]. Done by comparing tree-relative document IDs
     *  via DocumentFile, not string matching — URIs can be opaque. */
    fun isInsideTree(tree: Uri, child: Uri): Boolean {
        return try {
            val treeDoc = DocumentFile.fromTreeUri(requireContext(), tree) ?: return false
            val childDoc = DocumentFile.fromSingleUri(requireContext(), child) ?: return false
            var current: DocumentFile? = childDoc
            while (current != null) {
                if (current.uri == treeDoc.uri) return true
                current = current.parentFile
            }
            false
        } catch (e: Exception) {
            Log.w(TAG, "isInsideTree check failed: ${e.message}")
            false
        }
    }

    // ---- Mime ----

    private fun guessMime(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() }
            ?: return null
        return when (ext) {
            "txt", "log", "md" -> "text/plain"
            "html", "htm" -> "text/html"
            "json" -> "application/json"
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/aac"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "zip" -> "application/zip"
            "tar" -> "application/x-tar"
            "gz" -> "application/gzip"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            else -> "application/octet-stream"
        }
    }
}