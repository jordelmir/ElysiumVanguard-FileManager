# Phase 79 — Process Watcher (Universal Execution Engine: Telemetry and Recovery)

> **Status:** ✅ Shipped
> **Date:** 2026-07-19
> **Phase:** 79 / EV runtime / Telemetry and Recovery
> **Predecessor:** Phase 78 (Process Launcher, Universal Execution Engine: Process Supervisor)
> **Vertical:** EV runtime (`com.elysium.vanguard.core.orchestrator.*`)

---

## TL;DR

The Universal Execution Engine's
**Telemetry and Recovery** step is
operational. The typed observer that records
the lifecycle events emitted by the
`ProcessLauncher` (Phase 78).

Per the master vision's Universal Execution
Engine (section 6), the dispatch flow is:

```
Runtime Selection (Phase 76 first half)
    ↓
Sandbox and Mount Policy
    ↓
Process Supervisor (Phase 78)
    ↓
Telemetry and Recovery ← this phase
```

The `ProcessWatcher` is the **telemetry
collector**. The watcher's input is a stream
of `ProcessEvent`s emitted by the
`ProcessLauncher` (or by the OS in the
production case); the watcher's output is a
queryable list of events per handle.

The watcher is the **typed bridge** between
the launcher's state transitions + the
downstream consumers:

- The orchestrator's **recovery policy**
  (a future Phase — restart the process on
  crash, exponential backoff, etc.).
- The UI's **process monitor** (a future
  Phase — show the user the running
  processes, the CPU + RAM consumption, the
  exit code).
- The **audit log** (a future Phase — record
  every process launch for the security
  audit).
- The **analytics** (a future Phase — track
  the launch success rate, the most-used
  runtimes, the most-frequent failure
  reasons).

The watcher is **3 primitives**:

1. **`ProcessWatcher`** (sealed class) — the
   typed watcher interface.
2. **`ProcessEvent`** (sealed class, 4
   cases) — the typed lifecycle event:
   `Started` / `Exited` / `Failed` /
   `Heartbeat`.
3. **`ProcessWatcherError`** (sealed class,
   1 case) — the typed error envelope:
   `HandleNotFound`.

Plus an in-memory implementation
(`InMemoryProcessWatcher`) for testing + a
production Android implementation
(`AndroidProcessWatcher`) for the real
device (a future increment that uses
`Process.onExit()` + a coroutine scope to
collect events from the real process).

---

## What shipped

### `ProcessWatcher` (sealed class)

The typed watcher. The interface has:

- **`watch(handleId, launcher)`** —
  subscribe to events for a handle. The
  subscription is **idempotent** (watching
  the same handle twice has no effect).
  Returns a `Result.failure(ProcessWatcherError.HandleNotFound)`
  if the handle is not in the launcher's
  registry.
- **`unwatch(handleId)`** — unsubscribe
  from events for a handle. The
  unsubscription is **idempotent**
  (unwatching a non-watched handle has no
  effect).
- **`watchedHandles`** — the set of handle
  ids the watcher is currently watching.
- **`emit(event)`** — emit a new event.
  The event is recorded in the events
  list.
- **`eventsForHandle(handleId)`** — get
  all events for a specific handle, in
  emit order. Returns empty list if the
  handle has no events.
- **`latestEventForHandle(handleId)`** —
  get the most recent event for a
  specific handle. Returns `null` if the
  handle has no events.
- **`countEventsForHandle(handleId)`** —
  count the events for a specific handle.
  Returns 0 if the handle has no events.

### `ProcessEvent` (sealed class, 4 cases)

The typed lifecycle event. The sealed class
has 4 cases:

- **`Started(handleId, pid, timestampMs)`**
  — the process was launched successfully.
- **`Exited(handleId, exitCode, durationMs, timestampMs)`**
  — the process exited normally.
- **`Failed(handleId, failureReason, durationMs, timestampMs)`**
  — the process failed to launch OR
  crashed.
- **`Heartbeat(handleId, uptimeMs, timestampMs)`**
  — periodic heartbeat (the process is
  still running).

