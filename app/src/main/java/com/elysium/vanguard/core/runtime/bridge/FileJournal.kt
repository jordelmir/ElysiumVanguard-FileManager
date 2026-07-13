package com.elysium.vanguard.core.runtime.bridge

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

sealed class JournalEntry {

    abstract val id: String
    abstract val timestamp: Long
    abstract val sourcePath: String
    abstract val targetPath: String?

    data class Copy(
        override val id: String,
        override val timestamp: Long,
        override val sourcePath: String,
        override val targetPath: String,
        val sourceSha256: String,
        val sizeBytes: Long
    ) : JournalEntry()

    data class Move(
        override val id: String,
        override val timestamp: Long,
        override val sourcePath: String,
        override val targetPath: String,
        val sourceSha256: String,
        val sizeBytes: Long
    ) : JournalEntry()

    data class Delete(
        override val id: String,
        override val timestamp: Long,
        override val sourcePath: String,
        override val targetPath: String? = null,
        val backedUpTo: String?,
        val sourceSha256: String?,
        val sizeBytes: Long
    ) : JournalEntry()

    data class Mkdir(
        override val id: String,
        override val timestamp: Long,
        override val sourcePath: String,
        override val targetPath: String? = null
    ) : JournalEntry()
}

data class JournalMeta(
    val version: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val entryCount: Int = 0,
    val committed: Boolean = false
)

