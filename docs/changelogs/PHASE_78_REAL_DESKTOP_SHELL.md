# Phase 78 — Real Desktop Shell (real Compose windowing, not a text list)

The Universal Desktop Shell was rendering each window as
a bullet in a `Text` composable. The dock was a single
line of joined labels. The state machine was complete;
the rendering was a placeholder. Phase 78 replaces the
placeholder with a real Compose windowing surface.

## What shipped

### 1. The window frame

`app/src/main/java/com/elysium/vanguard/features/desktop/window/WindowFrame.kt`

Windows-11-style frame: title bar with icon + title +
3 buttons (min / max / close), body slot, 1dp border
(bright when focused, dim when not), 8dp elevation
shadow, 8dp rounded corners. Stateless; consumes the
focused / maximized / title / iconKey from the caller.

### 2. The dock

`app/src/main/java/com/elysium/vanguard/features/desktop/dock/Dock.kt`

`LazyRow` of items with icon + running indicator.
`PINNED_APP` items show only the icon; `RUNNING_WINDOW`
items show the icon + a 3dp dot. The dot is filled with
`primary` when the item is the focused window, dimmed
otherwise. Click dispatches by `DockItemKind`:
`RUNNING_WINDOW` focuses/restores; `PINNED_APP` opens
a new window.

### 3. The content registry

`app/src/main/java/com/elysium/vanguard/features/desktop/content/WindowContentRegistry.kt`

A `Map<String, WindowContent>` keyed by `iconKey`. Each
entry carries the icon `ImageVector` + the body
`@Composable () -> Unit`. The dock + the title bar + the
window body all read from the same `iconKey` — one
source of truth. Four placeholder bodies ship
(terminal / files / settings / notes); real apps plug
in by adding a single registry entry.

### 4. The drag math

`app/src/main/java/com/elysium/vanguard/features/desktop/drag/WindowDragMath.kt`

Pure JVM math: `applyDrag(bounds, deltaX, deltaY,
desktopBounds, titleBarHeight, dockReservedHeight)` clamps
the window to keep the title bar grabbable (left/right
edges) and to stay above the dock. `clampResize(proposed,
...)` enforces min/max width and height. The math is
split from the Compose `pointerInput` modifier so 14
JVM tests cover every clamp rule.

### 5. The desktop shell rewrite

`app/src/main/java/com/elysium/vanguard/features/desktop/DesktopShellScreen.kt`

The new shell:
- Renders a Sovereign-gradient background (deep navy
  → indigo → magenta).
- Sorts visible windows by z-order and renders each
  as a positioned `WindowFrame`.
- Wraps each window in a `Box` with a `pointerInput`
  modifier that captures drag gestures and calls
  `WindowDragMath.applyDrag` + the new
  `ViewModel.updateWindowBounds`.
- Wires the title bar click + the 3 buttons to the
  existing ViewModel methods (focus, minimize,
  maximize, restore, close).
- Renders the dock at the bottom with the system
  status badge ("elysium • v1.0") in the right corner.

### 6. The ViewModel addition

`DesktopShellViewModel.updateWindowBounds(id, newBounds)`

A no-op for `MAXIMIZED` + `MINIMIZED` windows; for
`NORMAL` windows, it persists the new bounds + updates
`lastInteractionAt`. The other ViewModel methods
(`openWindow`, `closeWindow`, `focusWindow`,
`minimizeWindow`, `maximizeWindow`, `restoreWindow`,
`pinApp`) are unchanged.

### 7. Test coverage

`app/src/test/java/com/elysium/vanguard/features/desktop/drag/WindowDragMathTest.kt`

14 new tests, all green:

- 2 basic translation tests (right, down)
- 1 top-edge clamp test
- 4 clamp tests (right edge, left edge, above-dock,
  zero-delta)
- 1 float-to-int truncation test
- 5 resize tests (min width, min height, max width,
  max height, passes-through)
- 1 tiny-desktop edge case

Test count: **2621 → 2635** (14 new + 0 broken).

## What we are NOT doing (yet)

- **Animations** (minimize/restore, focus transitions).
  Windows appear / disappear instantly today.
- **Resize handles** (the 8 little squares on the
  edges). The `clampResize` math is ready; the handles
  are not.
- **Real app bodies**. The registry's bodies are
  placeholders. A future phase wires the real terminal
  (proot), the real file manager, the real settings
  app, etc.
- **Sub-pixel drag smoothness**. The math truncates
  the float delta to int; a future phase can use
  `Float` bounds throughout.
- **Wallpaper**. The Sovereign gradient is the
  "wallpaper" today; a real image is a future phase.

## Files changed

- **NEW** `app/src/main/java/com/elysium/vanguard/features/desktop/window/WindowFrame.kt`
- **NEW** `app/src/main/java/com/elysium/vanguard/features/desktop/dock/Dock.kt`
- **NEW** `app/src/main/java/com/elysium/vanguard/features/desktop/drag/WindowDragMath.kt`
- **NEW** `app/src/main/java/com/elysium/vanguard/features/desktop/content/WindowContentRegistry.kt`
- **MODIFIED** `app/src/main/java/com/elysium/vanguard/features/desktop/DesktopShellScreen.kt`
  (text list → real Compose windowing)
- **MODIFIED** `app/src/main/java/com/elysium/vanguard/features/desktop/DesktopShellViewModel.kt`
  (new `updateWindowBounds`; rest unchanged)
- **NEW** `app/src/test/java/com/elysium/vanguard/features/desktop/drag/WindowDragMathTest.kt`
- **NEW** `docs/adr/ADR-032-real-desktop-shell.md`
- **NEW** `docs/changelogs/PHASE_78_REAL_DESKTOP_SHELL.md` (this file)

## Build status

- `compileDebugKotlin`: ✓
- `compileDebugUnitTestKotlin`: ✓
- `testDebugUnitTest`: 2635/2635 (14 new + 2621 existing)
- `assembleDebug`: ✓ (debug APK built)

## What's next

The remaining gap-closure items, in priority order:

1. Hilt-wiring of `HttpRemoteBuildClientImpl` + migration
   of the gateway's HTTP client (one-file).
2. Real Elysium Vanguard Linux distro (placeholder
   hash → built rootfs).
3. Real market downloader (HTTP byteSource for
   `MarketInstaller`).
4. `SecurityAudit` persistence (NDJSON).
5. Kill switch UI for signature mismatch.

The desktop surface is now a real shell; the next
visible improvement is the real apps plugged into
the content registry.
