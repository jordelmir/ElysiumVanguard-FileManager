# Phase 88 — Operator Plan Status Tracker (Foundry / AI Operator: Query Side)

> **Status:** ✅ Shipped
> **Date:** 2026-07-19
> **Phase:** 88 / Foundry / AI Operator
> **Predecessor:** Phase 85 (Operator Audit Log), Phase 86 (Operator Plan Executor), Phase 87 (Sandbox Application)
> **Vertical:** Foundry (`com.elysium.vanguard.foundry.core.operator.*`)

---

## TL;DR

The Foundry's **AI Operator** is now
**fully closed** as a 5-phase arc. The
**Operator Plan Status Tracker** is the
read side of the operator: a typed
utility that reads the `OperatorAuditLog`
(Phase 85) and computes the current
`PlanStatus` of a plan.

The arc:

- **Phase 83** — `OperatorIntent` (the
  typed intent).
- **Phase 84** — `OperatorPlan` (the
  multi-step plan).
- **Phase 85** — `OperatorAuditLog`
  (the immutable record of every
  action).
- **Phase 86** — `OperatorPlanExecutor`
  (the orchestrator; the **write
  side** of the operator).
- **Phase 88 (this phase)** — The
  tracker (the **query side** of the
  operator).

The tracker's algorithm:
- Read the audit log entries for the
  plan (in chronological order).
- If the most recent entry is a
  **terminal action** (`PlanExecutionCompleted`
  / `PlanExecutionFailed` / `PlanDenied`),
  return the corresponding status.
- If the most recent entry is a
  **non-terminal action** (e.g. a
  `PlanStepCompleted`), check if the
  plan has started (any of
  `PlanExecutionStarted` / `PlanStep*`):
  - If started → `Running`.
  - If not started → `Pending`.

The tracker is **pure-domain** (no I/O,
no Android dependencies). The test impl
is the
`InMemoryOperatorPlanStatusTracker`. The
production impl may be the same (the
tracker is stateless + pure; the same
impl is used in production).

---

## What shipped

### `OperatorPlanStatusTracker` (sealed class)

The typed query utility. The interface
has:

- **`currentStatus(planId, auditLog)`**
  — compute the current `PlanStatus`
  of a plan. The tracker reads the
  audit log + returns the typed
  status.

### `InMemoryOperatorPlanStatusTracker` (impl)

The in-memory implementation. The
tracker is **thread-safe** (no mutable
fields). The tracker is **stateless**
(the same impl is used in tests +
production).

The algorithm:
1. Read the audit log entries for
   the plan (in chronological order).
2. If the log has no entries for the
   plan → `Pending`.
3. Find the most recent **terminal
   action** (`PlanExecutionCompleted` /
   `PlanExecutionFailed` /
   `PlanDenied`).
4. If a terminal action is found,
   return the corresponding status.
5. If no terminal action is found,
   check if the plan has started
   (any of `PlanExecutionStarted` /
   `PlanStep*`):
   - If started → `Running`.
   - If not started → `Pending`.

### `OperatorPlanStatusError` (sealed class, 1 case)

The typed error envelope. The 1
variant:

- **`UnknownPlanId(planId)`** — the
  plan id is unknown. The tracker
  returns `Pending` for unknown plan
  ids (this is the conservative
  default).

---

## Design decisions

### Why is the tracker a sealed class, not an interface?

The `OperatorPlanStatusTracker` is a
sealed class with a single in-memory
impl. The sealed class captures the
**abstract behavior** (the platform's
typed query contract); the in-memory
impl is the test + production default.
A future Phase 7+ increment may add a
`PersistentOperatorPlanStatusTracker` (a
production impl backed by a database);
the sealed class allows the consumer to
pattern-match on the impl.

### Why is the algorithm based on the most recent terminal action?

