# Phase 85 — Operator Audit Log (Foundry / AI Operator: Immutable Action Record)

> **Status:** ✅ Shipped
> **Date:** 2026-07-19
> **Phase:** 85 / Foundry / AI Operator
> **Predecessor:** Phase 84 (Operator Plan), Phase 83 (AI Operator Intent)
> **Vertical:** Foundry (`com.elysium.vanguard.foundry.core.operator.*`)

---

## TL;DR

The Foundry's **Operator Audit Log** is
operational. The typed immutable record
of the AI Operator's actions.

Per the master vision (section 9):
"Auditoría de procesos. Registro
inmutable de operaciones críticas."

The audit log is the **immutable
append-only log** of every operator
action. The log captures:
- Every intent the AI agent issues.
- Every plan the AI agent creates.
- Every plan validation.
- Every human approval / denial.
- Every plan execution start /
  completion / failure.
- Every step start / completion /
  failure.
- Every plan cancellation.

The log is **6 primitives**:

1. **`OperatorAction`** (sealed class,
   12 cases) — the typed action the
   operator performs.
2. **`OperatorActionKind`** (enum, 12
   values) — the classification of the
   action.
3. **`OperatorAuditEntry`** (data class) —
   a single entry in the audit log.
4. **`OperatorAuditLog`** (sealed class)
   + `InMemoryOperatorAuditLog` (impl) —
   the append-only log.
5. **`AuditEntryId`** (UUID value class) —
   the typed id of an audit entry.
6. **`OperatorAuditError`** (sealed class,
   1 case) — `InvalidAuditEntryIdFormat`.

---

## What shipped

### `OperatorAction` (sealed class, 12 cases)

The typed action the operator performs.
The sealed class has 12 cases:

- **`IntentIssued(intentId, agentId, intentKind, timestampMs)`**
  — an intent was issued.
- **`PlanCreated(planId, agentId, stepCount, timestampMs)`**
  — a plan was created.
- **`PlanValidated(planId, isValid, errorCount, timestampMs)`**
  — a plan was validated.
- **`PlanApproved(planId, approverId, timestampMs)`**
  — a plan was approved by a human.
- **`PlanDenied(planId, approverId, reason, timestampMs)`**
  — a plan was denied by a human.
- **`PlanExecutionStarted(planId, timestampMs)`**
  — a plan started executing.
- **`PlanStepStarted(planId, stepOrder, intentId, timestampMs)`**
  — a step started.
- **`PlanStepCompleted(planId, stepOrder, exitCode, timestampMs)`**
  — a step completed.
- **`PlanStepFailed(planId, stepOrder, reason, timestampMs)`**
  — a step failed.
- **`PlanExecutionCompleted(planId, timestampMs)`**
  — a plan completed.
- **`PlanExecutionFailed(planId, reason, timestampMs)`**
  — a plan failed.
- **`PlanCancelled(planId, cancellerId, reason, timestampMs)`**
  — a plan was cancelled.

### `OperatorActionKind` (enum, 12 values)

The typed action kind. The 12 values
mirror the 12 `OperatorAction` cases.

### `OperatorAuditEntry` (data class)

A single entry in the audit log. The
entry has:

- **`entryId`** — UUID.
- **`action`** — the typed action.
- **`signature`** — the entry's
  signature.

### `OperatorAuditLog` (sealed class)

The typed log. The interface has:

- **`entries`** — the list of all
  entries (in append order).
- **`append(entry)`** — append a new
  entry to the log (the log is
  **append-only**; existing entries are
  never modified).
- **`entriesForPlan(planId)`** — get
  the entries for a specific plan.
- **`entriesForAgent(agentId)`** — get
  the entries for a specific agent.
- **`entriesByActionKind(kind)`** — get
  the entries of a specific action kind.
- **`size`** — the number of entries.

### `InMemoryOperatorAuditLog` (impl)

The in-memory implementation. The log
is **thread-safe** (the underlying list
is a `CopyOnWriteArrayList` for safe
iteration during query + safe mutation
during `append`).

The impl is **stateless** (no mutable
fields beyond the entries list); the
same impl is used in tests + production.

### `AuditEntryId` (UUID value class)

The typed id of an audit entry. The id
is a UUID (per the Foundry id
convention).

### `OperatorAuditError` (sealed class, 1 case)

The typed error envelope. The 1 variant:

- **`InvalidAuditEntryIdFormat(rawInput, parseFailure)`**
  — the audit entry id string was not
  a valid UUID.

---

## Design decisions

### Why an append-only log, not a mutable table?

A mutable table would allow the AI
agent to **modify or delete past
actions**, which is a security
violation (the agent could rewrite
history). An append-only log is the
**canonical** representation of an
immutable audit trail: once an entry
is added, it can never be modified or
deleted.

The append-only pattern is the
**standard** for audit logs in
security-sensitive systems (Linux
auditd, AWS CloudTrail, Kubernetes
audit log, etc.).

### Why a sealed class with 12 cases, not an enum?

A sealed class is **data-bearing**: each
case carries typed data (the `PlanId`,
the `stepOrder`, the `reason`, etc.).
An enum would require a separate
discriminator field + a `Map<String, Any>`
payload, which loses the type safety.

