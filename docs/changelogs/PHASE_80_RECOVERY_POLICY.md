# Phase 80 — Recovery Policy (Universal Execution Engine: Telemetry and Recovery)

> **Status:** ✅ Shipped
> **Date:** 2026-07-19
> **Phase:** 80 / EV runtime / Telemetry and Recovery (Recovery half)
> **Predecessor:** Phase 79 (Process Watcher, Universal Execution Engine: Telemetry)
> **Vertical:** EV runtime (`com.elysium.vanguard.core.orchestrator.*`)

---

## TL;DR

The Universal Execution Engine's
**Telemetry and Recovery** step is
**complete**. The typed rules that decide
whether to restart a failed process + the
backoff strategy between restart attempts.

Per the master vision's Universal Execution
Engine (section 6), the dispatch flow is:

```
Runtime Selection (Phase 76 first half)
    ↓
Sandbox and Mount Policy
    ↓
Process Supervisor (Phase 78)
    ↓
Telemetry and Recovery ← Phase 79 (telemetry) + Phase 80 (this phase, recovery)
```

Phase 79 was the **telemetry** step (the
process watcher records the lifecycle
events). This phase is the **recovery**
step: given a `ProcessEvent` + a
`RecoveryPolicy` + the current attempt
count, the `RecoveryExecutor` decides
whether to restart the process.

The recovery policy is **3 primitives**:

1. **`BackoffStrategy`** (sealed class, 3
   cases) — the typed strategy for the
   delay between restart attempts:
   `Fixed` / `Exponential` / `Linear`.
2. **`RecoveryPolicy`** (sealed class, 4
   cases) — the typed policy: `None` /
   `OnFailure` / `OnNonZeroExit` /
   `OnAnyExit`.
3. **`RecoveryDecision`** (sealed class, 3
   cases) — the typed decision:
   `DoNotRestart` / `RestartAfter` /
   `RestartExhausted`.

Plus the executor:

- **`RecoveryExecutor`** (sealed class) —
  the typed executor with `decide` +
  `computeDelay` + `isNoRecovery`.
- **`InMemoryRecoveryExecutor`** (impl) —
  the stateless executor (used in tests +
  production; the executor has no mutable
  state).

---

## What shipped

### `BackoffStrategy` (sealed class, 3 cases)

The typed backoff strategy. The strategy
determines the delay between restart
attempts. The sealed class has 3 cases:

- **`Fixed(delayMs)`** — the same delay
  for every restart attempt.
- **`Exponential(baseMs, maxMs, multiplier)`**
  — exponentially increasing delay
  (`baseMs * multiplier^attempt`, capped at
  `maxMs`).
- **`Linear(baseMs, incrementMs)`** —
  linearly increasing delay
  (`baseMs + incrementMs * attempt`).

### `RecoveryPolicy` (sealed class, 4 cases)

The typed recovery policy. The policy
determines **when** to restart a process.
The sealed class has 4 cases:

- **`None`** — never restart (the process
  is not recoverable; the user must
  intervene manually).
- **`OnFailure(maxAttempts, backoff)`** —
  restart on `Failed` events only (the
  process crashed OR failed to launch).
- **`OnNonZeroExit(maxAttempts, backoff)`**
  — restart on `Exited` events with
  non-zero exit code (the process exited
  with an error).
- **`OnAnyExit(maxAttempts, backoff)`** —
  restart on any `Exited` event
  (regardless of exit code; the process
  always restarts).

### `RecoveryDecision` (sealed class, 3 cases)

The typed recovery decision. The decision
is the executor's output given a
`RecoveryPolicy` + a `ProcessEvent` + the
current attempt count. The sealed class
has 3 cases:

- **`DoNotRestart(handleId, reason)`** —
  the process is not restarted.
- **`RestartAfter(handleId, nextAttempt, delayMs)`**
  — the process is restarted after a
  delay.
- **`RestartExhausted(handleId, attempts, lastReason)`**
  — the process has reached the max
  attempts; it is not restarted.

### `RecoveryExecutor` (sealed class)

The typed executor. The interface has:

- **`decide(policy, event, attemptCount)`**
  — decide whether to restart a process
  given a `RecoveryPolicy` + a
  `ProcessEvent` + the current attempt
  count. Returns a `RecoveryDecision`.
- **`computeDelay(backoff, attempt)`** —
  compute the delay before the next
  restart attempt. Returns a `Long`
  (millis).
