package com.elysium.vanguard.core.crdt

import java.io.File

/**
 * PHASE 9.11 — Companion sync file for Elysium documents.
 *
 * A `.elysium.sync` file lives next to a `.elysium.word/.sheet/.deck`
 * document and stores the [CrdtOpLog] of all CRDT ops ever applied
 * to that document on this node. The companion file lets two
 * devices pick up where they left off after a full offline period:
 * each side reads the other's log, replays it locally, and merges
 * in any new ops since their lastSeenHlc.
 *
 * Format (UTF-8 text):
 *
 *     # Elysium sync log
 *     # node: <nodeId>
 *     # lastSeen: <hlc>
 *     <CrdtOpLog.serialize()>
 *
 * The header lines are comment lines (start with `#`) so the body
 * is a valid `CrdtOpLog.serialize()` output.
 *
 * Phase 9.11 — first build; intentionally minimal.
 */
class ElysiumSyncFile(
    val documentFile: File,
    val log: CrdtOpLog,
    var lastSeen: HybridLogicalClock?,
    val nodeId: String
) {

    /**
     * Persist the companion file to disk. The companion is named
     * `<documentFile>.<nodeId>.elysium.sync` so multiple nodes
     * editing the same document don't clobber each other's logs.
     */
    fun save() {
        val file = companionFile()
        file.parentFile?.mkdirs()
        file.writeText(serialize())
    }

    fun companionFile(): File =
        File(documentFile.parentFile, "${documentFile.name}.$nodeId.elysium.sync")

    fun serialize(): String = buildString {
        appendLine("# Elysium sync log")
        appendLine("# node: $nodeId")
        val seen = lastSeen
        if (seen != null) {
            appendLine("# lastSeen: ${seen.serialize()}")
        } else {
            appendLine("# lastSeen: null")
        }
        append(log.serialize())
    }

    companion object {
        /**
         * Read a companion sync file from disk. Returns `null` if
         * the file doesn't exist or is malformed.
         */
        fun read(companionFile: File): ElysiumSyncFile? {
            if (!companionFile.isFile) return null
            val text = companionFile.readText()
            return parse(text, companionFile)
        }

        /**
         * Read the companion sync file associated with [documentFile]
         * for the given [nodeId]. Returns `null` if no companion
         * exists or it's malformed.
         */
        fun readFor(documentFile: File, nodeId: String): ElysiumSyncFile? {
            val cf = File(documentFile.parentFile, "${documentFile.name}.$nodeId.elysium.sync")
            return read(cf)
        }

        /**
         * Parse a sync file from text. The companion file's
         * filename lets us associate the parsed log with a
         * document path; the parse itself only needs the text.
         */
        fun parse(text: String, documentFile: File): ElysiumSyncFile? {
            var nodeId = "unknown"
            var lastSeen: HybridLogicalClock? = null
            val logLines = StringBuilder()
            for (line in text.split('\n')) {
                when {
                    line.startsWith("# node: ") -> nodeId = line.removePrefix("# node: ").trim()
                    line.startsWith("# lastSeen: ") -> {
                        val raw = line.removePrefix("# lastSeen: ").trim()
                        lastSeen = if (raw == "null") null else HybridLogicalClock.parse(raw)
                    }
                    line.startsWith("#") -> { /* comment */ }
                    else -> {
                        logLines.append(line).append('\n')
                    }
                }
            }
            val log = CrdtOpLog().parse(logLines.toString()) ?: return null
            return ElysiumSyncFile(
                documentFile = documentFile,
                log = log,
                lastSeen = lastSeen,
                nodeId = nodeId
            )
        }

        /**
         * Build a fresh companion file for [documentFile] with an
         * empty log. Useful when a node first opens a document.
         */
        fun empty(documentFile: File, nodeId: String): ElysiumSyncFile =
            ElysiumSyncFile(
                documentFile = documentFile,
                log = CrdtOpLog(),
                lastSeen = null,
                nodeId = nodeId
            )
    }
}