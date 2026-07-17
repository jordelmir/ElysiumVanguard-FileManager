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
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.elysium.vanguard.core.runtime.distros.Distro
import com.elysium.vanguard.core.runtime.distros.DistroCatalog
import com.elysium.vanguard.core.runtime.distros.profile.ElysiumProfile
import com.elysium.vanguard.core.runtime.windows.WindowsVmCatalog
import com.elysium.vanguard.core.runtime.windows.WindowsVmSpec
import java.util.UUID
import com.elysium.vanguard.core.runtime.runner.SessionState
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
    onOpenLinuxSession: (distroId: String) -> Unit = {},
    mainViewModel: MainScreenViewModel = hiltViewModel(),
    workspacesViewModel: WorkspacesViewModel = hiltViewModel()
) {
    val mainState by mainViewModel.state.collectAsState()
    val workspacesState by workspacesViewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()

    // Phase 40 — local UI state for the dialogs
    // (create workspace, add session). `null` means
    // the dialog is dismissed. The values are owned
    // by the screen, not the ViewModel, so the
    // ViewModel stays unaware of UI affordances.
    var showCreateDialog by remember { mutableStateOf(false) }
    var addSessionToWorkspaceId by remember { mutableStateOf<String?>(null) }

    // Phase 38 — surface the last action's result on
    // a snackbar so the user sees "session start
    // failed: Distro not installed" or similar. The
    // ViewModel writes `lastActionResult` on every
    // action; the Snackbar shows it once and clears.
    val lastResult = workspacesState.lastActionResult
    LaunchedEffect(lastResult) {
        val result = lastResult ?: return@LaunchedEffect
        if (result.isFailure) {
            val message = result.exceptionOrNull()?.message ?: "Action failed"
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sovereign Runtime", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Phase 40 — the "Create workspace" action.
                    // Opens [CreateWorkspaceDialog] when tapped.
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "Create workspace")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
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
                        WorkspaceCard(
                            workspace = workspace,
                            sessionStates = workspacesState.sessionStates,
                            onStartSession = { session ->
                                workspacesViewModel.startSession(workspace, session)
                            },
                            onStopSession = { session ->
                                workspacesViewModel.stopSession(workspace, session)
                            },
                            onAddSession = { addSessionToWorkspaceId = workspace.id },
                            onPause = { workspacesViewModel.pauseWorkspace(workspace.id) },
                            onActivate = { workspacesViewModel.activateWorkspace(workspace.id) },
                            onClose = { workspacesViewModel.closeWorkspace(workspace.id) },
                            onRemoveSession = { sessionId ->
                                workspacesViewModel.removeSession(workspace.id, sessionId)
                            },
                            // Phase 45 — the per-kind open
                            // affordance. LinuxProot sessions
                            // navigate to the terminal screen
                            // pre-loaded with the distro; WindowsVm
                            // sessions would open a VNC viewer
                            // (not yet implemented; for now the
                            // snackbar is the affordance).
                            onOpenSession = { session ->
                                when (session) {
                                    is WorkspaceSession.LinuxProot -> {
                                        onOpenLinuxSession(session.distroId)
                                    }
                                    is WorkspaceSession.WindowsVm -> {
                                        // Phase 45 — VNC viewer is
                                        // not yet implemented (Phase
                                        // 9.6.5 in the Worldwide Vision
                                        // doc). The snackbar is the
                                        // user-facing affordance until
                                        // then. The launch fires from
                                        // a coroutine scope because
                                        // [SnackbarHostState.showSnackbar]
                                        // is a suspend function.
                                        snackbarScope.launch {
                                            snackbarHostState.showSnackbar(
                                                "VNC viewer not yet implemented"
                                            )
                                        }
                                    }
                                }
                            },
                            // Phase 46 — the bulk start/stop
                            // actions. The ViewModel iterates
                            // per session and delegates to the
                            // per-session start/stop methods;
                            // the menu is the single entry
                            // point.
                            onStartAll = {
                                workspacesViewModel.startAllSessions(workspace.id)
                            },
                            onStopAll = {
                                workspacesViewModel.stopAllSessions(workspace.id)
                            }
                        )
                    }
                }
            }
        }
    }

    // Phase 40 — the two dialogs live as siblings of
    // the Scaffold so the Scaffold's content slot
    // stays a pure render of the state.
    if (showCreateDialog) {
        CreateWorkspaceDialog(
            onConfirm = { name ->
                workspacesViewModel.createWorkspace(name)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false }
        )
    }

    val targetWorkspaceId = addSessionToWorkspaceId
    if (targetWorkspaceId != null) {
        val workspace = workspacesState.workspaces.firstOrNull { it.id == targetWorkspaceId }
        if (workspace != null) {
            AddSessionDialog(
                onConfirm = { session ->
                    workspacesViewModel.addSession(workspace.id, session)
                    addSessionToWorkspaceId = null
                },
                onDismiss = { addSessionToWorkspaceId = null }
            )
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
 * name, its state, and a per-session row with
 * a Start / Stop button. Phase 38 — the
 * "Start / Stop" buttons are wired to
 * [WorkspacesViewModel.startSession] /
 * [WorkspacesViewModel.stopSession].
 * Phase 40 — the card gained a 3-dot menu with
 * Pause / Activate / Close actions + an "Add
 * session" affordance.
 */
@Composable
private fun WorkspaceCard(
    workspace: Workspace,
    sessionStates: Map<WorkspacesViewModel.SessionKey, SessionState>,
    onStartSession: (WorkspaceSession) -> Unit,
    onStopSession: (WorkspaceSession) -> Unit,
    onAddSession: () -> Unit,
    onPause: () -> Unit,
    onActivate: () -> Unit,
    onClose: () -> Unit,
    onRemoveSession: (String) -> Unit,
    onOpenSession: (WorkspaceSession) -> Unit,
    onStartAll: () -> Unit,
    onStopAll: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // --- Header: name + state chip + 3-dot menu ---
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
                Spacer(modifier = Modifier.width(4.dp))
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "Workspace menu",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        // Phase 46 — Start all / Stop all
                        // actions. The menu exposes the
                        // bulk operation alongside the
                        // per-session Start / Stop so
                        // the user can power-cycle a
                        // whole workspace in one tap.
                        // The items render regardless of
                        // workspace state — the per-
                        // session startability check
                        // happens in the ViewModel.
                        DropdownMenuItem(
                            text = { Text("Start all") },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onStartAll()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Stop all") },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Stop,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onStopAll()
                            }
                        )
                        if (workspace.state is WorkspaceState.Active) {
                            DropdownMenuItem(
                                text = { Text("Pause") },
                                leadingIcon = {
                                    Icon(Icons.Filled.Pause, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    onPause()
                                }
                            )
                        }
                        if (workspace.state is WorkspaceState.Paused ||
                            workspace.state is WorkspaceState.Closed
                        ) {
                            DropdownMenuItem(
                                text = { Text("Activate") },
                                leadingIcon = {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    onActivate()
                                }
                            )
                        }
                        if (workspace.state !is WorkspaceState.Closed) {
                            DropdownMenuItem(
                                text = { Text("Close") },
                                leadingIcon = {
                                    Icon(Icons.Filled.Close, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    onClose()
                                }
                            )
                        }
                    }
                }
            }

            // --- Per-session rows ---
            if (workspace.sessions.isEmpty()) {
                Text(
                    text = "No sessions",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                workspace.sessions.forEach { session ->
                    SessionRow(
                        session = session,
                        state = sessionStates[
                            WorkspacesViewModel.SessionKey(
                                workspaceId = workspace.id,
                                sessionId = session.id
                            )
                        ] ?: SessionState.Idle,
                        onStart = { onStartSession(session) },
                        onStop = { onStopSession(session) },
                        onRemove = { onRemoveSession(session.id) },
                        // Phase 45 — the "Open" affordance.
                        // The row only renders the button
                        // when the session is Running;
                        // here we pass the navigation
                        // callback unconditionally so the
                        // row can decide visibility.
                        onOpen = { onOpenSession(session) }
                    )
                }
            }

            // --- Add session affordance ---
            OutlinedButton(
                onClick = onAddSession,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add session", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * A single session row inside a [WorkspaceCard].
 * Shows the session's display name + kind, a
 * [SessionStateBadge], a Start / Stop button,
 * and (when the session is Running) an "Open"
 * affordance that navigates to the matching
 * runtime surface.
 *
 * Phase 40 — added a long-press friendly "remove"
 * affordance (`onRemove`).
 * Phase 45 — added `onOpen` (a navigation
 * callback the parent supplies for the
 * session's kind) and the [OpenSessionButton]
 * that surfaces it when the session is live.
 */
@Composable
private fun SessionRow(
    session: WorkspaceSession,
    state: SessionState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRemove: () -> Unit,
    onOpen: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = when (session) {
                is WorkspaceSession.LinuxProot -> Icons.Filled.Terminal
                is WorkspaceSession.WindowsVm -> Icons.Filled.DesktopWindows
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = sessionSubtitle(session),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        SessionStateBadge(state = state)
        // Phase 45 — the "Open" affordance appears
        // only when the session is Running AND a
        // navigation callback is provided. The
        // callback is null in tests / previews.
        if (state is SessionState.Running && onOpen != null) {
            Spacer(modifier = Modifier.width(8.dp))
            OpenSessionButton(onClick = onOpen)
        }
        Spacer(modifier = Modifier.width(8.dp))
        SessionActionButton(
            state = state,
            onStart = onStart,
            onStop = onStop
        )
        // Phase 40 — small remove button. Hidden
        // behind a 16dp icon so the row stays
        // compact; the visual affordance is a
        // "remove" hint (no label, just the icon)
        // since the user has the Start / Stop
        // button as the primary action.
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove session",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Phase 45 — the "Open" affordance for a Running
 * session. Compact icon-only button (the session
 * row is already full-width with badge + Start /
 * Stop + remove). The label is the icon's
 * contentDescription; the visual cue is a "play
 * in a window" icon (`OpenInNew`).
 */
@Composable
private fun OpenSessionButton(onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(32.dp)
    ) {
        Icon(
            Icons.Filled.OpenInNew,
            contentDescription = "Open session",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

private fun sessionSubtitle(session: WorkspaceSession): String = when (session) {
    is WorkspaceSession.LinuxProot -> "${session.distroId} • ${session.profileId}"
    is WorkspaceSession.WindowsVm -> "spec: ${session.windowsSpecId}"
}

/**
 * The Start / Stop button for a single session. The
 * button shows the action that the user CAN take
 * right now (not the current state):
 *
 *   - Idle / Stopped / Error → "Start" (the runner
 *     accepts a start).
 *   - Starting / Running → "Stop" (the runner
 *     accepts a stop).
 *   - Stopping → disabled (the runner is already
 *     tearing down; wait for the transition to
 *     finish before allowing a retry).
 */
@Composable
private fun SessionActionButton(
    state: SessionState,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    when (state) {
        is SessionState.Idle, is SessionState.Stopped, is SessionState.Error -> {
            Button(
                onClick = onStart,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "Start",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Start", style = MaterialTheme.typography.labelMedium)
            }
        }
        is SessionState.Starting, is SessionState.Running -> {
            Button(
                onClick = onStop,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(
                    Icons.Filled.Stop,
                    contentDescription = "Stop",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Stop", style = MaterialTheme.typography.labelMedium)
            }
        }
        is SessionState.Stopping -> {
            OutlinedButton(
                onClick = {},
                enabled = false,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text("Stopping…", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * A small pill that shows the [SessionState] name
 * (Idle / Starting / Running / Stopping / Stopped /
 * Error). The pill is informational — the action
 * button next to it is the user-facing affordance.
 */
@Composable
private fun SessionStateBadge(state: SessionState) {
    val (label, color) = when (state) {
        is SessionState.Idle -> "Idle" to MaterialTheme.colorScheme.onSurfaceVariant
        is SessionState.Starting -> "Starting" to MaterialTheme.colorScheme.tertiary
        is SessionState.Running -> "Running" to MaterialTheme.colorScheme.primary
        is SessionState.Stopping -> "Stopping" to MaterialTheme.colorScheme.tertiary
        is SessionState.Stopped -> "Stopped" to MaterialTheme.colorScheme.onSurfaceVariant
        is SessionState.Error -> "Error" to MaterialTheme.colorScheme.error
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

/**
 * Phase 40 — the "Create workspace" dialog.
 *
 * A minimal name-only form. The user types a
 * name, taps "Create", and the screen calls
 * [WorkspacesViewModel.createWorkspace] with
 * the trimmed name. Empty / blank names are
 * rejected client-side; the
 * [com.elysium.vanguard.core.runtime.workspaces.WorkspaceManager]
 * also rejects them server-side (defence in
 * depth). The dialog dismisses on Cancel and
 * on a successful Create.
 */
@Composable
private fun CreateWorkspaceDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    val isValid = name.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create workspace") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                isError = name.isNotEmpty() && !isValid
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (isValid) onConfirm(name.trim()) },
                enabled = isValid
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * Phase 41 — the catalog-driven "Add session"
 * dialog. The user picks a kind (Linux proot or
 * Windows VM) and a display name; the distroId /
 * profileId / windowsSpecId come from dropdowns
 * backed by the real [DistroCatalog] /
 * [ElysiumProfile] / [WindowsVmCatalog] data.
 *
 * The dialog returns a fully-formed
 * [WorkspaceSession] the screen hands to
 * [WorkspacesViewModel.addSession].
 */
@Composable
private fun AddSessionDialog(
    onConfirm: (WorkspaceSession) -> Unit,
    onDismiss: () -> Unit
) {
    var isLinux by remember { mutableStateOf(true) }
    var displayName by remember { mutableStateOf("") }
    // Phase 41 — the selected objects, not strings.
    // Initial: the first distro / first profile /
    // first Windows spec. A real distro/profile/spec
    // is always present (the catalogs are hand-
    // curated with at least one entry each).
    val distros = remember { DistroCatalog.ALL }
    val profiles = remember { ElysiumProfile.entries.toList() }
    val windowsSpecs = remember { WindowsVmCatalog.official().all }
    var selectedDistro by remember { mutableStateOf(distros.first()) }
    var selectedProfile by remember { mutableStateOf(profiles.first()) }
    var selectedWindowsSpec by remember { mutableStateOf(windowsSpecs.first()) }

    val isValid = displayName.isNotBlank() &&
        (if (isLinux) true else true) // catalogs are non-empty

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add session") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Kind toggle
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = isLinux,
                        onClick = { isLinux = true },
                        label = { Text("Linux") }
                    )
                    FilterChip(
                        selected = !isLinux,
                        onClick = { isLinux = false },
                        label = { Text("Windows") }
                    )
                }
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display name") },
                    singleLine = true
                )
                if (isLinux) {
                    // --- Distro picker ---
                    DropdownPicker(
                        label = "Distro",
                        options = distros,
                        selected = selectedDistro,
                        onSelected = { selectedDistro = it },
                        optionLabel = { it.displayName },
                        optionId = { it.id }
                    )
                    // --- Profile picker ---
                    DropdownPicker(
                        label = "Profile",
                        options = profiles,
                        selected = selectedProfile,
                        onSelected = { selectedProfile = it },
                        optionLabel = { it.displayName },
                        optionId = { it.id }
                    )
                } else {
                    // --- Windows spec picker ---
                    DropdownPicker(
                        label = "Windows spec",
                        options = windowsSpecs,
                        selected = selectedWindowsSpec,
                        onSelected = { selectedWindowsSpec = it },
                        optionLabel = { it.displayName },
                        optionId = { it.id }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!isValid) return@TextButton
                    val sessionId = "s-${UUID.randomUUID().toString().take(8)}"
                    val session = if (isLinux) {
                        WorkspaceSession.LinuxProot(
                            id = sessionId,
                            displayName = displayName.trim(),
                            distroId = selectedDistro.id,
                            profileId = selectedProfile.id
                        )
                    } else {
                        WorkspaceSession.WindowsVm(
                            id = sessionId,
                            displayName = displayName.trim(),
                            windowsSpecId = selectedWindowsSpec.id
                        )
                    }
                    onConfirm(session)
                },
                enabled = isValid
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * Phase 41 — a generic dropdown picker composable
 * shared by the three "Add session" pickers
 * (Distro, Profile, Windows spec). The picker
 * shows the selected option's [optionLabel] and
 * expands into a list of every [options] entry
 * on click. The selection is a value-typed
 * object (not a string), so the caller gets
 * type-safe access to the underlying data.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> DropdownPicker(
    label: String,
    options: List<T>,
    selected: T,
    onSelected: (T) -> Unit,
    optionLabel: (T) -> String,
    optionId: (T) -> String
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = optionLabel(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Filled.KeyboardArrowUp
                        else Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Toggle $label"
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "Select ${optionId(option)}"
                    }
                )
            }
        }
    }
}
