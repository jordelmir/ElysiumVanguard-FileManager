package com.elysium.vanguard.core.runtime.terminal.engine

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * PHASE 9.6.1 — VT100 / ECMA-48 escape-sequence parser.
 *
 * The parser is a tiny state machine. It accepts an arbitrarily fragmented
 * stream of UTF-8 bytes, incrementally decodes it, and
 * produces mutations on a [TerminalBuffer] through a sequence of method
 * calls. It is **deliberately** a write-side-only component — the buffer
 * owns layout, the parser only emits operations like "put char" or
 * "set cursor to row 3 col 5".
 *
 * Scope:
 *
 *   - C0 controls: NUL, BEL, BS, HT, LF, VT, FF, CR, SO, SI.
 *   - CSI: cursor moves, SGR (colors + attrs), erase ops.
 *   - OSC: ignored (we don't implement window titles for 9.6.1).
 *   - DCS, ESC + single-char sequences: ignored.
 *   - 8-bit C1 controls: recognized but emitted as their 7-bit
 *     equivalent (CSI = ESC '[', OSC = ESC ']').
 *
 * Not in scope (deferred):
 *
 *   - DEC cursor-key and bracketed-paste modes, alternate buffers and
 *     scroll-region / line-edit operations used by full-screen programs.
 *   - SGR 38;5;n (256-color) and SGR 38;2;r;g;b (truecolor).
 *   - Mouse reporting (CSI ?...h / CSI ?...l).
 *   - Sixel graphics.
 *
 * Phase 9.6.1 — first build; intentionally minimal.
 */
internal class TerminalParser(
    private val buffer: TerminalBuffer
) {
    /** Per VT100 §5.4: ground, escape, csi-entry, osc-string, etc. */
    private enum class State { GROUND, ESCAPE, CSI_ENTRY, OSC_STRING, OSC_ESCAPE }
    private var state: State = State.GROUND
    private val params: ArrayList<Int> = ArrayList(8)
    private var intermediate: Char = ' '
    private var privateMarker: Char? = null
    private var oscBuffer: StringBuilder = StringBuilder(64)
    private val utf8Decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var incompleteUtf8: ByteArray = ByteArray(0)
    @Volatile
    private var negotiatedInputModes: TerminalInputModes = TerminalInputModes.DEFAULT

    /** Thread-safe snapshot read by the Android input layer. */
    fun inputModes(): TerminalInputModes = negotiatedInputModes

    /**
     * Consume raw PTY bytes. UTF-8 and escape state are both retained across
     * arbitrary read boundaries, so a split emoji, CJK character or CSI prefix
     * produces the same model as one contiguous read.
     */
    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) {
            "byte range is outside source array"
        }
        if (length == 0) return
        val combined = ByteArray(incompleteUtf8.size + length)
        incompleteUtf8.copyInto(combined)
        bytes.copyInto(combined, incompleteUtf8.size, offset, offset + length)
        val input = ByteBuffer.wrap(combined)
        val output = CharBuffer.allocate(combined.size)
        utf8Decoder.decode(input, output, false)
        incompleteUtf8 = ByteArray(input.remaining())
        input.get(incompleteUtf8)
        output.flip()
        if (output.hasRemaining()) feed(output)
    }

    /**
     * Feed a chunk of text. Caller is responsible for UTF-8 decoding;
     * we work in String/Char so JVM-native Char streams do the right
     * thing for multi-byte scripts (CJK, emoji, RTL).
     */
    fun feed(text: CharSequence) {
        for (i in 0 until text.length) {
            val c = text[i]
            // The state machine dispatch is hot (called once per byte
            // from a typical PTY), so we keep it as a tight `when`
            // rather than a hash-based strategy.
            when (state) {
                State.GROUND -> handleGround(c)
                State.ESCAPE -> handleEscape(c)
                State.CSI_ENTRY -> handleCsiEntry(c)
                State.OSC_STRING -> handleOscString(c)
                State.OSC_ESCAPE -> handleOscEscape(c)
            }
        }
    }

    /** Flushes a final incomplete UTF-8 sequence as replacement text. */
    fun finishInput() {
        if (incompleteUtf8.isEmpty()) return
        val input = ByteBuffer.wrap(incompleteUtf8)
        val output = CharBuffer.allocate(incompleteUtf8.size)
        utf8Decoder.decode(input, output, true)
        utf8Decoder.flush(output)
        utf8Decoder.reset()
        incompleteUtf8 = ByteArray(0)
        output.flip()
        if (output.hasRemaining()) feed(output)
    }

    private fun handleGround(c: Char) {
        when (c) {
            in '\u0000'..'\u0017' -> handleC0(c)
            '\u007f' -> { /* DEL — ignored */ }
            '\u001b' -> {
                state = State.ESCAPE
                resetParams()
            }
            else -> buffer.putChar(c)
        }
    }

    private fun handleC0(c: Char) {
        // C0 controls we honor, plus LF/CR are handled in `handleGround`
        // dispatch via the ground-state range.  Anything else is ignored.
        when (c) {
            '\u0007' -> { /* BEL — would-be haptic; Phase 9.6.1 no-op. */ }
            '\u0008' -> buffer.backspace()
            '\u0009' -> buffer.horizontalTab()
            '\u000a' -> buffer.lineFeed()
            '\u000b' -> buffer.lineFeed()    // VT
            '\u000c' -> buffer.lineFeed()    // FF
            '\u000d' -> buffer.carriageReturn()
            '\u000e' -> { /* SO — ignore */ }
            '\u000f' -> { /* SI — ignore */ }
            else -> { /* ignore */ }
        }
    }

    private fun handleEscape(c: Char) {
        when (c) {
            '[' -> {
                state = State.CSI_ENTRY
                resetParams()
            }
            ']' -> {
                state = State.OSC_STRING
                oscBuffer.setLength(0)
            }
            '7', '8' -> {
                // DECSC / DECRC — save/restore cursor. Phase 9.6.1
                // saves nothing; we accept the sequence harmlessly so
                // vim-style apps that emit DECSC on mode change don't
                // fall over. Future version should implement this fully.
                state = State.GROUND
            }
            'D' -> { // IND
                buffer.lineFeed()
                state = State.GROUND
            }
            'E' -> { // NEL
                buffer.carriageReturn()
                buffer.lineFeed()
                state = State.GROUND
            }
            'M' -> { // RI
                buffer.reverseIndex()
                state = State.GROUND
            }
            else -> {
                // Unhandled ESC + single char: drop out and resume.
                state = State.GROUND
            }
        }
    }

    private fun handleCsiEntry(c: Char) {
        when (c) {
            '?', '>', '!' -> {
                if (params.isEmpty() && privateMarker == null) privateMarker = c
                else state = State.GROUND
            }
            in '0'..'9' -> {
                if (params.isEmpty()) params.add(0)
                val lastIdx = params.size - 1
                params[lastIdx] = params[lastIdx] * 10 + (c - '0')
            }
            ';' -> params.add(0)
            in '@'..'~' -> {
                finalizeCsi(c)
                state = State.GROUND
            }
            else -> state = State.GROUND
        }
    }

    private fun finalizeCsi(finalByte: Char) {
        // Reset intermediate so it doesn't carry across sequences.
        val i = intermediate
        intermediate = ' '
        // The trailing parameter canonicalization: missing → 0; explicit
        // 0 in many CSI uses means "default". We honor VT100 defaults.
        val p = params.toIntArray()
        when (finalByte) {
            'A' -> buffer.cursorUp(p.getOrElse(0) { 1 })
            'B' -> buffer.cursorDown(p.getOrElse(0) { 1 })
            'C' -> buffer.cursorRight(p.getOrElse(0) { 1 })
            'D' -> buffer.cursorLeft(p.getOrElse(0) { 1 })
            '@' -> buffer.insertBlankChars(p.getOrElse(0) { 1 })
            'H', 'f' -> {
                // CUP — cursor position 1-based.
                val row = p.getOrElse(0) { 1 }
                val col = p.getOrElse(1) { 1 }
                buffer.setCursorPosition(row, col)
            }
            'J' -> {
                when (p.getOrElse(0) { 0 }) {
                    0 -> buffer.eraseFromCursorToEndOfScreen()
                    1 -> buffer.eraseFromStartOfScreenToCursor()
                    2, 3 -> buffer.eraseEntireScreen()
                }
            }
            'K' -> {
                when (p.getOrElse(0) { 0 }) {
                    0 -> buffer.eraseFromCursorToEndOfLine()
                    1 -> buffer.eraseFromStartOfLineToCursor()
                    2 -> buffer.eraseEntireLine()
                }
            }
            'L' -> buffer.insertLines(p.getOrElse(0) { 1 })
            'M' -> buffer.deleteLines(p.getOrElse(0) { 1 })
            'P' -> buffer.deleteChars(p.getOrElse(0) { 1 })
            'S' -> buffer.scrollUp(p.getOrElse(0) { 1 })
            'T' -> buffer.scrollDown(p.getOrElse(0) { 1 })
            'X' -> buffer.eraseChars(p.getOrElse(0) { 1 })
            'm' -> applySgr(p)
            'h' -> setMode(p, enabled = true)
            'l' -> setMode(p, enabled = false)
            'r' -> if (privateMarker == null) {
                if (p.isEmpty()) buffer.resetScrollRegion()
                else buffer.setScrollRegion(
                    top = p.getOrElse(0) { 1 }.takeIf { it > 0 } ?: 1,
                    bottom = p.getOrElse(1) { buffer.rows }.takeIf { it > 0 } ?: buffer.rows
                )
            }
            else -> { /* ignore unknowns */ }
        }
        // Reset intermediate per sequence.
        if (i != ' ') intermediate = ' '
    }

    private fun setMode(parameters: IntArray, enabled: Boolean) {
        if (privateMarker != '?') return
        var modes = negotiatedInputModes
        parameters.forEach { mode ->
            when (mode) {
                1 -> modes = modes.copy(applicationCursorKeys = enabled)
                // xterm's alternate-screen variants. 1049 is the modern
                // save/restore form used by vim and less; 47/1047 remain for
                // tmux and older programs.
                47, 1047, 1049 -> if (enabled) buffer.enterAlternateScreen(clear = true)
                else buffer.exitAlternateScreen()
                2004 -> modes = modes.copy(bracketedPaste = enabled)
            }
        }
        negotiatedInputModes = modes
    }

    /**
     * SGR — Select Graphic Rendition. Recognizes the parameters any
     * terminal-101 tutorial lists: 0=reset, 1=bold, 22=normal weight,
     * 4=underline, 24=off, 30-37 fg colors, 40-47 bg colors, 90-97
     * bright fg, 100-107 bright bg. We don't index the parameter into
     * a generic dictionary because we want JIT-friendly inlining.
     */
    private fun applySgr(p: IntArray) {
        if (p.isEmpty()) {
            buffer.currentAttributes = TerminalAttributes.DEFAULT
            return
        }
        var attr = buffer.currentAttributes
        var i = 0
        while (i < p.size) {
            val op = p[i]
            when (op) {
                0 -> attr = TerminalAttributes.DEFAULT
                1 -> attr = attr.withFlag(TerminalAttributes.Flag.BOLD, true)
                2 -> attr = attr.withFlag(TerminalAttributes.Flag.DIM, true)
                3 -> attr = attr.withFlag(TerminalAttributes.Flag.ITALIC, true)
                4 -> attr = attr.withFlag(TerminalAttributes.Flag.UNDERLINE, true)
                5 -> attr = attr.withFlag(TerminalAttributes.Flag.BLINK, true)
                7 -> attr = attr.withFlag(TerminalAttributes.Flag.INVERSE, true)
                8 -> attr = attr.withFlag(TerminalAttributes.Flag.HIDDEN, true)
                22 -> attr = attr
                    .withFlag(TerminalAttributes.Flag.BOLD, false)
                    .withFlag(TerminalAttributes.Flag.DIM, false)
                23 -> attr = attr.withFlag(TerminalAttributes.Flag.ITALIC, false)
                24 -> attr = attr.withFlag(TerminalAttributes.Flag.UNDERLINE, false)
                25 -> attr = attr.withFlag(TerminalAttributes.Flag.BLINK, false)
                27 -> attr = attr.withFlag(TerminalAttributes.Flag.INVERSE, false)
                28 -> attr = attr.withFlag(TerminalAttributes.Flag.HIDDEN, false)
                in 30..37 -> {
                    val c = TerminalAttributes.Color.fromAnsiIndex(op - 30)
                    if (c != null) attr = attr.withForeground(c)
                }
                39 -> attr = attr.withForeground(TerminalAttributes.Color.ForegroundDefault)
                in 40..47 -> {
                    val c = TerminalAttributes.Color.fromAnsiIndex(op - 40)
                    if (c != null) attr = attr.withBackground(c)
                }
                49 -> attr = attr.withBackground(TerminalAttributes.Color.BackgroundDefault)
                in 90..97 -> {
                    val c = TerminalAttributes.Color.fromAnsiIndex(op - 90)
                    if (c != null) attr = attr.withForeground(c)
                }
                in 100..107 -> {
                    val c = TerminalAttributes.Color.fromAnsiIndex(op - 100)
                    if (c != null) attr = attr.withBackground(c)
                }
                38, 48 -> {
                    val foreground = op == 38
                    when (p.getOrNull(i + 1)) {
                        5 -> {
                            val rgb = p.getOrNull(i + 2)?.let(::ansi256ToRgb)
                            if (rgb != null) {
                                attr = if (foreground) attr.withForegroundRgb(rgb) else attr.withBackgroundRgb(rgb)
                                i += 2
                            }
                        }
                        2 -> {
                            val red = p.getOrNull(i + 2)
                            val green = p.getOrNull(i + 3)
                            val blue = p.getOrNull(i + 4)
                            if (red != null && green != null && blue != null) {
                                val rgb = (red.coerceIn(0, 255) shl 16) or
                                    (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255)
                                attr = if (foreground) attr.withForegroundRgb(rgb) else attr.withBackgroundRgb(rgb)
                                i += 4
                            }
                        }
                    }
                }
                else -> { /* ignore unsupported SGR */ }
            }
            i += 1
        }
        buffer.currentAttributes = attr
    }

    private fun ansi256ToRgb(index: Int): Int? {
        if (index !in 0..255) return null
        if (index < 16) {
            val color = TerminalAttributes.Color.fromAnsiIndex(index) ?: return null
            return ansiColorRgb(color)
        }
        if (index in 16..231) {
            val steps = intArrayOf(0, 95, 135, 175, 215, 255)
            val cube = index - 16
            return (steps[cube / 36] shl 16) or (steps[(cube / 6) % 6] shl 8) or steps[cube % 6]
        }
        val gray = 8 + (index - 232) * 10
        return (gray shl 16) or (gray shl 8) or gray
    }

    private fun ansiColorRgb(color: TerminalAttributes.Color): Int = when (color) {
        TerminalAttributes.Color.Black -> 0x000000
        TerminalAttributes.Color.Red -> 0xCD3131
        TerminalAttributes.Color.Green -> 0x0DBC79
        TerminalAttributes.Color.Yellow -> 0xE5E510
        TerminalAttributes.Color.Blue -> 0x2472C8
        TerminalAttributes.Color.Magenta -> 0xBC3FBC
        TerminalAttributes.Color.Cyan -> 0x11A8CD
        TerminalAttributes.Color.White -> 0xE5E5E5
        TerminalAttributes.Color.BrightBlack -> 0x666666
        TerminalAttributes.Color.BrightRed -> 0xF14C4C
        TerminalAttributes.Color.BrightGreen -> 0x23D18B
        TerminalAttributes.Color.BrightYellow -> 0xF5F543
        TerminalAttributes.Color.BrightBlue -> 0x3B8EEA
        TerminalAttributes.Color.BrightMagenta -> 0xD670D6
        TerminalAttributes.Color.BrightCyan -> 0x29B8DB
        TerminalAttributes.Color.BrightWhite -> 0xFFFFFF
        TerminalAttributes.Color.ForegroundDefault,
        TerminalAttributes.Color.BackgroundDefault -> 0xFFFFFF
    }

    private fun handleOscString(c: Char) {
        // OSC: terminated by BEL or ST (ESC \). We never actually use
        // anything inside; this is purely a sink so we don't trip the
        // state machine.
        when (c) {
            '\u0007' -> state = State.GROUND
            '\u001b' -> state = State.OSC_ESCAPE
            else -> if (oscBuffer.length < MAX_OSC_CHARS) oscBuffer.append(c)
        }
    }

    private fun handleOscEscape(c: Char) {
        when (c) {
            '\\' -> state = State.GROUND // String Terminator: ESC \
            '\u001b' -> Unit // retain OSC escape state for repeated ESC
            else -> {
                if (oscBuffer.length < MAX_OSC_CHARS - 1) {
                    oscBuffer.append('\u001b').append(c)
                }
                state = State.OSC_STRING
            }
        }
    }

    private fun resetParams() {
        params.clear()
        intermediate = ' '
        privateMarker = null
    }

    companion object {
        private const val MAX_OSC_CHARS = 8 * 1024
    }
}
