package com.elysium.vanguard.core.runtime.distros.gui.rfb

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Compose lifecycle bridge for a real [RfbSession] and its [RfbSurfaceView]. */
@Composable
internal fun RfbHost(modifier: Modifier = Modifier, session: RfbSession) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val surface = remember { RfbSurfaceView(context) }

    DisposableEffect(session) {
        surface.session = session
        val frameJob = scope.launch(Dispatchers.Default) {
            session.frames.filterNotNull().collectLatest(surface::present)
        }
        onDispose {
            frameJob.cancel()
            surface.session = null
        }
    }

    AndroidView(
        modifier = modifier.background(Color.Black),
        factory = { surface },
        update = { it.session = session }
    )
}