class FileJournal(
    private val journalDir: File,
    private val maxJournalSize: Int = DEFAULT_MAX_JOURNAL_SIZE
) {

    private val entries = mutableListOf<JournalEntry>()
    private var dirty = false

    init {
        journalDir.mkdirs()
        require(journalDir.isDirectory) { "journalDir must be a directory: $journalDir" }
    }

    fun recordCopy(source: File, target: File): JournalEntry.Copy {
        require(source.isFile) { "source must exist: $source" }
        val sha = sha256(source)
        val entry = JournalEntry.Copy(
            id = nextId("cp"),
            timestamp = now(),
            sourcePath = source.canonicalPath,
            targetPath = target.canonicalPath,
            sourceSha256 = sha,
            sizeBytes = source.length()
        )
        entries.add(entry)
        dirty = true
        return entry
    }

    fun recordMove(source: File, target: File): JournalEntry.Move {
        require(source.isFile) { "source must exist: $source" }
        val sha = sha256(source)
        val entry = JournalEntry.Move(
            id = nextId("mv"),
            timestamp = now(),
            sourcePath = source.canonicalPath,
            targetPath = target.canonicalPath,
            sourceSha256 = sha,
            sizeBytes = source.length()
        )
        entries.add(entry)
        dirty = true
        return entry
    }

    fun recordDelete(file: File, backupFile: File?): JournalEntry.Delete {
        require(file.isFile) { "source must exist: $file" }
        val sha = if (file.isFile) sha256(file) else null
        val entry = JournalEntry.Delete(
            id = nextId("rm"),
            timestamp = now(),
            sourcePath = file.canonicalPath,
            backedUpTo = backupFile?.canonicalPath,
            sourceSha256 = sha,
            sizeBytes = if (file.isFile) file.length() else 0L
        )
        entries.add(entry)
        dirty = true
        return entry
    }

    fun recordMkdir(dir: File): JournalEntry.Mkdir {
        val entry = JournalEntry.Mkdir(
            id = nextId("mkdir"),
            timestamp = now(),
            sourcePath = dir.canonicalPath
        )
        entries.add(entry)
        dirty = true
        return entry
    }

    fun commit() {
        if (!dirty && entries.isEmpty()) return
        persist()
        dirty = false
    }

    fun rollback(): List<RollbackAction> {
        val actions = mutableListOf<RollbackAction>()
        for (entry in entries.asReversed()) {
            actions += when (entry) {
                is JournalEntry.Copy -> rollbackCopy(entry)
                is JournalEntry.Move -> rollbackMove(entry)
                is JournalEntry.Delete -> rollbackDelete(entry)
                is JournalEntry.Mkdir -> rollbackMkdir(entry)
            }
        }
        entries.clear()
        journalDir.deleteRecursively()
        journalDir.mkdirs()
        dirty = false
        return actions
    }

    fun pending(): List<JournalEntry> = entries.toList()

    fun pendingOps(): Int = entries.size

    private fun rollbackCopy(entry: JournalEntry.Copy): RollbackAction {
        val target = File(entry.targetPath)
        return if (target.exists()) {
            try {
                Files.deleteIfExists(target.toPath())
                RollbackAction(entry.id, "copy", entry.sourcePath, success = true)
            } catch (e: IOException) {
                RollbackAction(entry.id, "copy", entry.sourcePath, success = false, error = e.message)
            }
        } else {
            RollbackAction(entry.id, "copy", entry.sourcePath, success = true, note = "target already gone")
        }
    }

    private fun rollbackMove(entry: JournalEntry.Move): RollbackAction {
        val target = File(entry.targetPath)
        return if (target.exists()) {
            try {
                Files.move(target.toPath(), File(entry.sourcePath).toPath(), StandardCopyOption.ATOMIC_MOVE)
                RollbackAction(entry.id, "move", entry.sourcePath, success = true)
            } catch (e: IOException) {
                try {
                    Files.move(target.toPath(), File(entry.sourcePath).toPath(), StandardCopyOption.REPLACE_EXISTING)
                    RollbackAction(entry.id, "move", entry.sourcePath, success = true, note = "non-atomic")
                } catch (e2: IOException) {
                    RollbackAction(entry.id, "move", entry.sourcePath, success = false, error = e2.message)
                }
            }
        } else {
            RollbackAction(entry.id, "move", entry.sourcePath, success = false, error = "target not found")
        }
    }

    private fun rollbackDelete(entry: JournalEntry.Delete): RollbackAction {
        val backup = entry.backedUpTo?.let { File(it) }
        return if (backup != null && backup.exists()) {
            try {
                Files.move(backup.toPath(), File(entry.sourcePath).toPath(), StandardCopyOption.ATOMIC_MOVE)
                RollbackAction(entry.id, "delete", entry.sourcePath, success = true, note = "restored from backup")
            } catch (e: IOException) {
                RollbackAction(entry.id, "delete", entry.sourcePath, success = false, error = e.message)
            }
        } else {
            RollbackAction(entry.id, "delete", entry.sourcePath, success = false, error = "no backup available")
        }
    }

    private fun rollbackMkdir(entry: JournalEntry.Mkdir): RollbackAction {
        val dir = File(entry.sourcePath)
        return try {
            Files.deleteIfExists(dir.toPath())
            RollbackAction(entry.id, "mkdir", entry.sourcePath, success = true)
        } catch (e: IOException) {
            RollbackAction(entry.id, "mkdir", entry.sourcePath, success = false, error = e.message)
        }
    }

    private fun persist() {
        journalDir.mkdirs()
        val metaFile = File(journalDir, "journal.json")
        val walDir = File(journalDir, "wal")
        walDir.mkdirs()
        val meta = JournalMeta(
            version = JOURNAL_VERSION,
            createdAt = entries.firstOrNull()?.timestamp ?: now(),
            entryCount = entries.size,
            committed = false
        )
        val json = buildString {
            appendLine("{")
            appendLine("  \"meta\": {")
            appendLine("    \"version\": ${meta.version},")
            appendLine("    \"createdAt\": ${meta.createdAt},")
            appendLine("    \"entryCount\": ${meta.entryCount},")
            appendLine("    \"committed\": ${meta.committed}")
            appendLine("  },")
            appendLine("  \"entries\": [")
            for ((i, entry) in entries.withIndex()) {
                val comma = if (i < entries.size - 1) "," else ""
                append("    ")
                entryToJson(entry, this)
                appendLine(comma)
            }
            appendLine("  ]")
            appendLine("}")
        }
        val tmpFile = File(journalDir, "journal.json.tmp")
        tmpFile.writeText(json)
        tmpFile.renameTo(metaFile)
        val committedMeta = meta.copy(committed = true)
        val committedJson = json.replace("\"committed\": false", "\"committed\": true")
        File(journalDir, "journal.committed").writeText(committedJson)
    }

    private fun entryToJson(entry: JournalEntry, sb: StringBuilder) {
        sb.append("{")
        sb.append("\"id\": \"${entry.id}\", ")
        sb.append("\"type\": \"${entry::class.simpleName?.lowercase()}\", ")
        sb.append("\"timestamp\": ${entry.timestamp}, ")
        sb.append("\"sourcePath\": \"${escape(entry.sourcePath)}\"")
        val tgt = entry.targetPath
        if (tgt != null) {
            sb.append(", \"targetPath\": \"${escape(tgt)}\"")
        }
        when (entry) {
            is JournalEntry.Copy -> {
                sb.append(", \"sourceSha256\": \"${entry.sourceSha256}\"")
                sb.append(", \"sizeBytes\": ${entry.sizeBytes}")
            }
            is JournalEntry.Move -> {
                sb.append(", \"sourceSha256\": \"${entry.sourceSha256}\"")
                sb.append(", \"sizeBytes\": ${entry.sizeBytes}")
            }
            is JournalEntry.Delete -> {
                entry.backedUpTo?.let { sb.append(", \"backedUpTo\": \"${escape(it)}\"") }
                entry.sourceSha256?.let { sb.append(", \"sourceSha256\": \"$it\"") }
                sb.append(", \"sizeBytes\": ${entry.sizeBytes}")
            }
            is JournalEntry.Mkdir -> {}
        }
        sb.append("}")
    }

    fun recover(): RecoveryResult {
        val metaFile = File(journalDir, "journal.json")
        val committedFile = File(journalDir, "journal.committed")
        if (!metaFile.exists() && !committedFile.exists()) {
            return RecoveryResult.NoJournal
        }
        val committedExists = committedFile.exists()
        if (committedExists) {
            val meta = committedFile.readText()
            if (meta.contains("\"committed\": true")) {
                journalDir.listFiles()?.filter { it.name != "wal" }?.forEach { it.delete() }
                return RecoveryResult.Committed
            }
        }
        if (metaFile.exists()) {
            return RecoveryResult.PendingRollback
        }
        return RecoveryResult.NoJournal
    }

    fun close() {
        if (dirty) persist()
        entries.clear()
        dirty = false
    }

    private fun nextId(prefix: String): String {
        val ts = System.currentTimeMillis()
        return "${prefix}_${ts}_${(Math.random() * 10000).toInt()}"
    }

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        private const val JOURNAL_VERSION = 1
        private const val DEFAULT_MAX_JOURNAL_SIZE = 10_000

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun escape(s: String): String {
            return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
        }
    }
}

data class RollbackAction(
    val entryId: String,
    val operation: String,
    val path: String,
    val success: Boolean,
    val error: String? = null,
    val note: String? = null
)

sealed class RecoveryResult {
    data object NoJournal : RecoveryResult()
    data object Committed : RecoveryResult()
    data object PendingRollback : RecoveryResult()
}
