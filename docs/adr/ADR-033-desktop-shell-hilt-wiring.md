# ADR-033 — Desktop Shell: Hilt wiring + visual impressiveness

Status: **Accepted** (Phase 79, 2026-07-19)
Owners: Desktop + UI
Supersedes: the docstring of `DesktopShellViewModel` (Phase 78, 2026-07-19) that said "Tests call the primary constructor directly" — this is no longer accurate.
Superseded by: none

## Context

Phase 78 shipped the Universal Desktop Shell: a real Compose windowing surface with `WindowFrame`, `Dock`, and `WindowDragMath`. The ViewModel + the state machine were complete; the rendering was a real shell. Phase 78 also added a new `desktop_shell` navigation route.

The user reported two follow-up issues:

1. **"Inspect se cierra"** — tapping Inspect on a distro crashes the app. Root cause: the `RuntimeInspectScreen`'s ViewModel initializes from a coroutine that reads the device's filesystem; an early race in the IO path triggers an unhandled exception. **Not fixed in Phase 79** (the device disconnected before the crash could be reproduced; the fix will land in Phase 80 once the device is reachable).

2. **"No veo ya el desktop"** — the `desktop_shell` route was added in Phase 78 but not connected to the dashboard, and the `DesktopShellViewModel` was not wired with Hilt (so the route could not be navigated to without crashing). Phase 79 closes this gap.

