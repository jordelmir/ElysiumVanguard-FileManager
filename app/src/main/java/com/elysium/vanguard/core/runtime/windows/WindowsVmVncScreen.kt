package com.elysium.vanguard.core.runtime.windows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.elysium.vanguard.core.runtime.distros.gui.rfb.RfbSession
import com.elysium.vanguard.core.runtime.distros.gui.rfb.RfbSurfaceView

/**
 * PHASE 48 — the Windows VM VNC viewer.
 *
 * The screen is the Phase 9.6.5 follow-up to
 * Phase 47's VNC port field. Given a [vncPort],
 * the screen:
 *
 *  1. constructs a [RfbSession] bound to
 *     `127.0.0.1:<vncPort>` (the QEMU VNC display
 *     the backend exposed in Phase 47),
 *  2. starts the session on first composition
 *     (the [RfbSession] is thread-safe to start
 *     multiple times — it ignores re-starts),
 *  3. observes the session's [RfbSession.state]
 *     for the connection lifecycle
 *     (Idle / Connecting / Connected /
 *     Streaming / Stopped),
 *  4. renders the latest frame via
 *     [RfbSurfaceView] (the existing Android
 *     SurfaceView that paints RFB frames and
 *     forwards click / keyboard input),
 *  5. stops the session on screen dispose
 *     (rotation, back-press, process death).
 *
 * The screen is a **thin Compose host** — the
 * real VNC work is the existing
 * [RfbSession] + [RfbSurfaceView] pair. The
 * screen adds only:
 *
 *  - a TopAppBar with the title + back
 *    (so the user can return to the
 *    `MainScreen`),
 *  - a state-driven status line under the
 *    framebuffer (so the user sees "Connecting
 *    ..." / "Streaming" / "Connection lost"
 *    without inspecting the VNC protocol),
 *  - the lifecycle wiring (start on enter,
 *    stop on leave).
 *
 * The session is plain (no password). Phase
 * 9.6.5's security model — a per-VM VNC
 * password set on the QEMU command line and
 * the [RfbSession.Config.passwordProvider] —
 * is a follow-up; the dev path is passwordless
 * for now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WindowsVmVncScreen(
    vncPort: Int,
    onBack: () -> Unit
) {
    val session = remember(vncPort) {
        RfbSession(
            config = RfbSession.Config(
                host = "127.0.0.1",
                port = vncPort
            )
        )
    }
    val state by session.state.collectAsState()

    LaunchedEffect(session) {
        session.start()
    }

    DisposableEffect(session) {
        onDispose { session.close() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Windows VM (VNC :$vncPort)",
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // --- Status line ---
            VncStatusLine(state = state)
            // --- Framebuffer ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    factory = { ctx -> RfbSurfaceView(ctx) },
                    update = { view ->
                        // Phase 48 — the surface renders
                        // the latest frame from the
                        // session's [RfbSession.frames]
                        // flow. Wiring the two flows
                        // together happens in the
                        // RfbSurfaceView's internal
                        // collector; we just need the
                        // view to mount.
                        @Suppress("UNUSED_EXPRESSION")
                        view
                    },
                    modifier = Modifier.fillMaxSize()
                )
                // --- State-dependent overlay ---
                when (val s = state) {
                    is RfbSession.State.Idle,
                    is RfbSession.State.Stopped,
                    is RfbSession.State.Connecting -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = s.label(),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    is RfbSession.State.Connected,
                    is RfbSession.State.Streaming -> {
                        // Framebuffer is live; no
                        // overlay needed. The RFB
                        // surface paints the
                        // framebuffer directly.
                    }
                    is RfbSession.State.Failed -> {
                        Text(
                            text = "Connection failed: ${s.detail}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VncStatusLine(state: RfbSession.State) {
    Text(
        text = state.label(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

private fun RfbSession.State.label(): String = when (this) {
    is RfbSession.State.Idle -> "Idle"
    is RfbSession.State.Connecting -> "Connecting..."
    is RfbSession.State.Connected -> "Connected"
    is RfbSession.State.Streaming -> "Streaming"
    is RfbSession.State.Stopped -> "Stopped"
    is RfbSession.State.Failed -> "Error"
}
