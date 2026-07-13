package com.elysium.vanguard.core.runtime.terminal.input

import com.elysium.vanguard.core.runtime.terminal.engine.TerminalInputModes

/** Keys with a defined VT/xterm byte sequence. */
internal enum class TerminalKey {
    ENTER,
    BACKSPACE,
    TAB,
    ESCAPE,
    UP,
    DOWN,
    RIGHT,
    LEFT,
    HOME,
    END,
    INSERT,
    DELETE,
    PAGE_UP,
    PAGE_DOWN,
    F1,
    F2,
    F3,
    F4,
    F5,
    F6,
    F7,
    F8,
    F9,
    F10,
    F11,
    F12
}

/**
 * Encodes Android input concepts as the VT bytes a Linux program expects.
 *
 * The class is Android-free so its compatibility table is covered by regular
 * JVM tests. It intentionally does not invent mouse/touch reporting: those
 * sequences are only enabled after the terminal has a real pointer policy.
 */
internal object TerminalInputEncoder {
    private const val ESC = '\u001b'

    fun key(
        key: TerminalKey,
        modes: TerminalInputModes = TerminalInputModes.DEFAULT,
        shift: Boolean = false,
        alt: Boolean = false,
        ctrl: Boolean = false
    ): ByteArray? {
        if (key == TerminalKey.TAB) return if (shift && !alt && !ctrl) bytes("${ESC}[Z") else byteArrayOf(0x09)
        if (key == TerminalKey.ENTER) return bytes("\r")
        if (key == TerminalKey.BACKSPACE) return byteArrayOf(0x7f)
        if (key == TerminalKey.ESCAPE) return bytes("$ESC")

        val modifier = modifierCode(shift, alt, ctrl)
        val sequence = when (key) {
            TerminalKey.UP -> cursor("A", modes, modifier)
            TerminalKey.DOWN -> cursor("B", modes, modifier)
            TerminalKey.RIGHT -> cursor("C", modes, modifier)
            TerminalKey.LEFT -> cursor("D", modes, modifier)
            TerminalKey.HOME -> homeEnd("H", modes, modifier)
            TerminalKey.END -> homeEnd("F", modes, modifier)
            TerminalKey.INSERT -> tilde(2, modifier)
            TerminalKey.DELETE -> tilde(3, modifier)
            TerminalKey.PAGE_UP -> tilde(5, modifier)
            TerminalKey.PAGE_DOWN -> tilde(6, modifier)
            TerminalKey.F1 -> function("P", modifier)
            TerminalKey.F2 -> function("Q", modifier)
            TerminalKey.F3 -> function("R", modifier)
            TerminalKey.F4 -> function("S", modifier)
            TerminalKey.F5 -> tilde(15, modifier)
            TerminalKey.F6 -> tilde(17, modifier)
            TerminalKey.F7 -> tilde(18, modifier)
            TerminalKey.F8 -> tilde(19, modifier)
            TerminalKey.F9 -> tilde(20, modifier)
            TerminalKey.F10 -> tilde(21, modifier)
            TerminalKey.F11 -> tilde(23, modifier)
            TerminalKey.F12 -> tilde(24, modifier)
            TerminalKey.ENTER,
            TerminalKey.BACKSPACE,
            TerminalKey.TAB,
            TerminalKey.ESCAPE -> return null
        }
        return bytes(sequence)
    }

    /** Prefixes Meta/Alt text with ESC, the convention used by readline/vim. */
    fun text(codePoint: Int, alt: Boolean = false): ByteArray {
        require(Character.isValidCodePoint(codePoint)) { "invalid Unicode code point" }
        val text = String(Character.toChars(codePoint))
        return bytes(if (alt) "$ESC$text" else text)
    }

    /** Ctrl+A…Ctrl+Z are ASCII control bytes; Alt remains an ESC prefix. */
    fun controlLetter(letter: Char, alt: Boolean = false): ByteArray {
        require(letter.uppercaseChar() in 'A'..'Z') { "control letter must be A-Z" }
        val control = (letter.uppercaseChar().code and 0x1f).toByte()
        return if (alt) byteArrayOf(0x1b, control) else byteArrayOf(control)
    }

    /** Uses xterm's bracketed-paste delimiters only after the guest enables it. */
    fun paste(bytes: ByteArray, modes: TerminalInputModes): ByteArray {
        if (!modes.bracketedPaste || bytes.isEmpty()) return bytes.copyOf()
        val open = "${ESC}[200~".toByteArray(Charsets.US_ASCII)
        val close = "${ESC}[201~".toByteArray(Charsets.US_ASCII)
        return ByteArray(open.size + bytes.size + close.size).also { encoded ->
            open.copyInto(encoded)
            bytes.copyInto(encoded, open.size)
            close.copyInto(encoded, open.size + bytes.size)
        }
    }

    private fun cursor(final: String, modes: TerminalInputModes, modifier: Int?): String = when {
        modifier != null -> "$ESC[1;${modifier}$final"
        modes.applicationCursorKeys -> "${ESC}O$final"
        else -> "$ESC[$final"
    }

    private fun homeEnd(final: String, modes: TerminalInputModes, modifier: Int?): String = when {
        modifier != null -> "$ESC[1;${modifier}$final"
        modes.applicationCursorKeys -> "${ESC}O$final"
        else -> "$ESC[$final"
    }

    private fun function(final: String, modifier: Int?): String =
        if (modifier == null) "${ESC}O$final" else "$ESC[1;${modifier}$final"

    private fun tilde(code: Int, modifier: Int?): String =
        if (modifier == null) "$ESC[${code}~" else "$ESC[${code};${modifier}~"

    private fun modifierCode(shift: Boolean, alt: Boolean, ctrl: Boolean): Int? {
        val bits = (if (shift) 1 else 0) or (if (alt) 2 else 0) or (if (ctrl) 4 else 0)
        return if (bits == 0) null else bits + 1
    }

    private fun bytes(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)
}
