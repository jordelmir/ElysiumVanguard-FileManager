package com.elysium.vanguard.features.runtime.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.elysium.vanguard.core.runtime.terminal.session.TerminalSession
import com.elysium.vanguard.core.runtime.terminal.view.TerminalHost
import com.elysium.vanguard.core.runtime.distros.launcher.LauncherKind

/**
 * PHASE 9.6.3 — Top-level terminal screen.
 *
 * Renders the [TerminalHost] plus a header that describes what the
 * session is running. When the [TerminalViewModel.launcherResolution]
 * StateFlow is non-null (the screen was opened from a distro's "Open"
 * button), the title shows the distro + the picked launcher kind. In
 * the 9.6.1 fallback (no distroId arg), the title falls back to the
 * standard "Elysium Terminal" with `sh · pid unknown · 80×24`.
 *
 * Phase 9.6.1 — first build; intentionally minimal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    onBack: () -> Unit,
    viewModel: TerminalViewModel = hiltViewModel()
) {
    val lifecycle by viewModel.lifecycle.collectAsState()
    val exitCode by viewModel.exitCode.collectAsState()
    val launcherPick by viewModel.launcherPick.collectAsState()

    Scaffold(
        containerColor = Color(0xFF0B0D10),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = headerTitle(launcherPick?.launcher?.kind),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            color = Color(0xFFE4E7EB)
                        )
                        Text(
                            text = lifecycleLabel(lifecycle, exitCode, launcherPick),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color(0xFF8B949E)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color(0xFFE4E7EB)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.sendInterrupt() }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Send Ctrl-C",
                            tint = Color(0xFFE4E7EB)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0F1115)
                )
            )
        }
    ) { padding: PaddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TerminalHost(
                modifier = Modifier.fillMaxSize(),
                session = viewModel.session,
                onBytesTyped = { bytes -> viewModel.send(bytes) },
                onSessionExited = { _ -> /* state already in flow */ }
            )

            exitCode?.let {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Shell exited (code $it)",
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFFF6E6E)
                    )
                    Text(
                        text = "Tap back to return.",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFF8B949E),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

private fun headerTitle(kind: LauncherKind?): String = when (kind) {
    LauncherKind.JAILED_SHELL -> "Elysium Terminal · jailed"
    LauncherKind.NATIVE_PROOT -> "Elysium Terminal · proot"
    LauncherKind.NAMESPACE_UNSHARE -> "Elysium Terminal · unshare"
    null -> "Elysium Terminal"
}

private fun lifecycleLabel(
    state: TerminalSession.State,
    exitCode: Int?,
    pick: com.elysium.vanguard.core.runtime.distros.launcher.LauncherPick?
): String {
    val base = when (state) {
        TerminalSession.State.NotStarted -> "starting…"
        TerminalSession.State.Starting -> "starting…"
        is TerminalSession.State.Running -> "sh · pid unknown · 80×24"
        is TerminalSession.State.Exited -> "exited code ${state.exitCode}"
        is TerminalSession.State.Error -> "err: ${state.message}"
        TerminalSession.State.Stopped -> exitCode?.let { "stopped ($it)" } ?: "stopped"
    }
    val reason = pick?.reason
    return if (reason != null) "$base · $reason" else base
}
