# Phase 113 — Multi-Desktop Shell (Spaces / Virtual Desktops)

**Status:** ✅ SHIPPED
**Date:** 2026-07-22
**Commit:** (this commit)
**Build:** APK 98 MB
**Tests:** 3769 (+23 from Phase 112)

## Vision Alignment

The vision's "Escritorio visualmente
impresionante" (gap #9) calls for a desktop
shell that feels like "una experiencia de
windows nativa en android". Phase 78 shipped
the floating-window freeform layout. Phase
112 added the split-screen layout. Phase
113 adds **multi-desktop** — the ability
to have multiple independent "spaces" or
"virtual desktops" open at once, each with
its own windows + dock + layout mode.

This is the "Spaces" feature on macOS,
"Virtual Desktops" on Windows 10/11, and
"Workspaces" on GNOME / KDE. The user
switches between them via a tab strip at
the top of the desktop.

## Deliverables

### 1. `MultiDesktopShellState` (`features/desktop/multidesktop/`)

A data class that holds a list of
`DesktopSessionState`s (one per session /
space) + the `activeIndex` + the
`nextSessionNumber` (for auto-naming
new sessions).

The state's `initial()` factory ships a
single default session (the standard
`FREEFORM` desktop with the 4 pinned
apps). The `nextSessionNumber` starts at
1, so the user's first auto-named
session is "Session 1", the second is
"Session 2", etc.

### 2. `MultiDesktopShellViewModel`

A new ViewModel that manages the
multi-session state. The VM owns the
list of sessions + the active index.
Methods:

- `createSession(name: String? = null)` —
  appends a new session. The new session
  starts with no windows + the standard
  4 pinned apps + `FREEFORM` layout.
  Becomes the active session.
- `closeSession(index: Int)` — removes
  the session. The user cannot close the
  last remaining session.
- `switchTo(index: Int)` — changes the
  active index. The session's state is
  preserved (the user can switch back).
- `openWindow(...)` / `closeWindow(...)` /
  `setLayoutMode(...)` — operate on the
  active session only.

The VM is the **single source of
mutations** for the multi-desktop state.
The Compose layer reads the state via
`collectAsState`.

### 3. `SessionTabStrip` Compose component

A row of tabs at the top of the desktop.
Each tab shows the session's name + a
window count badge + a close (×) button.
The active session's tab is highlighted
in the primary color. The strip also has
a "+" button at the end to add a new
session.

### 4. `MultiDesktopShellScreen`

The Compose layer that ties everything
together. The screen renders:

- The `SessionTabStrip` at the top-left.
- The active session's `DesktopShellContent`
  below.
- The existing `LayoutModeToggle` at the
  top-right (per-session).

## Test Coverage

| Test class | New tests | Total |
|---|---|---|
| `MultiDesktopShellViewModelTest` | 23 | 23 |

Total new: 23 test methods.
Total: 3769 tests, 1 pre-existing flake (unchanged
from Phase 112), 2 skipped.

## Architecture Decisions

### Why a list of `DesktopSessionState` (not a list of `DesktopShellViewModel`)?

The multi-shell is a single ViewModel
(one entry in the `ViewModelStore`); the
`sessions` field is the list of passive
states. This avoids the complexity of
nesting ViewModels (which is not a
standard Compose pattern) + the
`StateFlow<MultiDesktopShellState>`
naturally composes the session list +
the active index into a single
observable.

### Why an `activeIndex` (not an `activeSessionId`)?

The sessions are append-only; the user
can `closeSession` at an index, but the
index of the remaining sessions does not
change. A numerical index is the simplest
way to refer to the active session + the
invariant is a single `init { require(
activeIndex in sessions.indices) }` check.

### Why a `nextSessionNumber` (not random IDs)?

The user sees the session's name in the
tab strip. Auto-generated names like
"Session 1", "Session 2" are predictable +
the user can rename the session via the
custom name parameter. The counter is the
smallest surface that does the job.

### Why the last session cannot be closed (returns `Result.failure`)?

A multi-desktop shell with zero sessions
is a degenerate state. The user should
have at least one session at all times
(even if they don't like any of them,
they can close all the windows in the
last one + the session is still "there").
Returning a typed `FoundryError` lets
the UI show "cannot close the last
session" rather than silently emptying
the state.

### Why the tab strip at the top (not a sidebar)?

A horizontal tab strip is the standard
pattern for "switch between a small
number of similar things" (browser tabs,
code editor tabs). A sidebar is for
"browse a large number of things" (the
file manager). Sessions are a small
number (typically 2-5); a tab strip
shows them all at once + uses horizontal
space efficiently.

## Files

### New (production)

- `app/src/main/java/com/elysium/vanguard/features/desktop/multidesktop/MultiDesktopShellState.kt`
- `app/src/main/java/com/elysium/vanguard/features/desktop/multidesktop/MultiDesktopShellViewModel.kt`
- `app/src/main/java/com/elysium/vanguard/features/desktop/multidesktop/MultiDesktopShellScreen.kt`
- `app/src/main/java/com/elysium/vanguard/features/desktop/multidesktop/SessionTabStrip.kt`

### New (tests)

- `app/src/test/java/com/elysium/vanguard/features/desktop/multidesktop/MultiDesktopShellViewModelTest.kt`

## Next

- **Phase 114** — Monitor recursos + monitor
  temperatura: `/proc/meminfo`, `/proc/stat`,
  `/sys/class/thermal/`.
- **Phase 115** — Box64 / FEX / DXVK graphics
  translation: depends on having the binaries
  (Phase 101 rootfs lists them but the
  integration is Phase 109+ work).
- **Future cross-runtime drag-drop** — drag a
  file from a Linux VM window to a Windows
  VM window. The drag-drop requires file
  transfer between runtimes; the seam is in
  place (`FileAction` has the actions; the
  drag-drop is a future phase).
- **Future shared clipboard** — copy from one
  runtime, paste to another. The
  `SecretStore`-style audit log can record
  clipboard operations.
- **Future multi-desktop persistence** — save
  the multi-desktop state to disk + restore
  on app launch. The state is currently
  in-memory; persistence is a future phase.
