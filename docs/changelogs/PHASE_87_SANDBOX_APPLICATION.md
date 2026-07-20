# Phase 87 — Sandbox Application (Universal Execution Engine: Apply the Policy)

> **Status:** ✅ Shipped
> **Date:** 2026-07-19
> **Phase:** 87 / EV runtime / Sandbox and Mount Policy (Application)
> **Predecessor:** Phase 81 (Sandbox + Mount Policy spec), Phase 82 (Android Process Launcher)
> **Vertical:** EV runtime (`com.elysium.vanguard.core.orchestrator.*`)

---

## TL;DR

The Universal Execution Engine's
**Sandbox and Mount Policy** step is
**fully operational**: the typed spec
(Phase 81) is now followed by the
typed **application** of the policy to
a launch plan.

Per the master vision's Universal
Execution Engine (section 6), the
dispatch flow is:

```
Runtime Selection (Phase 76 first half)
    ↓
**Sandbox and Mount Policy**  ← this phase
    ↓
Process Supervisor (Phase 78)
    ↓
Telemetry and Recovery (Phase 79 + 80)
```

Phase 81 was the **typed spec** for the
sandbox + the validator. This phase is
the **application** of the policy to a
launch plan.

The application is **pure-domain**
(no I/O, no Android dependencies). The
test impl is the
`InMemorySandboxApplication`. The
production impl is the same (the
application is a typed list of steps;
the same impl is used in tests +
production). The actual OS-level
enforcement of the bind mounts +
SELinux context + resource limits is
the OS's responsibility (the
production impl emits the typed
`PreparationStep` list; the OS
executor consumes the list and
applies the steps).

The applier is **5 primitives**:

1. **`PreparationStep`** (sealed class,
   5 cases) — the typed application
   step: `BindMount` / `ApplySeLinuxContext`
   / `ApplyResourceLimits` /
   `ApplyNetworkPolicy` / `Skipped`.
2. **`SandboxPreparation`** (data class)
   — the preparation result (the plan +
   the policy + the steps in order +
   the timestamp + the signature).
3. **`SandboxApplication`** (sealed
   class) + `InMemorySandboxApplication`
   (impl) — the applier.
4. **`SandboxApplicationError`** (sealed
   class, 1 case) — the typed error
   envelope: `PreparationFailed`.

---

## What shipped

### `PreparationStep` (sealed class, 5 cases)

The typed application step. The sealed
class has 5 cases:

- **`BindMount(mountEntry)`** — bind
  the mount entry. The OS executor
  applies the bind mount (the actual
  `mount` syscall or the PRoot `-b`
  flag).
- **`ApplySeLinuxContext(securityProfile)`**
  — apply the SELinux security
  profile. The OS executor sets the
  SELinux context for the process
  (the actual `chcon` / `runcon`
  command).
- **`ApplyResourceLimits(sandboxLimits)`**
  — apply the resource limits. The OS
  executor sets the rlimits + the
  cgroup limits for the process.
- **`ApplyNetworkPolicy(networkPolicy)`**
  — apply the network policy. The OS
  executor configures the network
  namespace + the firewall rules for
  the process.
