# Phase 86 — Operator Plan Executor (Foundry / AI Operator: The Orchestrator)

> **Status:** ✅ Shipped
> **Date:** 2026-07-19
> **Phase:** 86 / Foundry / AI Operator
> **Predecessor:** Phase 85 (Operator Audit Log), Phase 84 (Operator Plan), Phase 83 (AI Operator Intent)
> **Vertical:** Foundry (`com.elysium.vanguard.foundry.core.operator.*`)

---

## TL;DR

The Foundry's **AI Operator** is now
**fully closed** as a 4-phase arc. The
**Operator Plan Executor** is the
orchestrator that ties the 3 previous
phases (intent + plan + audit log)
into a single execution flow.

The arc:

- **Phase 83** — `OperatorIntent` (the
  typed intent).
- **Phase 84** — `OperatorPlan` (the
  multi-step plan).
- **Phase 85** — `OperatorAuditLog`
  (the immutable record of every
  action).
- **Phase 86 (this phase)** — The
  executor (the orchestrator that
  validates the plan + each step +
  logs the results + tracks the
  `PlanStatus`).

The executor's algorithm:
1. Validate the plan as a whole
   (delegate to
   `InMemoryOperatorPlanValidator`).
2. If the plan is invalid, return
   `PlanExecutionResult` with
   `status = Failed`.
3. For each step in the plan (in
   `order`):
   a. Validate the step's intent
      against the operator's authority
      (delegate to
      `InMemoryOperatorIntentValidator`).
   b. If `Allowed`: "execute" the
      step (in pure-domain, return
      exit code 0; in production, call
      the `ProcessLauncher`), log a
      `PlanStepCompleted`, and
      continue.
   c. If `RequiresApproval`: log a
      `PlanExecutionFailed`, and
      return `PlanExecutionResult`
      with `status = Paused`.
   d. If `Denied`: log a
      `PlanExecutionFailed`, and
      return `PlanExecutionResult`
      with `status = Failed`.
4. If all steps completed: log a
   `PlanExecutionCompleted`, and
   return `PlanExecutionResult` with
   `status = Completed`.

The executor is **pure-domain** (no
I/O, no Android dependencies). The test
impl is the
`InMemoryOperatorPlanExecutor`. The
production impl may be the same (the
executor is stateless + pure; the same
impl is used in production).

---

## What shipped

### `OperatorPlanExecutor` (sealed class)

The typed orchestrator. The interface
has:

- **`execute(plan, authority, intentValidator, planValidator, auditLog, nowMs)`**
  — execute a plan. The executor
  validates the plan + each step +
  logs the results.

The `nowMs` parameter is the current
time (millis since epoch); the executor
uses it for the audit log timestamps.
The parameter is **explicit** (not
derived from `System.currentTimeMillis()`)
so the executor is **deterministic**
(the test can use a fixed `nowMs`).

### `StepResult` (sealed class, 5 cases)

The per-step result. The sealed class
has 5 cases:

- **`Validated(intentId)`** — the
  step's intent was validated as
  `Allowed` (the executor will
  execute the step next).
- **`Executed(intentId, exitCode)`** —
  the step was executed.
- **`AwaitingApproval(intentId, reason)`**
  — the step's intent requires human
  approval; the executor has paused
  the plan.
- **`Denied(intentId, reason)`** — the
  step's intent was denied; the
  executor has failed the plan.
- **`Failed(intentId, reason)`** — the
  step's execution failed; the
  executor has failed the plan.

### `PlanExecutionResult` (data class)

The plan execution result. The result
is the executor's output for a given
`OperatorPlan` + `OperatorAuthority` +
`OperatorAuditLog`.

The result has:

- **`executionId`** — UUID; the unique
  id of this execution attempt.
- **`planId`** — the plan that was
  executed.
- **`status`** — the final `PlanStatus`
  of the plan.
- **`stepResults`** — the per-step
  results (in `order`). The map only
  contains `Executed` results;
  `AwaitingApproval` / `Denied` /
  `Failed` results are the trigger
  for the plan's `Paused` / `Failed`
  status (they are NOT added to
  `stepResults`).
- **`startedAtMs`** — the timestamp
  the execution started.
- **`completedAtMs`** — the timestamp
  the execution completed (or paused
  / failed).

The map may be **empty** for plans
that did not execute any step (e.g.
an invalid plan that failed at
validation time, or a plan where the
first step was denied).

