package com.elysium.vanguard.core.runtime.distros.ssh

import java.io.File

/**
 * PHASE 9.6.11 — OpenSSH `known_hosts` file format parser.
 *
 * The line format is:
 *
 *     <host-pattern> <key-type> <base64-key> [comment]
 *
 * Where `<host-pattern>` may be a hostname, IP, or hashed list. We
 * support the plain-text form today; the hashed form (the `|1|` base64
 * markers) reads as opaque bytes and is preserved as-is when a user
 * edits the file manually.
 *
 * Phase 9.6.11 — first build; intentionally minimal.
 */
class KnownHostsFile(
    val entries: List<Entry>
) {
    data class Entry(
        /** Hostname pattern; allows comma-list for aliases. */
        val hostPattern: String,
        val keyType: String,
        val base64Key: String,
        val comment: String?
    )

    fun serialize(): String = buildString {
        for (e in entries) {
            append(e.hostPattern).append(' ')
            append(e.keyType).append(' ')
            append(e.base64Key)
            // Comments are intentionally dropped on serialize — they
            // exist for human reading only; the canonical form is the
            // hostPattern + keyType + base64 triple.
            append('\n')
        }
    }

    fun writeTo(file: File) {
        file.writeText(serialize())
    }

    fun findMatching(host: String): Entry? {
        return entries.firstOrNull { entry ->
            hostMatches(host, entry.hostPattern)
        }
    }

    private fun hostMatches(host: String, pattern: String): Boolean {
        return pattern.split(',').any { token ->
            token.trim().let {
                when {
                    it == host -> true
                    it.startsWith("*.") -> host.endsWith(it.substring(1)) // *.example.com
                    it.contains('*') -> false // wildcard unsupported beyond leading
                    else -> false
                }
            }
        }
    }

    fun addOrReplace(entry: Entry): KnownHostsFile {
        val filtered = entries.filterNot { it.hostPattern == entry.hostPattern && it.keyType == entry.keyType }
        return KnownHostsFile(filtered + entry)
    }

    companion object {
        fun fromText(text: String): KnownHostsFile {
            val result = ArrayList<Entry>()
            for (line in text.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                val parsed = parseLine(trimmed) ?: continue
                result += parsed
            }
            return KnownHostsFile(result)
        }

        fun fromFile(file: File): KnownHostsFile {
            if (!file.isFile) return KnownHostsFile(emptyList())
            return fromText(file.readText())
        }

        private fun parseLine(line: String): Entry? {
            // Split on whitespace, but keep the comment together.
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 3) return null
            val (hostPattern, keyType, base64, comment) = Quad(parts[0], parts[1], parts[2], parts.getOrNull(3))
            return Entry(hostPattern, keyType, base64, comment)
        }
    }

    private data class Quad(val a: String, val b: String, val c: String, val d: String?)
}
