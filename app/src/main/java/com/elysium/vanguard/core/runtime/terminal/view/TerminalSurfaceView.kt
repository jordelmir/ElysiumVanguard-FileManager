package com.elysium.vanguard.core.runtime.terminal.view

import android.content.Context
import android.content.ClipDescription
import android.content.ClipboardManager
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.elysium.vanguard.core.runtime.terminal.engine.TerminalBuffer
import com.elysium.vanguard.core.runtime.terminal.input.TerminalInputEncoder
import com.elysium.vanguard.core.runtime.terminal.input.TerminalKey
import com.elysium.vanguard.core.runtime.terminal.render.TerminalRenderer
import com.elysium.vanguard.core.runtime.terminal.session.TerminalSession

/**
 * PHASE 9.6.1 — Hardware-rendered terminal grid as a [SurfaceView].
 *
 * Why SurfaceView rather than a regular [android.view.View]: terminal
 * text repaints a lot (every CRLF, every keystroke, every
 * completion-result), and we want those paints to take the GPU path
 * rather than going through the UI thread's RenderNode pipeline. We
 * surface exactly one canvas per frame, controlled by [drawOnce], and
 * the Compose host uses [invalidate] to schedule the next one.
 *
 * Phase 9.6.1 hard rules:
 *
 *  - One session per view. Tap-to-focus is provided by the host.
 *  - We don't steal focus on attach. Compose decides when to focus us
 *    by exposing a `Modifier.focusRequester`.
 *  - Modifier keys (Shift, Ctrl, Alt) come from key events; we
 *    synthesize the conventional escape sequences (CSI + letter) so
 *    readline / vim / etc. Just Work.
 *
 * Phase 9.6.1 — first build; intentionally minimal.
 */
