package com.elysium.vanguard.core.runtime.terminal.view

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.elysium.vanguard.core.runtime.terminal.session.TerminalSession
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * PHASE 9.6.1 — Compose host for the terminal SurfaceView.
 *
 * Owns one [TerminalSession], owns one [TerminalSurfaceView], bridges
 * Compose lifecycle to View lifecycle. The host [TerminalScreen] sits
 * above this and is responsible for the surrounding chrome (top bar,
 * exit button) — *this* composable is the canvas and only the canvas.
 *
 * Phase 9.6.1 — first build; intentionally minimal.
 */
@androidx.compose.runtime.Composable
internal fun TerminalHost(
    modifier: Modifier = Modifier,
    session: TerminalSession,
    onBytesTyped: (ByteArray) -> Unit,
    onSessionExited: (Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    val surface = remember {
        TerminalSurfaceView(context).apply {
            onInput = { bytes -> onBytesTyped(bytes) }
            onPaste = { bytes -> session.writePaste(bytes) }
        }
    }

    // Wire the session once and keep the same renderer alive until
    // the composable leaves the composition.
    DisposableEffect(session) {
        surface.session = session
        session.start()
        val drawJob = scope.launch {
            session.output.collectLatest {
                // Force a repaint on every output chunk. We don't try
                // to coalesce here; the SurfaceView's invalidate is
                // already cheap because the Canvas paints on its own
                // thread.
                surface.drawOnce()
            }
        }
        val exitJob = scope.launch {
            session.events.collectLatest { e ->
                when (e) {
                    is TerminalSession.Event.Exited -> onSessionExited(e.exitCode)
                    is TerminalSession.Event.Failed -> onSessionExited(-1)
                    is TerminalSession.Event.TitleChanged -> { /* noop in 9.6.1 */ }
                }
            }
        }
        onDispose {
            drawJob.cancel()
            exitJob.cancel()
            surface.session = null
            session.stop()
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    AndroidView(
        modifier = modifier
            .background(Color.Black)
            .focusRequester(focusRequester),
        factory = { surface },
        update = {
            // Refresh on every recomposition; cheap because the surface
            // skips the paint when nothing changes.
            it.drawOnce()
        }
    )
}
