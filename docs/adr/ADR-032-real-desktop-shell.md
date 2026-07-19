# ADR-032 — Real Desktop Shell (real Compose windowing, not a text list)

Status: **Accepted** (Phase 78, 2026-07-19)
Owners: Desktop + UI
Supersedes: the docstring of `DesktopShellScreen` (Phase 1, 2026-06-10) that said "Phase 2 replaces the placeholder text with a real Compose windowing implementation (with draggable window frames, click-to-focus, etc.). The state machine + the ViewModel are stable; only the composable changes."
Superseded by: none

## Context

The Universal Desktop Shell is the master vision's flagship surface — the "Windows-like" workspace where every app (Market app, terminal, file manager, agent, settings) is a window on a shared desktop. The Phase 1 implementation was a **text list**: each `DesktopWindow` was rendered as a bullet in a `Text` composable, and the dock was a single line of joined labels:

```kotlin
state.windows.forEach { window ->
    Text(
        text = "- ${window.title} (${window.state.name}, z=${window.zOrder})",
        style = MaterialTheme.typography.bodySmall,
    )
}
```

This was never a real shell. The ViewModel + the state machine were complete (open / close / focus / minimize / maximize / restore / pin), but the UI did not render windows — it rendered a list. The user's "parece hecho por un piedrero indigente" critique of the Linux Desktop (Phase 74) was, in part, the same critique of the Desktop Shell: the surface was a placeholder.

Phase 78 closes this gap with a real Compose windowing implementation. The ViewModel + the state machine are unchanged (the seam was real; only the rendering was a placeholder). Phase 78 is a **composable-only** change.

## Decision

### 1. The window frame (`WindowFrame`)

A new composable at `app/src/main/java/com/elysium/vanguard/features/desktop/window/WindowFrame.kt`. The frame is presentational: it takes the window's title + iconKey + isFocused + isMaximized and renders a Windows-11-style frame:

- **Title bar** at the top: app icon (left), title (center-left, ellipsized), 3 buttons (right: minimize, maximize/restore, close). The title bar's background is `primaryContainer` when focused, `surfaceVariant` when not. The buttons are 36×32dp; the close button is tinted with the error color (Windows-11 behavior).
- **Body** below the title bar: renders the window's content (resolved from the `WindowContentRegistry`).
- **Border + shadow**: 1dp border (`primary` when focused, `outline` dimmed when not) + 8dp elevation shadow. Rounded corners (8dp).

The frame is stateless. It does not own the focus / state / z-order; it consumes them. The state lives in the `DesktopShellViewModel`.

### 2. The dock (`Dock`)

A new composable at `app/src/main/java/com/elysium/vanguard/features/desktop/dock/Dock.kt`. The dock is a `LazyRow` of items:

- **Each item**: 40×40dp icon box (rounded) + a 3dp running-indicator dot below the icon (visible for `RUNNING_WINDOW` items, absent for `PINNED_APP` items — a 5dp spacer keeps the layout consistent).
- **Pinned items**: only the icon. Clicking launches a new window.
- **Running items**: icon + running indicator. Clicking focuses the window (or restores it from `MINIMIZED`).
- **Focused item**: the icon is tinted with `primary`; the running indicator is filled with `primary` (vs. dimmed for unfocused running items).

The dock ships a `DockStatusBadge` helper for a small text label (used for the platform version "elysium • v1.0" in the right corner; reserved for clock + system status in Phase 79+).

### 3. The window content registry (`WindowContentRegistry`)

A new file at `app/src/main/java/com/elysium/vanguard/features/desktop/content/WindowContentRegistry.kt`. The registry maps a `iconKey` (the `DesktopWindow.iconKey` field) to a `WindowContent` value that carries:

