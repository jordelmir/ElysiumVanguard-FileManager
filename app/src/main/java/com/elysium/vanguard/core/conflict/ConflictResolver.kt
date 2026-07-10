package com.elysium.vanguard.core.conflict

import java.io.File

/**
 * PHASE 1.10 — Apply user-chosen resolutions to the filesystem.
 *
 * This is the second half of conflict resolution: the UI collects resolutions,
 * the user taps "Apply", and we walk the resolved-conflict list and perform the
 * actual file operations.
 *
 * Failure modes:
 *   - IO error during copy/move/rename → we mark the operation as failed and
 *     keep going. The user gets a summary of what worked and what didn't.
 *   - The "keep both" rename is itself a name conflict if a "<name> (1).<ext>"
 *     file already exists. We keep incrementing the suffix until we find a
 *     free name.
 */
class ConflictResolver {

    data class Outcome(
        val conflict: Conflict,
        val success: Boolean,
        val finalPath: String?,    // resulting path on disk (may differ from source/destination)
        val error: String? = null
    )

    /**
     * Apply [resolutions] to the underlying filesystem. Returns a list of outcomes
     * in the same order as the input. Read-only resolutions (KEEP_DESTINATION, SKIP)
     * are no-ops on disk and report success without touching files.
     */
    fun apply(
        conflicts: List<Conflict>,
        resolutions: Map<String, Conflict.Resolution>
    ): List<Outcome> {
        val out = mutableListOf<Outcome>()
        for (c in conflicts) {
            val resolution = resolutions[c.sourcePath] ?: Conflict.Resolution.PENDING
            val outcome = applyOne(c, resolution)
            out.add(outcome)
        }
        return out
    }

    private fun applyOne(c: Conflict, resolution: Conflict.Resolution): Outcome {
        val src = File(c.sourcePath)
        val dest = File(c.destinationPath)
        if (!src.exists()) {
            return Outcome(c, false, null, "Source missing")
        }
        return when (resolution) {
            Conflict.Resolution.PENDING ->
                Outcome(c, false, null, "No resolution chosen")
            Conflict.Resolution.SKIP ->
                Outcome(c, true, null)
            Conflict.Resolution.KEEP_DESTINATION ->
                // Source is dropped; destination is unchanged.
                Outcome(c, true, dest.absolutePath)
            Conflict.Resolution.KEEP_SOURCE -> {
                if (moveOrReplace(src, dest)) Outcome(c, true, dest.absolutePath)
                else Outcome(c, false, null, "Copy/replace failed")
            }
            Conflict.Resolution.KEEP_BOTH -> {
                val renamed = uniqueName(dest)
                if (moveOrReplace(src, renamed)) Outcome(c, true, renamed.absolutePath)
                else Outcome(c, false, null, "Rename to keep-both failed")
            }
        }
    }

    /**
     * Move [src] over [dest] if [src] and [dest] are on the same filesystem,
     * otherwise fall back to copy + delete. We use a two-step fallback because
     * `File.renameTo` only works within the same mount point.
     */
    private fun moveOrReplace(src: File, dest: File): Boolean {
        if (src.renameTo(dest)) return true
        // Cross-fs fallback: copy bytes, then delete source. Best effort.
        return try {
            src.copyTo(dest, overwrite = true)
            if (src.absolutePath != dest.absolutePath) src.delete()
            true
        } catch (_: Exception) { false }
    }

    /**
     * Find a free filename of the form "<name> (1).<ext>", incrementing the
     * counter until we hit a gap. Returns the dest unchanged if for some
     * reason we can't compute a name (defensive — shouldn't happen).
     */
    private fun uniqueName(dest: File): File {
        val parent = dest.parentFile ?: return dest
        val baseName = dest.name
        val dot = baseName.lastIndexOf('.')
        val (stem, ext) = if (dot > 0) baseName.substring(0, dot) to baseName.substring(dot)
        else baseName to ""
        var counter = 1
        while (counter < 10_000) {
            val candidate = File(parent, "$stem ($counter)$ext")
            if (!candidate.exists()) return candidate
            counter++
        }
        return dest
    }
}