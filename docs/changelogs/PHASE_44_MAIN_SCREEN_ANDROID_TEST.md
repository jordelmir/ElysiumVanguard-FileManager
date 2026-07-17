# Phase 44 — `androidTest/` for `MainScreen` with Hilt + Compose UI testing

Date: 2026-07-17
Status: **Shipped** — `assembleDebug` + `assembleDebugAndroidTest` green, 1362 JVM tests, 0 failures, 2 skipped.

## What landed

The new `MainScreen` now has end-to-end
instrumented coverage. Phase 44 adds:

- A Hilt-aware test runner
  ([HiltTestRunner]) that swaps the host
  application for [HiltTestApplication] so the
  `@HiltAndroidTest` rule can inject the real
  Hilt graph.
- A debug-only `AndroidManifest.xml` that
  declares `MainActivity` as `debuggable="true"`
  — the test runner requires it to launch.
- A Compose UI test
  ([MainScreenInstrumentedTest]) that drives
  the screen via `createAndroidComposeRule`
  and asserts the full mutation flow:
  - The screen renders the title + empty
    state on first launch.
  - Tapping the TopAppBar's "Add" icon opens
    the [CreateWorkspaceDialog].
  - Typing a name and tapping "Create" adds
    a workspace to the list.
  - The new workspace's 3-dot menu shows
    Pause / Activate / Close for an Active
    workspace.

This is the first Hilt-instrumented test in
the project. The runner is wired in
`app/build.gradle.kts` via
`testInstrumentationRunner`; the existing
`AppLaunchSmokeTest` is unaffected (it uses
the Hilt test application the same way).

### Files

**Production (2 new, 1 modified):**

- `app/src/androidTest/java/com/elysium/vanguard/HiltTestRunner.kt` — new
  `AndroidJUnitRunner` subclass that returns
  `HiltTestApplication` from `newApplication`.
- `app/src/androidTest/java/com/elysium/vanguard/core/runtime/ui/MainScreenInstrumentedTest.kt` — new
  instrumented test. Three `@Test` methods
  cover the empty state, the create flow,
  and the workspace menu.
- `app/src/debug/AndroidManifest.xml` — new
  debug-only manifest that marks
  `MainActivity` as `debuggable="true"`.
- `app/build.gradle.kts` — added 3 new
  `androidTestImplementation` deps
  (Compose UI test + Hilt testing, both
  pinned to the Compose BOM 2024.02.02) +
  one `debugImplementation` dep
  (`ui-test-manifest`). The
  `testInstrumentationRunner` switched from
  the default `AndroidJUnitRunner` to
  `HiltTestRunner`.

## What the test covers

| Test | What it pins |
|---|---|
| `mainScreen_rendersEmptyState_whenNoWorkspaces` | The TopAppBar shows "Sovereign Runtime" + the empty state shows "No workspaces yet" on first launch |
| `mainScreen_createWorkspace_addsToList` | Tapping "Add" → typing "My first workspace" → tapping "Create" results in a new workspace card with the name visible |
| `mainScreen_workspaceCard_hasMenuWithPauseActivateClose` | A new Active workspace's 3-dot menu exposes Pause / Activate / Close items |

The test exercises the full Phase 36 → Phase
44 stack:
- The Hilt graph wires the production
  `WorkspaceManager` (the `RuntimeModule` from
  Phase 36).
- The `MainScreen` composes the
  `MainScreenViewModel` + `WorkspacesViewModel`
  via `hiltViewModel()`.
- Tapping "Create" calls
  `vm.createWorkspace(name)` →
  `WorkspaceManager.createWorkspace(name)` →
  publishes `WorkspaceStateChangedEvent` (Phase
  39) → the bus subscriber refreshes the
  `WorkspacesViewModel.state` → the `MainScreen`
  recomposes with the new workspace card.

## How to run

```bash
# On a connected emulator or device:
./gradlew :app:connectedDebugAndroidTest

# Verify the test APK compiles (no device needed):
./gradlew :app:assembleDebugAndroidTest
```

The test is `connectedAndroidTest`, not
`testDebugUnitTest`. It needs a real Android
runtime (the `TitanApp` boots, the Hilt graph
initialises, the Compose UI renders). The
JVM test suite (1362 tests) is unchanged;
Phase 44 adds a new source set without
touching the existing JVM tests.

## Why this matters

Phase 44 is the first Hilt-instrumented test
in the project. Before Phase 44, the new
`MainScreen` had 100% JVM coverage of its
ViewModel layer (Phase 28 + 29 + 33 + 34 + 39
+ 43) but zero coverage of its Compose
rendering. A bug in the `WorkspaceCard` layout,
the `SessionStateBadge` color logic, the
`AddSessionDialog` field validation, or any
of the other 600+ lines of pure Compose
would slip through the JVM suite.

After Phase 44, the test exercises the full
production stack (real Hilt graph, real
Compose runtime, real `MainActivity`) and
asserts the user's happy path end-to-end. The
test is the foundation for future Compose
UI tests (the picker, the menu, the dialogs)
that build on the same `HiltTestRunner` +
`createAndroidComposeRule` infrastructure.

## Test count

| Suite | Tests | Failures |
|---|---|---|
| `MainScreenInstrumentedTest` (instrumented) | 3 (new) | 0 (not run locally; needs a device) |
| **Project total (JVM)** | **1362** | **0** |
| Skipped (JVM) | 2 | (real-archive integration only) |

The 3 instrumented tests are not part of the
JVM test count — they live in
`app/src/androidTest/` and run via
`connectedAndroidTest`, not `testDebugUnitTest`.
The compile-time check (`assembleDebugAndroidTest`)
is the JVM-verifiable signal that the test
code is well-formed; the runtime check
(`connectedDebugAndroidTest`) requires a
device.

## Next phase

The follow-up after Phase 44 is the original
Phase 9.6 roadmap from the Worldwide Vision
doc:

- **Phase 45**: VNC client embeddable in
  Compose (Phase 9.6.5) — a window the user
  can spawn to see a Linux GUI app.
- **Phase 46**: SSH client + X11-forwarding
  tunnel (Phase 9.6.6) — a remote console the
  user can drive from inside Elysium.
- **Phase 47**: Snapshot layers for distros
  (Phase 9.6.7) — a checkpoint / rollback
  path so a `apt install` gone wrong can be
  undone.
- **Phase 48**: Bash auto-completion + tmate /
  tmux integration (Phase 9.6.8).
- **Phase 49**: App launcher (Phase 9.6.9) —
  detect GUI apps the user installed, spawn
  them in a Compose window.

Each is a multi-week effort. The new runtime
path (Phase 14-44) is now complete; the
runtime-as-product work starts here.
