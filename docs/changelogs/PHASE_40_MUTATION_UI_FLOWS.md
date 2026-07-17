# Phase 40 — Create Workspace + Add Session + Pause/Activate/Close UI flows

Date: 2026-07-17
Status: **Shipped** — `assembleDebug` green, 1359 tests, 0 failures, 2 skipped.

## What landed

`MainScreen` is no longer read-only on the
mutation side. The user can now:

- **Create a workspace** — an "Add" icon in the
  TopAppBar opens a name-only dialog. The
  trimmed name flows to
  [WorkspacesViewModel.createWorkspace], which
  delegates to [WorkspaceManager.createWorkspace].
  The manager publishes the
  `WorkspaceStateChangedEvent` (Phase 39), the
  bus subscriber refreshes the screen.
- **Add a session to a workspace** — an
  "Add session" button at the bottom of every
  workspace card opens a kind-picker dialog
  (Linux proot or Windows VM). The dialog
  collects a display name + the kind-specific
  fields (distroId + profileId for Linux,
  windowsSpecId for Windows) and constructs a
  typed [WorkspaceSession] that flows to
  [WorkspacesViewModel.addSession].
- **Pause / Activate / Close a workspace** — a
  3-dot menu in the workspace card header
  exposes the three transitions. The menu
  items are gated on the current state (a
  `Closed` workspace can be re-activated; an
  `Active` one can be paused or closed; etc.).
- **Remove a session from a workspace** — a
  small "×" icon at the end of every session
  row calls
  [WorkspacesViewModel.removeSession]. The
  manager's per-workspace lock serialises the
  read-modify-write.

Phase 40 closes the gap between "see the
runtime's state" (Phase 37) and "mutate the
runtime's state". Combined with Phase 38's
Start/Stop buttons, the new runtime screen
now has a full mutation surface — every
`WorkspacesViewModel` action is reachable from
the UI.

### Files

**Production (1 modified):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/ui/MainScreen.kt` — added:
  - A "Create workspace" `IconButton` in the
    `TopAppBar.actions` slot.
  - A 3-dot `DropdownMenu` on every
    `WorkspaceCard` with Pause / Activate /
    Close items.
  - An "Add session" `OutlinedButton` at the
    bottom of every `WorkspaceCard`.
  - A "×" `IconButton` at the end of every
    `SessionRow` for session removal.
  - A `CreateWorkspaceDialog` (name-only
    `AlertDialog`).
  - An `AddSessionDialog` (kind-picker +
    display name + kind-specific fields via
    `OutlinedTextField`s).
  - The screen gained two `mutableStateOf`
    booleans for dialog visibility
    (`showCreateDialog`, `addSessionToWorkspaceId`).

**Tests:** none added. The action layer is
covered by [WorkspacesViewModelTest] (19
tests) and [WorkspaceManagerTest] (36 tests);
the Compose UI itself still needs `androidTest/`
for end-to-end coverage.

## What the screen now does

| Affordance | Action | Manager call |
|---|---|---|
| "Add" in TopAppBar | Opens `CreateWorkspaceDialog` | `vm.createWorkspace(name)` |
| "Add session" button | Opens `AddSessionDialog` | `vm.addSession(workspaceId, session)` |
| "×" on a session row | Removes the session | `vm.removeSession(workspaceId, sessionId)` |
| 3-dot → Pause | Pauses an Active workspace | `vm.pauseWorkspace(workspaceId)` |
| 3-dot → Activate | Activates a Paused / Closed workspace | `vm.activateWorkspace(workspaceId)` |
| 3-dot → Close | Closes an Active / Paused workspace | `vm.closeWorkspace(workspaceId)` |

The mutating actions flow through the
[WorkspacesViewModel] (no UI-side state
mutation) → the [WorkspaceManager] (publishes
the event) → the [RuntimeEventBus] → the
[WorkspacesViewModel] subscriber (refreshes
its state) → the [MainScreen] Compose tree
(recomposes with the new workspace list).

## Why this matters

Until Phase 40, the user could see and start /
stop sessions, but could not change the
workspace graph itself. Adding a workspace
required either an `androidTest/` (Phase 39+
in the changelog roadmap) or a future
"Create workspace" screen. Phase 40 makes the
existing screen a complete mutation surface:
every action the runtime supports is reachable
through a single `MainScreen` instance.

The mutation surface is also the natural
end-state for the new runtime path: the user
can now create a workspace, add Linux sessions
+ Windows sessions, start / stop the sessions
individually, pause the whole workspace,
re-activate it, close it, and re-open it — all
without leaving the `runtime_main` route.

## Test count

| Suite | Tests | Failures |
|---|---|---|
| (no new tests) | 0 | 0 |
| **Project total** | **1359** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Next phase

The follow-up after Phase 40 is **the
`androidTest/` end-to-end coverage for
`MainScreen`** — the only piece of the runtime
still missing instrumented coverage. A second
follow-up adds the **catalog-driven `Add
Session` picker** (Phase 41) — instead of
free-form text fields, the dialog shows the
real `DistroCatalog` (Linux) / `WindowsVmCatalog`
(Windows) the user can pick from. Phase 40's
free-form fields are the minimum viable UX;
Phase 41 makes the picker data-driven.
