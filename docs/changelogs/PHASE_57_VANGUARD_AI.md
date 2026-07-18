# Phase 57 — Vanguard AI (Agent Operator)

Date: 2026-07-18
Status: **Shipped** — `assembleDebug` green, 1720 tests, 0 failures, 2 skipped.

## What landed

The Vanguard AI pillar is real. The user
can type a natural-language goal (in
English or Spanish) and the agent produces
a plan + executes it. The agent is **OUR
intellectual property** — no third-party
LLM, no API key, no network call. The
rule-based parser is auditable: a user
with a "why did the agent install Debian
for this goal?" question reads the table
and answers it.

The master vision's "agent operator" is
now real:
- ✅ Install a distro (`install <id>` /
  `instalar <id>`).
- ✅ Create a Windows environment
  (`create windows env for <binary>` /
  `ejecutar windows <binary>`).
- ✅ Snapshot a workspace
  (`snapshot <id>` / `instantanea <id>`).
- ✅ Rollback to a snapshot
  (`rollback <id>` / `revertir <id>`).
- ✅ Build a project
  (`build <toolchain> <command>` /
  `compilar <toolchain> <command>`).
- ✅ Run a command (`run <cmd>` /
  `ejecutar <cmd>`).

The user explicitly said:

> "vamos a crear lo nuestro propietario,
> nuestra propia informacion intelectual
> propietaria"

Phase 57 ships the agent as OUR IP. The
parser's rule table is the source of
truth; a future phase adds an LLM-based
parser that the user explicitly enables.

## Files

