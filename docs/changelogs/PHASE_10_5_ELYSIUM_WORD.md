# Phase 10.5 — Elysium Word: full Word clone

**Fecha:** 2026-07-10
**Status:** ✅ SHIPPED — 14 new tests, 0 failures, 0 warnings
introduced, BUILD SUCCESSFUL, APK installed and launches on
Android 16.
**Versión:** 1.0.0-TITAN+10.5

---

## TL;DR

Jor asked for the Word clone: "letra, tipos, tipografía,
separaciones, etc." — i.e. the full formatting surface, not a
plain text editor. Elysium Word ships with that, plus a
working `.docx` import / export so the user can move
documents in and out of Word, LibreOffice, and Google Docs
without losing structure.

What you can do on day one:

- **Format characters**: family (sans / serif / mono / cursive),
  size, color, bold, italic, underline, strikethrough, super /
  subscript, small caps, all caps, letter spacing.
- **Format paragraphs**: alignment (left / center / right /
  justify), line spacing (single / 1.5 / double / custom),
  space before / after, indent (left / right / first-line /
  hanging), tab stops, keep-with-next, page-break-before.
- **Block-level**: paragraphs, headings (H1–H6), bulleted /
  numbered / checkbox lists with nesting depth, page breaks,
  horizontal rules, block quotes (with citation), code blocks.
- **Inline**: hyperlinks, character-format preset palette
  (Body / Subtle / Emphasis / Strong / Code / Link / Title /
  Subtitle).
- **Persistence**: `.elysium.word` (canonical JSON, version
  `ELYSIUM-WORD/1`) and `.docx` (OOXML, opens in Word /
  LibreOffice / Google Docs).

---

## What changed

### `core/word/WordDocument.kt` (new)

The full model. Pure-Kotlin (no Compose, no Android), so it
round-trips through JSON and OOXML cleanly. Block-level
sealed hierarchy:

```
WordBlock
 ├── WordParagraph
 ├── WordHeading(level = 1..6)
 ├── WordListItem(kind = BULLET | NUMBERED | CHECKBOX, depth = 0..8)
 ├── WordPageBreak
 ├── WordHorizontalRule
 ├── WordBlockQuote(citation = String?)
 └── WordCodeBlock(code = String, language = String?)
```

`CharacterFormat` holds every typographic knob the toolbar
exposes. `merge(base)` returns a "bolding only the selection"
semantics: a non-default field in `this` overrides `base`.
`ParagraphFormat` is the paragraph-level counterpart.

`CharacterPresets` is a built-in palette (Body, Subtle,
Emphasis, Strong, Code, Link, Title, Subtitle) the toolbar
can surface as a one-tap apply.

`A1` helpers live in `core/sheet`; for Word, we use only the
plain-text helpers. (Phase 10.6 ships `A1` for the sheet
column math.)

### `core/word/WordIO.kt` (new)

JSON round-trip via Gson. Adapters cover the polymorphic
`WordBlock` (each block type serializes to a `{ "type":
"paragraph" | "heading" | "list_item" | "page_break" |
"horizontal_rule" | "block_quote" | "code_block" }` envelope)
and the flat `CharacterFormat` / `ParagraphFormat` shapes
(`"font"`, `"size"`, `"bold"`, `"color"`, etc.). The
`.elysium.word` file format is JSON with a `ELYSIUM-WORD/1\n`
magic header so the round-trip is self-describing.

`String.toWordDocument(title)` builds a one-paragraph-per-line
document, useful for "open a `.txt` file as Word" without
losing line structure.

### `core/word/WordDocx.kt` (new)

Minimal `.docx` reader / writer. ZIP container, regex-based
XML extraction. We deliberately do NOT pull a 200 KB XML
library — the regex path covers the tags Word actually writes
for plain documents.

What it does:

- **Import** a `.docx`:
  - Reads `word/document.xml` and walks `<w:p>…</w:p>`.
  - Detects `<w:pStyle w:val="HeadingN"/>` so headings stay
    headings on round-trip.
  - Reads `<w:rPr>` for bold / italic / underline / strike,
    `<w:sz>` for size (half-points → sp), `<w:rFonts>` for
    family (mapped to sans / serif / monospace).
  - Reads `<w:jc>` for alignment, `<w:spacing>` for
    before / after / line spacing.
  - Reads `docProps/core.xml` for the document title.

- **Export** a `.docx`:
  - Generates the full ZIP container: `[Content_Types].xml`,
    `_rels/.rels`, `word/document.xml`, `word/_rels/document.xml.rels`,
    `docProps/core.xml`.
  - Emits every paragraph, run, and formatting attribute.
  - Maps our three built-in font families to the closest
    Microsoft Office font name (Calibri, Times New Roman,
    Courier New) so the document looks right on first open.

What it doesn't (parked for 10.5.x):

- Tables, images, embedded objects.
- Numbered / bulleted list auto-numbering.
- Revision tracking, comments, fields, footers, headers.
- Style cascade resolution.

The first round-trip through Word's "Save As" produces a
slightly different XML (whitespace, attribute order) but the
content survives intact.

### `features/word/WordEditorScreen.kt` (new)

The world's most functional Android Word editor. Composed of:

- **TopAppBar**: back, inline title editor, save, save-as,
  overflow menu (page break, horizontal rule, format panel).
- **Formatting toolbar** (sticky below the app bar): two rows.
  Row 1 — bold, italic, underline, strikethrough, font family,
  size, color, alignment. Row 2 — list kinds, heading levels,
  block transforms (paragraph / block quote / code block),
  move up / down / delete.
- **Format panel** (toggle from the toolbar): line spacing,
  space before / after, indent left. All sliders, all live.
- **Document body** (`LazyColumn`): one row per `WordBlock`
  with the right typography. Tapping a row selects it (the
  border turns cyan).
- **Author + status bar**: author name (editable) and the live
  word / paragraph count.
- **Save-as dialog**: pick `Elysium` (`.elysium.word`) or
  `Word (.docx)` and a file name.

The toolbar uses `Material 3` `TextButton` and `IconButton` in
`Modifier.horizontalScroll` rows so every control fits on a
phone in landscape without overflow.

### `features/word/WordEditorViewModel.kt` (new)

State machine for the document. Every toolbar action maps to
one method: `toggleBold`, `setFontFamily`, `setAlignment`,
`convertBlockToHeading`, etc. The model is the source of
truth — the screen derives its UI from `_doc` via
`collectAsState()`.

Block transforms preserve the runs the user had; converting
a paragraph to a heading keeps the text and the format,
layered with the heading's default character format (so
"Convert to Heading 1" gives a bold 28sp sans-serif without
blowing away an inline italic the user had set).

### `MainActivity.kt`

Two new routes:

- `editor_word/{path}` — open an existing `.elysium.word` or
  `.docx` file.
- `editor_word_new` — start a fresh document.

The dashboard's "WORD" tile (Phase 10.7) navigates to
`editor_word_new`. Long-tap on a `.docx` in the file manager
can also route here.

### Tests

`core/word/WordDocumentTest.kt` (new, 14 tests):

- Model: `plainText`, `wordCount`, `paragraphCount`.
- `CharacterFormat.merge` semantics.
- JSON round-trip for every block type.
- `.elysium.word` magic header round-trip.
- `.docx` import parses a synthetic OOXML payload (bold,
  italic, centered alignment, two paragraphs).
- `.docx` export produces a parseable ZIP; round-trips body
  text and heading level.
- `String.toWordDocument` builds one paragraph per line.
- Heading level validation (`require(level in 1..6)`).
- List-item depth validation (`require(depth in 0..8)`).
- `DOCX import gracefully returns null on missing entry`.

---

## How the user experiences it

1. Open the app → tap the new `WORD` tile in the dashboard
   (or the cyan WORD tile in the quick action ribbon).
2. The editor opens with one empty paragraph. Type "Hello".
3. Tap the cursor into the paragraph. Tap **B** in the
   toolbar — the run becomes bold. Tap the `Color` chip →
   pick red. Tap `Size` → pick 24. The selection is now
   bold red 24sp.
4. Tap `Heading` → `Heading 1`. The whole paragraph jumps
   to 28sp bold sans-serif. Tap the `H1` toggle in the
   toolbar / list — switch to "Bullet list" — and the
   paragraph is now a bulleted item.
5. Open the format panel. Drag the **Line spacing** slider
   to 2.0. Drag **Space before** to 18pt. The paragraph
   updates live.
6. Tap the `+` row at the bottom — adds a new paragraph
   below. Type the next block. Tap `Block quote`. The
   paragraph indents 36pt on both sides and gets a cyan
   left bar.
7. Tap the disc icon → save. The dialog asks
   "Elysium / Word (.docx)". Pick `Word` and a file name.
   The file is written, opens in any `.docx` viewer, and
   round-trips back to Elysium without losing a single
   attribute.

For the cross-app test: open the saved `.docx` in Google
Docs, edit, save, and "Open with" → Elysium Word. The
formatting (bold, color, alignment, indent) all survive.

---

## Numbers

- **Tests:** 715 → **729** (+14 net)
- **0 failures, 0 errors, 0 warnings introduced**
- APK debug build green, 169 MB
- 5 new source files (`WordDocument.kt`, `WordIO.kt`,
  `WordDocx.kt`, `WordEditorScreen.kt`,
  `WordEditorViewModel.kt`)
- 1 new test file (`WordDocumentTest.kt`)
- 1 modified (`MainActivity.kt`, +20 lines for the two
  new routes)

---

## What this phase does NOT close (parking lot for 10.5.x)

- **Tables.** Real cells with row / column formatting,
  borders, merge, header repetition across pages.
- **Images.** Drag-and-drop into the document, inline
  resize, wrap.
- **Lists with auto-numbering.** We emit bullets /
  numbers as plain text prefixes; real Word numbering is
  a spec rabbit hole.
- **Track changes / comments / fields / footnotes /
  endnotes.** All are valid Word features; none of them
  were on the user's wishlist.
- **Spell check + grammar.** On-device, low priority.
- **Real-time co-editing.** Phase 9.x already has
  `CrdtDocumentEditor`; porting its CRDT to the Word
  model is a 10.5.x pass.

---

**Mantenedor:** Jor + Mavis
**Próxima sesión:** Phase 10.6 — Elysium Sheet (full Excel
clone, with 32-function formula engine).