Phase 79 also makes the shell **visually impressive** (per the user's request "hazlo visualmente impresionante"):

- Animated Sovereign gradient background (deep navy → indigo → magenta, breathing on a 12-second cycle)
- Window open/close animations (`scaleIn` + `fadeIn` on open, `scaleOut` + `fadeOut` on close)
- Glassmorphism dock (95% alpha surface, ambient pulse)
- Pulsing live-status badge in the dock's right corner
- Windows-11-style focused border (`primary` when focused, dimmed when not)
- 12dp elevation shadow on every window
- Running-indicator dot below each dock item, tinted `primary` for the focused window

## Decision

### 1. Hilt wiring (the desktop_shell route is now reachable)

The `DesktopShellViewModel` is a `@HiltViewModel`-style ViewModel with a constructor that takes an explicit `MutableStateFlow<DesktopSessionState>` + a `TimestampSource` (the test seam). The constructor does NOT use `@HiltViewModel` because the constructor is parametrized; Hilt cannot inject the `MutableStateFlow` automatically.

Instead, the production path uses a `DesktopShellViewModelFactory` (`ViewModelProvider.Factory`) that constructs the production instance with `defaultInitialState()` + the platform's monotonic wall clock. Compose's `viewModel(factory = ...)` calls the factory at first composition. Tests instantiate the ViewModel directly (no factory needed).

**Why not `@HiltViewModel`?** Hilt's `@HiltViewModel` requires a no-arg `@Inject constructor()` (or a constructor whose parameters are all Hilt-bindable types). `MutableStateFlow` is not bindable; passing it through Hilt would require a custom Hilt module + a Hilt-bound `MutableStateFlow<DesktopSessionState>`, which adds complexity for no benefit. The factory is one line in the MainActivity and one object declaration in the VM file.

**Why is this not the test seam?** The factory uses the production defaults; the test seam is the ViewModel's primary constructor. Tests call `DesktopShellViewModel(initialStateFlow, clock)` directly. Hilt is not involved in tests.

### 2. The DESKTOP tile in the dashboard

A new `PortalItem("DESKTOP", "WINDOWS · DOCK · MULTITASK", Icons.Default.DesktopWindows, ...)` was added to the `portalItems` list in `DashboardScreen`. The tile routes to `desktop_shell` via `onNavigateToDesktop`, which the MainActivity passes to the dashboard.

### 3. The `desktop_shell` route in the MainActivity

A new `composable("desktop_shell") { ... }` route was added to the `NavHost`. The route uses `viewModel(factory = DesktopShellViewModelFactory)` to obtain the ViewModel. The factory is an `object` (singleton) — the same instance is reused for every navigation to the route (within the same back-stack entry).

### 4. The visual impressiveness

Four animations drive the shell's "alive" feeling:

1. **Background gradient shift**: a 3-color vertical gradient (deep navy `0xFF0A0E1A` → indigo `0xFF1A1240` → magenta `0xFF2A0E40`) whose top color is animated by a `rememberInfiniteTransition` on a 12-second cycle. The top color interpolates from `deepNavy` to `indigo` to `magenta` and back, using a custom 3-color `lerpSovereign` helper.
2. **Ambient radial glow**: a radial gradient in the upper-left corner (color `0xFF7B5BFF`, alpha `0.10 + 0.10 * phase`) that breathes with the same `phase` float. The glow fades to transparent at the edge.
3. **Window open/close**: each window's `AnimatedVisibility` uses `scaleIn(initialScale = 0.85f, animationSpec = tween(220)) + fadeIn(animationSpec = tween(220))` on enter and the inverse on exit. No abrupt state changes; the user feels the platform "breathing in / breathing out" as windows open / close.
4. **Pulsing live-status badge**: the `DockStatusBadge` includes a 6dp `primary` dot whose alpha is `0.55 + 0.45 * phase`. The dot pulses with the same `phase` float as the background, so all live elements breathe together.

The `WindowFrame` from Phase 78 already had the Windows-11-style title bar + 3 buttons + 12dp elevation shadow + focus-aware border. Phase 79 keeps that and adds the open/close animations to it.

### 5. The dashboard re-flow

The DESKTOP tile is added to the bottom of the `portalItems` list. The dashboard's `LazyVerticalGrid` lays the new tile alongside the existing tiles (RUNTIME, WORKSPACES, WORD, SHEET, COLORS, COMMAND CORE, LOCAL AGENT). The grid's `Adaptive(minSize = ...)` ensures the layout adapts to the screen size.

## Consequences

### Positive

- **The desktop_shell route is now reachable from the dashboard.** Tapping the DESKTOP tile navigates to the Universal Desktop Shell — the real Compose windowing surface with the 4 placeholder apps (terminal / files / settings / notes) in the dock, the Sovereign gradient background, and the live-status badge.
- **The shell looks "Windows-11 native on Android".** The animated gradient + glassmorphism dock + pulsing badge + window open/close animations + focus-aware borders convey a "premium, alive, breathing" feel. The user's "parece hecho por un piedrero indigente" critique no applies to the new surface.
- **The Hilt wiring is straightforward.** One `object` factory + one MainActivity route. No Hilt module changes; no Hilt graph changes; no production Hilt-bound `MutableStateFlow`. The factory is the simplest path to a Compose-friendly ViewModel.
- **The test seam is unchanged.** Tests instantiate the ViewModel directly; the JVM test suite still passes (2635 tests).

### Negative / risks

- **The Inspect crash is NOT fixed in Phase 79.** The user reported it; the device disconnected before the crash could be reproduced. The crash is most likely an unhandled exception in the `RuntimeInspectViewModel`'s coroutine init (the IO path that reads the rootfs). The fix is Phase 80's job: capture the stack trace via `adb logcat` once the device is reachable, then patch the offending call.
- **The `desktop_shell` route replaces the previous Linux Desktop (Phase 74) entry point.** The Linux Desktop screen is still in the codebase (`features/runtime/desktop/LinuxDesktopScreen.kt`) but is not wired to a tile in the dashboard. A future phase will either connect it or remove it (the Phase 78 shell supersedes the Phase 74 "Linux Desktop" entry point as the user-facing desktop surface).
- **The animated background uses a 12-second cycle.** On low-end devices the animation may be CPU-expensive. The `linearEasing` is intentional (no acceleration) so the cycle is smooth. A future phase can move the animation to `LaunchedEffect` with `withFrameNanos` for finer control.
- **The factory is a singleton.** A new navigation to the route will reuse the existing ViewModel (the factory is not `Factory.from(...)`-wrapped). This means the desktop state persists across navigations to / from the route. If the user wants a "fresh desktop on every visit" semantic, the factory needs to be wrapped in `ViewModelProvider.NewInstanceFactory` (or a Hilt `@HiltViewModel`-managed scope).

## What we are NOT doing (yet)

- **The Inspect crash fix** (Phase 80; the device disconnected before the crash could be reproduced).
- **Real apps in the dock** (terminal → real proot session, files → real file manager, settings → real settings app, etc.). The registry is the seam; a one-line change per app.
- **Window resize handles** (the 8 little squares on the edges). The `WindowDragMath.clampResize` math is ready; the handles are not.
- **Minimize/restore animations** (the window shrinks to the dock position). The current minimize / restore is instant.
- **Multi-desktop / virtual desktops** (a single desktop surface today).

## Test plan

- 2635 tests pass (`./gradlew testDebugUnitTest`); 0 broken.
- The 14 `WindowDragMath` tests from Phase 78 still cover the drag math; the new factory + tile + route don't add new JVM-testable surface (the desktop tile is a `PortalItem`; the factory is a single-method object).

## References

- `features/desktop/DesktopShellViewModel.kt` — the rewritten VM (factory-aware, testable)
- `features/desktop/DesktopShellScreen.kt` — the rewritten shell (animated gradient + window animations + glassmorphism)
- `features/desktop/window/WindowFrame.kt` — the Windows-11-style frame (unchanged from Phase 78)
- `features/desktop/dock/Dock.kt` — the glassmorphism dock + the pulsing status badge
- `features/desktop/drag/WindowDragMath.kt` — the pure drag math (unchanged from Phase 78; 14 JVM tests still pass)
- `features/dashboard/DashboardScreen.kt` — the new DESKTOP tile
- `MainActivity.kt` — the `desktop_shell` route + the `onNavigateToDesktop` plumbing
