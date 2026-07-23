# PHASE 115 — Multi-Desktop Shell Wiring & Window State Methods

**Date**: 2026-07-23
**Scope**: Visual review caught 3 bugs in Phase 113 (Multi-Desktop Shell). All fixed in this phase.
**Build**: `./gradlew testDebugUnitTest` → 3796 tests, 1 pre-existing flake (not from this phase)
**APK**: 103 MB

---

## Summary

The DESKTOP card on the dashboard was supposed to launch the **multi-desktop shell**
(Phase 113, with session tabs + freeform/split/stack layout) but actually launched
the **single-session shell** (Phase 79, with no tabs). The multi-desktop code
existed but was unreachable + had several dead callbacks. This phase:

1. **Routes `desktop_shell` to `MultiDesktopShellScreen`** — the multi-shell is now
   the default desktop surface.
2. **Adds 6 missing window-state methods to `MultiDesktopShellViewModel`**
   (focusWindow, minimizeWindow, maximizeWindow, restoreWindow, updateWindowBounds,
   pinApp) that delegate to the active session.
3. **Wires the multi-shell screen's callbacks** to the new methods (the screen had
   empty lambdas for every window-state callback).
4. **Pins the session tab strip to the top-left** of the screen with explicit
   `align(TopStart)` + `zIndex(1f)` so it draws above the desktop content.

Combined with the **Phase 10.10.1 DashboardScreen clickable fix** (SovereignCard
3D rotation displaced the clickable hit area; fixed by moving `clickable` from
the outer modifier to the inner background Box).

## Bugs found in visual review

### Bug 1 — `desktop_shell` route pointed to single-session shell

`MainActivity.kt:246` called `DesktopShellScreen` (single-session) instead of
`MultiDesktopShellScreen`. Result: the user tapped "DESKTOP" and got a desktop
**without** session tabs.

**Fix**: wire `desktop_shell` → `MultiDesktopShellScreen` + use
`MultiDesktopShellViewModel.Companion.Factory`.

```kotlin
// MainActivity.kt:245 — before
composable("desktop_shell") {
    com.elysium.vanguard.features.desktop.DesktopShellScreen(
        viewModel = androidx.lifecycle.viewmodel.compose.viewModel(
            factory = com.elysium.vanguard.features.desktop.DesktopShellViewModelFactory,
        )
    )
}
// MainActivity.kt:245 — after
composable("desktop_shell") {
    com.elysium.vanguard.features.desktop.multidesktop.MultiDesktopShellScreen(
        viewModel = androidx.lifecycle.viewmodel.compose.viewModel(
            factory = com.elysium.vanguard.features.desktop.multidesktop
                .MultiDesktopShellViewModel.Companion.Factory,
        )
    )
}
```

### Bug 2 — `MultiDesktopShellScreen` callbacks were dead code

The screen's `onWindowClick`, `onWindowMinimize`, `onWindowMaximize`,
`onWindowRestore`, `onWindowDragged` callbacks all had `/* delegate to multi-VM */`
comments with no body. The screen delegated `onWindowClose` and `setLayoutMode`
correctly, but the rest were no-ops. The dock's `RUNNING_WINDOW` click handler
also had empty bodies (no actual focus / restore logic).

**Fix**: 
- Add the 6 missing methods to `MultiDesktopShellViewModel` (mirroring the
  single-session VM).
- Wire the screen callbacks to `viewModel.focusWindow(id)`,
  `viewModel.minimizeWindow(id)`, etc.
- Wire the dock's `RUNNING_WINDOW` click handler to call
  `viewModel.restoreWindow(id)` if the window is MINIMIZED, else
  `viewModel.focusWindow(id)`.

```kotlin
// MultiDesktopShellScreen.kt — before
onWindowClick = { /* delegate to multi-VM */ },
onWindowMinimize = { /* delegate to multi-VM */ },
// ... etc

// MultiDesktopShellScreen.kt — after
onWindowClick = { id -> viewModel.focusWindow(id) },
onWindowMinimize = { id -> viewModel.minimizeWindow(id) },
onWindowMaximize = { id -> viewModel.maximizeWindow(id) },
onWindowRestore = { id -> viewModel.restoreWindow(id) },
onWindowClose = { id -> viewModel.closeWindow(id) },
onWindowDragged = { id, bounds -> viewModel.updateWindowBounds(id, bounds) },
```

### Bug 3 — Session tab strip was invisible

The `SessionTabStrip` Box in `MultiDesktopShellScreen` had no explicit
`align(...)` modifier. In a `Box` with the default `contentAlignment = TopStart`,
it should have been at the top-left, but the desktop's window content (which
fills the screen) drew on top of it, hiding the strip entirely.

**Fix**: pin the tab strip to `TopStart` + `zIndex(1f)` so it draws above the
desktop content.

