package com.elysium.vanguard.features.filemanager

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.elysium.vanguard.core.saf.SafTreeManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PHASE 8.4 — Dual-mode file operations.
 *
 * This class is the single entry point for read/write operations on the file
 * manager. It picks the right backend at call time:
 *
 *   - **SAF mode** when [safTreeManager] has a usable tree URI. All operations
 *     go through [DocumentFile.fromTreeUri], which is the only API that
 *     works on Android 11+ scoped storage without MANAGE_EXTERNAL_STORAGE.
 *   - **Filesystem mode** as a fallback for app-private directories
 *     (`getExternalFilesDir`, `getFilesDir`) and for the rare case where
 *     the user has MANAGE_EXTERNAL_STORAGE granted (we don't request it
 *     by default, but if the user enables it from Settings we accept it).
 *
 * The two backends are kept strictly behind this facade. UI and ViewModels
 * never touch DocumentFile or raw File paths for user-visible operations.
 *
 * The class is intentionally conservative in error reporting. Failures return
 * null/empty rather than throwing — the calling layer decides whether to
 * surface a toast or treat it as a benign no-op. The data-loss risks from
 * silently swallowed exceptions are mitigated by the caller (Trash move
 * returns a clear error, etc.).
 */
@Singleton
class FileManagerRepositoryDual @Inject constructor(
    @ApplicationContext private val context: Context?,
    private val safTreeManager: SafTreeManager
) {

    private val tag = "FileManagerRepo"

    // ----- Listing -----

    /**
     * List the contents of [path] for the UI. [path] is interpreted as:
     *   - `saf:<relative-path>` for paths under the granted SAF tree.
     *   - an absolute filesystem path otherwise (app-private dirs).
     *
     * The caller (ViewModel) translates a "current directory" into the
     * right form. We keep the path type explicit in the string so the
     * ViewModel never has to remember which mode it's in.
     */
    fun listFiles(path: String): Flow<List<TitanFile>> = flow {
        val entries = listOnce(path)
        emit(entries)
    }.flowOn(Dispatchers.IO)

    fun listOnce(path: String): List<TitanFile> {
        if (path.startsWith("saf:")) {
            return listSaf(path.removePrefix("saf:").trim('/'))
        }
        return listFilesystem(path)
    }

    private fun listSaf(relative: String): List<TitanFile> {
        val tree = safTreeManager.refresh() ?: return emptyList()
        val root = DocumentFile.fromTreeUri(context ?: return emptyList(), tree) ?: return emptyList()
        val parent: DocumentFile = if (relative.isEmpty()) {
            root
        } else {
            safTreeManager.resolveChild(relative) ?: return emptyList()
        }
        return parent.listFiles().mapNotNull { doc ->
            mapDocumentFile(doc) ?: return@mapNotNull null
        }.sortedWith(compareByDescending<TitanFile> { it.isFolder }.thenBy { it.name.lowercase() })
    }

    private fun listFilesystem(path: String): List<TitanFile> {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        val children = dir.listFiles() ?: return emptyList()
        return children.map { f ->
            TitanFile(
                name = f.name,
                isFolder = f.isDirectory,
                size = if (f.isDirectory) "${f.list()?.size ?: 0} items" else formatSize(f.length()),
                path = f.absolutePath,
                mimeType = if (f.isDirectory) "resource/folder" else mimeFor(f.name),
                category = com.elysium.vanguard.core.util.FileThematics.getCategory(f.name, f.isDirectory),
                thematicColor = com.elysium.vanguard.core.util.FileThematics.getCategoryColor(
                    com.elysium.vanguard.core.util.FileThematics.getCategory(f.name, f.isDirectory)
                ),
                lastModified = f.lastModified(),
                permissions = fsPermissions(f)
            )
        }.sortedWith(compareByDescending<TitanFile> { it.isFolder }.thenBy { it.name.lowercase() })
    }

    private fun fsPermissions(f: File): String {
        val r = if (f.canRead()) "r" else "-"
        val w = if (f.canWrite()) "w" else "-"
        val x = if (f.canExecute()) "x" else "-"
        return "[$r$w$x]"
    }

    private fun mapDocumentFile(doc: DocumentFile): TitanFile? {
        val name = doc.name ?: return null
        val size = if (doc.isDirectory) {
            val childCount = doc.listFiles().size
            "$childCount items"
        } else {
            formatSize(doc.length())
        }
        // Path stays in the "saf:<rel>" format the caller knows about.
        // We derive the relative path by walking from root, but for now
        // we use the absolute document URI — the ViewModel rewrites it
        // back to "saf:<rel>" before navigation.
        return TitanFile(
            name = name,
            isFolder = doc.isDirectory,
            size = size,
            path = doc.uri.toString(),
            mimeType = if (doc.isDirectory) "resource/folder" else (doc.type ?: "application/octet-stream"),
            category = com.elysium.vanguard.core.util.FileThematics.getCategory(name, doc.isDirectory),
            thematicColor = com.elysium.vanguard.core.util.FileThematics.getCategoryColor(
                com.elysium.vanguard.core.util.FileThematics.getCategory(name, doc.isDirectory)
            ),
            lastModified = doc.lastModified(),
            permissions = "[rw-]"
        )
    }

    // ----- Operations -----

    /** Delete a file or folder. Returns true on success. */
    fun delete(path: String): Boolean {
        return if (path.startsWith("saf:")) {
            deleteSaf(path.removePrefix("saf:").trim('/'))
        } else {
            try {
                val f = File(path)
                if (f.isDirectory) f.deleteRecursively() else f.delete()
            } catch (_: Exception) { false }
        }
    }

    private fun deleteSaf(relative: String): Boolean {
        val doc = safTreeManager.resolveChild(relative) ?: return false
        return try { doc.delete() } catch (_: Exception) { false }
    }

    /**
     * Rename a file. [newName] is the basename only (no directory change).
     * Refuses to overwrite an existing sibling.
     */
    fun rename(path: String, newName: String): Boolean {
        return if (path.startsWith("saf:")) {
            renameSaf(path.removePrefix("saf:").trim('/'), newName)
        } else {
            try {
                val source = File(path)
                val dest = File(source.parent, newName)
                if (dest.exists() && dest.absolutePath != source.absolutePath) return false
                source.renameTo(dest)
            } catch (_: Exception) { false }
        }
    }

    private fun renameSaf(relative: String, newName: String): Boolean {
        val doc = safTreeManager.resolveChild(relative) ?: return false
        // DocumentFile.renameTo returns the new URI; we check null for failure.
        return try { doc.renameTo(newName) != null } catch (_: Exception) { false }
    }

    /**
     * Copy a single file. Caller is responsible for resolving the destination
     * (we don't auto-rename here — that's a UI policy, not a storage policy).
     * Returns true on success.
     */
    fun copy(sourcePath: String, destPath: String): Boolean {
        return if (sourcePath.startsWith("saf:") || destPath.startsWith("saf:")) {
            copySaf(sourcePath, destPath)
        } else {
            try {
                val src = File(sourcePath)
                val dst = File(destPath)
                if (src.isDirectory) src.copyRecursively(dst, overwrite = true)
                else src.copyTo(dst, overwrite = true)
                true
            } catch (_: Exception) { false }
        }
    }

    private fun copySaf(sourcePath: String, destPath: String): Boolean {
        val source = if (sourcePath.startsWith("saf:")) {
            safTreeManager.resolveChild(sourcePath.removePrefix("saf:").trim('/'))
        } else {
            // SAF can't take raw files; caller must convert.
            null
        } ?: return false
        val destTree = safTreeManager.refresh() ?: return false
        val destRoot = DocumentFile.fromTreeUri(context ?: return false, destTree) ?: return false
        val targetName = source.name ?: return false
        val target = destRoot.createFile("application/octet-stream", targetName) ?: return false
        return try {
            val resolver = context?.contentResolver ?: return false
            val input = resolver.openInputStream(source.uri) ?: return false
            val output = resolver.openOutputStream(target.uri) ?: run {
                input.close()
                return false
            }
            input.use { srcStream ->
                output.use { dstStream ->
                    srcStream.copyTo(dstStream, bufferSize = 64 * 1024)
                }
            }
            true
        } catch (_: Exception) { false }
    }

    /**
     * Move a single file. We try rename first; if it fails (cross-volume),
     * we fall back to copy + delete.
     */
    fun move(sourcePath: String, destPath: String): Boolean {
        return if (sourcePath.startsWith("saf:") || destPath.startsWith("saf:")) {
            moveSaf(sourcePath, destPath)
        } else {
            try {
                val src = File(sourcePath)
                val dst = File(destPath)
                if (src.renameTo(dst)) true
                else if (copy(sourcePath, destPath)) {
                    delete(sourcePath)
                } else false
            } catch (_: Exception) { false }
        }
    }

    private fun moveSaf(sourcePath: String, destPath: String): Boolean {
        val source = safTreeManager.resolveChild(sourcePath.removePrefix("saf:").trim('/')) ?: return false
        val destTree = safTreeManager.refresh() ?: return false
        val destRoot = DocumentFile.fromTreeUri(context ?: return false, destTree) ?: return false
        val targetName = source.name ?: return false
        val target = destRoot.createFile("application/octet-stream", targetName) ?: return false
        return try {
            context?.contentResolver?.openInputStream(source.uri)?.use { input ->
                context.contentResolver.openOutputStream(target.uri)?.use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            } ?: return false
            source.delete()
        } catch (_: Exception) { false }
    }

    /**
     * Recursive folder size. Streams the result; the caller should still
     * run this on Dispatchers.IO.
     */
    fun folderSize(path: String): Long {
        return if (path.startsWith("saf:")) {
            folderSizeSaf(path.removePrefix("saf:").trim('/'))
        } else {
            val f = File(path)
            if (!f.exists()) 0L
            else if (!f.isDirectory) f.length()
            else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
    }

    private fun folderSizeSaf(relative: String): Long {
        val doc = safTreeManager.resolveChild(relative) ?: return 0L
        if (!doc.isDirectory) return doc.length()
        return docSizeRecursive(doc)
    }

    private fun docSizeRecursive(doc: DocumentFile): Long {
        if (!doc.isDirectory) return doc.length()
        var total = 0L
        for (child in doc.listFiles()) {
            total += if (child.isDirectory) docSizeRecursive(child) else child.length()
        }
        return total
    }

    // ----- Storage stats -----

    /**
     * Storage stats. We use SAF-tree-relative stats when the user has a
     * tree granted, since that's the volume they actually see. Otherwise
     * we fall back to the app's external files dir which is always
     * available without permissions.
     */
    fun storageStats(): StorageStats {
        val statPath: String = if (safTreeManager.hasUsableTree) {
            val tree = safTreeManager.currentTreeUri.value ?: return fallbackStats()
            val path = tree.path
            if (path != null) {
                // The tree URI has no direct on-disk path; we can't use StatFs
                // on a content URI. Fall back to the user-visible primary
                // volume, which is what 99% of users mean.
                context?.getExternalFilesDir(null)?.parentFile?.parentFile?.parentFile?.absolutePath
                    ?: "/storage/emulated/0"
            } else fallbackStats().rootPath
        } else {
            context?.getExternalFilesDir(null)?.parentFile?.parentFile?.parentFile?.absolutePath
                ?: "/storage/emulated/0"
        }
        return storageStatsForPath(statPath)
    }

    fun storageStatsForPath(path: String): StorageStats {
        val stat = android.os.StatFs(path)
        val blockSize = stat.blockSizeLong
        val totalBlocks = stat.blockCountLong
        val availableBlocks = stat.availableBlocksLong
        val totalBytes = totalBlocks * blockSize
        val availableBytes = availableBlocks * blockSize
        val usedBytes = totalBytes - availableBytes
        return StorageStats(
            totalBytes = totalBytes,
            usedBytes = usedBytes,
            availableBytes = availableBytes,
            totalLabel = formatSize(totalBytes),
            usedLabel = formatSize(usedBytes),
            percentUsed = (usedBytes.toDouble() / totalBytes * 100).toInt(),
            rootPath = path
        )
    }

    private fun fallbackStats(): StorageStats = storageStatsForPath(
        context?.filesDir?.absolutePath ?: "/"
    )

    // ----- Format helpers (no Context needed) -----

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
        else -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
    }

    private fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() } ?: return "application/octet-stream"
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
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "zip" -> "application/zip"
            "tar" -> "application/x-tar"
            "gz" -> "application/gzip"
            else -> "application/octet-stream"
        }
    }
}