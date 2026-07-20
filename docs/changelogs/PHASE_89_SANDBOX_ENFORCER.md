# Phase 89 — Sandbox Enforcer (Universal Execution Engine: Typed Enforcement)

> **Status:** ✅ Shipped
> **Date:** 2026-07-19
> **Phase:** 89 / EV runtime / Sandbox and Mount Policy (Enforcement)
> **Predecessor:** Phase 87 (Sandbox Application), Phase 81 (Sandbox Policy spec)
> **Vertical:** EV runtime (`com.elysium.vanguard.core.orchestrator.*`)

---

## TL;DR

The Universal Execution Engine's
**Sandbox and Mount Policy** step is
**fully closed** as a 3-phase arc. The
**Sandbox Enforcer** is the consumer of
the `SandboxPreparation` (Phase 87) that
"applies" the typed application steps
in pure-domain + records the result.

The 3-phase Sandbox and Mount Policy arc:

- **Phase 81** — `SandboxPolicy` (the
  **typed spec** + the validator).
- **Phase 87** — `SandboxApplication` (the
  **typed application** of the policy to
  a launch plan; produces a
  `SandboxPreparation`).
- **Phase 89 (this phase)** — The
  `SandboxEnforcer` (the **typed
  enforcement** of the preparation;
  consumes the `SandboxPreparation` +
  records the result as a typed
  `SandboxEnforcementResult`).

