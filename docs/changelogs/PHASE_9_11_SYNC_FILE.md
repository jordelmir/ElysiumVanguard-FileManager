# PHASE 9.11 — Companion Sync File

Closed: 2026-07-09.

## What landed

### 9.11 — `ElysiumSyncFile` (9 tests)
- `ElysiumSyncFile(documentFile, log, lastSeen, nodeId)` — companion
  file format for Elysium documents. The companion lives at
  `<documentFile>.<nodeId>.elysium.sync` so multiple nodes editing
  the same document keep independent logs.
- `save()` writes to disk; `companionFile()` returns the path.
- `serialize()` produces a comment-prefixed format (lines starting
  with `#`) followed by the `CrdtOpLog` body so the file remains
  human-debuggable.
- `read()` / `readFor()` / `parse()` reconstruct the object from
  disk.
- `empty()` factory builds a fresh empty sync file for a node.

## Test-discovered bugs (1)

1. **`CrdtOpLog.merge` returned a new doc, didn't mutate `this`**:
   `bobLocalSync.log.merge(bobSync.log)` was called expecting the
   local log to absorb the remote log. But the merge returned a
   new doc, leaving `bobLocalSync.log` empty. Save then wrote only
   the empty log (header + 0 entries). The bug surfaced because
   the new test created an empty sync file, merged in a remote
   log, and asserted `bobReloaded.log.size == 1`. Fixed by
   changing `merge` to mutate `this` (matching the canonical CRDT
   semantics we already use in `CrdtDoc.merge` and
   `CrdtSequence.merge`).

## Quality

- Tests: **599** (+9).
- Failures: **0**.
- `assembleDebug`: **green**, 165 MB APK.

## What this unlocks

- A two-device sync flow:
  1. Alice edits `notes.elysium.word`; her `CrdtDocumentSession`
     records ops into her companion file
     (`notes.elysium.word.alice.elysium.sync`).
  2. Alice ships the companion file to Bob (via LocalServer,
     SftpServer, Bluetooth, USB, whatever).
  3. Bob reads Alice's companion into his `ElysiumSyncFile`,
     calls `log.merge(aliceSync.log)` to absorb the ops into his
     own companion, and saves.
  4. Bob opens `notes.elysium.word` — the rendered body reflects
     Alice's edits.
- This is the persistence layer for the entire CRDT story: ops
  survive across sessions, devices, and offline periods.

— elysium-autopilot