```kotlin
Box(
    modifier = Modifier
        .align(Alignment.TopStart)
        .zIndex(1f)
        .padding(top = 12.dp, start = 16.dp),
) {
    SessionTabStrip(...)
}
```

## New methods on `MultiDesktopShellViewModel`

All 6 methods follow the same pattern as the single-session VM but operate
on the **active session's state** (the other sessions are preserved unchanged).

```kotlin
fun focusWindow(id: String): Result<Unit>
fun minimizeWindow(id: String): Result<Unit>
fun maximizeWindow(id: String): Result<Unit>
fun restoreWindow(id: String): Result<Unit>
fun updateWindowBounds(id: String, newBounds: WindowBounds): Result<Unit>
fun pinApp(iconKey: String, label: String): Result<Unit>
```

**`focusWindow`**: raise the window to the top of the z-order; set it as the
session's focused id. If the window is MINIMIZED, restore it to NORMAL.

**`minimizeWindow`**: set the window's state to MINIMIZED. The focused id
falls through to the next visible window (if any) — same logic as
`closeWindow` but for hiding.

**`maximizeWindow`**: set the window's bounds to the desktop bounds + raise
z-order + set focused id.

**`restoreWindow`**: set the window's state to NORMAL. The focused id is set
to the window.

**`updateWindowBounds`**: apply new bounds. **No-op** when the window is
MAXIMIZED (maximized windows ignore drag updates — same invariant as the
single-session VM).

**`pinApp`**: add a new `DockItem` with kind `PINNED_APP`. **No-op** when an
app with the same `iconKey` is already pinned. Returns `Result.failure` for
blank inputs.

## Tests

**15 new tests** in `MultiDesktopShellViewModelTest` covering the new methods:

```
focusWindow raises the window to the top of the z-order
focusWindow on a non-existent window is a no-op
focusWindow targets the active session only
minimizeWindow sets the window to MINIMIZED state
minimizeWindow falls through the focused id when minimizing the focused window
minimizeWindow does not change the focused id when minimizing a non-focused window
maximizeWindow sets bounds to desktop bounds and raises z-order
restoreWindow sets a minimized window back to NORMAL
updateWindowBounds applies the new bounds in NORMAL state
updateWindowBounds is a no-op when the window is MAXIMIZED
updateWindowBounds is a no-op on a non-existent window
pinApp adds a new dock item to the active session
pinApp is a no-op when the iconKey is already pinned
pinApp with a blank iconKey returns failure
window state-machine methods target the active session only
```

## Files changed

```
app/src/main/java/com/elysium/vanguard/MainActivity.kt                                    (1 route change)
app/src/main/java/com/elysium/vanguard/features/dashboard/DashboardScreen.kt             (Phase 10.10.1 clickable fix)
app/src/main/java/com/elysium/vanguard/features/desktop/multidesktop/MultiDesktopShellScreen.kt  (callback wiring + alignment)
app/src/main/java/com/elysium/vanguard/features/desktop/multidesktop/MultiDesktopShellViewModel.kt  (6 new methods)
app/src/test/java/com/elysium/vanguard/features/desktop/multidesktop/MultiDesktopShellViewModelTest.kt  (+15 tests)
docs/changelogs/PHASE_115_MULTI_DESKTOP_WIRING.md                                          (this file)
```

## Visual review confirmed working

After the fix, the DESKTOP card on the dashboard correctly opens the
multi-desktop shell:
- **Top-left**: session tab strip with "Terminal" (default session) + "Session 1"
  (auto-named new session) + "+" button
- **Top-right**: Free / Split / Stack layout toggle
- **Bottom**: dock with Terminal, Files, Settings, Notes + 2 running indicators
  for the open windows in the active session
- **Window operations** (open, focus, minimize, maximize, restore, close) all
  work — verified visually by tapping the Minimize / Maximize / Close buttons
  on the open window and observing the state changes.
- **Layout modes** (Free / Split / Stack) all work — verified by tapping each
  chip and observing the window arrangement.
- **Session operations** (create, switch, close) all work — verified by
  tapping the "+" button to create a new session and tapping the tabs to
  switch between them.

## Lessons

- **Always test new screens in the navigation graph**. The multi-shell
  Composable was created in Phase 113 but never wired into the navigation;
  it existed as dead code. The new Phase should have a "this is reachable"
  test (a navigation test that asserts `navController.navigate("desktop_shell")`
  results in the multi-shell being composed).
- **Window-state methods on a multi-ViewModel must be added explicitly** when
  the single-session VM has them. Don't assume the wrapper VM inherits them.
- **Default `contentAlignment` of a `Box` is `TopStart`, but child Boxes
  with no explicit `align` can still be hidden** when another child fills
  the parent. Defensive: always add `align(Alignment.TopStart)` (or
  equivalent) + `zIndex(1f)` for overlay widgets.
