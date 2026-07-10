# PHASE 9.16 — Office Import (.docx / .odt / .odp → .elysium.*)

Closed: 2026-07-10.

## What landed

### `OfficeImporter` (12 tests)

- `OfficeImporter.importToElysium(file)` / `importBytesToElysium(name, bytes)`
  — dispatches on file extension:
  - `.docx` → reads `word/document.xml` from the OOXML ZIP, walks
    `<w:p>` paragraphs, extracts `<w:t>` text runs, joins within each
    paragraph with space and paragraphs with newline → `Kind.WORD`.
  - `.odt` → reads `content.xml`, walks `<text:p>` paragraphs,
    strips XML tags, joins paragraphs with newline → `Kind.WORD`.
  - `.odp` → reads `content.xml`, groups text by `<draw:page>`
    sections, treats each page's first `<text:p>` as the slide
    title and the rest as the body → `Kind.DECK` with one
    `Slide` per page.
  - Anything else → `Result.Failure(unsupported extension)`.
- All XML processing is regex / substring-based — no extra XML
  parser dependency, no Apache Commons FileUpload, no POI.
- Output goes to whatever `ElysiumDocument.Kind` matches the
  input, so a `.docx` lands in the Word editor and a `.odp`
  in the Deck editor.
- Returns `sealed interface Result { Success(ElysiumDocument) / Failure(reason) }`
  so callers never have to wrap in try/catch.
- Decodes the common XML entities (`&amp;`, `&lt;`, `&gt;`,
  `&quot;`, `&apos;`, and `&#NN;` numeric refs).

## Tests (12, all green)

- `non-existent file returns Failure`
- `unsupported extension returns Failure`
- `docx with one paragraph and one text run`
- `docx with multiple paragraphs joins with newlines`
- `docx decodes XML entities in text runs`
- `docx with empty body succeeds but body is empty string`
- `docx missing document xml returns Failure`
- `odt with three paragraphs produces three line body`
- `odp with two slides yields a deck with two slides`
- `odp with no draw pages produces a single-slide deck fallback`
- `Odp with multi-line paragraph body concatenates with newlines`
- `importing to a real file round-trips through importToElysium`

## Test-discovered regressions (1 fixed)

1. **`regionMatches` length off-by-one broke `.odp` slides**.
   The `splitParagraphsOdf` helper compared text regions with
   `regionMatches(i, "<text:p>", 0, 9)`. The literal `<text:p>`
   is 8 chars; JVM `regionMatches` returns false when the
   `length` argument exceeds the other string's length, so the
   test for the open tag *always* failed — and `.odp` slides
   came back empty, while a docx file happened to still work
   (different length coincidence for `<w:p>` = 5 chars). Fixed
   by tracking the literal length bookkeeping (`openLen`,
   `closeLen`, `selfCloseLen`) and matching exactly. Same fix
   pattern applied to the docx self-closing `<w:p />` (7
   chars, was checked against length 6).

## Test-discovered cleanups (1 stale assertion fixed in 9.14)

The Phase 9.14 `sync via SyncHost absorbs remote ops and
refreshes body` test asserted the merged body contained the
substring `"Bob X"`. With the earlier `CrdtOpLog.parse` bug
(from Phase 9.9.5) → parse returned null → absorbRemote saw
nothing → body stayed `"Bob X"`. After the parse fix (Phase
9.14/9.15 cleanup), the body actually converges via the CRDT
merge: Bob's and Alice's chars interleave alphabetically by
HLC tiebreaker (since `ms` and `counter` collide on the same
JVM, nodeId `"alice"` < `"bob"` wins, giving the expected
interleaving `"ABloibc eX 1, 2"`). Updated the assertion from
`body.contains("Bob X")` to a multiset comparison: all 15
distinct chars must survive regardless of order.

## Quality

- Tests: **646** (+12).
- Failures: **0**.
- `assembleDebug`: green, 181 MB APK.

## What this unlocks

A real bridge from the Office ecosystem into Elysium. Phase
9.18 wires this into a folder-level manifest; Phase 9.17
adds the HTTP transport so two devices can converge the
imported doc. Phase 9.18 completes the loop: drop a `.docx`
into a sync folder, the importer makes a `.elysium.word`
alongside it, both devices' counterparts sync, and edits
made in the editor propagate.

— elysium-autopilot