The **most recent terminal action** is
the **canonical state** of the plan. A
terminal action (`PlanExecutionCompleted`
/ `PlanExecutionFailed` / `PlanDenied`)
is **immutable**: once the action is
logged, the plan is in the corresponding
state forever (a new terminal action can
supersede it; a new non-terminal action
is just an intermediate step).

The algorithm:
- **Find the most recent terminal
  action.** If found, return the
  corresponding status. This is the
  **final state** of the plan.
- **If no terminal action is found,
  check if the plan has started.** A
  started plan is `Running`; a
  not-started plan is `Pending`.

### Why is the algorithm not based on the most recent entry of any kind?

The most recent entry of any kind is
not the canonical state. Example: a
plan that has executed 3 steps + a
`PlanStepCompleted` is in the
**Running** state, not the
**Completed** state (the plan execution
has not completed yet; only the last
step has completed).

The algorithm must distinguish between
**terminal actions** (the plan's final
state) and **intermediate actions**
(a step in the plan's execution). The
canonical state is the most recent
**terminal** action.

### Why is `Pending` the default for unknown plan ids?

An unknown plan id has no entries in
the audit log. The tracker returns
`Pending` (the conservative default)
rather than throwing an exception.

The `OperatorPlanStatusError.UnknownPlanId`
variant is available for callers that
want to **explicitly** check whether the
plan id is known (a future increment
may add a `isKnown(planId, auditLog)` method).

### Why is the tracker pure-domain, not stateful?

The tracker is **pure-domain** (no I/O,
no Android dependencies). The tracker
takes the audit log as input + returns
the typed status. The tracker does NOT
maintain any mutable state.

The pure-domain design has two benefits:
- The tracker is **testable** in the
  JVM (no need for a real Android
  device).
- The tracker is **deterministic** (the
  same `auditLog` produces the same
  `PlanStatus`).

The OS-level integration (the tracker
querying a persistent database) is a
future increment; the typed interface
is the **stable contract**.

---

## Tests

10 new tests in
`OperatorPlanStatusTrackerTest`. The
tests cover:

- **Empty log** (1 test): the tracker
  returns `Pending` for an empty log.
- **PlanCreated only** (1 test): the
  tracker returns `Pending` for a log
  with a `PlanCreated` entry.
- **PlanCreated + PlanApproved** (1
  test): the tracker returns `Pending`
  for a log with `PlanCreated` +
  `PlanApproved` entries.
- **PlanExecutionStarted** (1 test): the
  tracker returns `Running` for a log
  with a `PlanExecutionStarted` entry.
- **PlanExecutionCompleted** (1 test):
  the tracker returns `Completed` for a
  log with a `PlanExecutionCompleted`
  entry.
- **PlanExecutionFailed** (1 test): the
  tracker returns `Failed` with the
  reason for a log with a
  `PlanExecutionFailed` entry.
- **PlanDenied** (1 test): the tracker
  returns `Failed` with the denied
  reason for a log with a `PlanDenied`
  entry.
- **PlanCancelled** (1 test): the
  tracker returns `Pending` for a log
  with a `PlanCancelled` entry (the
  default case).
- **Multiple plans in the same log**
  (1 test): the tracker returns the
  correct status for each plan in the
  same log.
- **Realistic scenario** (1 test): the
  tracker returns the correct status
  as the plan lifecycle progresses
  (Pending → Pending → Running →
  Running → Running → Completed).

**Total foundry tests:** ~843 (was ~833;
+10 new).
**Total project tests:** 3386 (was 3376;
+10 new).

**1 test-discovered bug fixed** during
this phase:

1. **Algorithm was too simple** (based
   on the most recent entry of any kind,
   not the most recent terminal action).
   Symptom: a `PlanStepCompleted` after
   `PlanExecutionStarted` was returning
   `Pending` instead of `Running`. Fix:
   the algorithm now finds the most
   recent **terminal action** (the
   canonical state) + checks if the
   plan has started (for the
   `Running` / `Pending` distinction).

---

## Phase 88 closure

**The Foundry's AI Operator 5-phase arc
is COMPLETE.** The 5 phases are now:

```
Phase 83: OperatorIntent
  ↓ typed intent
Phase 84: OperatorPlan
  ↓ multi-step plan
Phase 85: OperatorAuditLog
  ↓ immutable record
Phase 86: OperatorPlanExecutor
  ↓ orchestrator (write side)
Phase 88: OperatorPlanStatusTracker
  ↓ query side (this phase)
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
  (Phase 86's executor; the
  **write side**).
- Log every action to the audit log
  (Phase 85's log).
- **Query the current status of a
  plan** (Phase 88's tracker; the
  **query side**).

The tracker is the **typed visibility**
of the operator's state. A consumer
(e.g. the UI, the security auditor, the
analytics pipeline) can ask "what is the
current status of plan X?" + get a
typed `PlanStatus` answer.

The executor (Phase 86) is the
**write side**: the executor logs the
events + returns the
`PlanExecutionResult`. The tracker
(Phase 88) is the **query side**: a
consumer can ask "what is the current
status of plan X?".

The two together form a complete
**CQRS** (Command-Query Responsibility
Segregation) pattern: the executor
is the **command** (writes); the
tracker is the **query** (reads). The
audit log (Phase 85) is the **store**.

---

## What's next

The next concrete deliverable is up to
the user. The remaining work:

### Universal Execution Engine (next concrete)

- **Phase 89 — CriticalE2E with real
  AndroidProcessLauncher + SandboxApplication**
  (replace the InMemoryProcessLauncher
  in the Phase 71 / Phase 77 E2E tests
  with the real AndroidProcessLauncher;
  the E2E test would also exercise the
  `SandboxApplication`).

### Elysium Linux (next concrete)

- **Phase 73 fourth half — Minimal rootfs
  + Mesa/Turnip/Box64/FEX/Wine
  integration** (the actual binary;
  reproducible build on a Linux build
  server with ARM64 cross-compilation).
- **Phase 72 — Capsule installer UI**
  (Compose) for the new Elysium Linux
  distro.

### AI Operator (production integration)

- **Production wiring of
  `OperatorPlanExecutor` with
  `AndroidProcessLauncher` + the
  `SandboxApplication`** (the executor
  launches the processes via the
  production launcher + applies the
  sandbox policy before each launch;
  the production wiring also uses the
  `OperatorPlanStatusTracker` to
  periodically report the plan's
  status to the UI).

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
| `app/src/main/java/com/elysium/vanguard/foundry/core/operator/OperatorPlanStatusTracker.kt` | new | OperatorPlanStatusTracker + InMemoryOperatorPlanStatusTracker + OperatorPlanStatusError |
| `app/src/test/java/com/elysium/vanguard/foundry/core/operator/OperatorPlanStatusTrackerTest.kt` | new | 10 JVM tests |

---

## The role in the bigger picture

The Operator Plan Status Tracker is the
**query side** of the AI Operator. The
tracker reads the audit log + returns
the current `PlanStatus` of a plan.

Per the master vision (section 8):
"La IA debía convertir eso en un plan
declarativo, mostrar cambios y ejecutar
únicamente operaciones autorizadas."

The tracker answers the consumer's
question: **"what is the current
status of plan X?"** The consumer can
be the UI (showing the user the plan's
status), the security auditor
(reviewing the plan's lifecycle), or
the analytics pipeline (tracking the
plan's metrics).

The tracker is the **typed visibility**
of the operator's state. Without the
tracker, the consumer would have to
manually inspect the audit log to
determine the plan's status (which is
error-prone + requires knowing the
audit log's event types). The tracker
is the **typed query** the consumer
needs.

The tracker is also the **preparation
for the UI**: the UI would call
`tracker.currentStatus(planId, log)`
to display the plan's status to the
user. The tracker is the **UI's
data source** for the plan's status.
