# Phase 79 — Desktop Shell: Hilt wiring + visually impressive

Phase 78 shipped the Universal Desktop Shell (real Compose windowing, WindowFrame, Dock, drag math). The `desktop_shell` route was added to the MainActivity but the `DesktopShellViewModel` was not wired with Hilt — tapping the route would have crashed. The DESKTOP tile was also missing from the dashboard. Phase 79 closes both gaps and makes the shell **visually impressive**.

## What shipped

### 1. Hilt wiring

The `DesktopShellViewModel` constructor is parametrized (it takes a `MutableStateFlow<DesktopSessionState>` + a `TimestampSource` for the test seam). Hilt cannot bind `MutableStateFlow` automatically, so the production path uses a `DesktopShellViewModelFactory` (`ViewModelProvider.Factory`):

```kotlin
object DesktopShellViewModelFactory : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return DesktopShellViewModel(
            initialStateFlow = MutableStateFlow(defaultInitialState()),
            clock = Timestamp.monotonicWallClock(),
        ) as T
    }
}
```

The MainActivity's `composable("desktop_shell")` route calls `viewModel(factory = DesktopShellViewModelFactory)`. The factory is a singleton; the desktop state persists across navigations to / from the route.

Tests instantiate the ViewModel directly via the primary constructor — no factory, no Hilt. The existing 2635-test suite still passes (0 broken).

### 2. The DESKTOP tile

A new `PortalItem("DESKTOP", "WINDOWS · DOCK · MULTITASK", Icons.Default.DesktopWindows, TitanColors.NeonCyan, onClick = onNavigateToDesktop, ...)` was added to the dashboard's `portalItems` list. The MainActivity passes `onNavigateToDesktop = { navController.navigate("desktop_shell") }`.

The DESKTOP tile now appears in the dashboard alongside RUNTIME, WORKSPACES, WORD, SHEET, COLORS, COMMAND CORE, and LOCAL AGENT. Tapping it opens the Universal Desktop Shell — the real Compose windowing surface from Phase 78.

### 3. The visually impressive shell

Four animations drive the shell's "alive" feeling:

- **Animated Sovereign gradient background**: a 3-color vertical gradient (deep navy → indigo → magenta) whose top color is animated by a `rememberInfiniteTransition` on a 12-second cycle. The top color interpolates through the Sovereign palette using a custom `lerpSovereign` helper.
- **Ambient radial glow**: a radial gradient in the upper-left corner (color `0xFF7B5BFF`, alpha `0.10 + 0.10 * phase`) that breathes with the same `phase` float.
- **Window open/close animations**: each window's `AnimatedVisibility` uses `scaleIn(initialScale = 0.85f, animationSpec = tween(220)) + fadeIn(animationSpec = tween(220))` on enter and the inverse on exit. No abrupt state changes.
- **Pulsing live-status badge**: the `DockStatusBadge` includes a 6dp `primary` dot whose alpha is `0.55 + 0.45 * phase`. The dot pulses with the same `phase` float as the background, so all live elements breathe together.

The `WindowFrame` from Phase 78 already had the Windows-11-style title bar + 3 buttons (min / max / close) + 12dp elevation shadow + focus-aware border. Phase 79 keeps that and adds the open/close animations.

The `Dock` got glassmorphism (95% alpha surface) and a pulsing live-status badge in the right corner. The four pinned apps (terminal / files / settings / notes) resolve to placeholder bodies via the `WindowContentRegistry`; real apps plug in by adding a single registry entry.

### 4. The dashboard tile

The DESKTOP tile joins the existing tiles. The dashboard's `LazyVerticalGrid` lays the new tile alongside the others; the grid's `Adaptive(minSize = ...)` ensures the layout adapts to the screen size.

## The Inspect crash — NOT fixed in Phase 79

The user reported that tapping "Inspect" on a distro closes the app. The root cause is most likely an unhandled exception in the `RuntimeInspectViewModel`'s coroutine init (the IO path that reads the rootfs). The device disconnected before the crash could be reproduced via `adb logcat`; the stack trace is required to patch the offending call.

**Phase 80's job**: reconnect the device, navigate to the Inspect screen, capture the stack trace, and patch the offending call. The fix is most likely a defensive null check + a try/catch in the coroutine init that converts the exception to a typed `RuntimeInspectError` + a `Result.failure` instead of crashing.

## What we are NOT doing (yet)

- **The Inspect crash fix** (Phase 80; the device disconnected before the crash could be reproduced).
- **Real apps in the dock** (terminal → real proot session, files → real file manager, settings → real settings app, etc.). The registry is the seam; a one-line change per app.
- **Window resize handles** (the 8 little squares on the edges). The `WindowDragMath.clampResize` math is ready; the handles are not.
- **Minimize/restore animations** (the window shrinks to the dock position). The current minimize / restore is instant.

## Files changed

- **MODIFIED** `app/src/main/java/com/elysium/vanguard/features/desktop/DesktopShellViewModel.kt` (rewritten for Hilt + factory; primary constructor still public for tests)
- **MODIFIED** `app/src/main/java/com/elysium/vanguard/features/desktop/DesktopShellScreen.kt` (animated gradient + window animations + glassmorphism)
- **MODIFIED** `app/src/main/java/com/elysium/vanguard/features/desktop/dock/Dock.kt` (glassmorphism + pulsing status badge)
- **MODIFIED** `app/src/main/java/com/elysium/vanguard/features/dashboard/DashboardScreen.kt` (DESKTOP tile added)
- **MODIFIED** `app/src/main/java/com/elysium/vanguard/MainActivity.kt` (`desktop_shell` route + `onNavigateToDesktop` plumbing)
- **MODIFIED** `app/src/test/java/com/elysium/vanguard/features/desktop/DesktopShellViewModelTest.kt` (primary constructor signature updated for the new factory-aware VM)
- **NEW** `docs/adr/ADR-033-desktop-shell-hilt-wiring.md`
- **NEW** `docs/changelogs/PHASE_79_DESKTOP_SHELL_HILT_AND_VISUAL.md` (this file)

## Build status

- `compileDebugKotlin`: ✓
- `compileDebugUnitTestKotlin`: ✓
- `testDebugUnitTest`: 2635/2635 (0 broken)
- `assembleDebug`: ✓ (debug APK built + installed)