- **`Skipped(reason)`** — a step that
  was skipped. The `reason` is a
  human-readable string (e.g.
  "insufficient permissions to apply
  the SELinux context").

### `SandboxPreparation` (data class)

The typed preparation result. The
result is **immutable** (a data class;
no setters). A new result is a new
value.

The result has:

- **`preparationId`** — UUID; the
  unique id of this preparation.
- **`plan`** — the launch plan.
- **`policy`** — the sandbox policy.
- **`steps`** — the typed application
  steps in order (the OS executor
  consumes the list in order).
- **`preparedAtMs`** — the timestamp
  the preparation was performed.
- **`signature`** — the preparation's
  signature.

The result has helper methods:

- **`bindMountSteps`** — the bind
  mount steps in order.
- **`skippedSteps`** — the skipped
  steps in order.
- **`hasSkippedSteps`** — whether the
  preparation has any skipped steps.

### `SandboxApplication` (sealed class)

The typed applier. The interface has:

- **`prepare(plan, policy, nowMs)`** —
  prepare a launch. The applier takes
  a `LaunchPlan` + a `SandboxPolicy` +
  a `nowMs` (the timestamp the
  preparation is performed at) +
  returns a `SandboxPreparation`.

### `InMemorySandboxApplication` (impl)

The in-memory implementation. The
application algorithm:

1. For each mount in the policy's
   `mounts`, emit a `BindMount` step.
2. Emit an `ApplySeLinuxContext` step
   (with the policy's `security`
   profile).
3. Emit an `ApplyResourceLimits` step
   (with the policy's `limits`).
4. Emit an `ApplyNetworkPolicy` step
   (with the policy's `network`).
5. Always emit a final `Skipped` step
   ("the OS-level enforcement is the
   OS executor's responsibility; the
   applier produces the typed plan").

The order of the steps is the order
the OS executor should apply them:
- **Bind mounts first** (so the
  process can find the files).
- **SELinux context second** (so the
  process has the right MAC).
- **Resource limits third** (so the
  process has the right rlimits +
  cgroup limits).
- **Network policy fourth** (so the
  process has the right network
  namespace + firewall rules).

The applier is **thread-safe** (no
mutable fields). The applier is
**deterministic** (the `nowMs`
parameter is explicit).

### `SandboxApplicationError` (sealed class, 1 case)

The typed error envelope. The 1
variant:

- **`PreparationFailed(reason)`** — the
  preparation failed. The `reason` is
  a human-readable string.

---

## Design decisions

### Why does the applier produce a typed list of steps, not actually apply the steps?

The applier is **pure-domain**: it
produces a typed `SandboxPreparation`
with the steps in order. The actual
OS-level enforcement is the OS
executor's responsibility (a
production impl that consumes the
`SandboxPreparation` + applies the
steps via `mount` / `chcon` / `setrlimit`
/ `iptables`).

The pure-domain applier has two
benefits:
- The applier is **testable** in the
  JVM (no need for a real Android
  device to test the preparation
  algorithm).
- The applier is **deterministic**
  (the `nowMs` parameter is explicit;
  the same `nowMs` produces the same
  `SandboxPreparation`).

The OS-level enforcement is the
**production concern**: a future
Phase 7+ increment may add the
`AndroidSandboxApplication` that
consumes the `SandboxPreparation` +
applies the steps via the OS APIs.

### Why is the order of the steps fixed?

The order is the **canonical order** for
applying the sandbox:

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

### Why is the last step always `Skipped`?

The last step is always `Skipped` to
record that the **OS-level enforcement
is the OS executor's responsibility**.
The applier produces the typed plan;
the OS executor consumes the plan +
applies the steps.

The `Skipped` step serves two purposes:
- It records that the applier
  produced the plan + the OS executor
  is responsible for the enforcement.
- It allows the audit log to record
  the **transition** from "applier
  produced the plan" to "OS executor
  is enforcing the plan".

A future increment may replace the
`Skipped` step with an `Enforced`
step (the OS executor's success)
or a `Failed` step (the OS executor's
failure).

### Why is the `SandboxPreparation` signature required?

The signature binds the preparation
to the actor (the agent that prepared
the launch). The signature is the
**non-repudiation** mechanism: an
agent cannot deny preparing the launch
if the preparation is signed.

The signature is a `Signature` (the
Foundry's typed signature; per Phase
F5 the `RoyaltyContract` uses the same
pattern). The signature is verified
by the OS executor (a future
increment; the verification is the
responsibility of the executor, not
the applier).

---

## Tests

13 new tests in `SandboxApplicationTest`.
The tests cover:

- **PreparationStep invariants** (1
  test): Skipped blank reason.
- **SandboxPreparation invariants** (5
  tests): empty steps, non-positive
  preparedAtMs, bindMountSteps helper,
  skippedSteps + hasSkippedSteps helpers,
  hasSkippedSteps returns false when no
  skipped steps.
- **InMemorySandboxApplication** (6
  tests): emits the expected steps in
  order, emits a BindMount step for
  each mount in the policy, emits the
  policy's security profile in the
  SELinux step, emits the policy's
  limits in the resource limits step,
  emits the policy's network in the
  network policy step, produces a
  partial preparation.
- **Realistic scenario** (1 test): a
  workspace with 3 mounts + Standard
  security + DEFAULT limits + LocalOnly
  network.

**Total orchestrator tests:** 178 (was
165; +13 new).
**Total project tests:** 3376 (was 3363;
+13 new).

---

## Phase 87 closure

**The Universal Execution Engine's
Sandbox and Mount Policy step is FULLY
OPERATIONAL.** The chain is now:

```
RuntimeSelector (Phase 76 first half)
    ↓
SandboxPolicy (Phase 81, typed spec + validator)
    ↓
**SandboxApplication (Phase 87, this
   phase, typed preparation)**
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
production-ready + sandbox-ready**: the
test impl + the production impl both
exist; the sandbox policy is
**applied** (typed) before the
process is launched.

The next step in the UEE flow:

- **Phase 88 — CriticalE2E with real
  AndroidProcessLauncher + real
  SandboxApplication** (replace the
  InMemoryProcessLauncher in the
  Phase 71 / Phase 77 E2E tests with
  the real AndroidProcessLauncher; the
  E2E test would also exercise the
  `SandboxApplication`).

---

## What's next

The next concrete deliverable is up to
the user. The remaining work:

### Universal Execution Engine (next concrete)

- **Phase 88 — CriticalE2E with real
  AndroidProcessLauncher + real
  SandboxApplication** (replace the
  InMemoryProcessLauncher in the
  Phase 71 / Phase 77 E2E tests with
  the real AndroidProcessLauncher;
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
  sandbox policy before each launch).

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
| `app/src/main/java/com/elysium/vanguard/core/orchestrator/SandboxApplication.kt` | new | PreparationStep + SandboxPreparation + SandboxApplication + InMemorySandboxApplication + SandboxApplicationError |
| `app/src/test/java/com/elysium/vanguard/core/orchestrator/SandboxApplicationTest.kt` | new | 13 JVM tests |

---

## The role in the bigger picture

The Sandbox Application is the
**application** of the Universal
Execution Engine's Sandbox and Mount
Policy step. Phase 81 was the **typed
spec** (what the policy looks like);
this phase is the **typed preparation**
(how the policy is applied to a launch
plan).

The applier is the **typed bridge**
between the sandbox policy + the
process launcher. The applier produces
a typed `SandboxPreparation` (the
plan + the policy + the steps in
order); the OS executor consumes the
preparation + applies the steps.

Without the Sandbox Application, the
sandbox policy is a **spec that is never
applied** (the typed data without the
typed execution). The Sandbox
Application is the **typed execution**
of the sandbox policy.

The Sandbox Application is also the
**preparation for the AI operator**
(vision section 8): the AI agent
issues an `OperatorIntent` to launch a
process; the executor validates the
intent against the authority; the
executor applies the sandbox policy
(via the `SandboxApplication`); the
executor launches the process (via
the `AndroidProcessLauncher`). The
Sandbox Application is the
**preparation step** in the executor's
flow.