- **`isNoRecovery(policy)`** — check
  whether the policy is `None` (the
  convenience predicate for the "no
  recovery" case).

### `InMemoryRecoveryExecutor` (impl)

The in-memory implementation for testing
+ production. The executor is **stateless**
(no mutable fields); the same impl is
used in production. The executor is
thread-safe (no shared state).

The executor's decision algorithm:

```
if policy is None:
    return DoNotRestart("policy is None")

if policy is OnFailure:
    if event is Failed:
        if attemptCount >= maxAttempts:
            return RestartExhausted(attempts, event.failureReason)
        else:
            return RestartAfter(attemptCount + 1, computeDelay(backoff, attemptCount + 1))
    else:
        return DoNotRestart("policy is OnFailure; event is <other>")

if policy is OnNonZeroExit:
    if event is Exited and event.exitCode != 0:
        # same as OnFailure but for Exited events
    elif event is Exited and event.exitCode == 0:
        return DoNotRestart("policy is OnNonZeroExit; exitCode is 0")
    else:
        return DoNotRestart("policy is OnNonZeroExit; event is <other>")

if policy is OnAnyExit:
    if event is Exited:
        # same as OnFailure but for any exit code
    else:
        return DoNotRestart("policy is OnAnyExit; event is <other>")
```

---

## Design decisions

### Why a sealed class for `BackoffStrategy`?

A sealed class is **type-safe +
exhaustive**. The consumer (the
executor) uses `when (backoff)` to
dispatch by case:

- `is BackoffStrategy.Fixed` — return
  `delayMs` for any attempt.
- `is BackoffStrategy.Exponential` —
  compute `base * multiplier^attempt`,
  capped at `maxMs`.
- `is BackoffStrategy.Linear` —
  compute `base + increment * attempt`.

A single class with a flag would lose
the type safety. The sealed class
captures the **3 distinct backoff
strategies** the platform supports.

### Why a sealed class for `RecoveryPolicy`?

A sealed class is **type-safe +
exhaustive**. The consumer (the
executor) uses `when (policy)` to
dispatch by case:

- `is RecoveryPolicy.None` — never
  restart.
- `is RecoveryPolicy.OnFailure` —
  restart on `Failed` events.
- `is RecoveryPolicy.OnNonZeroExit` —
  restart on `Exited` events with
  non-zero exit code.
- `is RecoveryPolicy.OnAnyExit` —
  restart on any `Exited` event.

A single class with flags would lose
the type safety. The sealed class
captures the **4 distinct recovery
policies** the platform supports.

### Why is `Exponential.multiplier` >= 1.0?

The multiplier is the **factor** by
which the delay grows per attempt. A
multiplier of 1.0 is "no growth" (the
delay is the same as `baseMs` for every
attempt). A multiplier > 1.0 is "growing
exponentially" (the delay is `baseMs *
multiplier^attempt`).

A multiplier < 1.0 is "shrinking" (the
delay decreases per attempt); this
**never makes sense** for a backoff
strategy (the purpose of backoff is to
**wait longer** between attempts, not
shorter). The `>= 1.0` constraint
rejects shrinking backoffs at the type
level.

### Why is `Failed.durationMs` >= 0 but `RestartExhausted.attempts` > 0?

A process that **failed at launch**
has `durationMs = 0` (the process never
started). A `RestartExhausted` decision
records the number of attempts that
were made; the minimum is 1 (at least
one attempt was made before giving up).

The asymmetry reflects the **physical
reality**: a failed launch is a 0ms
event; an exhausted recovery is a
post-attempt state. The constraint
enforces the **non-zero attempts**
invariant at the type level.

### Why is `computeDelay` exposed on the executor, not on the policy?

The backoff is a **function** of the
strategy + the attempt number. The
executor is the **stateless** component
that computes the function; the policy
is just the **data** (the strategy +
the max attempts).

A future increment may add a
**reusable** backoff function (e.g. a
`BackoffFunction` type that takes an
attempt and returns a delay). For now,
the `computeDelay` method on the
executor is the simplest interface.

---

## Tests

35 new tests in `RecoveryPolicyTest`. The
tests cover:

- **BackoffStrategy invariants** (6
  tests): Fixed delayMs < 0, Exponential
  baseMs <= 0, Exponential maxMs <
  baseMs, Exponential multiplier < 1.0,
  Linear baseMs < 0, Linear incrementMs
  < 0.
- **RecoveryPolicy invariants** (3
  tests): OnFailure maxAttempts <= 0,
  OnNonZeroExit maxAttempts <= 0,
  OnAnyExit maxAttempts <= 0.
- **RecoveryDecision invariants** (5
  tests): DoNotRestart blank reason,
  RestartAfter nextAttempt <= 0,
  RestartAfter delayMs < 0,
  RestartExhausted attempts <= 0,
  RestartExhausted blank lastReason.
- **InMemoryRecoveryExecutor — None
  policy** (3 tests): None returns
  DoNotRestart for any event,
  isNoRecovery returns true for None,
  isNoRecovery returns false for
  OnFailure.
- **InMemoryRecoveryExecutor —
  OnFailure** (4 tests): OnFailure
  returns DoNotRestart for Started,
  OnFailure returns DoNotRestart for
  Exited, OnFailure returns RestartAfter
  for Failed when attemptCount <
  maxAttempts, OnFailure returns
  RestartExhausted for Failed when
  attemptCount >= maxAttempts.
- **InMemoryRecoveryExecutor —
  OnNonZeroExit** (3 tests): OnNonZeroExit
  returns DoNotRestart for exit code 0,
  OnNonZeroExit returns RestartAfter for
  non-zero exit code when attemptCount
  < maxAttempts, OnNonZeroExit returns
  RestartExhausted when attemptCount
  >= maxAttempts.
- **InMemoryRecoveryExecutor —
  OnAnyExit** (3 tests): OnAnyExit
  returns RestartAfter for exit code 0,
  OnAnyExit returns RestartExhausted
  when attemptCount >= maxAttempts,
  OnAnyExit returns DoNotRestart for a
  Started event.
- **InMemoryRecoveryExecutor —
  computeDelay** (5 tests): Fixed
  backoff returns the same delay for
  any attempt, Exponential backoff
  returns base for attempt 0,
  Exponential backoff doubles the
  delay for each attempt, Exponential
  backoff caps the delay at maxMs,
  Linear backoff increases the delay
  linearly.
- **Realistic scenarios** (3 tests):
  process crashes 3 times + recovery
  restarts 3 times + gives up on the
  4th crash; exponential backoff
  produces the right delay sequence;
  OnAnyExit restarts on exit code 0 and
  recovers.

**Total orchestrator tests:** 139 (was
104; +35 new).
**Total project tests:** 3254 (was 3219;
+35 new).

---

## Phase 80 closure

**The Universal Execution Engine's
Telemetry and Recovery step is COMPLETE.**
The chain is now:

```
RuntimeSelector (Phase 76 first half)
    ↓
RuntimeDispatcher (Phase 76 second half)
    ↓
ProcessLauncher (Phase 78)
    ↓
ProcessWatcher (Phase 79 — telemetry)
    ↓
RecoveryExecutor + RecoveryPolicy (Phase 80 — recovery) ← this phase
```

The Universal Execution Engine's full
flow is now **typed** end-to-end. The
next step in the flow is:

- **Phase 81 — Sandbox + Mount Policy**
  (the typed spec for the SELinux
  sandbox + the bind mount
  configuration for the launched
  process).
- **Phase 82 — AndroidProcessLauncher**
  (the real production impl; uses
  `java.lang.Process` +
  `ProcessBuilder`).

---

## What's next

The next concrete deliverable is up to
the user. The remaining work:

### Universal Execution Engine (next concrete)

- **Phase 81 — Sandbox + Mount Policy**
  (the SELinux sandbox + the bind mount
  configuration for the launched
  process; the vision section 9 calls
  for sandboxing per workspace,
  allowlists, and bind mounts).
- **Phase 82 — AndroidProcessLauncher**
  (the real production impl; uses
  `java.lang.Process` +
  `ProcessBuilder`).
- **Phase 83 — CriticalE2E with real
  process launcher** (replace the
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
- **Phase F8 (G11) — International
  expansion** (i18n + multi-currency +
  multi-jurisdiction compliance).
- **Phase F9 (G12) — The Foundry public
  API** (the B2B API surface for
  third-party integrations).

---

## Files

| File | Status | Role |
| --- | --- | --- |
| `app/src/main/java/com/elysium/vanguard/core/orchestrator/RecoveryPolicy.kt` | new | BackoffStrategy + RecoveryPolicy + RecoveryDecision + RecoveryExecutor + InMemoryRecoveryExecutor |
| `app/src/test/java/com/elysium/vanguard/core/orchestrator/RecoveryPolicyTest.kt` | new | 35 JVM tests |

---

## The role in the bigger picture

The Recovery Policy is the **recovery
half** of the Universal Execution
Engine's Telemetry and Recovery step.
The watcher (Phase 79) records the
events; the recovery policy (this
phase) decides what to do with the
events.

The recovery policy is the **typed
rules** for the orchestrator's
auto-restart behavior. Without the
recovery policy, the orchestrator
would have no way to **automatically
restart** a crashed process; the user
would have to manually relaunch every
time a process crashes. The recovery
policy is the **typed automation** for
process lifecycle management.

The recovery policy is also the
**preparation for the AI operator**
(vision section 8): the AI agent will
need to **observe** the process
lifecycle (via the watcher) + **decide**
whether to restart the process (via
the recovery policy) + **execute** the
restart (via the launcher). The
recovery policy is the **decision
logic** the AI agent uses.
