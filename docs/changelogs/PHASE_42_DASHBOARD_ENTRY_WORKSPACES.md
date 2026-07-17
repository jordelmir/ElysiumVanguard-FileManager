# Phase 42 — Dashboard entry to the sovereign runtime home

Date: 2026-07-17
Status: **Shipped** — `assembleDebug` green, 1359 tests, 0 failures, 2 skipped.

## What landed

The dashboard now has a dedicated "WORKSPACES"
portal tile that navigates to the new
`runtime_main` route (the sovereign runtime
home screen — `MainScreen`). The existing
"RUNTIME" tile is unchanged and still points
to the catalog screen (`runtime`) for
"install a new distro".

Until Phase 42, the only way to reach the
new `MainScreen` was via deep link or via
the empty state inside the screen itself.
A first-time user who installed the app
would never see the workspaces screen from
the dashboard. Phase 42 closes that gap.

### Files

**Production (2 modified):**

- `app/src/main/java/com/elysium/vanguard/features/dashboard/DashboardScreen.kt` —
  added a new `PortalItem` for "WORKSPACES"
  (subtitle: "SESSIONS · STATUS · CONTROL",
  icon: `Icons.Default.Computer`, color:
  `gPrimary`). The `DashboardScreen` composable
  gained a new `onNavigateToWorkspaces` parameter
  (optional, default `null` so existing call
  sites that don't supply it stay green).
- `app/src/main/java/com/elysium/vanguard/MainActivity.kt` — the `dashboard`
  composable now passes
  `onNavigateToWorkspaces = { navController.navigate("runtime_main") }`.

**Tests:** none added. The dashboard's
existing layout is rendered with no
behavioural change for the new tile (it's
a navigation click target, same shape as
the other tiles).

## What the dashboard now shows

| Tile | Navigates to | Purpose |
|---|---|---|
| FILE SYSTEM | `file_manager` | The classic file manager |
| MEDIA VAULT | `gallery` | Photos / videos / albums |
| AUDIO HUB | `music_hub` | Music / playlists |
| RUNTIME | `runtime` | The catalog (install distros) |
| **WORKSPACES** (new) | `runtime_main` | The sovereign runtime home (workspaces, sessions, status) |
| WORD | `editor_word_new` | The Word clone |
| ... | ... | (other existing tiles unchanged) |

The two runtime tiles are intentionally
distinct: "RUNTIME" is for the catalog
(install a new distro), "WORKSPACES" is
for the home (manage existing workspaces
and sessions). A user who wants to install
a new distro taps RUNTIME; a user who wants
to start an existing session taps WORKSPACES.

## Why this matters

Until Phase 42, the new `MainScreen` was
unreachable from the dashboard. A first-time
user would:
1. Install the app
2. Open the dashboard
3. See "RUNTIME" (catalog only)
4. Tap RUNTIME, install a distro
5. Be done — no path to the new workspaces
   screen

Phase 42 adds the second path. A user who
has installed distros can:
1. Install the app
2. Open the dashboard
3. See "WORKSPACES"
4. Tap WORKSPACES, see the workspaces
   screen, add a session, tap Start

The full mutation surface (Phase 38 + 40) is
now reachable from the dashboard in one tap.

## Test count

| Suite | Tests | Failures |
|---|---|---|
| (no new tests) | 0 | 0 |
| **Project total** | **1359** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Next phase

The follow-up after Phase 42 is **Phase 43 —
the `androidTest/` end-to-end coverage for
`MainScreen`**. The Hilt-instrumented test
will exercise the full Phase 36 → Phase 42
stack (Hilt graph → `MainScreenViewModel` →
`WorkspacesViewModel` → `MainScreen` Compose
UI). Phase 43 requires `hilt-android-testing`
+ Compose UI test deps + a `HiltTestApplication`
test runner; the deps are added in this phase
and the test is structured to be runnable on
an emulator or device.