- The icon `ImageVector` (used by the dock + the title bar — one source of truth).
- The body composable (a `@Composable () -> Unit` that renders the window's content).

The registry ships four placeholder bodies:

- `terminal` — a dark terminal-style body with the platform's welcome message.
- `files` — a file manager-style body with a list of paths.
- `settings` — a settings panel body.
- `notes` — a notes app body with a Phase 78 message.

Unknown `iconKey`s fall back to a "Unknown app" placeholder. The registry exists so the real apps (when wired) can be plugged in by adding a single `mapOf("terminal" to WindowContent(...))` entry — the shell itself does not change.

### 4. The drag math (`WindowDragMath`)

A new file at `app/src/main/java/com/elysium/vanguard/features/desktop/drag/WindowDragMath.kt`. The math is pure (no Android dependency) so every clamp rule is JVM-testable.

Two functions:

- `applyDrag(bounds, deltaX, deltaY, desktopBounds, titleBarHeight, dockReservedHeight): WindowBounds` — translates a window by a pixel delta. Clamps:
  - **x** is clamped to `[-width + MIN_VISIBLE_WIDTH, desktop.width - MIN_VISIBLE_WIDTH]` so the title bar is always grabbable from the left or right edge.
  - **y** is clamped to `[0, desktop.height - dockReservedHeight - titleBarHeight]` so the window can be dragged to the top but never under the dock.
  - `MIN_VISIBLE_WIDTH = 80` pixels (the minimum strip of the window that must remain on-screen).

- `clampResize(proposed, desktopBounds, titleBarHeight, dockReservedHeight): WindowBounds` — clamps a proposed resize:
  - `width >= MIN_VISIBLE_WIDTH` and `width <= desktopBounds.width`
  - `height >= titleBarHeight * 2` (a resized window must have a visible body) and `height <= desktopBounds.height - dockReservedHeight`
  - `x` and `y` are clamped to the desktop bounds.

The 14 JVM tests in `WindowDragMathTest` cover: basic translation, the four clamp rules (top, right, left, bottom-dock), the float-to-int conversion, the resize minimums / maximums, the "reasonable size passes through" case, and a tiny-desktop edge case.

### 5. The desktop shell (`DesktopShellScreen`)

The `DesktopShellScreen` is rewritten. The new implementation:

- Renders a **vertical gradient** background (the Sovereign palette: deep navy → indigo → magenta). This is the platform's signature look.
- Sorts the visible windows by `zOrder` (lowest first, highest on top) and renders each as a `PositionedWindow` (a `WindowFrame` positioned at its bounds).
- Each `PositionedWindow` is wrapped in a `Box` with a `pointerInput` modifier that captures drag gestures on the title bar. The drag calls `WindowDragMath.applyDrag` and the new `DesktopShellViewModel.updateWindowBounds`.
- The frame's title bar click focuses the window (calls `viewModel.focusWindow`).
- The frame's minimize / maximize / restore / close buttons call the corresponding ViewModel methods.
- The dock is rendered at the bottom (72dp). Each dock item's click handler dispatches based on `DockItemKind`: a `RUNNING_WINDOW` click focuses or restores; a `PINNED_APP` click opens a new window.

The desktop size is captured via `onSizeChanged` (in pixels). The drag math uses the pixel size; the window position is converted to `Dp` for rendering.

### 6. The ViewModel addition (`updateWindowBounds`)

The only ViewModel change: a new `updateWindowBounds(id, newBounds)` method. The method is a no-op for `MAXIMIZED` (the desktop bounds are the only valid position) and `MINIMIZED` (the dock is the position) windows; for `NORMAL` windows, it persists the new bounds + updates `lastInteractionAt`.

The other ViewModel methods (`openWindow`, `closeWindow`, `focusWindow`, `minimizeWindow`, `maximizeWindow`, `restoreWindow`, `pinApp`) are unchanged. The state machine is the same.

## Consequences

### Positive

- **The desktop shell is a real shell.** Windows render at their bounds; the title bar is draggable; the buttons work; the dock shows the running + pinned apps with a running indicator. The user opens the dashboard's "DESKTOP" tile and sees a real Windows-11-like workspace.
- **JVM-testable drag math.** 14 tests cover every clamp rule. The Compose `pointerInput` modifier is the only thing that touches Android; the math itself is in a 90-line pure object.
- **The state machine is unchanged.** The ViewModel + the data classes (`DesktopSessionState`, `DesktopWindow`, `WindowBounds`, `WindowState`, `DockItem`, `DockItemKind`) are the same. Phase 78 is a rendering-only change.
- **The content registry is a single source of truth for icons + bodies.** The dock + the title bar + the window body all read from the same `iconKey`. A new app is one entry in the registry's `byIconKey` map.
- **The desktop is styled.** The Sovereign gradient + the Windows-11 title bar + the rounded corners + the running indicator are the platform's signature look. The "parece hecho por un piedrero indigente" critique no applies to this surface.

### Negative / risks

- **No animations yet.** A real Windows-11-style shell has minimize/restore animations (the window scales down to the dock icon, then back). Phase 78 ships no animations; windows appear / disappear instantly. A future phase adds `AnimatedVisibility` + `scaleIn`/`scaleOut` transitions.
- **No resize handles.** The `WindowFrame` does not render resize handles (the 8 little squares on the edges of a Windows window). The `WindowDragMath.clampResize` function is ready for a future phase that adds the handles.
- **No real apps yet.** The `WindowContentRegistry` ships placeholder bodies. The terminal is a welcome message (not a real proot session); the file manager is a path list (not a real filesystem); the settings app is a static panel. A future phase wires the real apps into the registry.
- **The drag delta is integer-rounded.** `WindowDragMath.applyDrag` calls `deltaX.toInt()`, which truncates toward zero. Sub-pixel drag smoothness is lost. A future phase can use `Float` bounds and round only at render time.
- **The modifier's drag is greedy.** The `pointerInput` modifier captures any drag in the window's bounds, not just the title bar. A future phase can scope the modifier to the title bar's bounds only (using `Modifier.then` on the title bar child instead of the wrapping `Box`).
- **The desktop size is captured in pixels, not `Dp`.** The conversion to `Dp` happens at render time. A future phase can use `Dp` bounds throughout for consistency with Compose's coordinate system.

## What we are NOT doing (yet)

- **Animations** (minimize/restore, focus transitions, window open/close).
- **Resize handles** (the `clampResize` math is ready; the handles are not).
- **Real app bodies** (terminal → proot, file manager → real FS, etc.). The registry is the seam.
- **The desktop wallpaper** (the gradient is the "wallpaper" today; a future phase adds a real image + a `Wallpaper` registry).
- **Multi-monitor / multi-desktop** (a single desktop surface today; the data model supports many windows but the surface is one).

## Test plan (14 tests, all green in `WindowDragMathTest`)

- 2 basic translation tests (right, down)
- 1 clamp at top test
- 4 clamp tests (right, left, down-above-dock, zero-delta)
- 1 float-to-int truncation test
- 5 resize tests (min width, min height, max width, max height, passes-through)
- 1 tiny-desktop edge case

## References

- `features/desktop/DesktopShellScreen.kt` — the rewritten shell
- `features/desktop/DesktopShellViewModel.kt` — unchanged + the new `updateWindowBounds`
- `features/desktop/window/WindowFrame.kt` — the window frame
- `features/desktop/dock/Dock.kt` — the dock
- `features/desktop/drag/WindowDragMath.kt` — the pure drag math
- `features/desktop/content/WindowContentRegistry.kt` — the icon + body registry
- `features/desktop/model/*` — the data classes (unchanged)
- `test/desktop/drag/WindowDragMathTest.kt` — the 14-test truth table
