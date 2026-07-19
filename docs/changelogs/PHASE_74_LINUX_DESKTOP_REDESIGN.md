# Phase 74 — Linux Desktop screen, redesigned (Windows-grade experience on Android)

The user reported the Linux Desktop screen looked "hecho por un
piedrero indigente" — it didn't feel like a native Windows
experience on Android. The previous implementation was a black
background, a thin-bordered card with monospace text, a plain
list of apps, and a "live" overlay that was just a status string
+ a button. Phase 74 ships a Windows-grade redesign while keeping
the same ViewModel + state machine (no behavioral change to the
guest runtime, no new permissions, no new dependencies).

## What changed

The screen was rewritten end-to-end. Every primitive that was
a string is now a typed, gradient-styled, animation-aware
composable.

### 1. Branded top bar

The TopAppBar became a custom `LinuxDesktopTopBar` with:

- A circular back button.
- A brand mark (gradient + neon border + `DesktopWindows` icon).
- A two-line title block (LINUX DESKTOP + guest summary).
- A **status pill** (`LIVE` / `READY` / `TERMINAL` / `OFFLINE`)
  with a pulsing dot + color-coded label.

The pill is `LIVE` (green) when the VNC stream is active,
`READY` (yellow) when the runtime is detected but not yet
launched, `TERMINAL` (cyan) when only the PTY is available,
and `OFFLINE` (red) when the rootfs is unhealthy. The pulse
uses `infiniteRepeatable` with a 1.4s tween — the same animation
primitive the app uses elsewhere.

### 2. Hero capability card

The thin-bordered monospace card became a `HeroCapabilityCard`:

- A 56dp gradient icon block (color-coded by status) with the
  capability's hero icon (`MonitorHeart` / `PlayArrow` /
  `Terminal` / `Info`).
- The capability title + detail in 18sp / 11sp, with a 16sp
  line-height for legibility.
- A `DetectedServerChip` that surfaces the VNC server
  detection (`server: /Xtigervnc`).
- A `DesktopErrorBanner` (red, only when `desktopError` is
  non-blank) that shows the typed failure detail.
- A `PrimaryCtaButton` — the most important change. The CTA
  changes label + color by status (`LAUNCH DESKTOP` yellow
  when ready, `DISCONNECT` red when live, `OFFLINE` muted
  when unavailable) and **pulses** when the system is ready
  to launch. The pulse is a `Modifier.scale` driven by
  `animateFloatAsState(targetValue = if (enabled && ready)
  pulse else 1.0f)` — the animation only plays when the
  button can actually be tapped.
- A `SecondaryCtaButton` (TERMINAL) that opens the real
  PTY terminal.

The whole card has a 16dp shadow in the status color, so the
ready state has a visible halo on the home screen.

### 3. App grid (modern, not a list)

The plain `LazyColumn` of `Card`-based app rows became a
`LazyVerticalGrid`:

- 4 columns on tablet, 3 on medium, 2 on phone (the
  `BoxWithConstraints` picks the column count).
- Each tile is a 40dp icon block (gradient + neon border)
  with the app's name + 2-line comment.
- The icon is picked by exec heuristic: `terminal` /
  `xterm` → `Terminal` (cyan), `firefox` / `chromium` →
  `NetworkCheck` (yellow), `code` / `vscode` → `DeveloperMode`
  (cyan), `file` / `nautilus` / `thunar` → `Storage`
  (silver), `vim` / `nano` / `emacs` → `Speed` (green),
  fallback `Apps` (silver).
- Each tile pulses subtly via `infiniteRepeatable` (a
  1.02x scale, 2.2s tween) so the grid feels alive.
- The "no apps discovered" state is a separate
  `AppsEmptyState` card with an `AppShortcut` icon + a
  helpful message ("Install a desktop environment via the
  runtime to populate this grid").

### 4. System status footer (guest telemetry)

The screen now ends with a `SystemStatusFooter` row of four
`MetricTile`s: OS / PACKAGES / ENTRIES / READY. Each tile has:

- A small icon (Bolt / Memory / Storage / CheckCircle).
- A short label (8sp, monospace, bold, letter-spaced).
- A large value (16sp, monospace, bold, accent color).
- A 4dp progress bar that animates from 0% to the
  metric's value via `animateFloatAsState` (600ms
  `tween`, `FastOutSlowInEasing`).

The `READY` tile's value is `STREAM` / `YES` / `TERM` / `NO`
based on the current `DesktopStatusKind` — the user can see
at a glance whether the guest is online. (The `OS` /
`PACKAGES` / `ENTRIES` tiles use the data the
`RootfsIntrospectorSnapshot` actually carries; a follow-up
phase adds a real-time CPU / RAM / Disk stream from the
running session.)

### 5. Live desktop workspace (VNC stream + floating glass toolbar)

When the session is streaming, the VNC host fills the
workspace and a glass toolbar floats on top:

- A circular back button (top-left).
- A status pill with `LIVE` + resolution (`1920×1080`) +
  frame count, all in a single rounded row with a pulsing
  green dot.
- Three circular action buttons (top-right): keyboard
  toggle (cyan when active, white when inactive),
  screenshot, disconnect (red).
- A bottom-center hint card that fades in when the
  keyboard toggle is on (AnimatedVisibility + fadeIn /
  fadeOut, 200ms tween).
- A bottom-left signature pill "ELYSIUM VANGUARD · REMOTE
  DESKTOP" with a pulsing green dot.

The toolbar buttons all have the same `ToolbarIconButton`
shape: 40dp circle, black 65% opacity background, 18% white
border, 18dp icon. They feel like a real remote desktop's
control bar.

## State preservation

- The same `LinuxDesktopViewModel` powers the new screen
  (no behavior change).
- The same `RfbHost` renders the VNC stream (no behavior
  change).
- The same `RootfsIntrospectorSnapshot` / `LinuxAppEntry` /
  `GraphicalDesktopCapability` flow drives the data.
- The `LiveDesktopWorkspace` is the only composable
  affected by the live state path; the rest of the
  redesign is on the idle path.

## Build / test status

- `compileDebugKotlin` — green.
- `assembleDebug` — green.
- `testDebugUnitTest` — **all 2578 unit tests green, 0 failures**
  (the screen is presentational; no new JVM tests are added
  — the JVM suite asserts the ViewModel + the data layer,
  not the visual design).
- 0 new lint warnings.
- Install on the user's device — verified.

## Files

- `app/src/main/java/com/elysium/vanguard/features/runtime/desktop/LinuxDesktopScreen.kt` (REWRITTEN, ~1200 lines)

## Notes for follow-ups

- **Live CPU / RAM / Disk / Network**: the footer currently
  uses the static introspector snapshot. A future phase
  exposes a `GuestMetrics` stream from the running
  `TerminalSession` and feeds the metric tiles with live
  values.
- **App icons**: the heuristic-based icon picker is a
  placeholder. A future phase parses each
  `LinuxAppEntry`'s `Icon` field (or downloads icons
  from the app's own assets) and renders them.
- **Windowed desktop overlay**: the live workspace is
  full-bleed VNC. A future phase adds a Compose windowed
  shell on top (multi-window with draggable frames),
  matching the master vision's "Escritorio universal"
  surface.
- **Theme accent**: the screen uses the file-section
  accent indirectly (via the status pill color). A
  follow-up wires the runtime accent from
  `SectionColorManager` to the per-distro brand color.