The 12 cases reflect the **12 distinct
events** the operator logs. The cases
are not arbitrary: each case corresponds
to a **typed event** in the operator's
lifecycle.

### Why is the signature required on every entry?

The signature binds the entry to the
actor (the agent or the human reviewer).
A signature is the **non-repudiation**
mechanism: an agent cannot deny issuing
an intent if the intent is signed.

The signature is a `Signature` (the
Foundry's typed signature; per Phase F5
the `RoyaltyContract` uses the same
pattern). The signature is verified by
the audit log consumer (a future
increment; the verification is the
responsibility of the consumer, not the
log).

### Why are helper extensions used to extract `planId` / `agentId`?

The `entriesForPlan` and `entriesForAgent`
methods need to extract the `planId` /
`agentId` from each action. The
extraction is **not** a field on
`OperatorAction` (the field is
`val planId: PlanId` for some cases,
`val approverId: UserId` for others,
etc.). The helper extension
(`planIdOrNull()` / `agentIdOrNull()`)
provides a uniform extraction.

A `when` expression on the sealed class
is **exhaustive**: the compiler verifies
that every case is handled. The helper
extension is the **type-safe** way to
extract the optional values.

---

## Tests

16 new tests in `OperatorAuditLogTest`.
The tests cover:

- **OperatorAction invariants** (8
  tests): IntentIssued non-positive
  timestampMs, PlanCreated non-positive
  stepCount, PlanValidated negative
  errorCount, PlanDenied blank reason,
  PlanStepStarted non-positive stepOrder,
  PlanStepFailed blank reason,
  PlanExecutionFailed blank reason,
  PlanCancelled blank reason.
- **InMemoryOperatorAuditLog** (7 tests):
  append adds an entry to the log, append
  preserves the append order, entriesForPlan
  returns only entries with the plan id,
  entriesForPlan returns empty for an
  unknown plan, entriesForAgent returns
  only entries with the agent id,
  entriesByActionKind returns only entries
  of the kind, size returns the number of
  entries.
- **Realistic scenario** (1 test): the
  full lifecycle of a plan (intent issued,
  plan created, plan validated, plan
  approved, plan execution started, step
  started, step completed, plan
  execution completed) is logged in order.

**Total foundry tests:** ~821 (was ~805;
+16 new).
**Total project tests:** 3351 (was 3335;
+16 new).

---

## Phase 85 closure

**The Foundry's AI Operator audit log
foundation is operational.** The
chain is now:

```
AI Agent (the user-supplied intent)
    ↓
OperatorPlan (the multi-step plan)
    ↓
OperatorPlanValidator (the intra-plan
   invariants)
    ↓
OperatorAuthority + OperatorIntentValidator
   (per-step authority check)
    ↓
ValidationResult per step (Allowed /
   RequiresApproval / Denied)
    ↓
Human review (if any step requires
   approval)
    ↓
OperatorAuditLog ← THIS PHASE
   (immutable record of every action)
    ↓
OperatorPlanExecutor (Phase 86, future;
   the orchestrator that takes the
   approved plan + executes it step by
   step; logs every action to the
   audit log)
```

The audit log is the **observability
layer** for the AI Operator. The log
captures every action the operator
performs; the consumer (the user, the
security auditor, the analytics
pipeline) can inspect the log to
understand what the operator did.

The next step in the operator chain:

- **Phase 86 — OperatorPlanExecutor**
  (the orchestrator that takes an
  `OperatorPlan` + the operator's
  authority + the human's approval +
  executes the plan step by step;
  tracks the plan's `PlanStatus`; logs
  every action to the `OperatorAuditLog`).

---

## What's next

The next concrete deliverable is up to
the user. The remaining work:

### AI Operator (next concrete)

- **Phase 86 — OperatorPlanExecutor**
  (the orchestrator that takes an
  `OperatorPlan` + the operator's
  authority + the human's approval +
  executes the plan step by step;
  tracks the plan's `PlanStatus`;
  logs every action to the
  `OperatorAuditLog`).

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
| `app/src/main/java/com/elysium/vanguard/foundry/core/operator/OperatorAuditLog.kt` | new | OperatorAction + OperatorActionKind + OperatorAuditEntry + OperatorAuditLog + InMemoryOperatorAuditLog + AuditEntryId + OperatorAuditError |
| `app/src/test/java/com/elysium/vanguard/foundry/core/operator/OperatorAuditLogTest.kt` | new | 16 JVM tests |

---

## The role in the bigger picture

The Operator Audit Log is the
**observability layer** for the AI
Operator. The log captures every
action the operator performs; the
consumer (the user, the security
auditor, the analytics pipeline) can
inspect the log to understand what
the operator did.

Per the master vision (section 9):
"Auditoría de procesos. Registro
inmutable de operaciones críticas."

The Operator Audit Log is the **typed
representation of the audit log**.
The log is:

- **Append-only** (the consumer can add
  entries, but cannot modify or delete
  them).
- **Thread-safe** (multiple consumers
  can append concurrently).
- **Typed** (every entry is a typed
  `OperatorAction` + a signature; the
  type system enforces the schema).
- **Queryable** (the consumer can
  filter by plan id, agent id, or
  action kind).

The Operator Audit Log is the
**preparation for the operator
executor** (Phase 86): the executor
will log every step it performs; the
audit log is the **observability
record** of the executor's actions.
