# Phase 25 — Observability (Event Bus + Persistent Log)

Date: 2026-07-15
Status: **Shipped** — `assembleDebug` green, 1258 tests, 0 failures, 2 skipped.

## What landed

The runtime now has a unified observability path. Every
state change is a `RuntimeEvent` published on the
`RuntimeEventBus`; the `BusToLogAdapter` subscribes once
and appends every event to the persistent
`RuntimeEventLog`. The 5 prior audit logs (network,
hardware, workspace, VM, distro) are unified into one
seam.

### Files

**Production (4 new):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/observability/RuntimeEvent.kt`
  — the sealed `RuntimeEvent` class. 8 variants:
  `NetworkDecisionEvent`, `HardwareDecisionEvent`,
  `WorkspaceStateChangedEvent`, `SessionAddedEvent`,
  `SessionRemovedEvent`, `VmStateChangedEvent`,
  `DistroInstalledEvent`, `DistroInstallFailedEvent`.
  Every event carries an `atMs` timestamp + a nullable
  `workspaceId`.
- `app/src/main/java/com/elysium/vanguard/core/runtime/observability/RuntimeEventBus.kt`
  — the pub/sub seam. `RuntimeEventBus` interface +
  `SynchronizedEventBus` (production, `CopyOnWriteArrayList`
  of handlers, catches subscriber exceptions) +
  `RecordingEventBus` (test, records every event).
- `app/src/main/java/com/elysium/vanguard/core/runtime/observability/RuntimeEventLog.kt`
  — the persistent file-backed log. Append-only NDJSON
  (one JSON object per line). Hand-rolled JSON writer
  + parser (~250 lines) — `org.json` is a stub on the
  unit-test classpath, so the log uses a no-dependency
  serializer. Round-trip tested for every event
  variant; special characters (`"`, `\`, `\n`) are
  preserved.
- `app/src/main/java/com/elysium/vanguard/core/runtime/observability/BusToLogAdapter.kt`
  — the seam that connects the bus to the log. Single
  subscriber; closing the adapter stops delivery.

**Tests (1 new):**

- `app/src/test/java/com/elysium/vanguard/core/runtime/observability/ObservabilityPhase25Test.kt`
  — 16 unit tests covering: every event variant, the
  `RecordingEventBus` (FIFO order, fanout,
  unsubscribe, clear), the `SynchronizedEventBus`
  (catches subscriber crashes, thread-safety), the
  `RuntimeEventLog` (round-trip for every variant,
  empty file handling, clear, special characters),
  the `BusToLogAdapter` (open/close lifecycle, survives
  a handler crash), and 8 × 50 concurrent publishes
  with all events landing in the log.

**ADR (1 new):**

- `docs/adr/ADR-015-observability.md` — context,
  decision, the three-piece split (events / bus /
  log+adapter), the hand-rolled JSON rationale, the
  thread-safety story, consequences, alternatives,
  revisit triggers.

### Why this matters

Master order §25: "The runtime surfaces every state
change as an event. A persistent audit log captures
every event for diagnostic and support use." Until
Phase 25, every component had its own audit log; a
unified view required hand-rolled wiring per
consumer; a persistent audit log was inconsistent
across components.

Phase 25 closes the gap. The `RuntimeEventBus` is the
single in-memory fanout; the `RuntimeEventLog` is the
single persistent record. The 5 prior audit logs
(network, hardware, workspace, VM, distro) become
emitters on the bus; the `BusToLogAdapter` subscribes
once. A user can attach the log file to a bug report;
the support team reads it with `cat` + `jq`.

### What the bus + log deliver

| Concern | Where it lives |
|---|---|
| In-memory fanout | `RuntimeEventBus` |
| Subscriber crash safety | `SynchronizedEventBus` catches exceptions |
| Persistent record | `RuntimeEventLog` (NDJSON) |
| Bus-to-log seam | `BusToLogAdapter` (one subscriber) |
| Human-readable | Each line is a JSON object |
| Round-trip safe | Special characters (`"`, `\`, `\n`) preserved |
| Thread-safe | 8 × 50 concurrent publishes all land in the log |

The 16 unit tests pin every concern. The hand-rolled
JSON writer + parser is round-trip tested; the
synchronous append is wrapped in `runCatching` so a
handler crash does not break the publisher.

## Test count

| Suite | Tests | Failures |
|---|---|---|
| `ObservabilityPhase25Test` | 16 | 0 |
| **Project total** | **1258** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Next phase

**§36 remaining ADRs** (the master order lists 001,
002, 015, 016 in addition to 005–015 — 3 to go: 001
runtime backend abstraction, 002 PTY terminal renderer,
016 next big subsystem).

Or **§24 UX** (master order) — the runtime's main
screen + workspace list + settings. The
`RuntimeEventBus` is the seam the UI subscribes to;
the workspaces list is `WorkspaceManager.listWorkspaces()`.
