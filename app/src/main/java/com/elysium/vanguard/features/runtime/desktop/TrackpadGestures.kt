package com.elysium.vanguard.features.runtime.desktop

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.IntSize

/**
 * Phase 75 — trackpad-style gestures for the Linux Desktop screen.
 *
 * The user reported the Linux Desktop felt "hecho por un piedrero
 * indigente" and asked for a Microsoft-style trackpad experience:
 *
 *   - 1-finger drag → mouse move (no button)
 *   - 2-finger tap   → left click
 *   - 2-finger drag  → left click + drag (selection)
 *   - 3-finger tap   → right click
 *
 * The gesture detector is a [Modifier] extension that intercepts
 * pointer events before they reach the [RfbSurfaceView]. The
 * detector maintains a small state machine (Track1 / Track2 /
 * Track3) and dispatches [RfbSession.sendPointer] calls with the
 * right RFB button mask.
 *
 * RFB button masks (per the RFB spec):
 *   - bit 0 (1) = left button
 *   - bit 1 (2) = middle button
 *   - bit 2 (4) = right button
 *   - 0 = all up
 *
 * The detector is **stateless across gestures**: every press starts
 * a new state-machine run; the previous gesture's `lastX / lastY`
 * is reset. This avoids "stuck button" bugs when a gesture is
 * interrupted by an external event.
 *
 * The handler also runs a defensive guard: a `2 → 1` finger
 * release during a Track2 (left-drag) sends a `button up` event
 * so the server-side mouse is never left in a pressed state.
 * Same for `3 → 2` (the right-click already fired on `3 → 0`).
 *
 * Coordinate mapping: the touch is in the rendered View's
 * pixel space; the VNC server's framebuffer is in the server's
 * pixel space. The caller passes the rendered size + the
 * server's framebuffer size; the handler scales the touch
 * coordinates to the server's space. Server coordinates are
 * clamped to `[0, serverWidth - 1]` × `[0, serverHeight - 1]`.
 */
fun Modifier.trackpadGestures(
    renderedSize: IntSize,
    serverWidth: Int,
    serverHeight: Int,
    onMove: (serverX: Int, serverY: Int) -> Unit,
    onLeftDown: (serverX: Int, serverY: Int) -> Unit,
    onLeftUp: (serverX: Int, serverY: Int) -> Unit,
    onRightClick: (serverX: Int, serverY: Int) -> Unit,
    onStateChange: (TrackpadIndicatorState) -> Unit = {},
): Modifier = this.pointerInput(renderedSize, serverWidth, serverHeight) {
    // Defensive: when the view hasn't been laid out
    // yet (the rendered size is `0 x 0`), ignore all
    // pointer events. Without this guard the first
    // touch would dispatch a `sendPointer(0, 0, mask)`
    // because the scale-to-server math collapses to
    // 0. The pointerInput is keyed on `renderedSize` so
    // it restarts once the view is laid out, and the
    // `awaitFirstDown` will then resume correctly.
    if (renderedSize.width <= 0 || renderedSize.height <= 0) {
        return@pointerInput
    }
    awaitEachGesture {
        val pointers = mutableMapOf<PointerId, Offset>()
        var mode: GestureMode = GestureMode.Idle
        var lastX = 0
        var lastY = 0

        val firstDown = awaitFirstDown(requireUnconsumed = false)
        pointers[firstDown.id] = firstDown.position
        // First finger down — start tracking.
        mode = GestureMode.Track1
        lastX = scaleX(firstDown.position.x, renderedSize.width, serverWidth)
        lastY = scaleY(firstDown.position.y, renderedSize.height, serverHeight)
        onStateChange(TrackpadIndicatorState.OneFingerMove)

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            // Update the live pointer positions for every change
            // in the event (covers Press, Move, and Release).
            for (change in event.changes) {
                if (change.pressed) {
                    pointers[change.id] = change.position
                } else {
                    pointers.remove(change.id)
                }
            }

            when (event.type) {
                PointerEventType.Press -> {
                    val count = pointers.size
                    val next = when (count) {
                        1 -> GestureMode.Track1
                        2 -> GestureMode.Track2
                        3 -> GestureMode.Track3
                        else -> mode
                    }
                    if (next != mode) {
                        // Transitioning UP (Track1 → Track2, Track2 → Track3).
                        if (mode == GestureMode.Track2 && next == GestureMode.Track3) {
                            // A new finger joined during a left drag.
                            // Release the left button before the
                            // right-click sequence takes over.
                            onLeftUp(lastX, lastY)
                        }
                        mode = next
                        onStateChange(mode.toIndicatorState())
                    }
                }
                PointerEventType.Move -> {
                    val active = pointers.values.firstOrNull() ?: continue
                    val sx = scaleX(active.x, renderedSize.width, serverWidth)
                    val sy = scaleY(active.y, renderedSize.height, serverHeight)
                    lastX = sx
                    lastY = sy
                    when (mode) {
                        GestureMode.Track1 -> onMove(sx, sy)
                        GestureMode.Track2 -> onLeftDown(sx, sy)
                        GestureMode.Track3 -> Unit // No move while right-clicking
                        GestureMode.Idle -> Unit
                    }
                }
                PointerEventType.Release -> {
                    val count = pointers.size
                    when {
                        count == 0 -> {
                            // All fingers lifted — finalize the gesture.
                            when (mode) {
                                GestureMode.Track1 -> Unit // 1-finger tap, no click
                                GestureMode.Track2 -> onLeftUp(lastX, lastY)
                                GestureMode.Track3 -> onRightClick(lastX, lastY)
                                GestureMode.Idle -> Unit
                            }
                            mode = GestureMode.Idle
                            onStateChange(TrackpadIndicatorState.Idle)
                            return@awaitEachGesture
                        }
                        mode == GestureMode.Track2 && count == 1 -> {
                            // 2 → 1 fingers during a left drag.
                            onLeftUp(lastX, lastY)
                            mode = GestureMode.Track1
                            onStateChange(TrackpadIndicatorState.OneFingerMove)
                        }
                        mode == GestureMode.Track3 -> {
                            mode = when (count) {
                                2 -> GestureMode.Track2
                                1 -> GestureMode.Track1
                                else -> GestureMode.Idle
                            }
                            onStateChange(mode.toIndicatorState())
                        }
                    }
                }
            }
        }
    }
}