### `InMemoryOperatorPlanExecutor` (impl)

The in-memory implementation. The
executor:

- Validates the plan as a whole.
- For each step (in `order`):
  - Validates the step's intent
    against the authority.
  - "Executes" the step (returns
    `exitCode = 0` in pure-domain; in
    production, calls the
    `ProcessLauncher`).
  - Logs the result to the audit
    log.
- Returns the final `PlanStatus` +
  the `stepResults` map.

The executor is **thread-safe** (no
mutable fields). The executor is
**deterministic** (the `nowMs`
parameter is explicit).

### `ExecutionId` (UUID value class)

The typed id of an execution. The id is
a UUID (per the Foundry id convention).

### `OperatorExecutionError` (sealed class, 1 case)

The typed error envelope. The 1
variant:

- **`InvalidExecutionIdFormat(rawInput, parseFailure)`**
  — the execution id string was not
  a valid UUID.

---

## Design decisions

### Why is `stepResults` empty for plans that didn't execute any step?

A plan can fail at validation time
(invalid plan) or fail at the first
step (denied step). In both cases,
**no step was executed**, so the
`stepResults` map is empty.

The plan's `status` (Failed) conveys
that the plan did not complete. The
`stepResults` map is a record of the
**executed** steps; if no step was
executed, the map is empty.

### Why is `AwaitingApproval` / `Denied` not added to `stepResults`?

`AwaitingApproval` and `Denied` are
the **triggers** for the plan's
`Paused` / `Failed` status; they are
not **step results** in the sense of
"this step completed with an exit
code".

A `stepResult` is the result of
**executing** a step. An
`AwaitingApproval` / `Denied` /
`Failed` step did not execute; the
plan's status conveys the failure.

The `StepResult` sealed class still
includes the `AwaitingApproval` /
`Denied` / `Failed` cases for the
**caller's convenience** (the caller
can pattern-match on the
`StepResult` to get the typed reason).
The executor's choice to NOT add
these cases to `stepResults` is a
design decision: the map is the
record of **executed** steps.

### Why is the `nowMs` parameter explicit?

The executor is **deterministic**:
the same `nowMs` produces the same
`PlanExecutionResult` + the same
audit log entries (in the same
order, with the same timestamps).

The `nowMs` parameter is the
**testability hook**: the test can
use a fixed `nowMs` to verify the
executor produces the expected
result.

A production caller would pass
`System.currentTimeMillis()`. The
executor does NOT call
`System.currentTimeMillis()`
internally (this would make the
executor non-deterministic).

### Why is the executor a sealed class, not an interface?

