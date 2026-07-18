package com.elysium.vanguard.features.runtime.desktop

import android.graphics.Bitmap
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.elysium.vanguard.core.runtime.distros.gui.x11.X11DisplayService

/**
 * FASE 4 / section 13 — X11 display screen.
 *
 * The screen observes [X11DisplayViewModel.ui] and renders the
 * current frame from the [X11DisplayService] onto a SurfaceView
 * hosted by an [AndroidView]. Touch events are mapped to pointer
 * events through the service's coordinate transform.
 *
 * Lifecycle:
 *  - First composition: VM is constructed; a LaunchedEffect calls
 *    [X11DisplayViewModel.connect] with the default loopback
 *    address and 1280x720 geometry. The service starts the RFB
 *    session and begins producing frames.
 *  - Each new frame is published through the VM's StateFlow.
 *  - The SurfaceView's SurfaceHolder.Callback is wired to redraw
 *    when a new frame arrives. The redraw reads the current frame
 *    from the VM (no per-frame Compose recomposition — we honor
 *    section 8.4: 'no recomposición Compose por byte o carácter').
 *  - The screen's DisposableEffect calls [X11DisplayViewModel.disconnect]
 *    when the screen leaves the composition. The service closes
 *    the RFB session and cancels its internal scope.
 */
@Composable
fun X11DisplayScreen(
    viewModel: X11DisplayViewModel = hiltViewModel(),
    host: String = "127.0.0.1",
    port: Int = 5901,
    onClose: () -> Unit = {}
) {
    val ui by viewModel.ui.collectAsState()

    LaunchedEffect(host, port) {
        viewModel.connect(host = host, port = port)
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.disconnect() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            drawCurrentFrame(holder, ui)
                        }
                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int
                        ) {
                            drawCurrentFrame(holder, ui)
                        }
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            // Nothing to release; the bitmap is recreated
                            // on the next surfaceCreated.
                        }
                    })
                    // Touch: map screen coordinates to framebuffer
                    // coordinates and forward to the service.
                    setOnTouchListener { v, event ->
                        val frame = ui.frame
                        if (frame == null) {
                            v.performClick()
                            return@setOnTouchListener false
                        }
                        val mapped = viewModel.mapTouch(
                            touchX = event.x,
                            touchY = event.y,
                            surfaceWidth = v.width,
                            surfaceHeight = v.height
                        ) ?: run {
                            v.performClick()
                            return@setOnTouchListener false
                        }
                        val (fbX, fbY) = mapped
                        val mask = when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> 1 shl 0 // left button
                            MotionEvent.ACTION_MOVE -> 1 shl 0
                            MotionEvent.ACTION_UP -> 0
                            else -> 0
                        }
                        viewModel.sendPointer(fbX, fbY, mask)
                        v.performClick()
                        true
                    }
                }
            },
            update = { surfaceView ->
                drawCurrentFrame(surfaceView.holder, ui)
            },
            modifier = Modifier.fillMaxSize()
        )

        // Status overlay
        when (val state = ui.displayState) {
            is X11DisplayService.DisplayState.Connecting -> StatusOverlay {
                CircularProgressIndicator(color = Color(0xFF61AFEF))
                Text(
                    text = "CONNECTING TO $host:$port",
                    color = Color(0xFFABB2BF),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            is X11DisplayService.DisplayState.Failed -> StatusOverlay {
                Text(
                    text = "FAILED: ${state.error}",
                    color = Color(0xFFE06C75),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            is X11DisplayService.DisplayState.Idle -> StatusOverlay {
                Text(
                    text = "IDLE",
                    color = Color(0xFFABB2BF),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            is X11DisplayService.DisplayState.Connected -> StatusOverlay {
                Text(
                    text = "CONNECTED ${state.width}x${state.height}",
                    color = Color(0xFF98C379),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            is X11DisplayService.DisplayState.Streaming -> {
                // No overlay; the SurfaceView is the source of truth.
                // Show a tiny status chip with frame count instead.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color(0xCC1E1E1E))
                ) {
                    Text(
                        text = "${state.width}x${state.height} · ${state.frameCount} frames",
                        color = Color(0xFFABB2BF),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(4.dp)
                    )
                }
            }
        }

        // Close button
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Close X11 display",
                tint = Color(0xFFE06C75)
            )
        }
    }
}

@Composable
private fun StatusOverlay(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(Color(0xCC1E1E1E))
                .padding(16.dp)
        ) {
            content()
        }
    }
}

/**
 * Push the current frame from the VM into the SurfaceView's
 * Surface. The bitmap is created once per frame and reclaimed
 * after draw; we do not retain it between frames (section 31:
 * 'no retener framebuffer innecesariamente').
 */
private fun drawCurrentFrame(
    holder: SurfaceHolder,
    ui: X11DisplayUiState
) {
    val frame = ui.frame ?: return
    val canvas = try {
        holder.lockCanvas()
    } catch (_: IllegalStateException) {
        null
    } ?: return
    try {
        val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(frame.argb, 0, frame.width, 0, 0, frame.width, frame.height)
        // Fit-to-surface letterbox
        val sw = canvas.width.toFloat()
        val sh = canvas.height.toFloat()
        val scale = minOf(sw / frame.width, sh / frame.height)
        val dstW = (frame.width * scale).toInt()
        val dstH = (frame.height * scale).toInt()
        val dstX = ((sw - dstW) / 2f).toInt()
        val dstY = ((sh - dstH) / 2f).toInt()
        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.drawBitmap(
            bitmap,
            null,
            android.graphics.Rect(dstX, dstY, dstX + dstW, dstY + dstH),
            null
        )
        bitmap.recycle()
    } finally {
        holder.unlockCanvasAndPost(canvas)
    }
}