private enum class GestureMode { Idle, Track1, Track2, Track3 }

/**
 * Phase 75 — the public trackpad state for the UI.
 *
 * The Compose screen reflects this in a small floating
 * chip ("1F", "2F DRAG", "3F") so the user can see what
 * the detector is currently interpreting.
 */
enum class TrackpadIndicatorState {
    /** No fingers down. */
    Idle,

    /** 1 finger down — moving the cursor. */
    OneFingerMove,

    /** 2 fingers down — left button held, drag mode. */
    TwoFingerDrag,

    /** 3 fingers down — about to right-click on release. */
    ThreeFingerPending,
}

private fun GestureMode.toIndicatorState(): TrackpadIndicatorState = when (this) {
    GestureMode.Idle -> TrackpadIndicatorState.Idle
    GestureMode.Track1 -> TrackpadIndicatorState.OneFingerMove
    GestureMode.Track2 -> TrackpadIndicatorState.TwoFingerDrag
    GestureMode.Track3 -> TrackpadIndicatorState.ThreeFingerPending
}

private fun scaleX(x: Float, viewWidth: Int, serverWidth: Int): Int {
    if (viewWidth <= 0 || serverWidth <= 0) return 0
    return (x * serverWidth / viewWidth)
        .toInt()
        .coerceIn(0, serverWidth - 1)
}

private fun scaleY(y: Float, viewHeight: Int, serverHeight: Int): Int {
    if (viewHeight <= 0 || serverHeight <= 0) return 0
    return (y * serverHeight / viewHeight)
        .toInt()
        .coerceIn(0, serverHeight - 1)
}

/**
 * Phase 75 — the dispatch helpers that turn trackpad events
 * into RFB pointer events. The standard RFB button mask is:
 *
 *   0 = all up, 1 = left, 2 = middle, 4 = right.
 *
 * `RfbSession.sendPointer` is non-blocking; the call writes
 * to a channel that the session's input pump drains. The
 * session serializes pointer events on the wire.
 *
 * The dispatcher is `internal` because [RfbSession] is
 * `internal` (Kotlin does not allow `public` functions to
 * expose `internal` types in their signature). The same
 * module can call it; cross-module callers would need a
 * different public seam.
 */
internal object TrackpadDispatcher {
    const val BUTTON_NONE = 0
    const val BUTTON_LEFT = 1
    const val BUTTON_MIDDLE = 2
    const val BUTTON_RIGHT = 4

    /** Send a left-click at the given server coordinates. */
    fun leftClick(
        session: com.elysium.vanguard.core.runtime.distros.gui.rfb.RfbSession,
        serverX: Int,
        serverY: Int,
    ) {
        session.sendPointer(serverX, serverY, BUTTON_LEFT)
        session.sendPointer(serverX, serverY, BUTTON_NONE)
    }

    /** Send a right-click at the given server coordinates. */
    fun rightClick(
        session: com.elysium.vanguard.core.runtime.distros.gui.rfb.RfbSession,
        serverX: Int,
        serverY: Int,
    ) {
        session.sendPointer(serverX, serverY, BUTTON_RIGHT)
        session.sendPointer(serverX, serverY, BUTTON_NONE)
    }
}
