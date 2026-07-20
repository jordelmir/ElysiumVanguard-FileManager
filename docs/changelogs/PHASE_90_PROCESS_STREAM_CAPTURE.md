# Phase 90 — Process Stream Capture (Universal Execution Engine: Stdout/Stderr Capture)

> **Status:** ✅ Shipped
> **Date:** 2026-07-19
> **Phase:** 90 / EV runtime / Telemetry and Recovery (Stream Capture)
> **Predecessor:** Phase 78 (Process Launcher), Phase 79 (Process Watcher), Phase 82 (Android Process Launcher)
> **Vertical:** EV runtime (`com.elysium.vanguard.core.orchestrator.*`)

---

## TL;DR

The Universal Execution Engine's
**Telemetry and Recovery** step gains
the **stream capture** (the stdout/stderr
capture). The read side of the process
I/O.

The Telemetry and Recovery arc:

- **Phase 79** — `ProcessWatcher` (the
  process state tracker; the lifecycle
  events).
- **Phase 80** — `RecoveryPolicy` (the
  recovery decision logic).
- **Phase 90 (this phase)** —
  `ProcessStreamCapture` (the stdout/stderr
  capture; the read side of the process
  I/O).

The stream capture is **pure-domain**
(no I/O, no Android dependencies). The
test impl is the
`InMemoryProcessStreamCapture`. The
production impl may be the same (the
stream capture is a typed record of the
chunks; the actual stream reading is the
OS executor's responsibility).

The stream capture is the **read side
of the process I/O**:
- The `ProcessLauncher` (Phase 78) is
  the **write side** (launches the
  process; the OS executor reads the
  stdout/stderr streams).
- The `ProcessStreamCapture` (Phase 90)
  is the **read side** (records the
  stdout/stderr chunks).

---

## What shipped

### `ProcessStreamCapture` (sealed class)

The typed stream capture. The interface
has:

- **`chunks`** — the list of all chunks
  (in append order).
- **`append(chunk)`** — append a new
  chunk to the capture. The capture is
  **append-only**; existing chunks are
  never modified.
- **`stdoutChunksForHandle(handleId)`**
  — get the stdout chunks for a
  specific handle.
- **`stderrChunksForHandle(handleId)`**
  — get the stderr chunks for a
  specific handle.
- **`stdoutAsString(handleId)`** —
  concatenate the stdout chunks for a
  specific handle into a single string.
- **`stderrAsString(handleId)`** —
  concatenate the stderr chunks for a
  specific handle into a single string.
- **`size`** — the number of chunks in
  the capture.

### `StreamChunk` (sealed class, 3 cases)

The typed stream chunk. The sealed
class has 3 cases:

- **`StdoutChunk(handleId, data, timestampMs)`**
  — a stdout chunk. The chunk contains
  the stdout data emitted by the
  process.
- **`StderrChunk(handleId, data, timestampMs)`**
  — a stderr chunk. The chunk contains
  the stderr data emitted by the
  process.
- **`StreamClosed(handleId, reason, timestampMs)`**
  — the stream was closed. The
  `reason` is a human-readable string
  (e.g. "process exited with code 0" or
  "process killed").

### `InMemoryProcessStreamCapture` (impl)

The in-memory implementation. The
capture is **thread-safe** (the
underlying list is a
`CopyOnWriteArrayList` for safe iteration
during query + safe mutation during
`append`).

The impl is **stateless** (no mutable
fields beyond the chunks list); the
same impl is used in tests + production.

### `ProcessStreamError` (sealed class, 1 case)

The typed error envelope. The 1
variant:

- **`StreamCaptureFailed(reason)`** —
  the stream capture failed. The
  `reason` is a human-readable string.

---

## Design decisions

### Why is the stream capture a separate piece from the `ProcessWatcher`?

The `ProcessWatcher` (Phase 79) tracks
**lifecycle events** (`Started` /
`Exited` / `Failed` / `Heartbeat`). The
`ProcessStreamCapture` (Phase 90)
tracks **stream chunks** (`StdoutChunk`
/ `StderrChunk` / `StreamClosed`).

The two pieces are **complementary**:
- The watcher records **what happened**
  to the process (the lifecycle).
- The capture records **what the process
  said** (the I/O).

A consumer can use both:
- The watcher to know the process's
  state (running / exited / failed).
- The capture to know the process's
  output (the stdout / stderr).

The two-pieces design has two benefits:
- **Separation of concerns**: the
  watcher focuses on lifecycle; the
  capture focuses on I/O. A consumer
  can use the watcher without the
  capture (e.g. for a simple monitoring
  UI) or the capture without the
  watcher (e.g. for a logging tool).
- **Testability**: the watcher is
  testable in the JVM (no I/O); the
  capture is testable in the JVM (no
  I/O). The two pieces can be tested
  independently.

### Why is the capture append-only, not mutable?

The capture is **append-only** (the
consumer can add chunks, but cannot
modify or delete them). The
append-only pattern is the **canonical**
representation of an immutable
stream log.

The append-only pattern has two
benefits:
- **Audit**: the consumer can inspect
  the stream to see exactly what the
  process said (the stream is a
  faithful record of the process's
  output).
- **Replay**: the consumer can replay
  the stream to reproduce the process's
  output (e.g. for debugging).

### Why is the `StreamClosed` chunk a separate case?

A `StreamClosed` chunk is a **typed
event** that records the closure of
the stream. The chunk is distinct
from a `StdoutChunk` / `StderrChunk`
because:
- A `StreamClosed` chunk is **not data**
  (it does not contain stdout / stderr
  data).
- A `StreamClosed` chunk records
  **why** the stream was closed (the
  `reason` is a human-readable string).

A consumer can pattern-match on the
chunk:
- `is StreamChunk.StdoutChunk` →
  handle the stdout data.
- `is StreamChunk.StderrChunk` →
  handle the stderr data.
- `is StreamChunk.StreamClosed` →
  the stream is closed; stop reading.

### Why is the `timestampMs` parameter required?

The `timestampMs` parameter records **when**
the chunk was captured. The consumer
can use the timestamp to:
- Order the chunks (the chunks are
  already in append order, but the
  timestamp is the **canonical** order).
- Compute the duration between chunks
  (e.g. the time between two stdout
  chunks).
- Display the chunks in a UI with
  timestamps (the UI shows the user
  when each chunk was emitted).

The timestamp is **explicit** (the
capture does not derive it from
`System.currentTimeMillis()`) so the
capture is **deterministic** (the test
can use a fixed `timestampMs`).

---

## Tests

12 new tests in
`ProcessStreamCaptureTest`. The tests
cover:

- **StreamChunk invariants** (4 tests):
  StdoutChunk non-positive timestampMs,
  StderrChunk non-positive timestampMs,
  StreamClosed blank reason, StreamClosed
  non-positive timestampMs.
- **InMemoryProcessStreamCapture** (7
  tests): append adds a chunk to the
  capture, append preserves the append
  order, stdoutChunksForHandle returns
  only stdout chunks for the handle,
  stderrChunksForHandle returns only
  stderr chunks for the handle,
  stdoutAsString concatenates the stdout
  chunks for a handle, stderrAsString
  concatenates the stderr chunks for a
  handle, size returns the number of
  chunks.
- **Realistic scenario** (1 test): a
  process emits interleaved stdout and
  stderr chunks; the capture records
  all of them in order; the stdout and
  stderr are independently queryable.

**Total orchestrator tests:** 203 (was
191; +12 new).
**Total project tests:** 3411 (was 3399;
+12 new).

---

## Phase 90 closure

**The Telemetry and Recovery step has
the stream capture.** The chain is now:

```
Phase 79: ProcessWatcher
   ↓ process lifecycle events
Phase 90: ProcessStreamCapture
   ↓ stdout/stderr chunks (this phase)
Phase 80: RecoveryPolicy
   ↓ recovery decision logic
```

The watcher (Phase 79) + the capture
(Phase 90) form a complete telemetry
layer for the Universal Execution
Engine:
- The watcher records **what happened**
  to the process (the lifecycle).
- The capture records **what the process
  said** (the I/O).

A consumer (the UI, the security
auditor, the analytics pipeline, the
AI operator) can use both:
- The watcher to know the process's
  state.
- The capture to know the process's
  output.

The capture is also the **preparation
for the AI operator** (vision section
8): the AI agent can read the capture
to see what the process said, then
make decisions based on the output.

The full UEE flow is now:

```
RuntimeSelector (Phase 76 first half)
    ↓
SandboxPolicy (Phase 81, typed spec + validator)
    ↓
SandboxApplication (Phase 87, typed application)
    ↓
SandboxEnforcer (Phase 89, typed enforcement)
    ↓
RuntimeDispatcher (Phase 76 second half)
    ↓
ProcessLauncher (Phase 78, typed spec)
    ↓
AndroidProcessLauncher (Phase 82, production impl)
    ↓
Process running on the OS
    ↓
ProcessWatcher (Phase 79, lifecycle events)
    ↓
**ProcessStreamCapture (Phase 90, stdout/stderr chunks)** ← this phase
    ↓
RecoveryPolicy (Phase 80, recovery)
```

The chain is now **typed end-to-end +
production-ready + sandbox-ready +
enforcement-ready + telemetry-ready**:
the test impl + the production impl
both exist; the policy is
**validated** + **applied** +
**enforced**; the process is
**launched** + **observed** + **streamed**
+ **recovered**.

---

## What's next

The next concrete deliverable is up to
the user. The remaining work:

### Universal Execution Engine (next concrete)

- **Phase 91 — CriticalE2E with real
  AndroidProcessLauncher + SandboxApplication
  + SandboxEnforcer + ProcessStreamCapture**
  (replace the InMemoryProcessLauncher
  in the Phase 71 / Phase 77 E2E tests
  with the real AndroidProcessLauncher;
  the E2E test would also exercise the
  `SandboxApplication` + the
  `SandboxEnforcer` + the
  `ProcessStreamCapture`).

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
  `SandboxEnforcer` + the
  `ProcessStreamCapture`** (the executor
  launches the processes via the
  production launcher + applies the
  sandbox policy before each launch +
  enforces the sandbox after the
  launch + captures the process's
  stdout / stderr + logs every action
  to the audit log).

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
| `app/src/main/java/com/elysium/vanguard/core/orchestrator/ProcessStreamCapture.kt` | new | StreamChunk + ProcessStreamCapture + InMemoryProcessStreamCapture + ProcessStreamError |
| `app/src/test/java/com/elysium/vanguard/core/orchestrator/ProcessStreamCaptureTest.kt` | new | 12 JVM tests |

---

## The role in the bigger picture

The Process Stream Capture is the
**read side of the process I/O** in
the Universal Execution Engine. Phase
78 (`ProcessLauncher`) was the
**write side** (launches the process);
this phase is the **read side**
(captures the process's stdout /
stderr).

The stream capture is the **typed
visibility** of the process's output.
A consumer (the UI, the security
auditor, the analytics pipeline, the
AI operator) can inspect the capture
to see exactly what the process said.

Per the master vision (section 8):
"La IA debía convertir eso en un plan
declarativo, mostrar cambios y ejecutar
únicamente operaciones autorizadas."

The AI agent (Phase 83-86) issues
intents + executes them. The
`ProcessStreamCapture` (Phase 90) is
the **AI agent's eyes** into the
process's output: the AI agent reads
the capture to see what the process
said, then makes decisions based on
the output (e.g. "the install failed
because of X; let me try Y").

The capture is the **feedback loop**
between the process + the AI agent.
Without the capture, the AI agent
would be **blind** (it can launch
processes, but cannot see what the
processes said). With the capture, the
AI agent can make **informed
decisions** based on the process's
output.

The capture is also the **UX
foundation** for the real-time process
output (a future UI feature would show
the user the process's stdout / stderr
in real-time).
