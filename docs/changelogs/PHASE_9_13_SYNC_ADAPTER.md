# PHASE 9.13 — Sync Adapter Interface + In-Memory Implementation

Closed: 2026-07-09.

## What landed

### 9.13 — `CrdtSyncAdapter` + `InMemorySyncAdapter` (5 tests)
- `CrdtSyncAdapter` interface — the boundary between CRDT
  runtime and any transport (HTTP, SFTP, Bluetooth, USB, in-
  memory loopback for tests). Single method:
  `syncWith(session): Int` returns ops absorbed.
- `InMemorySyncAdapter` — holds a reference to a peer's
  `CrdtDocumentSession` directly. Push/pull simply call
  `peer.save()` then read the companion file as the "remote
  log" and apply it via `session.absorbRemote`.
- Tests:
  - `syncWith pushes local ops to peer and pulls peer ops` —
    bidirectional sync absorbs at least 1 op on each side.
  - `sync with no remote ops is a no-op` — empty peer log →
    0 absorbed.
  - `concurrent edits converge after a single sync round` —
    both chars survive on both nodes.
  - `repeated sync is idempotent` — sync 5 times, absorb 1
    op total.
  - `sync preserves CRDT semantics across many round-trips` —
    10 alternating edits + syncs; final body has 5 a's + 5
    b's on both nodes.

## Test-discovered bugs (2)

1. **Local ops never made it into the companion file**: the
   first cut of `CrdtDocumentSession.insertCharacter`,
   `setTitle`, etc. applied ops to `doc` but never recorded
   into `syncFile.log`. So the companion file was empty after
   edits, and the sync adapter couldn't ship anything.
   Fixed by recording into `localLog` on every local op.
2. **Save/reopen duplicated the body**: once local ops were
   being recorded into `syncFile.log`, the next bug surfaced:
   on `save()` the companion held the local ops + their effect
   was already in the on-disk file; on `open()` the body was
   seeded from the file (which contained the edits) AND the
   companion was absorbed (which re-applied the same ops).
   Result: body doubled on reopen ("Hi!" → "Hi!Hi!").
   Fixed by separating `localLog` (private to the session, used
   at save time) from `syncFile.log` (the union of local + remote
   ops, persisted on disk). `open()` now loads the companion
   into `syncFile.log` for future propagation but does NOT
   replay it — the doc was already seeded from the file content.

## Quality

- Tests: **608** (+5).
- Failures: **0**.
- `assembleDebug`: **green**, 165 MB APK.

## What this unlocks

- The transport layer for CRDT sync is now pluggable. New
  adapters (LocalServer HTTP, SftpServer, Bluetooth, USB) can be
  added as separate `CrdtSyncAdapter` implementations without
  touching the CRDT runtime.
- `InMemorySyncAdapter` is a fast, dependency-free test
  harness for two-node convergence scenarios. Future integration
  tests can stand up a real LocalServer and assert the same
  convergence properties end-to-end.

— elysium-autopilot