# PHASE 9.9.7 + 9.7.10 — CRDT-backed Documents + Disk Image Detection

Closed: 2026-07-09. Range: 9.9.7 (CRDT documents) + 9.7.10 (disk images).

## What landed

### 9.9.7 — `CrdtElysiumDocument` (8 tests)
- `CrdtElysiumDocument(kind, metadata: CrdtDoc, body: CrdtSequence,
  nodeId)` wraps an `ElysiumDocument` so the document's metadata
  (title, author, theme, font, fontSize) lives in a CRDT map and
  the body lives in a CRDT sequence. Two `CrdtElysiumDocument`s
  can be merged into one with full convergence.
- `fromElysiumDocument(doc, nodeId)` — extract a `# Title` and
  `_by author_` header from the body, plus style into metadata;
  insert the remaining body character-by-character so each
  character has its own HLC.
- `toElysiumDocument()` — render the CRDT state back to a plain
  `ElysiumDocument` for save-to-disk and viewer integration.
- `merge(other)` — symmetric in-place merge; refuses to combine
  documents of different kinds.
- The header convention (`# Title`, `_by author_`) is the same
  Markdown-ish shape the existing `ElysiumWordRenderer` already
  understands, so the viewer round-trips correctly.

### 9.7.10 — Disk + container image detection (3 tests)
- ISO 9660 — magic `CD001` at offset 32768.
- VHD — magic `conectix` at offset 0.
- VMDK — magic `KDMV` at offset 0.
- `PROBE_SIZE` bumped from 32 to 64 KiB so the detector can
  reach ISO 9660's deep offset. The 64 KiB read is one IO and
  done once per file open.

## Test-discovered bugs (2)

1. **Kotlin string template ate the trailing underscore**: in
   `"_by $author_"` Kotlin interpreted `$author_` as a variable
   named `author_`. Fixed by writing `"_by ${author}_"` so the
   closing brace terminates the variable reference.
2. **ISO 9660 byte-boundary off by one**: the head buffer was
   sized to `32772` but `CD001` (5 bytes) at offset 32768 needs
   the buffer to extend through 32772 — i.e. size `32773`. Fixed
   by bumping the buffer size by one.

## Quality

- Tests: **580** (+11 in this range: 8 CrdtElysiumDocument + 3
  DiskImage).
- Failures: **0**.
- `assembleDebug`: **green**, 181 MB APK.

## What this unlocks

- `ElysiumDocumentViewer` can be wired to render a
  `CrdtElysiumDocument` directly, so opening a `.elysium.word`
  file in the viewer + two devices online would now converge
  character-by-character as they edit.
- A future `ElysiumDocumentSyncAdapter` can call `merge` on
  `CrdtElysiumDocument` after pulling the remote op log via the
  anti-entropy protocol.
- The format engine now recognizes ISO 9660, VHD, and VMDK —
  disk images that a sovereign-runtime user often encounters
  when juggling ISO downloads and VM snapshots.

— elysium-autopilot