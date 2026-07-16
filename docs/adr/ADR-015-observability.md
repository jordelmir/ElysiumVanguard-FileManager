# ADR-015 — Observability (Event Bus + Persistent Log)

Status: **Accepted** (Phase 25, 2026-07-15)
Owners: Runtime
Supersedes: none
Superseded by: none

## Context

Master order §25: "The runtime surfaces every state
change as an event. A persistent audit log captures
every event for diagnostic and support use." Until
Phase 25, every component (network broker, hardware
enforcer, workspace manager, Windows VM manager,
distro manager) had its own audit log + its own
listener pattern. A unified view required hand-rolled
wiring per consumer; a persistent audit log was
inconsistent across components.

The challenge: a unified event bus + a persistent log
must be JVM-testable end-to-end (no Android dependency),
thread-safe (producers run from multiple coroutines),
and a stable contract (the wire format is what the
support team reads; changes break bug reports).

## Decision

We split observability into three small classes:

1. **`RuntimeEvent` (sealed)** — every event the
   runtime can emit. Eight variants: network decision,
   hardware decision, workspace state change, session
   added / removed, VM state change, distro install /
   install-failed. The sealed class makes the closed
   set explicit; a new event type is a deliberate code
   change.

2. **`RuntimeEventBus` (interface)** — the in-memory
   pub/sub seam. Producers call `publish(event)`;
   consumers call `subscribe(handler)` and receive a
   callback for every event. The `SynchronizedEventBus`
   is the production impl; the `RecordingEventBus` is
   the test impl. A buggy subscriber's exception is
   caught and recorded (the publisher's thread is
   never broken).

3. **`RuntimeEventLog`** — the persistent file-backed
   log. Append-only NDJSON (one JSON object per line).
   The log is the durable counterpart to the bus; a
   user can attach the log file to a bug report. The
   reader parses the JSON Lines back into
   `RuntimeEvent` instances. The `BusToLogAdapter`
   wires the bus to the log.

The three pieces are independent: the bus is in-memory
(no disk), the log is on-disk (no subscribers), the
adapter is the seam. A future phase can swap the
synchronous append for a background queue without
changing the bus or the log.

### Why a sealed class for events

The runtime's UI subscribes to the bus and renders
events. A sealed class makes the closed set explicit;
the UI's `when` over `RuntimeEvent` is exhaustive; a
new event type is a deliberate code change. An enum
would be flatter but loses the per-event fields.

### Why `CopyOnWriteArrayList` for the bus

The bus's `publish` is on the publisher's thread; a
slow subscriber (e.g. the log adapter doing a disk
write) can block the publisher. `CopyOnWriteArrayList`
is a `synchronized` list with copy-on-write semantics:
iteration is a snapshot (no `ConcurrentModificationException`),
removal is rare (subscribers live for the process
lifetime). The trade-off: a copy on every `subscribe`
call, which is rare.

### Why hand-rolled JSON for the log

The runtime's `org.json` library is a stub on the
unit-test classpath (under
`isReturnDefaultValues = true`). The stub returns
default values, which is fine for the *parsing* path
(the parser uses `optString` + fallback). For the
*writing* path, the stub is not enough: the runtime
needs to *produce* well-formed JSON Lines. A 250-line
hand-rolled writer + parser handles the schema the
runtime emits and nothing more. The dependency-free
serializer stays out of the test-runtime's classpath
hole.

### Why an adapter, not a direct log write in the bus

The bus is in-memory; the log is on-disk. Mixing them
in one class couples two unrelated concerns: a
publisher that only wants in-memory events would
have to opt out of disk writes; a log writer that
only wants disk events would have to opt out of
in-memory fanout. The adapter is the seam: producers
publish to the bus, the adapter subscribes once and
forwards every event to the log.

## Consequences

### Positive

- **JVM-testable end-to-end.** The 16 unit tests cover
  every event variant, both bus impls, the log
  append / readAll round-trip, the adapter's open /
  close lifecycle, and the thread-safety under 8 × 50
  concurrent publishes. The hand-rolled JSON writer
  + parser is round-trip tested; special characters
  (`"`, `\`, `\n`) are preserved.
- **Subscriber crashes do not break publishers.** The
  `SynchronizedEventBus` catches every exception a
  handler throws and records it; other handlers still
  receive. A test pins this: a handler that throws on
  every event does not break the second handler.
- **Persistent log is human-readable.** Each line is
  a JSON object. A user can attach the log file to a
  bug report; the support team can read it with `cat`
  + `jq`. No proprietary binary format.
- **Adapter is the seam.** Producers (the various
  managers) publish to the bus; the adapter subscribes
  once. A future phase can add a second adapter (e.g.
  a crash-reporter adapter that sends events to a
  remote endpoint) without changing the producers.

### Negative

- **Synchronous append on the publisher's thread.** A
  slow log write blocks the publisher. A future phase
  swaps the synchronous append for a background queue.
- **JSON schema is bespoke.** A future contributor who
  adds a new event type must update both the writer
  (in `renderEvent`) and the parser (in `parseEvent`).
  The 8-variant pattern is small but bespoke.
- **`String` fields for event-specific data.** The
  events store the network decision, hardware class,
  VM state, etc. as `String` rather than typed
  enums. The writer + parser is simpler; the typed
  enum would require a second layer of validation
  (e.g. `decision` is one of `Allow` /
  `AllowWithConfirmation` / `Deny`). A future phase
  adds the typed validation.
- **In-memory index of recorded events in
  `RecordingEventBus` is unbounded.** A test that
  publishes millions of events keeps them in memory.
  The production bus does not have this issue (it
  hands events to subscribers, which are typically
  the log + the UI).

## Alternatives considered

1. **Use a third-party pub/sub library
   (`kotlinx.coroutines.channels`).** Rejected: the
   JVM unit tests would need the coroutines runtime
   on the classpath. The hand-rolled bus is
   thread-safe without coroutines.
2. **Use a third-party JSON library.** Rejected: the
   writer + parser is small (~250 lines) and bespoke
   to the schema. A library would be overkill.
3. **Skip the bus; every component writes directly to
   the log.** Rejected: a component that wants in-
   memory events (e.g. the UI) would have to re-read
   the log on every event. The bus is the in-memory
   fanout; the log is the durable record.

## Revisit triggers

- A new producer type (e.g. macOS VM manager) wants to
  emit events. The sealed class gains a variant; the
  writer + parser gain cases; the bus + log do not
  change.
- The log file grows unbounded. A future phase adds
  rotation (one file per day / per N events) +
  archival (older files moved to `archive/`).
- The synchronous append blocks the publisher. A
  future phase swaps the adapter for a coroutine-
  based async writer.
- A consumer wants to filter events (e.g. only
  `WorkspaceStateChanged`). The bus gains a typed
  `subscribe(filter: (RuntimeEvent) -> Boolean)`.
