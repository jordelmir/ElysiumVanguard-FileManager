# Phase 45 — "Open in terminal" navigation for Running Linux sessions

Date: 2026-07-17
Status: **Shipped** — `assembleDebug` green, 1362 tests, 0 failures, 2 skipped.

## What landed

The new runtime is now **fully usable**. Until
Phase 45, tapping Start on a Linux session would
flip the pill to "Running" but the user had no
way to actually USE the session. Phase 45
closes that gap: every Running LinuxProot
session has an "Open" button (compact
`OpenInNew` icon) that navigates to the
existing terminal screen with the distro
pre-loaded. The user taps Start, the session
flips to Running, the Open button appears,
tapping it drops them straight into a shell.

WindowsVm sessions get the same affordance,
but the VNC viewer is not yet implemented
(Phase 9.6.5 in the Worldwide Vision doc) —
tapping the Open button shows a snackbar
saying "VNC viewer not yet implemented".
This is a real affordance, not a silent
no-op: the user knows what happened.

### Files

**Production (2 modified):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/ui/MainScreen.kt` —
  - `MainScreen` gained a new `onOpenLinuxSession:
    (distroId: String) -> Unit` parameter (default
    `{}` so existing callers stay green).
  - The `WorkspaceCard` gained a new
    `onOpenSession: (WorkspaceSession) -> Unit`
    callback that the screen threads through
    based on the session's kind.
  - The `SessionRow` gained a new
    `onOpen: (() -> Unit)? = null` parameter.
    The `OpenSessionButton` (an `OpenInNew` icon
    button) renders only when the session is
    `SessionState.Running` AND the callback is
    non-null.
  - For LinuxProot sessions, the screen calls
    `onOpenLinuxSession(session.distroId)`.
  - For WindowsVm sessions, the screen fires a
    snackbar ("VNC viewer not yet implemented")
    via the `SnackbarHostState`. The launch is
    in a `rememberCoroutineScope` because
    `SnackbarHostState.showSnackbar` is a
    suspend function.
  - New `OpenInNew` icon import.

- `app/src/main/java/com/elysium/vanguard/MainActivity.kt` — the `runtime_main`
  composable's `MainScreen` invocation now
  passes `onOpenLinuxSession = { distroId ->
  val encoded = URLEncoder.encode(distroId,
  StandardCharsets.UTF_8.toString());
  navController.navigate("terminal_distro/$encoded") }`.
  This is the same URL-encoding + route
  pattern the existing `runtime` composable
  uses for the "Open Terminal" action.

## What the screen now does

| Action | Effect |
|---|---|
| Tap **Start** on a Linux session | Runner starts the session; pill flips to "Running" |
| Tap **Start** on a Linux session, then tap **Open** | Navigates to `terminal_distro/<distroId>` (the user is now in a shell) |
| Tap **Start** on a Windows session | Runner starts the session; pill flips to "Running" |
| Tap **Start** on a Windows session, then tap **Open** | Snackbar: "VNC viewer not yet implemented" (Phase 9.6.5) |
| Tap **Stop** on a Running session | Runner stops the session; pill flips to "Stopped"; the Open button disappears (only shown when Running) |

The Open button is a `OpenInNew` icon (`↗`),
placed between the state pill and the
Start/Stop button. The button only renders
when:
- the session is `SessionState.Running`,
  AND
- the parent supplied a non-null `onOpen`
  callback (the parameter is nullable for
  tests / previews).

The session row is compact even with the
Open button: badge → Open → Start/Stop →
remove. Tapping the row's text area does
nothing (the row is not a click target; the
buttons are the affordances).

## Why this matters

Until Phase 45, the user could manage the
runtime but could not **use** it. A user who
tapped Start on a Debian session saw
"Running" in the pill and then... nothing.
The terminal screen existed (Phase 9.6.1)
but was reachable only from the dashboard.
Phase 45 closes the loop:

1. User opens the dashboard
2. Taps WORKSPACES
3. Taps Create, names the workspace, taps Add
4. Picks "Debian" in the Add Session dialog,
   taps Add
5. Taps Start on the Debian session
6. The Open button appears
7. Taps Open → **in a shell**

The full user journey from "I want to run
Linux" to "I'm in a Linux shell" is now
**6 taps**, all inside the new `runtime_main`
route, with no context switches to the old
catalog / dashboard / terminal paths.

## Test count

| Suite | Tests | Failures |
|---|---|---|
| (no new tests) | 0 | 0 |
| **Project total** | **1362** | **0** |
| Skipped | 2 | (real-archive integration only) |

The new composable (`OpenSessionButton`) is
a single `IconButton` wrapper; it has no
behavioural surface worth testing on the JVM
(it's an icon + an onClick). The
end-to-end path (Linux session → Open →
navigated to terminal) is a future
`MainScreenInstrumentedTest` test that
needs a Running session in the store — that
is Phase 9.6.5 (VNC viewer) territory, not
this phase.

## Next phase

The follow-up after Phase 45 is the
**Phase 9.6.5 VNC viewer** (Worldwide Vision
doc) — a Compose composable that renders the
WindowsVm session's VNC stream, with
click / keyboard forwarding. Phase 9.6.5 is
the natural complement to Phase 45: with
both, the Open affordance for WindowsVm
sessions works end-to-end (Start + Open →
VNC viewer, click on the window, type on the
keyboard, see the Windows desktop inside
Elysium Vanguard).

A smaller follow-up is **Phase 46 — the
`MainScreenInstrumentedTest` test for the
"Start then Open" flow**. The current test
covers the empty state, the create flow, and
the menu; Phase 46 would add a "tapping Start
flips the pill to Running" assertion that
exercises the new Phase 38 + Phase 45 wiring
end-to-end. This is a small phase; it does
not require a real distro install because
the runner is `HiltTestRunner`-backed and the
test can inject a fake `SessionRunner`.