The event is **immutable** (a sealed class
with data class cases; no setters). A new
event is a new value.

`Failed.durationMs` is `>= 0` (a process
that failed at launch has `durationMs = 0`).
`Exited.durationMs` is `> 0` (a process
that exited normally has run for at least
one ms).

### `InMemoryProcessWatcher` (impl)

The in-memory implementation for testing.
The watcher:

- **`watch`** — validates the handle
  against the launcher; adds the handle
  id to the watched set; returns
  `Result.failure(HandleNotFound)` if the
  handle is unknown.
- **`unwatch`** — removes the handle id
  from the watched set (idempotent).
- **`emit`** — appends the event to the
  events list (preserves order).
- **`eventsForHandle` /
  `latestEventForHandle` /
  `countEventsForHandle`** — filter /
  search the events list by handle id.

The watcher is **thread-safe** (the
underlying list is a `CopyOnWriteArrayList`
for safe iteration during query + safe
mutation during `emit`; the watched set is
a `ConcurrentHashMap.newKeySet()` for
thread-safe add/remove/contains).

### `ProcessWatcherError` (sealed class, 1 case)

The typed error envelope. The 1 variant:

- **`HandleNotFound(handleId)`** — the
  handle id is not in the launcher's
  registry.

---

## Design decisions

### Why a sealed class for `ProcessEvent`, not a single class with a status flag?

A sealed class is **type-safe +
exhaustive**. The consumer (the
orchestrator's recovery policy, the UI's
process monitor) uses `when (event)` to
dispatch by case:

- `is ProcessEvent.Started` — the
  process was launched; check `pid` +
  `timestampMs`.
- `is ProcessEvent.Exited` — the
  process exited; check `exitCode` +
  `durationMs`.
- `is ProcessEvent.Failed` — the
  process failed; check `failureReason` +
  `durationMs`.
- `is ProcessEvent.Heartbeat` — the
  process is alive; check `uptimeMs` +
  `timestampMs`.

A single class with a flag would lose the
type safety; the consumer would need to
check the flag. The sealed class captures
the **4 distinct lifecycle event kinds** the
process can emit.

### Why is the watcher decoupled from the launcher?

The watcher is **independent of the
launcher** (it just receives events). The
launcher emits events; the watcher records
them. The launcher can hold a reference
to the watcher and call
`watcher.emit(event)` when state
transitions happen.

The decoupling is good because:

- The watcher can be tested independently
  (no launcher required).
- The launcher can be used without a
  watcher (the launcher emits events
  when a watcher is present; the
  launcher does not fail if no watcher
  is present).
- The watcher can collect events from
  multiple launchers (a future
  multi-launcher scenario).

### Why is `watch` validated against the launcher?

The `watch` method takes a `launcher`
parameter and validates that the handle
exists. The validation prevents
**dangling subscriptions** (a watcher
subscribed to a handle that was never
launched; the watcher would never
receive events).

A future increment may relax the
validation (e.g. allow pre-subscriptions
for handles that will be launched
later). For now, the validation is the
**strict** mode.

### Why is `watch` idempotent?

The `watch` method is **idempotent**
(watching the same handle twice has no
effect). The idempotency is important for
**retries** (a network failure may cause
the consumer to retry the watch call; the
watch should not fail on the retry).

A future increment may add a `watch` with
a `replace: Boolean` parameter for
**explicit re-subscription** (the consumer
wants to reset the subscription). For
now, the idempotent watch is the default.

### Why is `Failed.durationMs` >= 0 but `Exited.durationMs` > 0?

A process that **failed at launch** has
`durationMs = 0` (the process never
started; the launch attempt failed
immediately). A process that **exited
normally** has `durationMs > 0` (the
process ran for at least one ms before
exiting).

The asymmetry reflects the **physical
reality** of process lifecycles: a failed
launch has zero duration; a successful
exit has positive duration. The
`durationMs` field is the **typed** way
to express this asymmetry.

---

## Tests

28 new tests in `ProcessWatcherTest`. The
tests cover:

