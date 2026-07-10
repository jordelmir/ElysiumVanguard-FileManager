# PHASE 9.12 — CrdtDocumentSession Wire-up to Companion Sync File

Closed: 2026-07-09.

## What landed

### 9.12 — `CrdtDocumentSession` companion integration (4 tests)
- `syncFile: ElysiumSyncFile` field on every session — the
  per-node companion that lives at
  `<documentFile>.<nodeId>.elysium.sync`.
- `open(file, nodeId)` now reads the companion file if present
  and absorbs its log into the session. New edits on top of the
  absorbed log retain the local clock state.
- `save()` now persists BOTH the document (plain
  `ElysiumDocument`) and the companion file (CRDT op log +
  lastSeen HLC).
- `absorbRemote(remote)` re-applies another node's companion
  into the local session — replays into the doc + merges into
  the companion file so future syncs see the merged history.

## Test-discovered bugs (1)

1. **Synthetic HLCs lost to wall-clock order**: the first cut
   of `absorbRemote merges a remote companion into the session`
   test used synthetic HLCs like `(2000, 0, alice)` and
   `(2001, 0, alice)` for the remote ops. But the local
   `CrdtDocumentSession.open()` seeds the body with HLCs at
   `System.currentTimeMillis()` (e.g. `1_783_xxx_xxx_xxx`).
   When the remote's low-HLC `!` was absorbed, it sorted to the
   BEGINNING of the sequence (before the high-HLC local `H`
   and `i`), producing `!Hi` instead of `Hi!`.
   Fixed by using `Long.MAX_VALUE - 100` as the synthetic HLC
   base so the remote ops are guaranteed to be higher than any
   wall-clock-derived HLC the local session could have.

## Quality

- Tests: **603** (+4).
- Failures: **0**.
- `assembleDebug`: **green**, 165 MB APK.

## What this unlocks

- The CRDT lifecycle is now closed:
  1. Open `.elysium.word` → session loads body + companion.
  2. Companion ships edits from offline periods → session
     absorbs.
  3. Edit body / metadata → ops accumulate with fresh HLCs.
  4. Save → document AND companion written together.
  5. Ship companion to peer → peer absorbs and renders
     converged state.
- The wire-up is fully testable; the only thing left is the
  transport for shipping the companion file (LocalServer,
  SftpServer, Bluetooth, USB — all already present in the
  codebase as separate features).

— elysium-autopilot