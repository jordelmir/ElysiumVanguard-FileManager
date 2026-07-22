# Phase 112 — Split-Screen Layout

**Status:** ✅ SHIPPED
**Date:** 2026-07-22
**Commit:** (this commit)
**Build:** APK 98 MB
**Tests:** 3746 (+13 from Phase 111)

## Vision Alignment

The vision's "Escritorio visualmente
impresionante" (gap #9) calls for a desktop
shell that feels like "una experiencia de
windows nativa en android". Phase 78 shipped
the floating-window freeform layout. Phase
112 adds the **split-screen layout** — the
ability to tile windows side-by-side (or
stacked) with a single click, the same way
modern desktop OSes (Windows Snap, macOS
Split View, GNOME Tiling) handle it.

The split math is a pure function with
explicit invariants. The Compose layer
applies the math at render time; the
windows' stored bounds are preserved when
the user switches back to freeform.

## Deliverables

### 1. `LayoutMode` enum (`features/desktop/layout/WindowLayoutMath.kt`)

Three modes:
- `FREEFORM` — windows float at user-defined bounds (default).
- `SPLIT_HORIZONTAL` — visible windows are arranged in a single row, side-by-side.
- `SPLIT_VERTICAL` — visible windows are arranged in a single column, stacked.

### 2. `WindowLayoutMath` (pure function)

A pure function that takes a list of windows
+ a desktop bounds + a layout mode + returns
a `Map<windowId, computedBounds>`. The math:

- **FREEFORM** → empty map (no overrides;
  the renderer uses the windows' stored
  bounds).
- **SPLIT_HORIZONTAL** → tiles the visible
  windows in a 4-column grid, wrapping to
  a second row when more than 4 windows.
- **SPLIT_VERTICAL** → tiles the visible
  windows in a 1-column grid, stacking them
  top-to-bottom.

The math reserves `DOCK_RESERVED_HEIGHT_PX
= 72` pixels at the bottom for the dock.
The math skips minimized windows (they
render in the dock, not on the desktop).

### 3. `layoutMode` on `DesktopSessionState`

A new field defaulting to `FREEFORM`. The
ViewModel's `setLayoutMode(mode)` method
updates the field.

### 4. `setLayoutMode` on `DesktopShellViewModel`

A new method that updates the state's
`layoutMode`. Switching modes is a
non-destructive operation — the windows'
stored bounds are preserved. The user can
switch back to `FREEFORM` and the windows
return to their previous positions.

### 5. `LayoutModeToggle` Compose component

A row of 3 chips (Freeform / Split /
Stack) in the top-right of the desktop.
The current mode is highlighted in the
primary color; the others sit at 40% alpha.
Clicking a chip switches the mode.

### 6. Updated `DesktopShellContent`

The Compose layer reads the layout math's
output and applies the computed bounds as
an override on top of the windows' stored
bounds. The override is a per-render
transform — it does NOT mutate the
state.

## Test Coverage

| Test class | New tests | Total |
|---|---|---|
| `WindowLayoutMathTest` | 10 | 10 |
| `DesktopShellViewModelTest` | +3 (layout mode) | 22 |

Total new: 13 test methods.
Total: 3746 tests, 1 pre-existing flake (unchanged
from Phase 111), 2 skipped.

## Architecture Decisions

### Why a pure function for the math (not a Compose modifier)?

The math is testable in isolation (no Android
dependencies). The Compose layer reads the
math's output and applies it to the window
render. Pure functions are easier to reason
about, test, and refactor.

### Why a render override (not state mutation)?

Switching layout modes is a non-destructive
operation. The user can switch back to
`FREEFORM` and the windows return to their
previous positions. If the math mutated the
state, switching back to `FREEFORM` would
have nothing to restore (the bounds would
have been overwritten with the split
positions).

### Why reserved dock height (not full desktop)?

The dock is always visible. A split mode
that uses the full desktop height would
have the bottom row of windows hidden
behind the dock. The math subtracts the
dock's height (72 pixels) from the
available vertical space.

### Why a row of 3 chips (not a single cycling button)?

The user can see all 3 modes at a glance +
tap the one they want directly. A "cycle"
button would require 2-3 taps to reach a
specific mode. The chips also make the
current mode obvious (the highlighted
chip).

### Why max 4 per row (not 2 or 6)?

4 is the sweet spot for the typical
desktop (1920×1080): 4 windows of 480×504
pixels each. With 2 per row the windows
are too wide; with 6+ per row the windows
are too narrow for any real content.

## Files

### New (production)

- `app/src/main/java/com/elysium/vanguard/features/desktop/layout/WindowLayoutMath.kt`
- `app/src/main/java/com/elysium/vanguard/features/desktop/dock/LayoutModeToggle.kt`

### New (tests)

- `app/src/test/java/com/elysium/vanguard/features/desktop/layout/WindowLayoutMathTest.kt`

### Modified (production)

- `app/src/main/java/com/elysium/vanguard/features/desktop/model/DesktopSessionState.kt` (added `layoutMode` field)
- `app/src/main/java/com/elysium/vanguard/features/desktop/DesktopShellViewModel.kt` (added `setLayoutMode`)
- `app/src/main/java/com/elysium/vanguard/features/desktop/DesktopShellScreen.kt` (wired the math + the toggle)
- `app/src/test/java/com/elysium/vanguard/features/desktop/DesktopShellViewModelTest.kt` (3 new layout-mode tests)

## Next

- **Phase 113** — Multi-sesiones en el shell:
  shell shows 1 desktop; needs multiple
  instances (the user can switch between
  "Windows 11" desktop + "macOS Sonoma"
  desktop + "GNOME 45" desktop, each with
  its own set of open windows).
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
