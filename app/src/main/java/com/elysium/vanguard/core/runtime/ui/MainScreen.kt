package com.elysium.vanguard.core.runtime.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.elysium.vanguard.core.runtime.workspaces.Workspace
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceSession
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceState

/**
 * Phase 37 — the runtime's main screen.
 *
 * The [MainScreen] is the entry point of the new
 * runtime UI. It consumes two `@HiltViewModel`s:
 *
 *   - [MainScreenViewModel] — the status bar's
 *     source of truth: `runningSessionCount`,
 *     `linuxDistrosInstalled`, `windowsVmsRunning`.
 *     This ViewModel composes the four runtime
 *     collaborators into a single state snapshot
 *     (Phase 28 + Phase 34).
 *   - [WorkspacesViewModel] — the workspace list's
 *     source of truth. Each card is a
 *     [com.elysium.vanguard.core.runtime.ui.WorkspaceSummary];
 *     the per-session Start / Stop actions are
 *     Phase 38+ (this phase is read-only).
 *
 * The screen is intentionally minimal in Phase 37:
 * a TopAppBar with the title + back, a row of three
 * stat cards (the "status bar"), and a LazyColumn of
 * workspace cards. Every state value comes from the
 * two ViewModels; the screen does no business logic
 * of its own.
 *
 * The two ViewModels are both `@HiltViewModel` +
 * `@Inject constructor` (Phase 36). The
 * `hiltViewModel()` Compose helper wires them to
 * the production graph (the real `WorkspaceManager`,
 * the real `SessionRunner`, the real `DistroManager`,
 * etc.) without any factory boilerplate.
 *
 * Phase 38 will add:
 *   - "Create workspace" button + form
 *   - "Add session" per workspace
 *   - Per-session Start / Stop buttons that call
 *     `vm.startSession(workspace, session)` /
 *     `vm.stopSession(workspace, session)`
 *   - Pause / Activate / Close workspace actions
 *
 * Phase 39 will add an `androidTest/` that drives
 * the screen end-to-end with a real Hilt graph
 * (the only piece of the runtime still inside the
 * `features/` package, and the only piece that
 * needs Android-instrumented coverage).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onBack: () -> Unit,
    onOpenRuntime: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    mainViewModel: MainScreenViewModel = hiltViewModel(),
    workspacesViewModel: WorkspacesViewModel = hiltViewModel()
) {
    val mainState by mainViewModel.state.collectAsState()
    val workspacesState by workspacesViewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sovereign Runtime", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            // --- Status bar: three stat cards ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatusCard(
                    icon = Icons.Filled.PlayArrow,
                    label = "Sessions",
                    value = mainState.runningSessionCount.toString(),
                    modifier = Modifier.weight(1f)
                )
                StatusCard(
                    icon = Icons.Filled.Terminal,
                    label = "Distros",
                    value = mainState.linuxDistrosInstalled.toString(),
                    modifier = Modifier.weight(1f)
                )
                StatusCard(
                    icon = Icons.Filled.DesktopWindows,
                    label = "Windows",
                    value = mainState.windowsVmsRunning.toString(),
                    modifier = Modifier.weight(1f)
                )
            }

            // --- Workspaces header ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Workspaces",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "(${workspacesState.workspaces.size})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // --- Workspace list ---
            if (workspacesState.workspaces.isEmpty()) {
                EmptyWorkspacesState(
                    onOpenRuntime = onOpenRuntime,
                    onOpenTerminal = onOpenTerminal
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(workspacesState.workspaces, key = { it.id }) { workspace ->
                        WorkspaceCard(workspace = workspace)
                    }
                }
            }
        }
    }
}

/**
 * A single status-bar card. Shows an icon, a label,
 * and a large numeric value. The card is the
 * Compose-side rendering of one
 * [MainScreenState] field.
 */
@Composable
private fun StatusCard(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * A single workspace card. Shows the workspace
 * name, its state, and a one-line summary of its
 * sessions (total + per-kind breakdown). The
 * "Start / Stop" buttons are Phase 38.
 */
@Composable
private fun WorkspaceCard(workspace: Workspace) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Computer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = workspace.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.weight(1f))
                StateChip(state = workspace.state)
            }
            Text(
                text = sessionSummary(workspace),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StateChip(state: WorkspaceState) {
    val (label, color) = when (state) {
        is WorkspaceState.Active -> "Active" to MaterialTheme.colorScheme.primary
        is WorkspaceState.Paused -> "Paused" to MaterialTheme.colorScheme.secondary
        is WorkspaceState.Closed -> "Closed" to MaterialTheme.colorScheme.error
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.15f))
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

private fun sessionSummary(workspace: Workspace): String {
    val linuxCount = workspace.sessions.count { it is WorkspaceSession.LinuxProot }
    val windowsCount = workspace.sessions.count { it is WorkspaceSession.WindowsVm }
    val parts = mutableListOf<String>()
    parts += "${workspace.sessions.size} session${if (workspace.sessions.size == 1) "" else "s"}"
    if (linuxCount > 0) parts += "$linuxCount Linux"
    if (windowsCount > 0) parts += "$windowsCount Windows"
    return parts.joinToString(" • ")
}

/**
 * The empty state. Phase 37 is read-only; the
 * "Create workspace" button is Phase 38. The two
 * links here redirect to the existing
 * [com.elysium.vanguard.features.runtime.RuntimeScreen]
 * (for distro install) and the terminal screen
 * (for the open-shell UX) so the user has a
 * productive action even when no workspaces
 * exist yet.
 */
@Composable
private fun EmptyWorkspacesState(
    onOpenRuntime: () -> Unit,
    onOpenTerminal: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "No workspaces yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Install a Linux distro or open a terminal session " +
                    "to bootstrap your first workspace.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.OutlinedButton(onClick = onOpenRuntime) {
                    Text("Browse Distros")
                }
                androidx.compose.material3.OutlinedButton(onClick = onOpenTerminal) {
                    Text("Open Terminal")
                }
            }
        }
    }
}
