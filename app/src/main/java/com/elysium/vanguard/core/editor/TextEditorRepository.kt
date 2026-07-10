package com.elysium.vanguard.core.editor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PHASE 2.7 — Repository for loading and saving text files in the editor.
 *
 * Responsibilities:
 *   - Read text files from disk (UTF-8, with a fallback to ISO-8859-1 for legacy
 *     files that pre-date Unicode)
 *   - Detect the language from the file extension
 *   - Persist user edits back to disk atomically (write to temp + rename)
 *
 * We use plain `java.io.File` rather than SAF because the editor is opened from
 * the file manager, where we already have an absolute path. SAF flow is reserved
 * for cases where the user wants to edit a file we don't have permission for.
 */
@Singleton
class TextEditorRepository @Inject constructor() {

    /**
     * Load the contents of [path] as text. Returns null if the file doesn't exist
     * or can't be read.
     */
    suspend fun load(path: String): String? = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists() || !file.canRead()) return@withContext null
        try {
            file.readText(Charsets.UTF_8)
        } catch (_: Exception) {
            // Some legacy text files aren't valid UTF-8; fall back to Latin-1 so
            // the user at least sees something instead of a crash.
            try {
                file.readText(Charsets.ISO_8859_1)
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Persist [contents] back to [path]. Writes to a sibling temp file first and
     * then renames — the rename is atomic on the same filesystem, so we never
     * leave the user with a half-written file even if the app is killed mid-save.
     *
     * @return true on success, false on any IO failure
     */
    suspend fun save(path: String, contents: String): Boolean = withContext(Dispatchers.IO) {
        val target = File(path)
        val parent = target.parentFile ?: return@withContext false
        if (!parent.canWrite()) return@withContext false
        val temp = File(parent, ".${target.name}.${System.currentTimeMillis()}.tmp")
        try {
            temp.writeText(contents, Charsets.UTF_8)
            // Atomic replace.
            if (!temp.renameTo(target)) {
                // Some filesystems (rare) don't support atomic rename across an
                // existing file. Fall back to delete-then-rename.
                if (target.exists() && !target.delete()) return@withContext false
                if (!temp.renameTo(target)) return@withContext false
            }
            true
        } catch (_: FileNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            temp.delete()
            false
        }
    }

    /** Detect the language for [path]. */
    fun detectLanguage(path: String): Language = Language.forFile(path)
}