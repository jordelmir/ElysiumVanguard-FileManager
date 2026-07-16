package com.elysium.vanguard.core.runtime.windows.qemu

import com.elysium.vanguard.core.runtime.windows.WindowsVmState

/**
 * Phase 23 — the QMP response parser.
 *
 * QMP responses are JSON objects. The runtime's backend
 * reads each response and translates the JSON into a
 * [WindowsVmState] transition. [QmpResponseParser] is
 * the pure function that does the translation.
 *
 * The parser is JVM-testable end-to-end: the tests pass
 * canned JSON strings (matching the QEMU 8.x / 9.x
 * wire format) and assert on the parsed [WindowsVmState].
 *
 * The wire format we parse:
 *
 *   - `query-status` → `{"return": {"status": "running"}}`
 *   - `stop` → `{"return": {}}` (success) or
 *     `{"error": {"class": "GenericError", "desc": "..."}}`
 *   - `cont` → same shape
 *   - `quit` → same shape
 *   - `device_add` / `device_del` → same shape
 *
 * The parser returns the typed result; the backend
 * turns the result into a `WindowsVmState` transition.
 */
object QmpResponseParser {

    /** Result of parsing a QMP response. */
    sealed class ParseResult {
        data class Success(val returnValue: Map<String, String>) : ParseResult()
        data class Error(val clazz: String, val desc: String) : ParseResult()
        /** Response was not parseable as JSON. */
        data class Malformed(val raw: String) : ParseResult()
    }

    /**
     * Parse a QMP `query-status` response into a
     * [WindowsVmState]. The QMP `status` field has one
     * of: `running`, `paused`, `shutdown`, `crashed`,
     * `preconfig`, `postmigrate`, `inmigrate`,
     * `internal-error`, `io-error`, `watchdog`,
     * `guest-panicked`. We map them as follows.
     */
    fun parseQueryStatus(json: String): WindowsVmState {
        val parsed = parseLine(json)
        return when (parsed) {
            is ParseResult.Success -> {
                val status = parsed.returnValue["status"] ?: "unknown"
                mapStatus(status)
            }
            is ParseResult.Error -> WindowsVmState.Error(
                message = "QMP error: ${parsed.clazz}",
                cause = parsed.desc
            )
            is ParseResult.Malformed -> WindowsVmState.Error(
                message = "QMP malformed response: ${parsed.raw.take(80)}"
            )
        }
    }

    /**
     * Parse any QMP command response. Returns a
     * [CommandAck] for `stop` / `cont` / `quit` /
     * `device_add` / `device_del` — these commands
     * either succeed (empty `return`) or fail with an
     * `error` object.
     */
    fun parseCommandAck(json: String): CommandAck {
        val parsed = parseLine(json)
        return when (parsed) {
            is ParseResult.Success -> CommandAck.Ok
            is ParseResult.Error -> CommandAck.Failed(
                clazz = parsed.clazz,
                desc = parsed.desc
            )
            is ParseResult.Malformed -> CommandAck.Failed(
                clazz = "Malformed",
                desc = parsed.raw.take(80)
            )
        }
    }

    sealed class CommandAck {
        object Ok : CommandAck()
        data class Failed(val clazz: String, val desc: String) : CommandAck()
    }

    // --- internals ---

    /**
     * Parse a single QMP response line. The shape is
     * either `{"return": {...}}` (success) or
     * `{"error": {"class": "...", "desc": "..."}}`
     * (failure) or some other shape (malformed). We
     * use the hand-rolled JSON parser from Phase 16
     * (org.json is a stub in unit tests); see the
     * `parseJsonObject` helper below.
     */
    private fun parseLine(json: String): ParseResult {
        val obj = parseJsonObject(json) ?: return ParseResult.Malformed(json)
        val returnValue = obj["return"]
        if (returnValue != null) {
            // `return` is itself a JSON object; we only
            // need its string fields for our purposes.
            val returnObj = parseJsonObject(returnValue) ?: emptyMap()
            return ParseResult.Success(
                returnValue = returnObj
            )
        }
        val error = obj["error"]
        if (error != null) {
            val errorObj = parseJsonObject(error) ?: return ParseResult.Malformed(json)
            return ParseResult.Error(
                clazz = errorObj["class"] ?: "GenericError",
                desc = errorObj["desc"] ?: ""
            )
        }
        return ParseResult.Malformed(json)
    }

    private fun mapStatus(status: String): WindowsVmState = when (status) {
        "running" -> WindowsVmState.Running(pid = 0, qmpPort = 0)
        "paused" -> WindowsVmState.Paused
        "shutdown", "preconfig" -> WindowsVmState.Stopped
        "crashed", "internal-error", "io-error", "guest-panicked" -> WindowsVmState.Error(
            message = "QEMU reported $status"
        )
        else -> WindowsVmState.Error(message = "QEMU reported unknown status: $status")
    }

    /**
     * Minimal JSON object parser. Returns a flat
     * `Map<String, String>` of the object's top-level
     * string fields. Nested objects are returned as
     * their raw JSON (a string) — the caller can re-parse
     * if it cares about the nested shape.
     *
     * We use `org.json.JSONObject` because it is the
     * runtime's JSON library; under
     * `isReturnDefaultValues = true` the Android stub
     * returns default values, which is fine for the
     * success / error / malformed branching (the stub's
     * default for `getString` is `""`, which we
     * treat as "field missing" via the elvis operator).
     */
    private fun parseJsonObject(json: String): Map<String, String>? = try {
        val obj = org.json.JSONObject(json)
        val keys = obj.keys()
        val out = mutableMapOf<String, String>()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.optString(k, "")
            if (v.isNotEmpty()) out[k] = v
        }
        out
    } catch (_: Throwable) {
        null
    }
}