**Production (3 new + 2 modified):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/agent/AgentPlan.kt` —
  the value types. `NaturalLanguageGoal`
  (the user's input + languageCode +
  autoConfirm flag). `AgentPlan` (the
  planner's output: id, actions,
  riskLevel, createdAtMs, goal,
  targetWorkspaceId for snapshot policy).
  `RiskLevel` (LOW / MEDIUM / HIGH).
  `AgentAction` (sealed class with 6
  variants: InstallDistro,
  CreateWindowsEnvironment,
  CreateSnapshot, RollbackToSnapshot,
  RunBuild, RunCommand). Each action
  has a `describe()` for the UI's
  "review the plan" dialog.
- `app/src/main/java/com/elysium/vanguard/core/runtime/agent/NaturalLanguageParser.kt` —
  the rule-based parser. 6 regex rules
  covering install / create-windows /
  snapshot / rollback / build / run.
  English + Spanish keywords. The parser
  is a pure function: same input, same
  output, no side effects. Returns a
  `ParserOutcome` (`Parsed` or
  `Unparseable` with a typed reason).
- `app/src/main/java/com/elysium/vanguard/core/runtime/agent/PlanExecutor.kt` —
  the runner. Consumes an `AgentPlan`,
  dispatches each action to the
  `AgentCollaborators` interface, takes
  a snapshot before the first destructive
  action (when the plan has a
  `targetWorkspaceId`), rolls back on
  failure. HIGH-risk plans require
  user confirmation (`autoConfirm=true`
  overrides). Publishes 5 new
  `RuntimeEvent`s on the bus
  (AgentActionStarted / Completed /
  Failed / RolledBack / Refused).
- `app/src/main/java/com/elysium/vanguard/core/runtime/observability/RuntimeEvent.kt`
  (modified) — 5 new events for agent
  state transitions.
- `app/src/main/java/com/elysium/vanguard/core/runtime/observability/RuntimeEventLog.kt`
  (modified) — the file-backed audit log
  gains 5 new JSON Lines render paths +
  5 parse paths.

**ADR:**

- `docs/adr/ADR-027-vanguard-ai.md` —
  the design record. Captures the
  four-piece split (AgentAction /
  AgentPlan / NaturalLanguageParser /
  PlanExecutor), the rule-based parser
  rationale (deterministic / offline /
  auditable / fast), the snapshot-on-
  failure rationale (executor owns the
  policy, planner is pure), the risk
  level gate (HIGH-risk plans require
  user confirmation), and the revisit
  triggers (LLM parser, sophisticated
  snapshot policy, multi-step planning).

**Tests (2 new):**

- `app/src/test/java/com/elysium/vanguard/core/runtime/agent/NaturalLanguageParserTest.kt` —
  14 tests covering: English + Spanish
  install rules; English + Spanish
  create-windows rules (with QEMU + FEX
  + default Box64 runtime hints);
  English + Spanish snapshot rules;
  English + Spanish rollback rules;
  English + Spanish build rules;
  English + Spanish run-command rules;
  unknown input returns Unparseable with
  a reason; blank input rejected by the
  goal's init-block; plan id is generated
  from the idGenerator.
- `app/src/test/java/com/elysium/vanguard/core/runtime/agent/PlanExecutorTest.kt` —
  10 tests covering: executor refuses a
  HIGH-risk plan without user
  confirmation; executor honours
  autoConfirm for HIGH-risk plans;
  executor takes a snapshot before the
  first destructive action WHEN
  targetWorkspaceId is set; executor does
  NOT take a snapshot when
  targetWorkspaceId is null; executor
  rolls back on a failed destructive
  action; executor reports rolledBack=false
  when the rollback itself fails; executor
  publishes AgentActionStarted /
  Completed events on success; executor
  publishes AgentActionFailed /
  AgentActionRolledBack events on a
  failed destructive action; executor
  returns Success when every action
  succeeds; executor does not take a
  snapshot before a read-only action.
  Includes a `FakeAgentCollaborators`
  that records every call in a
  thread-safe list.

## Why this matters

The master vision's "agent operator" was
the central nervous system of the platform.
Until Phase 57 the user had to know the
exact runtime API to install a distro,
create a Windows env, or build a project.
Phase 57 flips that: the user types a
natural-language goal, the agent produces
a plan, and the executor runs it.

The agent is OUR intellectual property.
The rule table is auditable. No third-party
LLM dependency. The snapshot-on-failure
policy is the master vision's "create
snapshots before modifying an environment;
revert automatically when an operation
fails" rule — the executor takes a
snapshot before the first destructive
action (when the plan targets a
workspace) and rolls back on failure.

The HIGH-risk gate is the safety net: a
user with a "the agent should always
require confirmation for rollback"
preference gets that by default. A user
with a "the agent should auto-confirm"
preference sets `autoConfirm = true`.

## What the test suite caught

- **Snapshot policy was initially too
  aggressive.** The first version of
  the executor took a snapshot before
  the first destructive action, even
  when the plan had no `targetWorkspaceId`.
  The test `executor does not take a
  snapshot when targetWorkspaceId is null`
  caught the issue. Fixed by adding the
  optional `targetWorkspaceId` field to
  `AgentPlan` and gating the snapshot
  policy on it.

- **`workspaceIdFor(action)` was unused
  after the snapshot policy changed.**
  The compiler caught the dead method;
  removed. The action's workspace is now
  derived from the plan's
  `targetWorkspaceId`, not from the
  individual action.

- **`Plan` vs `ParserOutcome` return type
  mismatch.** The first version of
  `parse()` returned the inner
  `AgentPlan?` directly, then the
  function's declared return type was
  `ParserOutcome`. The compiler caught
  the mismatch on the first build. Fixed
  with a `?.let { ParserOutcome.Parsed(it) }`.

All three are exactly the kind of
regressions the test suite is supposed to
surface — both production code and test
code get pinned by the suite.

## Architectural invariants (Phase 57)

- **The parser is a pure function.** Same
  input, same output, no side effects. A
  test asserts this; the agent can be
  re-parsed at any time without state
  changes.

- **The snapshot / rollback policy is
  the executor's, not the planner's.**
  The planner says "install this distro";
  the executor decides whether to
  snapshot. The planner is pure; the
  executor is the policy holder.

- **HIGH-risk plans require user
  confirmation.** A user with
  `autoConfirm = false` gets a refusal
  for `RollbackToSnapshot` and
  `RunCommand`. A user with
  `autoConfirm = true` skips the gate.

- **The agent is OUR intellectual
  property.** The rule table, the plan
  format, the executor's policy — all
  owned by Elysium Vanguard. No
  third-party LLM dependency.

## Test count

| Suite | Tests | Failures |
|---|---|---|
| `NaturalLanguageParserTest` | 14 (new) | 0 |
| `PlanExecutorTest` | 10 (new) | 0 |
| **Project total** | **1720** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Next phase

The follow-up after Phase 57 is **Phase
58 — Vanguard Cloud (sync, backups, remote
builds)**. The master vision says:

> "Sync, builds, backups y artefactos."

Phase 58 ships the cloud sync subsystem:
- A `CloudSync` interface + `LocalCloudSync`
  stub (a real cloud provider is a Phase
  60+ concern).
- A `BackupService` that creates an
  encrypted backup of the workspace state
  to the cloud.
- A `RemoteBuildClient` impl that talks
  to a real Oracle Free build server
  (the seam was Phase 56; the impl is
  Phase 58).