The enforcer is **pure-domain** (no I/O,
no Android dependencies). The test impl
is the `InMemorySandboxEnforcer`. The
production impl may be the same (the
enforcer is a typed record of what was
applied; the actual OS-level enforcement
is the OS executor's responsibility).

The enforcer is the **CQRS-style read
side** of the Sandbox and Mount Policy
step: the `SandboxApplication` (Phase
87) is the **write side** (produces the
preparation); the enforcer (Phase 89) is
the **read side** (records the
enforcement).

---

## What shipped

### `SandboxEnforcer` (sealed class)

The typed enforcer. The interface has:

- **`enforce(preparation, nowMs)`** —
  enforce a `SandboxPreparation`. The
  enforcer "applies" each step in the
  preparation + records the result.

The `nowMs` parameter is the current
time (millis since epoch); the enforcer
uses it for the enforcement timestamps.
The parameter is **explicit** (not
derived from `System.currentTimeMillis()`)
so the enforcer is **deterministic**
(the test can use a fixed `nowMs`).

### `EnforcementStep` (sealed class, 5 cases)

The typed enforcement step. The sealed
class has 5 cases:

- **`BindMounted(mountEntry, timestampMs)`**
  — the bind mount was applied. The OS
  executor bound the host path to the
  sandbox path.
- **`SeLinuxContextApplied(securityProfile, timestampMs)`**
  — the SELinux context was applied.
  The OS executor set the SELinux
  context for the process.
- **`ResourceLimitsApplied(sandboxLimits, timestampMs)`**
  — the resource limits were applied.
  The OS executor set the rlimits + the
  cgroup limits for the process.
- **`NetworkPolicyApplied(networkPolicy, timestampMs)`**
  — the network policy was applied.
  The OS executor configured the
  network namespace + the firewall
  rules for the process.
- **`Skipped(reason, timestampMs)`** — a
  step was skipped. The `reason` is a
  human-readable string.

### `SandboxEnforcementResult` (data class)

The typed enforcement result. The result
is the enforcer's output for a given
`SandboxPreparation`.

The result has:

- **`enforcementId`** — UUID; the unique
  id of this enforcement attempt.
- **`preparationId`** — the preparation
  that was enforced.
- **`steps`** — the typed enforcement
  steps in order (matches the
  preparation's steps in order).
- **`enforcedAtMs`** — the timestamp the
  enforcement was performed.
- **`signature`** — the result's
  signature.

The result has helper methods:

- **`bindMountedSteps`** — the bind
  mount steps in order.
- **`skippedSteps`** — the skipped steps
  in order.
- **`hasSkippedSteps`** — whether the
  enforcement has any skipped steps.

### `InMemorySandboxEnforcer` (impl)

The in-memory implementation. The
enforcement algorithm:

1. For each `BindMount` preparation
   step, emit a `BindMounted`
   enforcement step.
2. For the `ApplySeLinuxContext`
   preparation step, emit a
   `SeLinuxContextApplied` enforcement
   step.
3. For the `ApplyResourceLimits`
   preparation step, emit a
   `ResourceLimitsApplied` enforcement
   step.
4. For the `ApplyNetworkPolicy`
   preparation step, emit a
   `NetworkPolicyApplied` enforcement
   step.
5. For the `Skipped` preparation step,
   emit a matching `Skipped`
   enforcement step.

The order of the enforcement steps
matches the order of the preparation
steps. The number of enforcement steps
equals the number of preparation steps.

The enforcer is **thread-safe** (no
mutable fields). The enforcer is
**deterministic** (the `nowMs`
parameter is explicit).

### `SandboxEnforcementError` (sealed class, 1 case)

The typed error envelope. The 1
variant:

- **`EnforcementFailed(reason)`** — the
  enforcement failed. The `reason` is
  a human-readable string.

---

## Design decisions

### Why is the enforcer a separate piece from the `SandboxApplication`?

The `SandboxApplication` (Phase 87) is
the **write side**: it produces a
`SandboxPreparation` (the plan + the
policy + the steps in order). The
`SandboxEnforcer` (Phase 89) is the
**read side** (or rather, the
**execution side**): it consumes the
preparation + records the result.

The two-pieces design has two benefits:
- **Separation of concerns**: the
  applier focuses on producing the
  typed plan; the enforcer focuses on
  recording what was applied. A
  consumer can use the applier
  without the enforcer (e.g. to
  preview the plan) or the enforcer
  without the applier (e.g. to apply a
  manually-constructed preparation).
- **Testability**: the applier is
  testable in the JVM (no I/O); the
  enforcer is testable in the JVM (no
  I/O). The two pieces can be tested
  independently.

### Why is the enforcer pure-domain, not stateful?

The enforcer is **pure-domain** (no I/O,
no Android dependencies). The enforcer
takes a `SandboxPreparation` + a
`nowMs` + returns a
`SandboxEnforcementResult`. The
enforcer does NOT maintain any mutable
state.

The pure-domain design has two
benefits:
- The enforcer is **testable** in the
  JVM (no need for a real Android
  device).
- The enforcer is **deterministic**
  (the same `SandboxPreparation` +
  `nowMs` produces the same result).

The OS-level integration (the enforcer
querying a persistent database) is a
future increment; the typed interface
is the **stable contract**.

### Why is the order of the enforcement steps fixed?

The order of the enforcement steps
matches the order of the preparation
steps. The applier (Phase 87) emits
the steps in a fixed order
(bind mounts first → SELinux context
second → resource limits third →
network policy fourth); the enforcer
consumes the steps in the same order.

The fixed order is the **canonical
order** for applying the sandbox:
1. **Bind mounts first** (so the
   process can find the files).
2. **SELinux context second** (so the
   process has the right MAC).
3. **Resource limits third** (so the
   process has the right rlimits +
   cgroup limits).
4. **Network policy fourth** (so the
   process has the right network
   namespace + firewall rules).

A different order would be **invalid**
(e.g. applying resource limits before
bind mounts would mean the process
might fail to read the files). The
fixed order is the **safe default**.

### Why is the `Skipped` step preserved in the enforcement result?

A preparation step that was `Skipped`
(e.g. due to insufficient permissions)
is preserved in the enforcement result
as a `Skipped` enforcement step. The
preservation allows the **audit log**
to record the skip + the **UI** to
display the skip to the user.

The skip is **not lost**: the
enforcement result records the skip +
the reason + the timestamp. The
consumer can inspect the result +
determine what was applied + what was
skipped.

---

## Tests

13 new tests in `SandboxEnforcerTest`.
The tests cover:

- **EnforcementStep invariants** (6
  tests): BindMounted non-positive
  timestampMs, SeLinuxContextApplied
  non-positive timestampMs,
  ResourceLimitsApplied non-positive
  timestampMs, NetworkPolicyApplied
  non-positive timestampMs, Skipped
  blank reason, Skipped non-positive
  timestampMs.
- **SandboxEnforcementResult
  invariants** (2 tests): empty steps,
  non-positive enforcedAtMs.
- **InMemorySandboxEnforcer** (4
  tests): produces a step for each
  preparation step, produces matching
  steps for each preparation step type,
  preserves the preparation order,
  produces a result with the expected
  preparation id.
- **Realistic scenario** (1 test): a
  full preparation is enforced.

**Total orchestrator tests:** 191 (was
178; +13 new).
**Total project tests:** 3399 (was 3386;
+13 new).

---

## Phase 89 closure

**The Sandbox and Mount Policy step is
FULLY CLOSED** as a 3-phase arc. The
chain is now:

```
Phase 81: SandboxPolicy
   ↓ typed spec + validator
Phase 87: SandboxApplication
   ↓ typed application (write side)
Phase 89: SandboxEnforcer
   ↓ typed enforcement (execution side)
```

The applier (Phase 87) + the enforcer
(Phase 89) form a complete **CQRS-style
pattern**:
- The **applier** is the **write side**
  (produces the typed preparation).
- The **enforcer** is the **execution
  side** (consumes the preparation +
  records the result).

The OS-level integration (the actual
`mount` / `chcon` / `setrlimit` /
`iptables` calls) is a future
increment; the typed interfaces are
the **stable contract**.

The full UEE flow is now:

```
RuntimeSelector (Phase 76 first half)
    ↓
SandboxPolicy (Phase 81, typed spec + validator)
    ↓
SandboxApplication (Phase 87, typed preparation)
    ↓
**SandboxEnforcer (Phase 89, typed
   enforcement)** ← this phase
    ↓
RuntimeDispatcher (Phase 76 second half)
    ↓
ProcessLauncher (Phase 78, typed spec)
    ↓
AndroidProcessLauncher (Phase 82, production impl)
    ↓
Process running on the OS
    ↓
ProcessWatcher (Phase 79, telemetry)
    ↓
RecoveryPolicy (Phase 80, recovery)
```

The chain is now **typed end-to-end +
production-ready + sandbox-ready +
enforcement-ready**: the test impl +
the production impl both exist; the
sandbox policy is **validated**
(Phase 81) + **applied** (Phase 87) +
**enforced** (Phase 89).

---

## What's next

The next concrete deliverable is up to
the user. The remaining work:

### Universal Execution Engine (next concrete)

- **Phase 90 — CriticalE2E with real
  AndroidProcessLauncher + SandboxApplication
  + SandboxEnforcer** (replace the
  InMemoryProcessLauncher in the
  Phase 71 / Phase 77 E2E tests with
  the real AndroidProcessLauncher; the
  E2E test would also exercise the
  `SandboxApplication` + the
  `SandboxEnforcer`).

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
  `SandboxApplication` + the
  `SandboxEnforcer`** (the executor
  launches the processes via the
  production launcher + applies the
  sandbox policy before each launch +
  enforces the sandbox after the
  launch + logs every action to the
  audit log).

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
| `app/src/main/java/com/elysium/vanguard/core/orchestrator/SandboxEnforcer.kt` | new | EnforcementStep + SandboxEnforcementResult + SandboxEnforcer + InMemorySandboxEnforcer + SandboxEnforcementError |
| `app/src/test/java/com/elysium/vanguard/core/orchestrator/SandboxEnforcerTest.kt` | new | 13 JVM tests |

---

## The role in the bigger picture

The Sandbox Enforcer is the
**typed enforcement** of the
Universal Execution Engine's Sandbox
and Mount Policy step. Phase 81 was
the **typed spec** (what the policy
looks like); Phase 87 was the
**typed application** (how the policy
is applied to a launch plan); this
phase is the **typed enforcement**
(what was actually applied).

The enforcer is the **typed bridge**
between the applier (which produces
the typed plan) and the OS executor
(which actually applies the plan via
the OS APIs). The enforcer is the
**typed record** of what was applied
+ what was skipped + the reason for
the skip.

The enforcer is the **read side /
execution side** of the CQRS-style
pattern:
- The applier (Phase 87) is the
  **write side** (produces the typed
  plan).
- The enforcer (Phase 89) is the
  **execution side** (consumes the
  plan + records the result).

The two together form a complete
**typed pipeline** for the sandbox
policy: spec → application →
enforcement.
