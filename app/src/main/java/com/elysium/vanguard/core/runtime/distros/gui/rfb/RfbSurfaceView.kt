package com.elysium.vanguard.core.runtime.distros.gui.rfb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

internal class RfbSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    private val drawLock = Any()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var bitmap: Bitmap? = null
    private var latestFrame: RfbFrame? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var longPressPending = false
    private val longPressRunnable = Runnable { handleLongPress() }
    private val LONG_PRESS_MS = 500L

    private var pointerDown = false
    private var pointerPos = RfbPointer(0, 0)
    private var scrollAccumX = 0f
    private var scrollAccumY = 0f
    private val SCROLL_THRESHOLD = 40f

    var session: RfbSession? = null

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            if (longPressPending) {
                longPressPending = false
                mapPointer(e.x, e.y)?.let {
                    session?.sendPointer(it.x, it.y, RIGHT_BUTTON_MASK)
                    session?.sendPointer(it.x, it.y, 0)
                }
            }
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            mapPointer(e.x, e.y)?.let {
                session?.sendPointer(it.x, it.y, MIDDLE_BUTTON_MASK)
                session?.sendPointer(it.x, it.y, 0)
            }
            return true
        }

        override fun onScroll(
            e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float
        ): Boolean {
            if (e2.pointerCount >= 2) {
                scrollAccumY += distanceY
                scrollAccumX += distanceX
                while (scrollAccumY >= SCROLL_THRESHOLD) {
                    mapPointer(e2.x, e2.y)?.let { session?.sendPointer(it.x, it.y, WHEEL_DOWN_MASK) }
                    scrollAccumY -= SCROLL_THRESHOLD
                }
                while (scrollAccumY <= -SCROLL_THRESHOLD) {
                    mapPointer(e2.x, e2.y)?.let { session?.sendPointer(it.x, it.y, WHEEL_UP_MASK) }
                    scrollAccumY += SCROLL_THRESHOLD
                }
                while (scrollAccumX >= SCROLL_THRESHOLD) {
                    mapPointer(e2.x, e2.y)?.let { session?.sendPointer(it.x, it.y, WHEEL_RIGHT_MASK) }
                    scrollAccumX -= SCROLL_THRESHOLD
                }
                while (scrollAccumX <= -SCROLL_THRESHOLD) {
                    mapPointer(e2.x, e2.y)?.let { session?.sendPointer(it.x, it.y, WHEEL_LEFT_MASK) }
                    scrollAccumX += SCROLL_THRESHOLD
                }
                return true
            }
            if (pointerDown) {
                mapPointer(e2.x, e2.y)?.let { p ->
                    pointerPos = p
                    session?.sendPointer(p.x, p.y, PRIMARY_BUTTON_MASK)
                }
                return true
            }
            return false
        }
    })

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun present(frame: RfbFrame) {
        synchronized(drawLock) {
            latestFrame = frame
            drawLatestLocked()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) = synchronized(drawLock) { drawLatestLocked() }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
        synchronized(drawLock) { drawLatestLocked() }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        mainHandler.removeCallbacks(longPressRunnable)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        val frame = latestFrame ?: return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                showKeyboard()
                longPressPending = true
                mainHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                mapPointer(event.x, event.y)?.let {
                    pointerDown = true
                    pointerPos = it
                    session?.sendPointer(it.x, it.y, PRIMARY_BUTTON_MASK)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    mainHandler.removeCallbacks(longPressRunnable)
                    longPressPending = false
                    if (pointerDown) {
                        session?.sendPointer(pointerPos.x, pointerPos.y, 0)
                        pointerDown = false
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) return true
                if (longPressPending) {
                    mapPointer(event.x, event.y)?.let {
                        pointerPos = it
                        session?.sendPointer(it.x, it.y, PRIMARY_BUTTON_MASK)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPressRunnable)
                if (longPressPending) {
                    longPressPending = false
                }
                if (pointerDown) {
                    session?.sendPointer(pointerPos.x, pointerPos.y, 0)
                    pointerDown = false
                }
            }
        }
        return true
    }

    private fun handleLongPress() {
        longPressPending = false
        if (pointerDown) {
            session?.sendPointer(pointerPos.x, pointerPos.y, 0)
            pointerDown = false
        }
        session?.sendPointer(pointerPos.x, pointerPos.y, RIGHT_BUTTON_MASK)
        session?.sendPointer(pointerPos.x, pointerPos.y, 0)
    }

    private fun mapPointer(x: Float, y: Float): RfbPointer? {
        val frame = latestFrame ?: return null
        return RfbViewport(frame.width, frame.height, width, height).map(x, y)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val keysym = event.toRfbKeysym() ?: return super.onKeyDown(keyCode, event)
        session?.sendKey(keysym, down = true)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val keysym = event.toRfbKeysym() ?: return super.onKeyUp(keyCode, event)
        session?.sendKey(keysym, down = false)
        return true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_FULLSCREEN
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        return RfbInputConnection(this)
    }

    private fun drawLatestLocked() {
        val frame = latestFrame ?: return
        if (!holder.surface.isValid || width <= 0 || height <= 0) return
        val target = bitmap?.takeIf { it.width == frame.width && it.height == frame.height }
            ?: Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888).also { bitmap = it }
        target.setPixels(frame.argb, 0, frame.width, 0, 0, frame.width, frame.height)
        val viewport = RfbViewport(frame.width, frame.height, width, height)
        val canvas: Canvas = holder.lockCanvas() ?: return
        try {
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(
                target, null,
                RectF(viewport.offsetX, viewport.offsetY, viewport.offsetX + viewport.drawWidth, viewport.offsetY + viewport.drawHeight),
                bitmapPaint
            )
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun showKeyboard() {
        post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun KeyEvent.toRfbKeysym(): Int? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> XK_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> XK_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> XK_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> XK_RIGHT
        KeyEvent.KEYCODE_ENTER -> XK_RETURN
        KeyEvent.KEYCODE_DEL -> XK_BACKSPACE
        KeyEvent.KEYCODE_TAB -> XK_TAB
        KeyEvent.KEYCODE_ESCAPE -> XK_ESCAPE
        KeyEvent.KEYCODE_FORWARD_DEL -> XK_DELETE
        else -> unicodeChar.takeIf { it > 0 }
    }

    private fun sendText(text: CharSequence) {
        var index = 0
        while (index < text.length) {
            val keysym = Character.codePointAt(text, index)
            session?.sendKey(keysym, down = true)
            session?.sendKey(keysym, down = false)
            index += Character.charCount(keysym)
        }
    }

    private class RfbInputConnection(private val view: RfbSurfaceView) : BaseInputConnection(view, true) {
        private val composer = RfbImeComposer(
            sendText = view::sendText,
            sendBackspace = {
                view.session?.sendKey(XK_BACKSPACE, down = true)
                view.session?.sendKey(XK_BACKSPACE, down = false)
            }
        )

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            composer.setComposingText(text)
            return true
        }

        override fun finishComposingText(): Boolean {
            composer.finishComposingText()
            return true
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (text.isNullOrEmpty()) return false
            composer.commitText(text)
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            composer.deleteBefore(beforeLength)
            return true
        }
    }

    private companion object {
        const val PRIMARY_BUTTON_MASK = 1
        const val MIDDLE_BUTTON_MASK = 2
        const val RIGHT_BUTTON_MASK = 4
        const val WHEEL_UP_MASK = 8
        const val WHEEL_DOWN_MASK = 16
        const val WHEEL_LEFT_MASK = 32
        const val WHEEL_RIGHT_MASK = 64
        const val XK_BACKSPACE = 0xFF08
        const val XK_TAB = 0xFF09
        const val XK_RETURN = 0xFF0D
        const val XK_ESCAPE = 0xFF1B
        const val XK_LEFT = 0xFF51
        const val XK_UP = 0xFF52
        const val XK_RIGHT = 0xFF53
        const val XK_DOWN = 0xFF54
        const val XK_DELETE = 0xFFFF
    }
}
