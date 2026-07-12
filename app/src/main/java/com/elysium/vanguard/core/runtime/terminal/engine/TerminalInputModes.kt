package com.elysium.vanguard.core.runtime.terminal.engine

/**
 * Input protocol negotiated by the program running inside the PTY.
 *
 * These modes are deliberately immutable: parser writes occur on the PTY I/O
 * coroutine while Android key events happen on the UI thread, so publishing
 * one value prevents the UI from observing a half-updated protocol state.
 */
internal data class TerminalInputModes(
    /** DECCKM (`CSI ? 1 h`): arrows use SS3 (`ESC O A`) instead of CSI. */
    val applicationCursorKeys: Boolean = false,
    /** Bracketed paste (`CSI ? 2004 h`): pasted bytes must be delimited. */
    val bracketedPaste: Boolean = false
) {
    companion object {
        val DEFAULT = TerminalInputModes()
    }
}
