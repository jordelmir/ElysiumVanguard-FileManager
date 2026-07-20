# Phase 84 — Operator Plan (Foundry / AI Operator: Multi-Step Plans)

> **Status:** ✅ Shipped
> **Date:** 2026-07-19
> **Phase:** 84 / Foundry / AI Operator
> **Predecessor:** Phase 83 (AI Operator Intent, single-step)
> **Vertical:** Foundry (`com.elysium.vanguard.foundry.core.operator.*`)

---

## TL;DR

The Foundry's **AI Operator Plan** is
operational. The typed representation of a
**multi-step plan** that the AI agent can
issue.

Per the master vision (section 8):
"La IA debía convertir eso en un plan
declarativo, mostrar cambios y ejecutar
únicamente operaciones autorizadas."

Example: "Instala Blender, configura
aceleración Vulkan y crea un acceso
directo." The AI converts this to a
3-step plan:
1. `InstallDistro("com.elysium.linux")`
2. `LaunchCapsule("com.elysium.blender", runtime="linux")`
3. `CreateWorkspace("workspaces/blender", sandboxProfile="standard")`

The `OperatorPlan` is the typed
representation of the multi-step plan.
Each step is a typed `OperatorIntent`
(per Phase 83). The plan is **pure-domain**
(no I/O, no Android dependencies).

The plan is **5 primitives**:

1. **`PlanStep`** — a single step in the
   plan (order + description + intent).
2. **`OperatorPlan`** — the multi-step plan
   (a list of steps + the agent + the
   timestamp + the signature).
3. **`PlanStatus`** — the typed plan status
   (the lifecycle state).
4. **`PlanValidationResult`** — the
   validator's output (the plan is valid /
   the plan has errors).
5. **`OperatorPlanValidator`** + impl —
   the validator.

Plus:

- **`PlanId`** (UUID value class) — the
  typed id of an operator plan.
- **`OperatorPlanError`** (sealed class, 1
  case) — `InvalidPlanIdFormat`.

---

## What shipped

### `PlanStep` (data class)

A single step in an `OperatorPlan`. The
step is **immutable** (a data class; no
setters). A new step is a new value.

The step has:

- **`order: Int`** — the step's order in the
  plan (1-based; the first step is
  `order = 1`).
- **`description: String`** — a human-
  readable description of the step.
- **`intent: OperatorIntent`** — the typed
  intent the step performs (per Phase 83).

### `OperatorPlan` (data class)

The typed operator plan. The plan is
**immutable** (a data class; no setters).
A new plan is a new value.

The plan has:

- **`planId: PlanId`** — UUID.
- **`agentId: UserId`** — the AI agent that
  issued the plan.
- **`steps: List<PlanStep>`** — the list of
  steps (in order of `order`).
- **`createdAtMs: Long`** — the timestamp
  the plan was created.
- **`signature: Signature`** — the plan's
  signature.

The plan has helper methods:

- **`stepByOrder(order)`** — get a step by
  order. Returns `null` if no step has the
  given order.
- **`firstStep`** — the step with the
  lowest `order`.
- **`stepCount`** — the number of steps.
- **`sortedOrders`** — the orders of all
  steps in sorted order.

### `PlanStatus` (sealed class, 5 cases)

The typed plan status. The status is the
**lifecycle state** of the plan. The
sealed class has 5 cases:

- **`Pending`** — the plan has been
  created but not yet executed.
- **`Running`** — the plan is currently
  executing (at least one step has
  started).
- **`Completed`** — all steps completed
  successfully.
- **`Failed(reason)`** — a step failed; the
  plan stopped. The reason is a human-
  readable string.
- **`Paused(reason)`** — the plan is
  paused (e.g. waiting for human
  approval). The reason is a human-
  readable string.

### `PlanValidationResult` (sealed class, 2 cases)

The validator's output. The sealed class
has 2 cases:

- **`Valid`** — the plan is valid. The
  plan's intra-plan invariants are all
  satisfied.
- **`Invalid(errors)`** — the plan is
  invalid. The errors are a list of
  human-readable strings.

### `OperatorPlanValidator` (sealed class)

The typed validator. The interface has:

