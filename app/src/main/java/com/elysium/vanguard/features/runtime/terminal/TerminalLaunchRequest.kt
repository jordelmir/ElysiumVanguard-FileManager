package com.elysium.vanguard.features.runtime.terminal

import java.util.Base64

/**
 * Safe navigation payload for a command the user explicitly chose in a Linux
 * desktop entry. URL-safe Base64 keeps shell punctuation out of Compose route
 * parsing; the command is still sent as raw PTY input, never interpolated into
 * an Android shell command.
 */
internal object TerminalLaunchRequest {
    const val ARGUMENT = "initialCommand"
    private const val MAX_COMMAND_BYTES = 8 * 1024

    fun encode(command: String): String {
        val bytes = command.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= MAX_COMMAND_BYTES && '\u0000' !in command) {
            "invalid terminal launch command"
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun decode(encoded: String?): String? {
        if (encoded.isNullOrEmpty()) return null
        val bytes = runCatching { Base64.getUrlDecoder().decode(encoded) }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_COMMAND_BYTES) return null
        val command = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull() ?: return null
        return command.takeIf { '\u0000' !in it && it.isNotBlank() }
    }

    fun asTerminalInput(command: String): String = if (command.endsWith('\n')) command else "$command\n"
}
