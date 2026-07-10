package com.elysium.vanguard.core.runtime.distros.snapshot

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * PHASE 9.6.12 — Layer content hasher.
 *
 * Computes a stable SHA-256 over an entire rootfs tree. The hash is
 * content-addressable: same file contents in the same paths → same
 * hash. We accumulate file content bytes (not just metadata) so two
 * layers that differ only in file *contents* hash differently.
 *
 * Direction-sensitivity: we walk the tree in lex-order so the hash is
 * reproducible across platforms, regardless of inode order.
 *
 * Phase 9.6.12 — first build; intentionally minimal.
 */
object LayerHasher {

    /**
     * Compute the SHA-256 hex digest over [rootfsDir]. Returns
     * `null` if [rootfsDir] is not a directory.
     */
    fun sha256(rootfsDir: File): String? {
        if (!rootfsDir.isDirectory) return null
        val digest = MessageDigest.getInstance("SHA-256")
        walkForHash(rootfsDir, rootfsDir, digest)
        return digest.digest().toHex()
    }

    /**
     * Compute a "fast" hash over a single file (used for incremental
     * layer updates when only one file changed).
     */
    fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().toHex()
    }

    private fun walkForHash(
        base: File,
        node: File,
        digest: MessageDigest
    ) {
        // Mix the path first so two layers with same content but
        // different file names hash differently. Path is relative
        // to the rootfs base.
        val relPath = node.relativeToOrNull(base)?.invariantSeparatorsPath
            ?: node.invariantSeparatorsPath
        digest.update(relPath.toByteArray(Charsets.UTF_8))
        if (node.isDirectory) {
            // Mark the directory entry so a renamed file and a renamed
            // dir-with-same-name don't collide.
            digest.update(0.toByte()) // 0 = directory
            val children = node.listFiles() ?: return
            for (child in children.sortedBy { it.invariantSeparatorsPath }) {
                walkForHash(base, child, digest)
            }
        } else if (node.isFile) {
            digest.update(1.toByte()) // 1 = file
            // File size prefix so two files with different sizes but
            // same partial reads don't accidentally produce the same hash.
            val size = node.length()
            digest.update(longToBytes(size))
            FileInputStream(node).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    digest.update(buffer, 0, n)
                }
            }
        }
    }

    private fun longToBytes(value: Long): ByteArray {
        return ByteArray(8) { ((value shr ((7 - it) * 8)) and 0xFF).toByte() }
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte) }
}
