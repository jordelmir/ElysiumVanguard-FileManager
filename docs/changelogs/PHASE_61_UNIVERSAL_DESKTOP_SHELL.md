# Phase 61 — Universal Desktop Shell (ventanas Compose, dock)

> **Status:** shipped 2026-07-18 against git head `ecfadc3` (the Elysium Vanguard Linux distro listing).
> **Build evidence:**
> - `testDebugUnitTest` — **1792 tests, 0 failures, 0 errors, 2 skipped** (was 1773; +19 new in this commit)
> - `assembleDebug` — green, `app-debug.apk` 101 MB
> - **0 lint errors, 0 warnings**

---

## 0. What this phase is

The **Universal Desktop Shell** is the
platform's desktop environment for the
Market-installed apps. When the user
launches an app from the Market
(the Elysium Vanguard Linux distro or a
community distro), the app runs **inside
the desktop shell** — a Compose-based
window manager with a dock.

Phase 61 ships the **state machine +
ViewModel + data model + placeholder
composable**. The full Compose windowing
implementation (draggable window frames,
click-to-focus, etc.) is a Phase 2
follow-up that depends on the Compose
windowing primitives stabilizing in a
later version of Compose (the primitives
used by this project, 1.5.x, are
experimental).

The Phase 1 placeholder proves the
end-to-end integration works: the
ViewModel enforces the state machine, the
state is exposed as a `StateFlow`, the
composable consumes the state. The
placeholder renders the desktop background
+ a text list of windows + a dock with the
running items.

---

## 1. Architecture decisions

- **MVVM + StateFlow** (per the existing EV
  pattern: `MainScreenViewModel`,
  `WorkspacesViewModel`): the ViewModel is
  the **only legitimate way** to mutate
  the session state. The composable is
  pure (it consumes the state, dispatches
  user actions to the ViewModel).
- **Single source of truth**: the
  `DesktopSessionState` is the canonical
  state. The composable + the dock both
  read from the same state; there is no
  separate state for the dock.
- **State machine enforcement** (per
  `R-DI-1` in `docs/foundry/risk-register.md`):
  the window state machine is enforced
  by the ViewModel, not by the data
  class. The `DesktopWindow.state` field
  is a passive value; the ViewModel
  enforces the transitions.
- **Append-only windows** (per ADR-0006):
  windows are added, not replaced. A close
  removes the window from the list, but
  the next zOrder is monotonic (no two
  windows share a zOrder).
- **Focused window is on top** (per the
  platform's "the focus is the truth"
  rule): the focused window always has
  the highest zOrder. The ViewModel
  enforces this invariant on every state
  mutation.

---

## 2. Files added (5 main + 1 test = 6 new)

```
app/src/main/java/com/elysium/vanguard/features/desktop/
├── model/
│   ├── WindowState.kt            (3-value enum: NORMAL, MINIMIZED, MAXIMIZED)
│   ├── DesktopWindow.kt          (data class + WindowBounds)
│   ├── DockItem.kt               (data class + DockItemKind enum)
│   └── DesktopSessionState.kt    (the session's source of truth)
├── DesktopShellViewModel.kt      (the state machine)
└── DesktopShellScreen.kt         (the placeholder composable)

app/src/test/java/com/elysium/vanguard/features/desktop/
└── DesktopShellViewModelTest.kt  (19 tests)
```

---

## 3. The window state machine

```
       minimize                    restore
NORMAL ----------> MINIMIZED -----------> NORMAL
   |                                       ^
   | maximize                             | restore
   v                                       |
MAXIMIZED ------------------------------> NORMAL
   |                                       ^
   | close (always)                        | close (always)
   v                                       v
   (removed from windows list)
```

The state machine is enforced by
`DesktopShellViewModel.minimizeWindow`,
`maximizeWindow`, `restoreWindow`, and
`closeWindow`. The `DesktopWindow.state`
field is a passive value; the ViewModel
owns the transitions.

---

## 4. The 4 invariants

The `DesktopShellViewModel` enforces 4
invariants on every state mutation:

1. **The focused window is always on top.**
   `focusWindow` + `openWindow` +
   `maximizeWindow` set the focused
   window's zOrder to the next available
   value.
2. **The zOrder is monotonically increasing.**
   `nextZOrder` is a counter that
   increments on every focus event. No
   two windows share a zOrder.
3. **A `MAXIMIZED` window's bounds are the
   desktop bounds.** `maximizeWindow`
   sets the bounds to the desktop's
   bounds.
4. **Closing a window removes the
   corresponding dock item.** `closeWindow`
   filters the `dockItems` list to remove
   the `RUNNING_WINDOW` item for the
   closed window.

