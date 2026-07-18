package com.elysium.vanguard.core.runtime.policy

import java.io.File

/**
 * Phase 50 — the append-only mount decision
 * audit log.
 *
 * The audit log is the runtime's forensic record
 * of every mount decision the
 * [MountPolicyEnforcer] has made. Each entry is
 * a [MountAuditEntry] (timestamp, workspaceId,
 * sessionId, hostPath, guestPath, decision,
 * reason). The log is append-only; the only
 * mutation is [clear] (used by tests + a future
 * "rotate" job).
 *
 * The log is a small, focused surface — not a
 * general event stream. A user with a security
 * question can attach the log file to a support
 * ticket and the support team can answer "what
 * was this session allowed to see" in one
 * query, without parsing the runtime event log.
 *
 * The interface is JVM-testable; the production
 * impl is a file-backed NDJSON writer at
 * `<filesDir>/runtime/mount-audit.ndjson`.
 */
interface MountAuditLog {

    /**
     * Append [entry] to the log. The append is
     * atomic for small writes; the impl is
     * responsible for serialising concurrent
     * appends (the [FileMountAuditLog] uses a
     * `@Synchronized` lock).
     */
    fun append(entry: MountAuditEntry)

    /**
     * Read every entry from the log in append
     * order. Returns an empty list when the log
     * is empty or missing.
     */
    fun readAll(): List<MountAuditEntry>

    /** Truncate the log. Used by tests + a
     *  future rotation job. */
    fun clear()

    /** Total bytes on disk (0 if the log is
     *  empty). */
    fun size(): Long

    /** True iff [size] is 0. */
    fun isEmpty(): Boolean = size() == 0L
}

/**
 * A single mount decision.
 *
 * The [decision] is one of `"Allowed"`,
 * `"Denied"`, or `"ReadOnlyTightened"`. The
 * [reason] is a human-readable explanation; for
 * a `Denied` decision it is the policy's
 * reason (e.g. "hostPath '/sdcard/private' is
 * not in the workspace's mount allowlist").
 */
data class MountAuditEntry(
    val atMs: Long,
    val workspaceId: String,
    val sessionId: String,
    val hostPath: String,
    val guestPath: String,
    val decision: String,
    val reason: String
) {
    init {
        require(workspaceId.isNotBlank()) { "workspaceId must not be blank" }
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(hostPath.isNotBlank()) { "hostPath must not be blank" }
        require(guestPath.isNotBlank()) { "guestPath must not be blank" }
        require(decision in ALLOWED_DECISIONS) {
            "decision must be one of $ALLOWED_DECISIONS, was '$decision'"
        }
    }

    companion object {
        const val DECISION_ALLOWED: String = "Allowed"
        const val DECISION_DENIED: String = "Denied"
        const val DECISION_READ_ONLY_TIGHTENED: String = "ReadOnlyTightened"

        val ALLOWED_DECISIONS: Set<String> = setOf(
            DECISION_ALLOWED,
            DECISION_DENIED,
            DECISION_READ_ONLY_TIGHTENED
        )
    }
}

/**
 * Production [MountAuditLog] impl. Writes one
 * JSON object per line to [logFile]. The file's
 * parent directory is created on construction.
 *
 * The append is `@Synchronized` to serialise
 * concurrent appends from multiple threads.
 * The reads ([readAll]) are not synchronised;
 * a concurrent append during a read may or may
 * not appear in the result. (Phase 50 does not
 * promise a consistent read; a future
 * "consistent read" mode is a trivial
 * follow-up.)
 */