The `OperatorPlanExecutor` is a
sealed class with a single in-memory
impl. The sealed class captures the
**abstract behavior** (the
platform's typed executor contract);
the in-memory impl is the test +
production default. A future Phase
7+ increment may add a
`RemoteOperatorPlanExecutor` (a
production impl that delegates to a
remote service for the execution);
the sealed class allows the consumer
to pattern-match on the impl.

---

## Tests

12 new tests in
`OperatorPlanExecutorTest`. The tests
cover:

- **StepResult invariants** (3 tests):
  AwaitingApproval blank reason, Denied
  blank reason, Failed blank reason.
- **PlanExecutionResult invariants** (4
  tests): accepts empty stepResults,
  rejects non-positive startedAtMs,
  rejects completedAtMs < startedAtMs,
  durationMs = completedAtMs -
  startedAtMs.
- **InMemoryOperatorPlanExecutor** (4
  tests): all-allowed plan returns
  Completed with 3 step results,
  requires-approval plan returns Paused
  with 1 step result, denied plan
  returns Failed with 0 step results,
  invalid plan returns Failed without
  executing any step.
- **Realistic scenario** (1 test):
  the "Install Blender" 3-step plan is
  executed with a Full authority →
  status = Completed + 8 audit log
  entries in chronological order.

**Total foundry tests:** ~833 (was ~821;
+12 new).
**Total project tests:** 3363 (was 3351;
+12 new).

**3 test-discovered bugs fixed** during
this phase:

1. **`PlanExecutionResult.stepResults`
   was too restrictive** (required
   `isNotEmpty()`). The invalid plan
   case has no executed steps; the
   empty map is the correct result.
   Fix: removed the `isNotEmpty()`
   check; the empty map is now
   allowed.
2. **Executor was adding
   `AwaitingApproval` / `Denied` to
   `stepResults`**, but these are
   triggers for the plan's status,
   not executed step results. Fix:
   the executor does NOT add
   `AwaitingApproval` / `Denied` /
   `Failed` to `stepResults`; the
   map only contains `Executed`
   results.
3. **Test used `ReadOnly` authority
   for `InstallDistro` step, which
   produces `Denied` (not
   `RequiresApproval`)**. The
   `ReadOnly` scope denies any kind
   not in the safe set. Fix: test
   uses `Restricted` authority (which
   allows specific kinds + requires
   approval for others).

---

## Phase 86 closure

**The Foundry's AI Operator foundation
is COMPLETE.** The 4-phase arc is now:

```
Phase 83: OperatorIntent
  ↓ typed intent
Phase 84: OperatorPlan
  ↓ multi-step plan
Phase 85: OperatorAuditLog
  ↓ immutable record
Phase 86: OperatorPlanExecutor
  ↓ orchestrator (this phase)
```

The AI Operator is the **typed
representation of the AI as system
operator** (per the master vision
section 8). The operator can:

- Issue typed intents (Phase 83).
- Convert intents to multi-step
  plans (Phase 84).
- Validate the plan (Phase 84's
  validator).
- Validate each step against the
  authority (Phase 83's validator).
- Execute the plan step by step
  (Phase 86's executor).
- Log every action to the audit log
  (Phase 85's log).

The executor is the **typed
orchestrator** that ties all 4
phases together. Without the
executor, the 4 phases are typed
data; the executor is the
**execution logic**.

---

## What's next

The next concrete deliverable is up to
the user. The remaining work:

### AI Operator (next concrete)

The AI Operator is now a **complete
4-phase arc**. The next step is the
**production integration**:
- The production `OperatorPlanExecutor`
  integrates with the
  `AndroidProcessLauncher` (Phase 82) to
  actually launch the processes.
- The production executor logs to a
  persistent audit log (a future
  Phase 7+ increment).

### Universal Execution Engine (next concrete)

- **Phase 87 — Sandbox Application**
  (the integration that takes a
  `SandboxPolicy` + a `LaunchPlan` and
  applies the bind mounts + the SELinux
  profile + the resource limits BEFORE
  launching the process).
- **Phase 88 — CriticalE2E with real
  AndroidProcessLauncher** (replace the
  InMemoryProcessLauncher in the
  Phase 71 / Phase 77 E2E tests with
  the real AndroidProcessLauncher).

### Elysium Linux (next concrete)

- **Phase 73 fourth half — Minimal rootfs
  + Mesa/Turnip/Box64/FEX/Wine
  integration** (the actual binary;
  reproducible build on a Linux build
  server with ARM64 cross-compilation).
- **Phase 72 — Capsule installer UI**
  (Compose) for the new Elysium Linux
  distro.

### Foundry program (next concrete)

- **Phase F7 (G9+G10) — Production
  hardening**: threat model + SLOs +
  on-call + runbooks + red team + CVE
  SLA + observability + multi-module
  split (per ADR-0023).

---

## Files

| File | Status | Role |
| --- | --- | --- |
| `app/src/main/java/com/elysium/vanguard/foundry/core/operator/OperatorPlanExecutor.kt` | new | StepResult + PlanExecutionResult + OperatorPlanExecutor + InMemoryOperatorPlanExecutor + ExecutionId + OperatorExecutionError |
| `app/src/test/java/com/elysium/vanguard/foundry/core/operator/OperatorPlanExecutorTest.kt` | new | 12 JVM tests |

---

## The role in the bigger picture

The Operator Plan Executor is the
**orchestrator** of the AI Operator.
The executor ties the 3 previous
phases (intent + plan + audit log)
into a single execution flow.

Per the master vision (section 8):
"La IA debía convertir eso en un plan
declarativo, mostrar cambios y ejecutar
únicamente operaciones autorizadas."

The executor is the **typed execution
of the declarative plan**:
- The AI agent converts the natural-
  language intent to a typed plan.
- The executor validates the plan.
- The executor validates each step
  against the authority.
- The executor executes each step.
- The executor logs every action to
  the audit log.

The executor is the **typed enforcement
of the AI authority boundary** in
production: the AI agent's intents are
validated, approved, and executed; the
audit log records every action. The
human user can review the plan +
approve / deny + monitor the
execution + audit the actions.
