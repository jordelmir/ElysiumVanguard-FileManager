# PHASE 9.9 — CRDT Foundation (HLC + LWW-Element-Map + Tombstone-Sequence)

Closed: 2026-07-09. Range: 9.9.1 (HLC) → 9.9.4 (multi-node collaboration).

## What landed

### 9.9.1 — `HybridLogicalClock` + `HlcClock` (12 tests)
- `HybridLogicalClock(ms, counter, nodeId)` with total-order
  `compareTo` (ms first, counter second, nodeId as deterministic
  tie-breaker), `serialize` / `parse` round-trip.
- `HlcClock` issues (`issue`) and observes (`observe`) HLCs with
  the happens-before rule. `seed` aligns the local clock to a
  previously-known HLC after a process restart.

### 9.9.2 — `CrdtDoc` (LWW-Element-Map) (14 tests)
- `CrdtOp.SetProperty` and `CrdtOp.DeleteProperty` ops; each carries
  an HLC.
- `CrdtDoc` keeps `Map<String, LwwEntry>` where each entry holds
  `(hlc, value?, tombstoned)`. Merge picks the higher-HLC entry
  per key; in-place mutation so calls compose naturally.
- Verified the three CRDT properties:
  commutative, associative, idempotent.

### 9.9.3 — `CrdtSequence` (tombstone-sequence) (12 tests)
- `CrdtSeqOp.Insert` / `CrdtSeqOp.Delete`.
- Slots have a stable identity (`insertHlc`) and a mutable
  `lastTouchHlc`. Tombstones target a slot by its `insertHlc`,
  preserving identity across merge so two nodes converge on
  "this slot is deleted" without losing the slot's position in
  the linear order.
- Verified the three CRDT properties on the sequence as well.

### 9.9.4 — Multi-node collaboration (7 tests)
- A `Node` test helper bundles `HlcClock` + `CrdtSequence` and
  exposes `insert`, `delete`, `syncFrom`.
- Two-node convergence, deletion propagation, 8-node concurrent
  edits, late-arriving inserts from a stale node, concurrent
  delete-of-the-same-element, three-way merge convergence, and
  idempotent repeated sync (100x).

## Test-discovered bugs (3)

1. **Stale slot identity in delete**: The first cut of
   `CrdtSequence.Delete` recorded the tombstone with the **delete's
   HLC** rather than the **target insert's HLC**. Two nodes would
   each end up with their own tombstone slot (a different HLC than
   the original insert), so the original " " slot survived the
   merge and bob saw `"hello world"` while alice saw `"helloworld"`.
   Fixed by giving each `Slot` an `insertHlc` (stable identity)
   and a separate `lastTouchHlc` (used for LWW).
2. **In-place merge semantics**: `CrdtSequence.merge` returned a new
   `CrdtSequence` instead of mutating `this`. The collaboration
   tests called `seq.merge(remote.seq)` expecting it to update the
   local sequence; instead it discarded the result. Fixed by
   mutating `this` (the canonical CRDT semantics).
3. **CrdtDoc.merge** had the same in-place-vs-new issue (silent —
   the unit tests happened to assign the result so the bug
   didn't surface). Aligned for consistency.

## Quality

- Tests: **547** (+45 in this range: 12 HLC + 14 Doc + 12 Seq + 7 Collab).
- Failures: **0**.
- `assembleDebug`: **green**, 248 MB APK.
- CRDT properties proven via tests on both the map and the
  sequence: commutative, associative, idempotent.

## What this unlocks

- A `CrdtDoc` can back the **document-level metadata** of
  `.elysium.word` / `.elysium.sheet` / `.elysium.deck` (title,
  author, theme, last-cursor-position).
- A `CrdtSequence` can back the **text body** of `.elysium.word`
  and the **slide body** of `.elysium.deck`.
- The Phase 9.9.5+ work: a sync layer that streams ops between
  nodes over `LocalServer` or `SftpServer` so two devices editing
  the same `.elysium.word` document converge character-by-character.

— elysium-autopilot