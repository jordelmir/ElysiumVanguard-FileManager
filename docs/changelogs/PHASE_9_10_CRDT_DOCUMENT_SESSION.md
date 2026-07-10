# PHASE 9.10 — CRDT-backed Document Editing Session

Closed: 2026-07-09.

## What landed

### 9.10 — `CrdtDocumentSession` (10 tests)
- `CrdtDocumentSession(doc, file, nodeId, initialClock?)` — the
  runtime counterpart to `CrdtElysiumDocument`. Wraps a
  CRDT-backed document and ties it to a file on disk.
- Local ops:
  - `insertCharacter(ch)` — appends a character to the body
    with a fresh HLC.
  - `deleteCharacterAt(liveIndex)` — tombstone the slot at the
    given live index.
  - `setTitle(newTitle)` / `setAuthor(newAuthor)` — replace or
    clear metadata fields.
  - `bodyLength()` / `bodyAsString()` — read views.
- `save()` — serializes the CRDT state back to a plain
  `ElysiumDocument` and writes it to the file.
- `open(file, nodeId)` and `create(file, kind, nodeId)` factory
  methods.
- `CrdtElysiumDocument.fromElysiumDocument(doc, nodeId, clock?)`
  now accepts an optional `HlcClock` so the caller can keep the
  clock state across multiple operations and not lose the
  causality guarantee.

## Test-discovered bugs (1)

1. **Clock drift across open()**: the first cut of
   `CrdtDocumentSession` had its own `HlcClock` that started at
   `(0, 0)`. After `open()` populated the body via the doc's
   internal clock, the session's first `insertCharacter` issued
   an HLC that could be LOWER than the seeded body chars'
   HLCs — the new char then sorted to the BEGINNING of the
   sequence (e.g. `"Hi" + "!"` → `"!Hi"` instead of `"Hi!"`).
   Fixed by seeding the session's clock from the highest HLC
   already in the document (metadata + body), and threading the
   same `HlcClock` instance through
   `CrdtElysiumDocument.fromElysiumDocument` so the initial seed
   ops and the runtime ops come from a single clock.

## Quality

- Tests: **590** (+10).
- Failures: **0**.
- `assembleDebug`: **green**, 181 MB APK.

## What this unlocks

- A `CrdtDocumentEditor` Compose screen can wrap a
  `CrdtDocumentSession` and render the body in a `TextField`,
  issuing insert/delete ops per keystroke. Saving writes back
  to the file. Multiple devices opening the same file (after
  exchanging their op logs via the anti-entropy protocol) would
  converge character-by-character without any UI rewrite.
- The `bodyAsString()` snapshot is the same shape the
  `ElysiumDocumentViewer` already renders, so the editor and
  viewer can share a single rendering layer.

— elysium-autopilot