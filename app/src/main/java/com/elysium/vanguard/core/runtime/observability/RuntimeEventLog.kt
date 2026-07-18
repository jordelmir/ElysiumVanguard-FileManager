package com.elysium.vanguard.core.runtime.observability

import java.io.File
import java.io.IOException

/**
 * Phase 25 — the persistent event log.
 *
 * The [RuntimeEventLog] is the file-backed audit log. Every
 * event the bus receives can be appended to the log via
 * [append]. The log is a sequence of JSON Lines
 * (newline-delimited JSON) — each line is a self-
 * contained JSON object. The format is append-only;
 * reads via [readAll] parse the file in order.
 *
 * The log is the durable counterpart to the in-memory
 * bus. A user that wants to share a bug report with
 * the support team can attach the log file; the file
 * is human-readable (each line is a JSON object) and
 * machine-parseable (no proprietary binary format).
 *
 * We use a hand-rolled JSON writer for the event's
 * fields; the Android `org.json` library is a stub on
 * the unit-test classpath (under
 * `isReturnDefaultValues = true`).
 */
class RuntimeEventLog(
    private val logFile: File,
    private val clock: () -> Long = System::currentTimeMillis
) {
    init {
        logFile.parentFile?.mkdirs()
    }

    /** Append [event] to the log file. The append is
     *  atomic for small writes (< 4 KB on most filesystems)
     *  but the bus-side concurrency is the caller's
     *  responsibility; the [SynchronizedEventLogAdapter]
     *  serialises. */
    @Synchronized
    @Throws(IOException::class)
    fun append(event: RuntimeEvent) {
        val json = renderEvent(event)
        logFile.appendText(json + "\n")
    }

    /** Read every event from the log file, in append
     *  order. Returns an empty list when the file does
     *  not exist. */
    @Throws(IOException::class)
    fun readAll(): List<RuntimeEvent> {
        if (!logFile.isFile) return emptyList()
        val out = mutableListOf<RuntimeEvent>()
        for (line in logFile.readLines()) {
            if (line.isBlank()) continue
            val event = parseEvent(line) ?: continue
            out += event
        }
        return out
    }

    /** Truncate the log. */
    @Synchronized
    fun clear() {
        if (logFile.isFile) logFile.delete()
    }

    fun size(): Long = if (logFile.isFile) logFile.length() else 0L
    fun isEmpty(): Boolean = size() == 0L

    // --- JSON rendering ---

    /**
     * Render [event] as a single-line JSON object. The
     * shape is intentionally small: `kind`, `atMs`,
     * `workspaceId`, and the event-specific fields. A
     * reader can identify the event type via the `kind`
     * field.
     */
    private fun renderEvent(event: RuntimeEvent): String = when (event) {
        is RuntimeEvent.NetworkDecisionEvent ->
            jsonObject(
                "kind" to "NetworkDecision",
                "atMs" to event.atMs,
                "workspaceId" to (event.workspaceId ?: ""),
                "sessionId" to event.sessionId,
                "dest" to event.dest,
                "port" to event.port,
                "decision" to event.decision
            )
        is RuntimeEvent.HardwareDecisionEvent ->
            jsonObject(
                "kind" to "HardwareDecision",
                "atMs" to event.atMs,
                "workspaceId" to (event.workspaceId ?: ""),
                "sessionId" to event.sessionId,
                "hardwareClass" to event.hardwareClass,
                "action" to event.action,
                "decision" to event.decision
            )
        is RuntimeEvent.WorkspaceStateChangedEvent ->
            jsonObject(
                "kind" to "WorkspaceStateChanged",
                "atMs" to event.atMs,
                "workspaceId" to event.workspaceId,
                "fromState" to event.fromState,
                "toState" to event.toState
            )
        is RuntimeEvent.SessionAddedEvent ->
            jsonObject(
                "kind" to "SessionAdded",
                "atMs" to event.atMs,
                "workspaceId" to event.workspaceId,
                "sessionId" to event.sessionId,
                "sessionKind" to event.sessionKind
            )
        is RuntimeEvent.SessionRemovedEvent ->
            jsonObject(
                "kind" to "SessionRemoved",
                "atMs" to event.atMs,
                "workspaceId" to event.workspaceId,
                "sessionId" to event.sessionId
            )
        is RuntimeEvent.SessionStartedEvent ->
            jsonObject(
                "kind" to "SessionStarted",
                "atMs" to event.atMs,
                "workspaceId" to event.workspaceId,
                "sessionId" to event.sessionId,
                "sessionKind" to event.kind,
                "launcherKind" to (event.launcherKind ?: ""),
                "pid" to event.pid
            )
        is RuntimeEvent.SessionStoppedEvent ->
            jsonObject(
                "kind" to "SessionStopped",
                "atMs" to event.atMs,
                "workspaceId" to event.workspaceId,
                "sessionId" to event.sessionId,
                "exitCode" to event.exitCode
            )
        is RuntimeEvent.SessionStartFailedEvent ->
            jsonObject(
                "kind" to "SessionStartFailed",
                "atMs" to event.atMs,
                "workspaceId" to event.workspaceId,
                "sessionId" to event.sessionId,
                "sessionKind" to event.kind,
                "error" to event.error
            )
        is RuntimeEvent.VmStateChangedEvent ->
            jsonObject(
                "kind" to "VmStateChanged",
                "atMs" to event.atMs,
                "workspaceId" to (event.workspaceId ?: ""),
                "vmId" to event.vmId,
                "fromState" to event.fromState,
                "toState" to event.toState
            )
        is RuntimeEvent.DistroInstalledEvent ->
            jsonObject(
                "kind" to "DistroInstalled",
                "atMs" to event.atMs,
                "workspaceId" to (event.workspaceId ?: ""),
                "distroId" to event.distroId,
                "profileId" to event.profileId,
                "elapsedMs" to event.elapsedMs
            )
        is RuntimeEvent.DistroInstallFailedEvent ->
            jsonObject(
                "kind" to "DistroInstallFailed",
                "atMs" to event.atMs,
                "workspaceId" to (event.workspaceId ?: ""),
                "distroId" to event.distroId,
                "error" to event.error
            )
        is RuntimeEvent.SnapshotCreatedEvent ->
            jsonObject(
                "kind" to "SnapshotCreated",
                "atMs" to event.atMs,
                "workspaceId" to event.workspaceId,
                "snapshotId" to event.snapshotId,
                "label" to event.label,
                "copyStrategy" to event.copyStrategy
            )
        is RuntimeEvent.SnapshotRestoredEvent ->
            jsonObject(
                "kind" to "SnapshotRestored",
                "atMs" to event.atMs,
                "workspaceId" to event.workspaceId,
                "snapshotId" to event.snapshotId,
                "label" to event.label
            )
        is RuntimeEvent.SnapshotDeletedEvent ->
            jsonObject(
                "kind" to "SnapshotDeleted",
                "atMs" to event.atMs,
                "workspaceId" to event.workspaceId,
                "snapshotId" to event.snapshotId
            )
        is RuntimeEvent.MountAllowedEvent ->
            jsonObject(
                "kind" to "MountAllowed",
                "atMs" to event.atMs,
                "workspaceId" to event.workspaceId,
                "sessionId" to event.sessionId,
                "hostPath" to event.hostPath,
                "guestPath" to event.guestPath,
                "readOnly" to event.readOnly
            )
        is RuntimeEvent.MountPolicyViolationEvent ->
            jsonObject(
                "kind" to "MountPolicyViolation",
                "atMs" to event.atMs,
                "workspaceId" to event.workspaceId,
                "sessionId" to event.sessionId,
                "hostPath" to event.hostPath,
                "guestPath" to event.guestPath,
                "reason" to event.reason
            )
    }

    private fun jsonObject(vararg pairs: Pair<String, Any?>): String {
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in pairs) {
            if (!first) sb.append(",")
            first = false
            sb.append("\"").append(escapeJson(k)).append("\":")
            appendJsonValue(sb, v)
        }
        sb.append("}")
        return sb.toString()
    }

    private fun appendJsonValue(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(value.toString())
            is Number -> sb.append(value.toString())
            is String -> sb.append("\"").append(escapeJson(value)).append("\"")
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

    /**
     * Minimal JSON Line parser. The format is one JSON
     * object per line; we extract the `kind` field to
     * know which subclass to construct, then parse the
     * remaining fields with the hand-rolled parser. The
     * parser is intentionally narrow — it knows the
     * fields the writer emits and nothing more.
     */
    private fun parseEvent(line: String): RuntimeEvent? {
        val map = parseFlatJsonObject(line) ?: return null
        val kind = map["kind"] ?: return null
        val atMs = map["atMs"]?.toLongOrNull() ?: return null
        val workspaceId = map["workspaceId"]?.takeIf { it.isNotEmpty() }
        return when (kind) {
            "NetworkDecision" -> RuntimeEvent.NetworkDecisionEvent(
                atMs = atMs,
                workspaceId = workspaceId,
                sessionId = map["sessionId"] ?: "",
                dest = map["dest"] ?: "",
                port = map["port"]?.toIntOrNull() ?: 0,
                decision = map["decision"] ?: ""
            )
            "HardwareDecision" -> RuntimeEvent.HardwareDecisionEvent(
                atMs = atMs,
                workspaceId = workspaceId,
                sessionId = map["sessionId"] ?: "",
                hardwareClass = map["hardwareClass"] ?: "",
                action = map["action"] ?: "",
                decision = map["decision"] ?: ""
            )
            "WorkspaceStateChanged" -> RuntimeEvent.WorkspaceStateChangedEvent(
                atMs = atMs,
                workspaceId = map["workspaceId"] ?: "",
                fromState = map["fromState"] ?: "",
                toState = map["toState"] ?: ""
            )
            "SessionAdded" -> RuntimeEvent.SessionAddedEvent(
                atMs = atMs,
                workspaceId = map["workspaceId"] ?: "",
                sessionId = map["sessionId"] ?: "",
                sessionKind = map["sessionKind"] ?: ""
            )
            "SessionRemoved" -> RuntimeEvent.SessionRemovedEvent(
                atMs = atMs,
                workspaceId = map["workspaceId"] ?: "",
                sessionId = map["sessionId"] ?: ""
            )
            "VmStateChanged" -> RuntimeEvent.VmStateChangedEvent(
                atMs = atMs,
                workspaceId = workspaceId,
                vmId = map["vmId"] ?: "",
                fromState = map["fromState"] ?: "",
                toState = map["toState"] ?: ""
            )
            "DistroInstalled" -> RuntimeEvent.DistroInstalledEvent(
                atMs = atMs,
                workspaceId = workspaceId,
                distroId = map["distroId"] ?: "",
                profileId = map["profileId"] ?: "",
                elapsedMs = map["elapsedMs"]?.toLongOrNull() ?: 0L
            )
            "DistroInstallFailed" -> RuntimeEvent.DistroInstallFailedEvent(
                atMs = atMs,
                workspaceId = workspaceId,
                distroId = map["distroId"] ?: "",
                error = map["error"] ?: ""
            )
            "SnapshotCreated" -> RuntimeEvent.SnapshotCreatedEvent(
                atMs = atMs,
                workspaceId = map["workspaceId"] ?: "",
                snapshotId = map["snapshotId"] ?: "",
                label = map["label"] ?: "",
                copyStrategy = map["copyStrategy"] ?: ""
            )
            "SnapshotRestored" -> RuntimeEvent.SnapshotRestoredEvent(
                atMs = atMs,
                workspaceId = map["workspaceId"] ?: "",
                snapshotId = map["snapshotId"] ?: "",
                label = map["label"] ?: ""
            )
            "SnapshotDeleted" -> RuntimeEvent.SnapshotDeletedEvent(
                atMs = atMs,
                workspaceId = map["workspaceId"] ?: "",
                snapshotId = map["snapshotId"] ?: ""
            )
            "MountAllowed" -> RuntimeEvent.MountAllowedEvent(
                atMs = atMs,
                workspaceId = map["workspaceId"] ?: "",
                sessionId = map["sessionId"] ?: "",
                hostPath = map["hostPath"] ?: "",
                guestPath = map["guestPath"] ?: "",
                readOnly = map["readOnly"]?.toBooleanStrictOrNull() ?: false
            )
            "MountPolicyViolation" -> RuntimeEvent.MountPolicyViolationEvent(
                atMs = atMs,
                workspaceId = map["workspaceId"] ?: "",
                sessionId = map["sessionId"] ?: "",
                hostPath = map["hostPath"] ?: "",
                guestPath = map["guestPath"] ?: "",
                reason = map["reason"] ?: ""
            )
            else -> null
        }
    }

    /**
     * Parse a flat JSON object (one level, string keys,
     * string or numeric values) into a `Map<String,
     * String>`. The parser is hand-rolled (no library
     * dependency) and narrow: it handles the shape the
     * writer emits and nothing more. Nested objects or
     * arrays are not supported.
     */
    private fun parseFlatJsonObject(json: String): Map<String, String>? {
        val trimmed = json.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        val inner = trimmed.substring(1, trimmed.length - 1).trim()
        if (inner.isEmpty()) return emptyMap()
        val out = mutableMapOf<String, String>()
        var i = 0
        while (i < inner.length) {
            // Skip whitespace + comma
            while (i < inner.length && (inner[i] == ' ' || inner[i] == ',')) i++
            if (i >= inner.length) break
            // Read key (quoted)
            if (inner[i] != '"') return null
            i++
            val keyEnd = findStringEnd(inner, i) ?: return null
            val key = unescapeJson(inner.substring(i, keyEnd))
            i = keyEnd + 1
            // Skip colon
            while (i < inner.length && inner[i] == ' ') i++
            if (i >= inner.length || inner[i] != ':') return null
            i++
            while (i < inner.length && inner[i] == ' ') i++
            // Read value (string or number)
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
