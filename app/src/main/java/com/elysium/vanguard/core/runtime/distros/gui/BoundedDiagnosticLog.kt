package com.elysium.vanguard.core.runtime.distros.gui

/**
 * Thread-safe, bounded diagnostic transcript for a short-lived local process.
 *
 * The graphical launcher only surfaces this text when startup fails. It keeps
 * the newest output because that normally contains the server's fatal reason,
 * and removes control characters so terminal escape sequences cannot alter UI.
 */
internal class BoundedDiagnosticLog(private val maxChars: Int) {
    init {
        require(maxChars > 0) { "maxChars must be positive" }
    }

    private val content = StringBuilder()

    fun append(chunk: String) = synchronized(content) {
        chunk.forEach { character ->
            if (character == '\n' || character == '\t' || character >= ' ') {
                content.append(character)
            }
        }
        if (content.length > maxChars) {
            content.delete(0, content.length - maxChars)
        }
    }

    fun snapshot(): String = synchronized(content) {
        content.toString().trim()
    }
}