- **`validate(plan)`** — validate an
  `OperatorPlan`. Returns a
  `PlanValidationResult`.
- **`isValid(plan)`** — check whether an
  `OperatorPlan` is valid (the convenience
  predicate for the "no errors" case).

### `InMemoryOperatorPlanValidator` (impl)

The in-memory implementation. The
validator enforces 3 rules:

- **Rule 1: Steps' orders are unique.** No
  two steps have the same `order`.
- **Rule 2: Steps' orders are contiguous
  starting from 1.** The orders are
  `1, 2, 3, ..., N` (no gaps).
- **Rule 3: All steps' intents have the
  same `agentId` as the plan.** The agent
  that issued the plan is the agent that
  issued every step.

The validator is **stateless** (no mutable
fields); the same impl is used in tests +
production. The validator is thread-safe.

### `PlanId` (UUID value class)

The typed id of an operator plan. The id
is a UUID (per the Foundry id convention).

### `OperatorPlanError` (sealed class, 1 case)

The typed error envelope. The 1 variant:

- **`InvalidPlanIdFormat(rawInput, parseFailure)`**
  — the plan id string was not a valid
  UUID.

---

## Design decisions

### Why a multi-step plan, not a single-step intent?

A single `OperatorIntent` is a single
operation. A real-world AI task (e.g.
"install Blender + configure Vulkan +
create shortcut") requires **multiple
operations in sequence**. The
`OperatorPlan` is the typed representation
of the multi-step task.

The plan is also the **unit of human
review**: a human reviews the whole plan
before execution, not each intent
individually. The human can approve the
plan, deny the plan, or modify the plan
before execution.

### Why is the plan's `agentId` enforced to match all step intents?

An `OperatorPlan` is a **single AI agent's
declaration of intent**. The agent that
issued the plan is the agent that issued
every step. Allowing multiple agents to
contribute to a plan would create
**accountability gaps** (who is
responsible for the plan's execution?).

The enforcement at the type level (the
`require` check in the validator's
`Rule 3`) prevents the construction of a
plan that violates this invariant. The
multi-agent orchestration (where multiple
AI agents collaborate on a plan) is a
**future increment** (a future Phase 7+)
that would add a `MultiAgentPlan` type
with explicit per-step agent attribution.

### Why are the step orders required to be contiguous (1, 2, 3, ...)?

A plan with non-contiguous orders (e.g.
1, 3, 5) is **ambiguous**: what about
steps 2, 4, 6? Are they implicit?
Skipped? Missing? The contiguity check
forces the plan author to be **explicit**
about the plan's structure.

The contiguity also makes the plan
**deterministic**: the executor can
process the steps in the order of their
`order` field (which is the sorted order
of the orders). The executor does not
need to interpret gaps or skips; the plan
is unambiguous.

### Why is `PlanStatus` a sealed class with 5 cases, not an enum?

A sealed class is **data-bearing** for the
failure cases (`Failed(reason)` +
`Paused(reason)`). The reasons are
human-readable strings that the UI can
display to the user.

An enum would require a separate field for
the reason (a `reason: String?` field
that is `null` for `Pending` / `Running`
/ `Completed`). The sealed class is the
**type-safe** representation: the reason
is non-nullable for `Failed` and
`Paused`, and the type system enforces
this.

---

## Tests

21 new tests in `OperatorPlanTest`. The
tests cover:

- **PlanStep invariants** (3 tests):
  well-formed configuration, non-positive
  order, blank description.
- **OperatorPlan invariants** (8 tests):
  well-formed configuration, empty steps,
  non-positive createdAtMs, stepByOrder
  returns the step for the order,
  stepByOrder returns null for an unknown
  order, firstStep returns the step with
  the lowest order, stepCount returns
  the number of steps, sortedOrders
  returns the orders in sorted order.
- **PlanStatus invariants** (2 tests):
  Failed blank reason, Paused blank
  reason.
- **PlanValidationResult invariants** (1
  test): Invalid empty errors.
- **InMemoryOperatorPlanValidator** (6
  tests): returns Valid for a well-formed
  plan, reports duplicate step orders,
  reports non-contiguous step orders,
  reports steps with mismatched agentId,
  isValid returns true for a valid plan,
  isValid returns false for an invalid
  plan.
- **Realistic scenario** (1 test): the
  "Install Blender" plan from the master
  vision (3 steps: install distro +
  launch capsule + create workspace) is
  a valid plan.

**Total foundry tests:** ~805 (was ~784;
+21 new).
**Total project tests:** 3335 (was 3314;
+21 new).

**2 test-discovered bugs fixed** during
this phase:

1. **Test used `OperatorIntent.CreateSnapshot`
   which doesn't exist** (the Phase 83
   `OperatorIntent` has `CreateWorkspace`
   but not `CreateSnapshot`). Fix:
   replaced `CreateSnapshot` with
   `CreateWorkspace` in the realistic
   scenario.
2. **Test fixture `buildStep()` created
   each step with a different random
   `agentId`**, breaking the validator's
   `Rule 3` (all steps must have the same
   `agentId` as the plan). Fix: the
   `buildStep()` fixture now takes an
   explicit `agentId` parameter; the
   `buildPlan()` fixture propagates the
   plan's `agentId` to each step by
   default.

---

## Phase 84 closure

**The Foundry's AI Operator multi-step
plan foundation is operational.** The
chain is now:

```
AI Agent (the user-supplied intent)
    ↓
OperatorPlan (the multi-step plan)
    ↓
InMemoryOperatorPlanValidator (the
   intra-plan invariants)
    ↓
PlanValidationResult (Valid / Invalid)
    ↓
OperatorAuthority + OperatorIntentValidator
   (per-step authority check, per Phase 83)
    ↓
ValidationResult per step (Allowed /
   RequiresApproval / Denied)
    ↓
Human review (if any step requires
   approval)
    ↓
Execution (step by step)
```

The chain is **typed end-to-end** (the
plan is captured as a typed
`OperatorPlan`; the intra-plan
invariants are enforced at the type
level; each step is validated against
the authority).

The next step in the operator chain:

- **Phase 85 — OperatorPlanExecutor** (the
  orchestrator that takes an
  `OperatorPlan` + the operator's
  authority + the human's approval +
  executes the plan step by step;
  tracks the plan's status; handles
  step failures + restarts).
- **Phase 86 — OperatorAuditLog** (the
  operator's actions are recorded in an
  immutable audit log).

---

## What's next

The next concrete deliverable is up to
the user. The remaining work:

### AI Operator (next concrete)

- **Phase 85 — OperatorPlanExecutor** (the
  orchestrator that takes an
  `OperatorPlan` + the operator's
  authority + the human's approval +
  executes the plan step by step).
- **Phase 86 — OperatorAuditLog** (the
  operator's actions are recorded in an
  immutable audit log).

### Universal Execution Engine (next concrete)

- **Phase 87 — Sandbox Application** (the
  integration that takes a
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
| `app/src/main/java/com/elysium/vanguard/foundry/core/operator/OperatorPlan.kt` | new | PlanStep + OperatorPlan + PlanStatus + PlanValidationResult + OperatorPlanValidator + InMemoryOperatorPlanValidator + PlanId + OperatorPlanError |
| `app/src/test/java/com/elysium/vanguard/foundry/core/operator/OperatorPlanTest.kt` | new | 21 JVM tests |

---

## The role in the bigger picture

The Operator Plan is the **multi-step
plan** primitive for the AI as system
operator (per the master vision section
8). The AI agent converts a natural-
language intent (e.g. "Install Blender,
configure Vulkan, create shortcut") into
a typed multi-step plan; the plan is
validated as a whole; each step is
validated against the agent's authority;
the plan is executed step by step.

The Operator Plan is the **typed
declaration of intent** that the AI
operator uses. Without the Operator
Plan, the AI agent can only issue
single-step intents (one operation at a
time). The Operator Plan is the **unit
of human review** (a human reviews the
whole plan before execution, not each
intent individually).

The Operator Plan is also the
**preparation for the AI orchestrator**
(Phase 85): the orchestrator will take
an `OperatorPlan` + the operator's
authority + the human's approval + execute
the plan step by step. The Operator Plan
is the **input** the orchestrator uses.
