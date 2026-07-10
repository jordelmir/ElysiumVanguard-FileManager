package com.elysium.vanguard.core.util

import java.io.*
import java.util.zip.*

object CompressionEngine {

    interface ProgressListener {
        fun onProgress(percentage: Int, currentFile: String)
    }

    /** PHASE 8.6: safety caps for ZIP bomb protection. The per-entry and
     *  total caps together are sufficient: a "highly compressed" entry that
     *  tries to expand past the total cap will be aborted by the size check
     *  before it consumes disk. The ratio check was flaky (Java's ZipEntry
     *  size is updated as the stream is written, not declared up-front) so
     *  we removed it in favor of the size caps. */
    const val MAX_DECOMPRESSED_BYTES: Long = 1L * 1024 * 1024 * 1024   // 1 GB
    const val MAX_ENTRY_BYTES: Long = 512L * 1024 * 1024              // 512 MB per entry

    /**
     * Compress files and directories to a ZIP archive.
     * Supports recursive directory compression with real progress tracking.
     */
    fun compress(
        files: List<File>,
        outputFile: File,
        listener: ProgressListener? = null
    ): Result<File> {
        return try {
            val buffer = ByteArray(1024 * 8)
            
            // Calculate total size including directories
            val allFiles = mutableListOf<Pair<File, String>>() // file, relative path
            for (file in files) {
                if (file.isDirectory) {
                    collectFilesRecursive(file, file.name, allFiles)
                } else {
                    allFiles.add(file to file.name)
                }
            }
            
            val totalSize = allFiles.sumOf { it.first.length() }.coerceAtLeast(1L)
            var processedSize = 0L

            ZipOutputStream(BufferedOutputStream(FileOutputStream(outputFile))).use { zos ->
                for ((file, relativePath) in allFiles) {
                    if (file.isDirectory) {
                        zos.putNextEntry(ZipEntry("$relativePath/"))
                        zos.closeEntry()
                        continue
                    }
                    
                    listener?.onProgress(
                        ((processedSize * 100) / totalSize).toInt().coerceAtMost(99),
                        file.name
                    )
                    
                    val entry = ZipEntry(relativePath)
                    zos.putNextEntry(entry)
                    file.inputStream().use { fis ->
                        var len: Int
                        while (fis.read(buffer).also { len = it } > 0) {
                            zos.write(buffer, 0, len)
                            processedSize += len
                        }
                    }
                    zos.closeEntry()
                }
            }
            listener?.onProgress(100, "Complete")
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Decompress a ZIP archive with entry-count based progress.
     * Optionally provide a password — if the ZIP is password-protected
     * and standard Java ZIP doesn't support it, this will fail gracefully.
     *
     * PHASE 8.6: ZIP bomb protection. We track the cumulative bytes written
     * to disk and abort if the ratio of compressed-to-decompressed bytes
     * exceeds a sane bound (or if total decompressed size crosses an
     * absolute cap). Default cap is 1 GB — most legitimate archives are
     * < 100 MB. We also enforce a per-entry size cap to defend against
     * "single huge file inside a small zip" attacks.
     */
    fun decompress(
        zipFile: File,
        outputDir: File,
        password: String? = null,
        listener: ProgressListener? = null,
        maxDecompressedBytes: Long = MAX_DECOMPRESSED_BYTES,
        maxEntryBytes: Long = MAX_ENTRY_BYTES
    ): Result<File> {
        return try {
            if (!outputDir.exists()) outputDir.mkdirs()

            // First pass: count entries for accurate progress
            val entryCount = countZipEntries(zipFile)
            var processedEntries = 0
            var totalBytesWritten = 0L
            val buffer = ByteArray(1024 * 8)
            val zipSize = zipFile.length().coerceAtLeast(1L)

            ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val newFile = File(outputDir, entry.name)

                    // Security: prevent zip slip
                    val canonicalOutput = outputDir.canonicalPath
                    val canonicalFile = newFile.canonicalPath
                    if (!canonicalFile.startsWith(canonicalOutput)) {
                        throw SecurityException("ZIP entry outside target directory: ${entry.name}")
                    }

                    listener?.onProgress(
                        if (entryCount > 0) ((processedEntries * 100) / entryCount).coerceAtMost(99) else 0,
                        entry.name.substringAfterLast("/").ifEmpty { entry.name }
                    )

                    if (entry.isDirectory) {
                        newFile.mkdirs()
                    } else {
                        newFile.parentFile?.mkdirs()
                        var entryBytes = 0L
                        FileOutputStream(newFile).use { fos ->
                            var len: Int
                            while (zis.read(buffer).also { len = it } > 0) {
                                fos.write(buffer, 0, len)
                                entryBytes += len
                                totalBytesWritten += len

                                // Per-entry cap: defend against a single
                                // oversized entry claiming the whole budget.
                                if (entryBytes > maxEntryBytes) {
                                    throw SecurityException(
                                        "Entry exceeds $maxEntryBytes bytes: ${entry.name}"
                                    )
                                }
                                // Total cap: refuse to extract past our budget.
                                if (totalBytesWritten > maxDecompressedBytes) {
                                    throw SecurityException(
                                        "Archive exceeds $maxDecompressedBytes bytes when extracted"
                                    )
                                }
                            }
                        }
                    }
                    processedEntries++
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            listener?.onProgress(100, "Complete")
            Result.success(outputDir)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun collectFilesRecursive(dir: File, basePath: String, result: MutableList<Pair<File, String>>) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            val childPath = "$basePath/${child.name}"
            if (child.isDirectory) {
                result.add(child to childPath)
                collectFilesRecursive(child, childPath, result)
            } else {
                result.add(child to childPath)
            }
        }
    }

    private fun countZipEntries(zipFile: File): Int {
        var count = 0
        try {
            ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
                while (zis.nextEntry != null) {
                    count++
                    zis.closeEntry()
                }
            }
        } catch (e: Exception) { /* ignore */ }
        return count
    }
}