internal class TerminalSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    /** Logical grid dimensions. Used by the host to compute cell metrics. */
    var cols: Int = 80
        set(value) { field = value.coerceAtLeast(2) }

    var rows: Int = 24
        set(value) { field = value.coerceAtLeast(2) }

    /** Session this view is bound to. May be set after attach; null before. */
    var session: TerminalSession? = null
        set(value) {
            field = value
            value?.resize(cols, rows)
            drawOnce()
        }

    private var cellWidthPx: Float = 0f
    private var cellHeightPx: Float = 0f
    private var renderer: TerminalRenderer? = null

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    /** Implemented by host to forward typed bytes to the session. */
    var onInput: ((ByteArray) -> Unit)? = null

    /** Implemented by host to forward clipboard bytes as a distinct paste action. */
    var onPaste: ((ByteArray) -> Unit)? = null

    override fun surfaceCreated(holder: SurfaceHolder) {
        session?.buffer?.requestFullRedraw()
        drawOnce()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        recomputeMetrics(w, h)
        drawOnce()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        renderer = null
    }

    private fun recomputeMetrics(width: Int, height: Int) {
        // Derive rows/columns from the actual window so phones,
        // foldables and tablets use the available terminal canvas.
        cellHeightPx = 16f * resources.displayMetrics.density * resources.configuration.fontScale
        cellWidthPx = cellHeightPx * 0.6f  // monospace aspect ≈ 0.6
        cols = (width / cellWidthPx).toInt().coerceIn(20, 220)
        rows = (height / cellHeightPx).toInt().coerceIn(6, 120)
        session?.resize(cols, rows)
        session?.buffer?.requestFullRedraw()
        renderer = TerminalRenderer(cellWidthPx, cellHeightPx)
    }

    /**
     * Single-frame paint. The session pipes output through the
     * [TerminalBuffer]; the renderer reads at draw time. We don't
     * accumulate dirty regions in 9.6.1 — `invalidate()` here means
     * "schedule a paint on the next VSYNC", which Compose already
     * manages for us via `AndroidView { invalidate() }`.
     */
    fun drawOnce() {
        val activeSession = session
        if (activeSession == null || renderer == null) return
        val buffer: TerminalBuffer = activeSession.buffer
        val surfaceHolder = holder
        val canvas: Canvas? = surfaceHolder.lockCanvas()
        if (canvas == null) return
        try {
            renderer!!.draw(canvas, buffer)
        } finally {
            surfaceHolder.unlockCanvasAndPost(canvas)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            requestFocus()
            post {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        return true
    }

    override fun onKeyDown(eventCode: Int, event: KeyEvent): Boolean {
        // Translate keys to bytes the shell understands. We prioritize
        // correctness over completeness: Ctrl-A..Z, Enter, Backspace,
        // Tab, Esc, arrows.
        val modes = session?.inputModes()
        val bytes: ByteArray? = when (val key = mapKeyEvent(event, eventCode)) {
            is KeyStroke.Special -> TerminalInputEncoder.key(
                key = key.key,
                modes = modes ?: com.elysium.vanguard.core.runtime.terminal.engine.TerminalInputModes.DEFAULT,
                shift = event.isShiftPressed,
                alt = event.isAltPressed,
                ctrl = event.isCtrlPressed
            )
            is KeyStroke.Char -> TerminalInputEncoder.text(key.code, alt = event.isAltPressed)
            is KeyStroke.CtrlChar -> TerminalInputEncoder.controlLetter(key.letter, alt = event.isAltPressed)
            null -> null
        }
        if (bytes != null && bytes.isNotEmpty()) {
            onInput?.invoke(bytes)
            return true
        }
        return super.onKeyDown(eventCode, event)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_FULLSCREEN
        outAttrs.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        return TerminalInputConnection(this)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    /** Public bridge for [TerminalInputConnection] to forward chars. */
    fun deliverInput(bytes: ByteArray) {
        onInput?.invoke(bytes)
    }

    /** Sends clipboard content through the session's negotiated paste protocol. */
    fun deliverPaste(bytes: ByteArray) {
        onPaste?.invoke(bytes)
    }

    /**
     * Translate a key event into something the shell parser can react
     * to. Returns null when no translation applies (the framework should
     * keep trying other handlers).
     */
    private fun mapKeyEvent(event: KeyEvent, code: Int): KeyStroke? {
        return when {
            event.action != KeyEvent.ACTION_DOWN -> null
            code == KeyEvent.KEYCODE_ENTER -> KeyStroke.Special(TerminalKey.ENTER)
            code == KeyEvent.KEYCODE_DEL -> KeyStroke.Special(TerminalKey.BACKSPACE)
            code == KeyEvent.KEYCODE_TAB -> KeyStroke.Special(TerminalKey.TAB)
            code == KeyEvent.KEYCODE_ESCAPE -> KeyStroke.Special(TerminalKey.ESCAPE)
            code == KeyEvent.KEYCODE_DPAD_UP -> KeyStroke.Special(TerminalKey.UP)
            code == KeyEvent.KEYCODE_DPAD_DOWN -> KeyStroke.Special(TerminalKey.DOWN)
            code == KeyEvent.KEYCODE_DPAD_LEFT -> KeyStroke.Special(TerminalKey.LEFT)
            code == KeyEvent.KEYCODE_DPAD_RIGHT -> KeyStroke.Special(TerminalKey.RIGHT)
            code == KeyEvent.KEYCODE_MOVE_HOME -> KeyStroke.Special(TerminalKey.HOME)
            code == KeyEvent.KEYCODE_MOVE_END -> KeyStroke.Special(TerminalKey.END)
            code == KeyEvent.KEYCODE_INSERT -> KeyStroke.Special(TerminalKey.INSERT)
            code == KeyEvent.KEYCODE_FORWARD_DEL -> KeyStroke.Special(TerminalKey.DELETE)
            code == KeyEvent.KEYCODE_PAGE_UP -> KeyStroke.Special(TerminalKey.PAGE_UP)
            code == KeyEvent.KEYCODE_PAGE_DOWN -> KeyStroke.Special(TerminalKey.PAGE_DOWN)
            code in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ->
                KeyStroke.Special(TerminalKey.entries[TerminalKey.F1.ordinal + code - KeyEvent.KEYCODE_F1])
            event.isCtrlPressed && code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> {
                KeyStroke.CtrlChar(('A'.code + code - KeyEvent.KEYCODE_A).toChar())
            }
            // KeyEvent.getUnicodeChar() returns Int. We compare with
            // the literal 0 (Int) so the comparison is well-typed.
            event.unicodeChar != 0 && !event.isCtrlPressed ->
                KeyStroke.Char(event.unicodeChar)
            else -> null
        }
    }

    private sealed class KeyStroke {
        data class Special(val key: TerminalKey) : KeyStroke()
        /** Unicode codepoint produced by the key event; rendered to UTF-8 in the host. */
        data class Char(val code: Int) : KeyStroke()
        /** ASCII letter for a Ctrl+letter combo. */
        data class CtrlChar(val letter: kotlin.Char) : KeyStroke()
    }
}

/**
 * PHASE 9.6.1 — Input connection that delegates to the host view.
 *
 * The Compose TextField ecosystem calls commitText / deleteSurroundingText
 * on this object when the soft keyboard produces input. We translate
 * commits to UTF-8 bytes and forward them to [TerminalSurfaceView.deliverInput].
 */
private class TerminalInputConnection(
    private val view: TerminalSurfaceView
) : BaseInputConnection(view, false) {
    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (text == null || text.isEmpty()) return false
        view.deliverInput(text.toString().toByteArray(Charsets.UTF_8))
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (beforeLength > 0) {
            repeat(beforeLength) { view.deliverInput(byteArrayOf(0x7f)) }
        }
        if (afterLength > 0) {
            repeat(afterLength) { view.deliverInput(byteArrayOf(0x1b, '['.code.toByte(), '3'.code.toByte(), '~'.code.toByte())) }
        }
        return true
    }

    override fun performContextMenuAction(id: Int): Boolean {
        if (id != android.R.id.paste && id != android.R.id.pasteAsPlainText) {
            return super.performContextMenuAction(id)
        }
        val clipboard = view.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return false
        if (!clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
            !clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
        ) return false
        val text = clip.getItemAt(0).coerceToText(view.context)?.toString() ?: return false
        if (text.isEmpty()) return false
        view.deliverPaste(text.toByteArray(Charsets.UTF_8))
        return true
    }
}
