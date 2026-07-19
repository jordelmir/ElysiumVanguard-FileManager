# Phase 73 — Vanguard AI rule-based agent (real production wiring)

The master vision section 8 calls Vanguard AI "the agent that
operates the platform": install distros, create Windows
environments, take snapshots, roll back, run builds, run
commands. Until Phase 73 the rule-based agent (the proprietary,
no-third-party-LLM path) was a typed contract + a parser + an
executor with full unit-test coverage, but **no production
collaborator** — the `AgentCollaborators` interface had zero
implementations. The agent could parse a goal into a plan, but
no collaborator ran the plan. Phase 73 wires the production
graph.

## What shipped

### 1. The production collaborator

`app/src/main/java/com/elysium/vanguard/core/runtime/agent/RealAgentCollaborators.kt`

The first `AgentCollaborators` implementation. The class:

- `installDistro` → `DistroManager.installBlocking` (the real
  download + extract + verify + atomic-activate + os-release
  overlay path).
- `createWindowsEnvironment` → typed "not yet wired" failure
  (the `WindowsVmManager` lacks an `installFromBinary` seam;
  Phase 74 work).
- `createSnapshot` → `WorkspaceManager.snapshotWorkspace` with
  the workspace's first LinuxProot session's rootfs resolved
  via `DistroManager.findInstalled`.
- `rollbackToSnapshot` → `WorkspaceManager.rollbackWorkspace`,
  same rootfs resolution.
- `runBuild` → `LocalBuildRunner.build` with the parsed
  `ToolchainKind` (case-insensitive; `RUST | C_CPP | JAVA_KOTLIN
  | GRADLE | NODE | PYTHON | GO | WEBASSEMBLY | LINUX_ARM64`).
- `runCommand` → `ProcessLauncher.start` (with a defensive
  `catch (RuntimeException)` wrapper so a stub launcher
  throwing an unchecked exception is caught + surfaced as
  `AgentStepResult.Failure`).

Every method returns a typed `AgentStepResult.Success` /
`AgentStepResult.Failure`. No method throws.

### 2. The Hilt module + interface binding

`app/src/main/java/com/elysium/vanguard/core/runtime/agent/AgentModule.kt`

- `provideNaturalLanguageParser` — the rule-based parser.
- `providePlanExecutor` — the executor with the
  `AgentCollaborators` collaborator + the runtime event bus.
- `provideLocalBuildRunner` — the build runner (it had no
  `@Inject` constructor; Hilt now provides it).
- `provideToolchainRegistry` — the toolchain registry (empty
  by default; a follow-up phase wires the real detector).
- `AgentCollaboratorsModule` — the `@Binds` module that maps
  the `AgentCollaborators` interface to `RealAgentCollaborators`.

### 3. The rule-based UI

`app/src/main/java/com/elysium/vanguard/features/agent/`

- `LocalAgentViewModel` — wraps the parser + executor; takes
  a `NaturalLanguageGoal` from the UI, parses it, executes
  the plan, surfaces the outcome.
- `LocalAgentScreen` — a chat-style surface (parses the goal
  on submit, shows the proposed plan, asks the user to
  confirm a HIGH-risk plan before executing, surfaces the
  executor's `Success` / `Failure` / `Refused` outcome).

### 4. The nav integration

`MainActivity.kt` + `DashboardScreen.kt`:

- New route `local_agent` in the nav graph.
- New dashboard tile "LOCAL AGENT" (parallel to "COMMAND
  CORE", which is the HTTP-gateway Command Core).
- The two systems coexist; the user picks which agent to
  talk to from the dashboard.

### 5. Tests

`app/src/test/java/com/elysium/vanguard/core/runtime/agent/RealAgentCollaboratorsTest.kt`

- 16 JVM tests for `RealAgentCollaborators`:
  - `installDistro` (success / failure)
  - `createWindowsEnvironment` (typed Phase-74 failure)
  - `createSnapshot` (success on LinuxProot workspace /
    failure on missing workspace / failure on no-LinuxProot
    workspace / failure on manager's snapshot failure)
  - `rollbackToSnapshot` (success / failure on missing
    workspace)
  - `runBuild` (known toolchain / unknown toolchain / typed
    runner error / case-insensitive normalization)
  - `runCommand` (success with pid / spawn failure / empty
    command list)

## Build / test status

- `compileDebugKotlin` — green.
- `testDebugUnitTest` — **all 2578 unit tests green, 0 failures**
  (16 new + the prior 2558 + 4 test-discovered fixes during
  development).
- `assembleDebug` — green.
- 0 new lint warnings (the `clickableSafe` extension is
  the only marginally novel addition; the project's
  `clickable` is a Compose extension function and the local
  helper is documented).

## Notes

- The Hilt `@Binds` failure mode is real: forgetting
  `AgentCollaboratorsModule` produces a "MissingBinding" error
  on `AgentCollaborators` at the `PlanExecutor` injection
  site. Caught during development; documented in the KDoc.
- The `InMemoryProotBackend` (the test stub the E2E uses)
  already returns a canned `nextWrites` list; the
  `RealAgentCollaborators` is the production-side answer
  for the rule-based agent, not the proot-backend answer.
- The `createWindowsEnvironment` failure is a typed message
  that names Phase 74 as the path to wire
  `WindowsVmManager.installFromBinary`. The executor's
  audit log records "windows environment not yet wired"
  instead of silently succeeding.

## Files

- `app/src/main/java/com/elysium/vanguard/core/runtime/agent/RealAgentCollaborators.kt` (NEW)
- `app/src/main/java/com/elysium/vanguard/core/runtime/agent/AgentModule.kt` (NEW)
- `app/src/main/java/com/elysium/vanguard/features/agent/LocalAgentViewModel.kt` (NEW)
- `app/src/main/java/com/elysium/vanguard/features/agent/LocalAgentScreen.kt` (NEW)
- `app/src/main/java/com/elysium/vanguard/MainActivity.kt` (UPDATED: `local_agent` route + `onNavigateToLocalAgent`)
- `app/src/main/java/com/elysium/vanguard/features/dashboard/DashboardScreen.kt` (UPDATED: `onNavigateToLocalAgent` param + LOCAL AGENT tile)
- `app/src/test/java/com/elysium/vanguard/core/runtime/agent/RealAgentCollaboratorsTest.kt` (NEW, 16 tests)
