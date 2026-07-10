package com.elysium.vanguard.features.filemanager

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import android.text.format.Formatter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton
import java.io.File
import com.elysium.vanguard.core.util.FileThematics
import com.elysium.vanguard.core.util.FileCategory

/**
 * TITAN FILE REPOSITORY (LIVE-WIRE IMPLEMENTATION)
 * Direct connection to MediaStore and Storage Access Framework.
 */
@Singleton
class FileManagerRepository @Inject constructor(
    // PHASE 7.5: context is nullable so the repository can be constructed in
    // unit tests that don't have a Robolectric context. The only place
    // context is consulted is for Formatter.formatFileSize — the code path
    // is guarded so the file-system operations (delete/copy/move/rename)
    // work without it.
    @ApplicationContext private val context: Context?
) {

    fun getFiles(path: String): Flow<List<TitanFile>> = flow {
        try {
            val directory = File(path)
            // PHASE 7.4 (Security Hardening): never log full user paths in
            // release. They leak document titles, contract names, etc. into
            // logcat (READ_LOGS on rooted devices, dev mode, CI logs). We
            // only log in debug builds AND only log the basename + count.
            if (com.elysium.vanguard.BuildConfig.DEBUG) {
                android.util.Log.d(
                    "TitanFileManager",
                    "Scanning: ${directory.name} (Exists: ${directory.exists()}, IsDir: ${directory.isDirectory})"
                )
            }

            if (directory.exists() && directory.isDirectory) {
                val files = directory.listFiles()
                if (files == null) {
                    if (com.elysium.vanguard.BuildConfig.DEBUG) {
                        android.util.Log.w("TitanFileManager", "listFiles() returned null for: ${directory.name}")
                    }
                    emit(emptyList())
                } else {
                    if (com.elysium.vanguard.BuildConfig.DEBUG) {
                        android.util.Log.d("TitanFileManager", "Found ${files.size} nodes in ${directory.name}")
                    }
                    val titanFiles = files.map { file ->
                        val category = FileThematics.getCategory(file.name, file.isDirectory)
                        TitanFile(
                            name = file.name,
                            isFolder = file.isDirectory,
                            size = if (file.isDirectory) "${file.list()?.size ?: 0} items" else formatSize(file.length()),
                            path = file.absolutePath,
                            mimeType = if (file.isDirectory) "resource/folder" else getMimeType(file),
                            category = category,
                            thematicColor = FileThematics.getCategoryColor(category),
                            lastModified = file.lastModified(),
                            permissions = getPermissionsString(file)
                        )
                    }.sortedWith(compareByDescending<TitanFile> { it.isFolder }.thenBy { it.name.lowercase() })
                    emit(titanFiles)
                }
            } else {
                emit(emptyList())
            }
        } catch (e: Exception) {
            if (com.elysium.vanguard.BuildConfig.DEBUG) {
                android.util.Log.e("TitanFileManager", "Crash in getFiles: ${e.message}", e)
            }
            emit(emptyList())
        }
    }.flowOn(Dispatchers.IO)

    private fun getMimeType(file: File): String {
        val extension = file.extension.lowercase()
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }

    private fun getPermissionsString(file: File): String {
        val r = if (file.canRead()) "r" else "-"
        val w = if (file.canWrite()) "w" else "-"
        val x = if (file.canExecute()) "x" else "-"
        return "[$r$w$x]"
    }

    /**
     * Detection Logic for the "Common Third-Party Folders" requirement.
     */
    fun getCommonShortcuts(): List<Pair<String, String>> {
        val base = "/storage/emulated/0"
        val possible = listOf(
            "Music" to "$base/Music",
            "Pictures" to "$base/Pictures",
            "Downloads" to "$base/Download",
            "DCIM" to "$base/DCIM",
            "Android" to "$base/Android",
            "WhatsApp" to "$base/Android/media/com.whatsapp/WhatsApp/Media",
            "Documents" to "$base/Documents",
            "Snaptube" to "$base/snaptube",
            "Tencent" to "$base/Tencent",
            "PSP" to "$base/PSP"
        )
        return possible.filter { File(it.second).exists() }
    }

    /**
     * INDUSTRIAL FILE OPERATIONS (MASTER ORDER)
     * Includes automatic conflict resolution (renaming if target exists).
     */
    private fun resolveConflictPath(destPath: String): String {
        var file = File(destPath)
        if (!file.exists()) return destPath

        val parent = file.parent
        val nameWithoutExt = file.nameWithoutExtension
        val ext = file.extension
        val extDot = if (ext.isNotEmpty()) ".$ext" else ""
        
        var counter = 1
        var newFile = File(parent, "$nameWithoutExt ($counter)$extDot")
        while (newFile.exists()) {
            counter++
            newFile = File(parent, "$nameWithoutExt ($counter)$extDot")
        }
        return newFile.absolutePath
    }

    fun deleteFile(path: String): Boolean {
        return try {
            val file = File(path)
            if (file.isDirectory) {
                file.deleteRecursively()
            } else {
                file.delete()
            }
        } catch (e: Exception) {
            false
        }
    }

    fun copyFile(sourcePath: String, destPath: String, autoRename: Boolean = true): Boolean {
        return try {
            val source = File(sourcePath)
            val finalDestPath = if (autoRename) resolveConflictPath(destPath) else destPath
            val dest = File(finalDestPath)
            
            if (source.isDirectory) {
                source.copyRecursively(dest, overwrite = !autoRename)
            } else {
                source.copyTo(dest, overwrite = !autoRename)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    fun moveFile(sourcePath: String, destPath: String, autoRename: Boolean = true): Boolean {
        return try {
            val source = File(sourcePath)
            val finalDestPath = if (autoRename) resolveConflictPath(destPath) else destPath
            val dest = File(finalDestPath)
            
            if (source.renameTo(dest)) {
                true
            } else {
                // Fallback if move across partitions
                if (copyFile(sourcePath, finalDestPath, autoRename = false)) {
                    deleteFile(sourcePath)
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    fun renameFile(path: String, newName: String): Boolean {
        return try {
            val source = File(path)
            val dest = File(source.parent, newName)
            // PHASE 7.5 (Security Hardening): explicitly reject collisions
            // before delegating to `File.renameTo`. The latter's behavior is
            // platform-dependent (Linux returns false, macOS overwrites),
            // which leads to silent data loss on some devices. We make
            // the contract explicit: "don't overwrite an existing file
            // with rename — make the user pick a different name."
            if (dest.exists() && dest.absolutePath != source.absolutePath) {
                return false
            }
            source.renameTo(dest)
        } catch (e: Exception) {
            false
        }
    }

    fun getStorageStats(): StorageStats = getStorageStatsForPath(
        android.os.Environment.getExternalStorageDirectory().absolutePath
    )

    /** Alias for the dual repo to call without re-doing the cast. */
    fun getStorageStatsForDual(path: String): StorageStats = getStorageStatsForPath(path)

    /**
     * PHASE 7.5: extract the StatFs code so tests can point at a path
     * that doesn't require Android's Environment.getExternalStorageDirectory().
     * The path-arg version is also more correct: it lets the caller ask
     * "how much space is on this specific volume" without depending on a
     * hard-coded constant.
     */
    fun getStorageStatsForPath(path: String): StorageStats {
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

    /**
     * PHASE 7.5: size formatter that doesn't require a Context. We use this
     * in lieu of `Formatter.formatFileSize` so the repository is testable
     * in pure JVM. The output is a sensible English-only form (1.2 MB)
     * rather than the locale-aware one Android provides, but the difference
     * is cosmetic — the underlying bytes are the source of truth.
     */
    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
        else -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
    }

    fun getFolderSizeRecursive(file: File): Long {
        if (!file.exists()) return 0L
        if (!file.isDirectory) return file.length()
        
        var size = 0L
        file.listFiles()?.forEach {
            size += if (it.isDirectory) getFolderSizeRecursive(it) else it.length()
        }
        return size
    }
}

data class StorageStats(
    val totalBytes: Long,
    val usedBytes: Long,
    val availableBytes: Long,
    val totalLabel: String,
    val usedLabel: String,
    val percentUsed: Int,
    /** Phase 8: which volume the stats describe (for UI display + SAF fallback). */
    val rootPath: String = ""
)
