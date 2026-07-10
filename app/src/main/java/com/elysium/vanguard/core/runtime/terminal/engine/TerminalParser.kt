package com.elysium.vanguard.core.runtime.terminal.engine

/**
 * PHASE 9.6.1 — VT100 / ECMA-48 escape-sequence parser.
 *
 * The parser is a tiny state machine. It accepts a stream of UTF-8 bytes
 * (we decode to a single Java String before handing to the parser) and
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
 *   - DEC private modes (private mode set/reset, DECTCEM, alt buffer).
 *   - SGR 38;5;n (256-color) and SGR 38;2;r;g;b (truecolor).
 *   - Mouse reporting (CSI ?...h / CSI ?...l).
 *   - Bracketed paste mode.
 *   - Sixel graphics.
 *   - Scroll regions (DECSTBM).
 *
 * Phase 9.6.1 — first build; intentionally minimal.
 */
internal class TerminalParser(
    private val buffer: TerminalBuffer
) {
    /** Per VT100 §5.4: ground, escape, csi-entry, osc-string, etc. */
    private enum class State { GROUND, ESCAPE, CSI_ENTRY, OSC_STRING }
    private var state: State = State.GROUND
    private val params: ArrayList<Int> = ArrayList(8)
    private var intermediate: Char = ' '
    private var oscBuffer: StringBuilder = StringBuilder(64)

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
            }
        }
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
            'D', 'E', 'M' -> {
                // IND / NEL / RI — minimal handling, treated as LF-ish.
                buffer.lineFeed()
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
            'm' -> applySgr(p)
            'h', 'l' -> { /* SET/RESET mode — ignored in 9.6.1 */ }
            'r' -> { /* DECSTBM scroll region — ignored */ }
            else -> { /* ignore unknowns */ }
        }
        // Reset intermediate per sequence.
        if (i != ' ') intermediate = ' '
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
                // 38;5;n and 38;2;r;g;b are truecolor — not 9.6.1 scope.
                else -> { /* ignore unsupported SGR */ }
            }
            i += 1
        }
        buffer.currentAttributes = attr
    }

    private fun handleOscString(c: Char) {
        // OSC: terminated by BEL or ST (ESC \). We never actually use
        // anything inside; this is purely a sink so we don't trip the
        // state machine.
        when (c) {
            '\u0007' -> state = State.GROUND
            '\u001b' -> {
                // Expecting '\' next for ST; we drop straight to ground.
                state = State.GROUND
            }
            else -> oscBuffer.append(c)
        }
    }

    private fun resetParams() {
        params.clear()
        intermediate = ' '
    }
}
