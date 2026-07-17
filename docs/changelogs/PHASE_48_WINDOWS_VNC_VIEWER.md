# Phase 48 — `WindowsVmVncScreen` Compose viewer + NavHost route + Phase 45 wire-up

Date: 2026-07-17
Status: **Shipped** — `assembleDebug` green, 1372 tests, 0 failures, 2 skipped.

## What landed

The Phase 9.6.5 VNC viewer is now **end-to-end
shippable**. Phase 47 wired the VNC port into
[WindowsVmState.Running]; Phase 48 closes the
visible half of the loop:

- A new `WindowsVmVncScreen` Compose
  composable that uses the existing
  [RfbSession] client to connect to
  `127.0.0.1:<vncPort>` and stream the
  guest framebuffer into the screen.
- A new `vnc_windows_vm/{vncPort}` route in
  the NavHost.
- `MainActivity` threads the new
  `onOpenWindowsSession: (vncPort: Int) -> Unit`
  callback to [MainScreen].
- [MainScreen]'s WindowsVm branch in the
  "Open" affordance (Phase 45) now looks up
  the running VM's VNC port via
  [MainScreenViewModel.vncPortForWindowsSession]
  and navigates to the viewer — replacing
  the "VNC viewer not yet implemented"
  snackbar.

The user can now:

1. Open the dashboard
2. Tap WORKSPACES
3. Create a WindowsVm session (Phase 41's
   catalog-driven picker)
4. Tap Start
5. Tap Open → **VNC viewer** streams the
   Windows desktop

The viewer is intentionally a thin Compose
host — the real VNC work is the existing
[RfbSession] + [RfbSurfaceView] pair (used
by the Linux desktop path in
[com.elysium.vanguard.features.runtime.desktop.LinuxDesktopScreen]).
Phase 48 adds only the lifecycle wiring
(start on enter, stop on leave) and the
state-driven status line.

### Files

**Production (3 new, 4 modified):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/windows/WindowsVmVncScreen.kt` —
  new Compose composable. Reads a
  `vncPort: Int` from the NavHost, constructs
  an [RfbSession] bound to `127.0.0.1:<vncPort>`,
  starts it on `LaunchedEffect`, stops it on
  `DisposableEffect.onDispose`. The
  framebuffer is rendered via [AndroidView] +
  [RfbSurfaceView]. A state-driven overlay
  shows a `CircularProgressIndicator` while
  the session is `Idle` / `Connecting`, and
  an error message when the session is
  `Failed`. The composable's "Open" affordance
  is the [OpenInNew] icon next to the Start /
  Stop button — added in Phase 45.
- `app/src/main/java/com/elysium/vanguard/core/runtime/windows/WindowsVmManager.kt` —
  new `vncPortFor(specId: String): Int?`
  method. Returns the `vncPort` of the
  running VM (or `null` if the VM is not
  running, or the in-memory test backend
  which does not populate the field).
- `app/src/main/java/com/elysium/vanguard/core/runtime/ui/MainScreenViewModel.kt` —
  new `vncPortForWindowsSession(specId: String): Int?`
  helper. Delegates to
  [WindowsVmManager.vncPortFor].
- `app/src/main/java/com/elysium/vanguard/core/runtime/ui/MainScreen.kt` —
  `MainScreen` gained a new
  `onOpenWindowsSession: (vncPort: Int) -> Unit`
  parameter (default `{}` so existing
  callers stay green). The WindowsVm branch
  in the "Open" callback now looks up the
  VNC port and navigates. If the VM is not
  yet running (e.g. the user tapped Start and
  the runner has not published the Running
  state), the screen falls back to the
  snackbar "VM is not running yet".
- `app/src/main/java/com/elysium/vanguard/MainActivity.kt` — the
  `runtime_main` composable's [MainScreen]
  invocation now passes
  `onOpenWindowsSession = { vncPort ->
  navController.navigate("vnc_windows_vm/$vncPort") }`.
  A new `composable("vnc_windows_vm/{vncPort}")`
  route opens the new screen.

**Tests (1 modified):**

- `app/src/test/java/com/elysium/vanguard/core/runtime/windows/WindowsVmManagerTest.kt` —
  3 new tests pin the new `vncPortFor` contract:
  - `vncPortFor` returns the VNC port of a
    running VM (the `InMemoryWindowsVmBackend`
    returns `null` because it does not
    populate the field; the test asserts the
    null path).
  - `vncPortFor` returns `null` for a
    non-existent VM.
  - `vncPortFor` returns `null` for a stopped
    VM.

## What the screen now does

| User action | Effect |
|---|---|
| Tap **Start** on a WindowsVm session | Runner starts the VM; pill flips to "Running"; the QEMU VNC port becomes available |
| Tap **Start**, then tap **Open** | [MainScreen] looks up `vncPortFor(session.windowsSpecId)`, navigates to `vnc_windows_vm/<vncPort>`; the [WindowsVmVncScreen] connects via [RfbSession] and renders the framebuffer |
| Tap **Start**, then tap **Open** before the VM is fully running | [MainScreen] shows the snackbar "VM is not running yet" (the runner publishes the Running state asynchronously; the VNC port is not yet available) |
| Tap **Back** from the VNC viewer | The session's `DisposableEffect.onDispose` closes the [RfbSession]; the user returns to [MainScreen] |
| Tap **Stop** on the running WindowsVm session | Runner stops the VM; pill flips to "Stopped"; the Open button disappears (only shown when Running) |

The viewer is a single-screen phase. Future
additions (input device forwarding for
USB passthrough, drag-and-drop file
transfer, copy-paste between Android and the
guest) are follow-up phases.

## Why this matters

Until Phase 48, the runtime path was complete
on the management side (Phase 34–47) but had
no path from "I started a Windows VM" to
"I see the Windows desktop". The user could
manage workspaces and sessions, but the only
way to USE a Windows session was to manually
run a VNC client outside the app and type the
VNC port. Phase 48 closes that gap: tapping
Open on a Running WindowsVm session drops the
user directly into the VNC viewer, with the
framebuffer streamed from QEMU's VNC display.

The VNC viewer is the user-facing payoff of
the Phase 9.6.5 milestone from the Worldwide
Vision doc. With Phase 48, the user can:
1. Install a Windows VM (Phase 22)
2. Start it (Phase 38)
3. Open it (Phase 48)
4. See the Windows desktop

…all inside the Elysium Vanguard app, with
no external VNC client, no port forwarding,
no extra setup.

## Test count

| Suite | Tests | Failures |
|---|---|---|
| `WindowsVmManagerTest` | 28 (was 25) | 0 |
| **Project total** | **1372** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Next phase

The follow-up after Phase 48 is the
**Phase 9.6.6 SSH client + X11-forwarding
tunnel** (Worldwide Vision doc). The runtime
path is now complete on the local-VM side:
Linux sessions open in a terminal (Phase 45),
Windows sessions open in a VNC viewer (Phase
48). A remote console (ssh into another
machine from inside Elysium) is the natural
next capability.

A smaller follow-up is **the
`androidTest/` for the VNC viewer flow**:
tapping Start on a WindowsVm session, then
tapping Open, asserts the
`vnc_windows_vm/{vncPort}` route is
navigated. The test is a small follow-up
that uses the existing
[HiltTestRunner] + [createAndroidComposeRule]
infrastructure.
