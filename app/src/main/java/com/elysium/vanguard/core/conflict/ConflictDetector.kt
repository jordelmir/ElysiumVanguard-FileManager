package com.elysium.vanguard.core.conflict

import java.io.File
import java.security.MessageDigest

/**
 * PHASE 1.10 — Detects conflicts before a batch copy/move.
 *
 * Given a list of [sourcePaths] and a [destinationDir], walks the list and
 * figures out which sources would clash with existing files in [destinationDir].
 *
 * The classification is two-pass:
 *   1. By name — any source whose name matches an existing file in the
 *      destination is a [Conflict.Kind.NAME] candidate.
 *   2. By content hash — for each name conflict, we hash the first 4 KB of
 *      both files. If they match exactly (or both are empty), we promote the
 *      conflict to [Conflict.Kind.DUPLICATE].
 *
 * Why hash only the head: a full SHA-256 would dominate the runtime for large
 * files. A 4 KB head collision is vanishingly unlikely between non-duplicate
 * files of the same extension (≤ 1 in 2^32 for random bytes), which is good
 * enough for the "true duplicate" detection this method performs.
 */
class ConflictDetector {

    /**
     * Scan [sourcePaths] against [destinationDir] and return a list of conflicts
     * in input order. Source files that don't have a name collision are simply
     * omitted from the result.
     */
    fun detect(sourcePaths: List<String>, destinationDir: File): List<Conflict> {
        if (sourcePaths.isEmpty() || !destinationDir.isDirectory) return emptyList()
        val dest = destinationDir.canonicalFile
        val out = mutableListOf<Conflict>()
        for (sourcePath in sourcePaths) {
            val src = File(sourcePath)
            if (!src.exists()) continue
            val candidate = File(dest, src.name)
            if (!candidate.exists()) continue
            val kind = classify(src, candidate)
            out.add(Conflict(
                sourcePath = src.absolutePath,
                sourceName = src.name,
                sourceSize = src.length(),
                destinationPath = candidate.absolutePath,
                destinationName = candidate.name,
                destinationSize = candidate.length(),
                kind = kind
            ))
        }
        return out
    }

    private fun classify(source: File, destination: File): Conflict.Kind {
        // Different sizes → definitely different content.
        if (source.length() != destination.length()) return Conflict.Kind.NAME
        // Same size; hash heads.
        val sHead = headSha256(source)
        val dHead = headSha256(destination)
        return if (sHead.contentEquals(dHead)) Conflict.Kind.DUPLICATE
        else Conflict.Kind.NAME
    }

    private fun headSha256(file: File, bytes: Int = 4 * 1024): ByteArray? {
        if (file.length() == 0L) return ByteArray(0)
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(bytes)
                val read = input.read(buf)
                if (read <= 0) return@use
                md.update(buf, 0, read)
            }
            md.digest()
        } catch (_: Exception) {
            null
        }
    }
}