class FileMountAuditLog(
    private val logFile: File,
    private val clock: () -> Long = System::currentTimeMillis
) : MountAuditLog {

    init {
        logFile.parentFile?.mkdirs()
    }

    @Synchronized
    override fun append(entry: MountAuditEntry) {
        val json = renderEntry(entry)
        logFile.appendText(json + "\n")
    }

    override fun readAll(): List<MountAuditEntry> {
        if (!logFile.isFile) return emptyList()
        val out = mutableListOf<MountAuditEntry>()
        for (line in logFile.readLines()) {
            if (line.isBlank()) continue
            val entry = parseEntry(line) ?: continue
            out += entry
        }
        return out
    }

    @Synchronized
    override fun clear() {
        if (logFile.isFile) logFile.delete()
    }

    override fun size(): Long = if (logFile.isFile) logFile.length() else 0L

    // --- JSON rendering ---

    private fun renderEntry(entry: MountAuditEntry): String {
        val sb = StringBuilder("{")
        appendField(sb, "atMs", entry.atMs, quoted = false, first = true)
        appendField(sb, "workspaceId", entry.workspaceId, quoted = true, first = false)
        appendField(sb, "sessionId", entry.sessionId, quoted = true, first = false)
        appendField(sb, "hostPath", entry.hostPath, quoted = true, first = false)
        appendField(sb, "guestPath", entry.guestPath, quoted = true, first = false)
        appendField(sb, "decision", entry.decision, quoted = true, first = false)
        appendField(sb, "reason", entry.reason, quoted = true, first = false)
        sb.append("}")
        return sb.toString()
    }

    private fun appendField(
        sb: StringBuilder,
        key: String,
        value: Any?,
        quoted: Boolean,
        first: Boolean
    ) {
        if (!first) sb.append(",")
        sb.append("\"").append(escapeJson(key)).append("\":")
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(value.toString())
            is Number -> sb.append(value.toString())
            is String -> {
                if (quoted) sb.append("\"").append(escapeJson(value)).append("\"")
                else sb.append(escapeJson(value))
            }
            else -> sb.append("\"").append(escapeJson(value.toString())).append("\"")
        }
    }

    private fun escapeJson(value: String): String {
        val sb = StringBuilder()
        for (c in value) {
            when {
                c == '\\' -> sb.append("\\\\")
                c == '"' -> sb.append("\\\"")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun parseEntry(line: String): MountAuditEntry? {
        val map = parseFlatJsonObject(line) ?: return null
        val atMs = map["atMs"]?.toLongOrNull() ?: return null
        return MountAuditEntry(
            atMs = atMs,
            workspaceId = map["workspaceId"] ?: return null,
            sessionId = map["sessionId"] ?: return null,
            hostPath = map["hostPath"] ?: return null,
            guestPath = map["guestPath"] ?: return null,
            decision = map["decision"] ?: return null,
            reason = map["reason"] ?: return null
        )
    }

    /**
     * Parse a flat JSON object (one level, string
     * keys, string or numeric values) into a
     * `Map<String, String>`. Same parser the
     * [com.elysium.vanguard.core.runtime.observability.RuntimeEventLog]
     * uses — duplicated here to keep the
     * MountAuditLog self-contained.
     */
    private fun parseFlatJsonObject(json: String): Map<String, String>? {
        val trimmed = json.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        val inner = trimmed.substring(1, trimmed.length - 1).trim()
        if (inner.isEmpty()) return emptyMap()
        val out = mutableMapOf<String, String>()
        var i = 0
        while (i < inner.length) {
            while (i < inner.length && (inner[i] == ' ' || inner[i] == ',')) i++
            if (i >= inner.length) break
            if (inner[i] != '"') return null
            i++
            val keyEnd = findStringEnd(inner, i) ?: return null
            val key = unescapeJson(inner.substring(i, keyEnd))
            i = keyEnd + 1
            while (i < inner.length && inner[i] == ' ') i++
            if (i >= inner.length || inner[i] != ':') return null
            i++
            while (i < inner.length && inner[i] == ' ') i++
            val value: String
            if (inner[i] == '"') {
                i++
                val valueEnd = findStringEnd(inner, i) ?: return null
                value = unescapeJson(inner.substring(i, valueEnd))
                i = valueEnd + 1
            } else {
                val start = i
                while (i < inner.length && inner[i] != ',' && inner[i] != ' ') i++
                value = inner.substring(start, i)
            }
            out[key] = value
        }
        return out
    }

    private fun findStringEnd(s: String, start: Int): Int? {
        var i = start
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i += 2
                '"' -> return i
                else -> i++
            }
        }
        return null
    }

    private fun unescapeJson(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val next = s[i + 1]) {
                    '\\' -> sb.append('\\')
                    '"' -> sb.append('"')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (i + 5 < s.length) {
                            val hex = s.substring(i + 2, i + 6)
                            sb.append(hex.toInt(16).toChar())
                            i += 4
                        }
                    }
                    else -> sb.append(next)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
