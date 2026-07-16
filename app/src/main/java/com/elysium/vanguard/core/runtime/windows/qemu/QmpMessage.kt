package com.elysium.vanguard.core.runtime.windows.qemu

/**
 * Phase 23 — the QMP wire-format builder.
 *
 * QMP (QEMU Machine Protocol) is JSON over a TCP socket.
 * The runtime's backend sends JSON messages and reads
 * JSON responses. [QmpMessage] is a pure function that
 * builds the *outgoing* JSON for each operation the
 * runtime issues.
 *
 * The message format is a single JSON object per line.
 * Example: `{"execute": "query-status"}\n`. The
 * response is a JSON object on its own line.
 *
 * The builder is JVM-testable end-to-end: no socket is
 * opened in the test path; the test asserts on the
 * exact JSON string the runtime would send.
 */
object QmpMessage {

    /**
     * Ask QEMU for the current VM state. The response
     * is a JSON object with a `status` field of `running`,
     * `paused`, `shutdown`, `crashed`, or `preconfig`.
     */
    fun queryStatus(): String =
        """{"execute": "query-status"}"""

    /**
     * Ask QEMU to stop the guest (QEMU `stop` command).
     * The guest's CPUs pause; the QEMU process keeps
     * running. Use [quit] to actually shut down.
     */
    fun stop(): String =
        """{"execute": "stop"}"""

    /**
     * Resume a paused guest (QEMU `cont` command). The
     * guest's CPUs run again.
     */
    fun cont(): String =
        """{"execute": "cont"}"""

    /**
     * Quit QEMU. The QEMU process exits. The runtime
     * treats this as the "stop" transition; the follow-up
     * `query-status` will return `shutdown`.
     */
    fun quit(): String =
        """{"execute": "quit"}"""

    /**
     * Attach a USB device to the guest. The runtime
     * uses this for hardware passthrough (a USB drive
     * the user plugged in, a USB license dongle, etc.).
     *
     * The device is identified by a string id; the
     * `device_add` QMP command adds a `usb-host` device
     * with the given vendor + product id.
     */
    fun deviceAdd(
        id: String,
        vendorId: Int,
        productId: Int
    ): String {
        // The JSON encodes a `device_add` command with
        // a `usb-host` driver and the bus+addr set to
        // auto. QEMU looks up the device by vendor:product
        // and binds it. `escapeJsonString` already wraps
        // the value in double quotes; the template
        // interpolates them directly.
        val safeId = escapeJsonString(id)
        val safeBus = escapeJsonString("usb.0")
        return """{"execute":"device_add","arguments":{"id":$safeId,"driver":"usb-host","bus":$safeBus,"vendorid":$vendorId,"productid":$productId}}"""
    }

    /**
     * Detach a USB device from the guest (QEMU
     * `device_del` command).
     */
    fun deviceDel(id: String): String {
        val safeId = escapeJsonString(id)
        return """{"execute":"device_del","arguments":{"id":$safeId}}"""
    }

    /**
     * Minimal JSON string escaper. QMP identifiers are
     * ASCII (alphanumeric + dash); the only characters
     * we need to escape are the backslash and the double
     * quote. We escape control characters as `\uXXXX` so
     * a malformed id never produces invalid JSON.
     */
    private fun escapeJsonString(value: String): String {
        val sb = StringBuilder("\"")
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
        sb.append("\"")
        return sb.toString()
    }
}