- **ProcessEvent.Started invariants**
  (3 tests): well-formed configuration,
  zero pid, non-positive timestampMs.
- **ProcessEvent.Exited invariants** (3
  tests): well-formed configuration,
  non-positive durationMs, non-positive
  timestampMs.
- **ProcessEvent.Failed invariants** (4
  tests): well-formed configuration,
  blank failureReason, negative
  durationMs, non-positive timestampMs.
- **ProcessEvent.Heartbeat invariants**
  (3 tests): well-formed configuration,
  negative uptimeMs, non-positive
  timestampMs.
- **InMemoryProcessWatcher — watch +
  unwatch** (5 tests): watch subscribes
  to events for a launched handle, watch
  rejects an unknown handle, watch is
  idempotent, unwatch unsubscribes,
  unwatch is idempotent.
- **InMemoryProcessWatcher — emit +
  query** (8 tests): emit records an
  event, emit preserves event order,
  eventsForHandle returns only events for
  the handle, eventsForHandle returns
  empty for an unknown handle,
  latestEventForHandle returns the most
  recent event, latestEventForHandle
  returns null for an unknown handle,
  countEventsForHandle returns the event
  count, countEventsForHandle returns 0
  for an unknown handle.
- **Realistic scenarios** (2 tests):
  full lifecycle (launched + heartbeat
  + exited) recorded by the watcher;
  multiple processes can be watched
  concurrently.

**Total orchestrator tests:** 104 (was 76;
+28 new).
**Total project tests:** 3219 (was 3191;
+28 new).

---

## Phase 79 closure

**The Universal Execution Engine's
Telemetry and Recovery step is
operational.** The watcher is the
**telemetry collector**; the next
increment will be the **recovery policy**
(Phase 80) — the typed rules for when
to restart a failed process, the
exponential backoff, the max attempts.

The full Universal Execution Engine flow
is now:

1. **File Type / Manifest Detection**
   (Phase 69, Capsule).
2. **Compatibility Resolver** (Phase 71,
   Critical E2E).
3. **Architecture Detection** (Phase 76,
   DeviceProfile).
4. **Runtime Selection** (Phase 76 first
   half, RuntimeSelector).
5. **Sandbox and Mount Policy** (Phase
   81, future).
6. **Process Supervisor** (Phase 78,
   ProcessLauncher).
7. **Telemetry and Recovery** (Phase 79,
   this phase — telemetry only; recovery
   in Phase 80).

---

## What's next

The next concrete deliverable is up to
the user. The remaining work:

### Universal Execution Engine (next concrete)

- **Phase 80 — RecoveryPolicy** (the
  typed rules for when to restart a
  failed process; exponential backoff;
  max attempts).
- **Phase 81 — Sandbox + Mount Policy**
  (the SELinux sandbox + the bind mount
  configuration for the launched
  process).
- **Phase 82 — AndroidProcessLauncher**
  (the real production impl; uses
  `java.lang.Process` + `ProcessBuilder`).

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
| `app/src/main/java/com/elysium/vanguard/core/orchestrator/ProcessWatcher.kt` | new | ProcessWatcher + ProcessEvent + ProcessWatcherError + InMemoryProcessWatcher |
| `app/src/test/java/com/elysium/vanguard/core/orchestrator/ProcessWatcherTest.kt` | new | 28 JVM tests |

---

## The role in the bigger picture

The Process Watcher is the **telemetry
collector** in the Universal Execution
Engine. The watcher closes the loop
between the launcher's state transitions
+ the downstream consumers (the recovery
policy, the UI's process monitor, the
audit log, the analytics).

Without the watcher, the launcher's state
transitions are **invisible** to the rest
of the system. The watcher is the
**typed visibility layer** that turns
the launcher's internal state into a
queryable event stream.

The watcher is also the **preparation
for the real AndroidProcessLauncher**:
the real launcher will use
`Process.onExit()` (Java 9+) to detect
when a process exits; the watcher is the
consumer of those `onExit` callbacks
(typed as `ProcessEvent.Exited`). The
`AndroidProcessWatcher` is a future
increment; the in-memory impl is the
test impl.
