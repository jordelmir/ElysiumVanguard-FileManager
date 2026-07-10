# PHASE 9.9.5–9.9.6 — Op Log + Anti-Entropy Sync Protocol

Closed: 2026-07-09. Range: 9.9.5 (op log) → 9.9.6 (sync protocol).

## What landed

### 9.9.5 — `CrdtOpLog` (12 tests)
- `CrdtOpLog` records every CRDT mutation (DocSet/DocDel/SeqIns/SeqDel)
  as a single line in a text-based wire format:
  - `DSET <hlc> <key> <value>`
  - `DDEL <hlc> <key>`
  - `SINS <hlc> <value>`
  - `SDEL <hlc> <targetHlc>`
- `serialize` / `parse` round-trip; lines sorted by HLC so the
  receiver replays in causal order. Newlines and backslashes in
  values are escaped.
- `merge` dedups by `hlc+kind+value` so applying both halves of a
  sync yields the same final state.
- `replay(doc, seq)` applies the entire log to a `(doc, seq)`
  pair.

### 9.9.6 — `CrdtSyncNode` + anti-entropy protocol (10 tests)
- `CrdtSyncNode(nodeId)` bundles an `HlcClock`, a `CrdtOpLog`, a
  `CrdtDoc`, and a `CrdtSequence` — the four things a node needs
  to participate in the network.
- Local ops: `setProperty`, `deleteProperty`, `insertSequence`,
  `deleteSequence` (each advances the local clock and records
  into the log + applies to the local doc/seq).
- Sync ops:
  - `absorb(remoteLog)` — applies only entries we haven't seen
    yet; returns the count of genuinely new entries.
  - `entriesSince(hlc)` — returns a `CrdtOpLog` of everything the
    caller has that's newer than the given HLC (or all entries if
    the HLC is null).
  - `lastSeen()` — the highest HLC the node has absorbed.

## Test-discovered bugs (2)

1. **Sync filter dropped legitimate remote ops**: the first cut
   of `absorb` filtered the merged log by `e.hlc > lastSeenHlc`.
   That's wrong: if our local clock has advanced past a remote's
   HLC (because we issued our own ops in between), the remote's
   older HLC is filtered out and the op is never applied. Carol
   in the late-arriving test absorbed alice's `HLC(1002:0:alice)`
   and then bob's `HLC(1001:0:bob)` was filtered out because
   1001 < 1002. Fix: track which HLCs are already in the local log
   (set of `hlc + class name`) and skip only those, regardless of
   HLC ordering.
2. **LinkedHashMap iteration order broke convergence assertions**:
   the doc snapshot uses `LinkedHashMap` to preserve insertion
   order, but each node's insertion order diverges (alice had
   `{a=1, b=2}` while bob had `{b=2, a=1}`). `assertEquals` on
   `LinkedHashMap` checks both keys and iteration order, so the
   convergence test failed even though the maps had the same
   content. Fix: compare using `toSortedMap()` so iteration order
   is normalized.

## Quality

- Tests: **569** (+22 in this range: 12 OpLog + 10 Sync).
- Failures: **0**.
- `assembleDebug`: **green**, 165 MB APK (clean rebuild, smaller
  than the cached 248 MB).
- End-to-end protocol proven via tests: two-node sync, late-
  arriving nodes, three-way merge, idempotent re-absorb,
  serialization round-trip.

## What this unlocks

- A `CrdtDoc` + `CrdtSequence` backed by `CrdtOpLog` can now
  synchronize over any byte transport. Phase 9.9.7+ work:
  - Wire the protocol over `LocalServer` HTTP for in-WiFi sync.
  - Wire it over `SftpServer` for cross-internet sync.
  - Add a `.elysium` companion file (`<doc>.elysium.sync`) that
    stores the log so two devices editing the same document
    converge even after a full offline period.

— elysium-autopilot