The invariants are verified by the
19 ViewModel tests (e.g. "zOrder is
monotonically increasing across focus
events" + "multiple windows have unique
zOrders" + "closeWindow removes the
window and the dock item").

---

## 5. The `DesktopSessionState` shape

```kotlin
data class DesktopSessionState(
    val windows: List<DesktopWindow>,
    val focusedWindowId: String?,
    val dockItems: List<DockItem>,
    val desktopBounds: WindowBounds,
    val nextZOrder: Int = INITIAL_Z_ORDER,
) {
    val focusedWindow: DesktopWindow? get() = ...
    val visibleWindows: List<DesktopWindow> get() = ...
}
```

The session state is **immutable**. The
ViewModel produces new `DesktopSessionState`
instances on every mutation; the
composable observes the `StateFlow` and
recomposes.

---

## 6. The `DesktopShellViewModel` API

```kotlin
fun openWindow(id, title, iconKey, defaultWidth, defaultHeight): Result<Unit>
fun closeWindow(id): Result<Unit>
fun focusWindow(id): Result<Unit>
fun minimizeWindow(id): Result<Unit>
fun maximizeWindow(id): Result<Unit>
fun restoreWindow(id): Result<Unit>
fun pinApp(iconKey, label): Result<Unit>
```

Every method returns `Result<Unit>` so the
consumer can pattern-match on the typed
error. The current error variant is
`FoundryError.VehicleDefinitionInvalid`
(blank id / blank iconKey / blank label).

---

## 7. The 19 tests cover

- Initial state is empty (no windows + no
  dock items).
- `openWindow` adds a window + focuses it.
- `openWindow` adds a `RUNNING_WINDOW`
  dock item.
- `openWindow` is a no-op when the window
  is already open.
- `openWindow` rejects blank id.
- `closeWindow` removes the window + the
  dock item.
- `closeWindow` focuses the next top-most
  window when the focused window is closed.
- `closeWindow` is a no-op when the
  window is not open.
- `focusWindow` brings the window to the
  top.
- `focusWindow` restores a minimized
  window to NORMAL.
- `minimizeWindow` sets the state to
  MINIMIZED.
- `minimizeWindow` removes the window
  from the focus if it was focused.
- `maximizeWindow` sets the state to
  MAXIMIZED + the bounds to the desktop
  bounds.
- `restoreWindow` sets the state to NORMAL.
- `pinApp` adds a `PINNED_APP` dock item.
- `pinApp` is a no-op when the app is
  already pinned.
- `pinApp` rejects blank iconKey or label.
- zOrder is monotonically increasing
  across focus events.
- Multiple windows have unique zOrders.

---

## 8. The placeholder composable

The Phase 1 `DesktopShellScreen` is a
**placeholder** that:
- Renders the desktop background as a
  dark color.
- Renders the list of windows as a text
  list (for debug + for the JVM preview).
- Renders the dock as a row of items at
  the bottom.

The placeholder proves the end-to-end
integration: the ViewModel + the state
machine + the StateFlow + the composable
all work together. The placeholder is
replaced with the real Compose windowing
implementation in Phase 2 (when the
Compose 1.6+ windowing primitives are
stable).

The composable includes a `DesktopShellScreenPreview`
function for the JVM preview tool. The
preview renders a sample session with 2
windows + 3 dock items.

---

## 9. What's NOT in Phase 61 (deferred to later phases)

- **Real Compose windows**: the
  draggable window frames + the click-to-
  focus + the resize handles. Phase 2 (when
  the Compose 1.6+ windowing primitives are
  stable).
- **Multi-monitor support**: the desktop
  shell is single-monitor in Phase 1.
  Multi-monitor is a Phase 2 follow-up.
- **Animations**: window open/close/focus
  animations. Phase 2.
- **Virtual desktops** (workspaces): the
  ability to have multiple desktops with
  different windows. Phase 2.
- **Right-click context menu**: a
  per-window context menu. Phase 2.
- **Window decorations**: the window
  title bar + the close/minimize/maximize
  buttons. Phase 2.

---

## 10. Build evidence

```
./gradlew testDebugUnitTest
  -> 1792 tests, 0 failures, 0 errors, 2 skipped
  -> Desktop tests: 19 (new in this commit)
  -> EV + Foundry + Market baseline: 1773

./gradlew assembleDebug
  -> BUILD SUCCESSFUL
  -> app-debug.apk: 101,465,169 bytes (101 MB)

Lint:
  -> 0 errors, 0 warnings
```

---

## 11. Next steps (continuing the pending list)

- **Phase 62** — Mesa Turnip Vulkan ICD:
  the graphics driver for Android-side GPU
  acceleration. Required for the Desktop
  Shell to render 3D apps.
- **Phase 63** — Security Zero Trust
  completion: the remaining hardening items
  (envelope encryption, secrets in vault,
  CVE monitoring, etc.).
- **Phase 64** — Instrumented test on real
  device: expand the `androidTest/` coverage
  to the Desktop Shell + the Market install
  flow.
- **Phase 65** — Multiple distros: the first
  batch of community distros in the catalog.

---

> "The desktop is the user's window into the
> Market-installed apps. The state machine is
> the spine; the ViewModel is the only path;
> the composable is the face. The placeholder
> proves the integration; the real windows
> come when the Compose primitives are ready.
> Until then, the foundation is solid: the
> state, the transitions, the invariants,
> the dock, the pinned apps."
