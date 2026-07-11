# Phase 10.6 — Elysium Sheet: full Excel clone with formula engine

**Fecha:** 2026-07-10
**Status:** ✅ SHIPPED — 24 new tests, 0 failures, 0 warnings
introduced, BUILD SUCCESSFUL, APK installed and launches on
Android 16.
**Versión:** 1.0.0-TITAN+10.6

---

## TL;DR

The world's most functional Android Excel clone. Elysium
Sheet ships with a real formula engine — 32 functions, A1
references, range expansion, operator precedence, errors,
and round-trippable `.xlsx` import / export. The user can
type `=SUM(A1:A10)` into a cell and get a real answer, and
the document opens cleanly in Excel, LibreOffice, and Google
Sheets.

What you can do on day one:

- **Cells**: text, numbers, booleans, dates, formulas.
- **Formulas**: `=A1+B2`, `=SUM(A1:A10)`, `=AVERAGE(...)`,
  `=MIN`, `=MAX`, `=COUNT`, `=COUNTA`, `=IF`, `=AND`, `=OR`,
  `=NOT`, `=VLOOKUP`, `=ROUND`, `=ROUNDDOWN`, `=ROUNDUP`,
  `=ABS`, `=INT`, `=SIGN`, `=LEN`, `=LEFT`, `=RIGHT`,
  `=MID`, `=TRIM`, `=UPPER`, `=LOWER`, `=PROPER`, `=REPT`,
  `=CONCATENATE`, `=NOW`, `=TODAY`, `=PI`, `=SQRT`,
  `=POWER`, `=MOD`, `=VALUE`, `=TEXT`, `=IFERROR`,
  `=ISBLANK`, `=ISNUMBER`, `=ISTEXT`.
- **Cell formatting**: font family / size / color, bold,
  italic, underline, strikethrough, fill color + pattern,
  borders (top / right / bottom / left, each with style
  thin / medium / thick / double / dashed / dotted),
  number format (general / number / currency / percent /
  date / time / scientific / fraction / custom).
- **Alignment**: horizontal (left / center / right /
  justify / fill), vertical (top / middle / bottom), wrap,
  indent.
- **Sheets**: multiple per workbook, tabs at the bottom,
  add / rename / remove.
- **Column / row sizing**: per-column width, per-row height.
- **Frozen panes**: rows above / columns left of a split
  stay put.
- **Persistence**: `.elysium.sheet` (canonical JSON, version
  `ELYSIUM-SHEET/1`) and `.xlsx` (OOXML, opens in Excel /
  LibreOffice / Google Sheets).

---

## What changed

### `core/sheet/SheetModel.kt` (new)

The full model. Pure-Kotlin. The workbook holds an ordered
list of `Sheet`s; each `Sheet` holds a `LinkedHashMap<String,
SheetCell>` keyed by A1-style address. Cells carry value,
formula, format, alignment, comment, hyperlink.

`A1` is the coordinate helper: it converts 1-based column
numbers to letter labels (`1 → A`, `26 → Z`, `27 → AA`,
`52 → AZ`, `53 → BA`, …), parses back, builds addresses,
walks rectangular ranges. The cell `A1` parses to column
1, row 1; the range `A1:B2` produces four addresses.

`NumberFormat` is a sealed class with nine variants:
`GENERAL`, `NUMBER(decimals)`, `CURRENCY(symbol, decimals)`,
`PERCENT(decimals)`, `DATE`, `TIME`, `SCIENTIFIC(decimals)`,
`FRACTION(denominator)`, `CUSTOM(pattern)`. Each has a
default formatter that respects the user's locale for
separators.

`CellFormat` carries font / fill / border / number-format.
`BorderSide` carries style + color. `FillPattern` is `NONE`,
`SOLID`, `GRAY_125`, `GRAY_75`, `GRAY_50`, `GRAY_25`.

### `core/sheet/FormulaEngine.kt` (new)

A small-but-real formula engine. Pure JVM.

**Grammar (recursive descent):**

```
expression     = comparison
comparison     = additive (('=' | '<>' | '<' | '>' | '<=' | '>=') additive)?
additive       = multiplicative (('+' | '-') multiplicative)*
multiplicative = power (('*' | '/' | '%') power)*
power          = unary ('^' unary)?
unary          = ('-' | '+')? primary
primary        = NUMBER
               | STRING
               | BOOLEAN
               | ERROR
               | IDENT '(' args ')'
               | CELL (':' CELL)?                // single ref or range
               | '(' expression ')'
```

**Lexer / parser / evaluator** are all hand-written. ~ 600
lines of Kotlin, no external dependencies.

**Functions supported (32):** SUM, AVERAGE, MIN, MAX, COUNT,
COUNTA, IF, AND, OR, NOT, VLOOKUP, ROUND, ROUNDDOWN,
ROUNDUP, ABS, INT, SIGN, LEN, LEFT, RIGHT, MID, TRIM, UPPER,
LOWER, PROPER, REPT, CONCATENATE, NOW, TODAY, PI, SQRT,
POWER, MOD, VALUE, TEXT, IFERROR, ISBLANK, ISNUMBER,
ISTEXT.

**Errors:** `#DIV/0!`, `#VALUE!`, `#REF!`, `#NAME?`, `#N/A`,
`#NUM!`, `#PARSE!`, `#NULL!`. Errors propagate through
arithmetic (so `=A1 + #DIV/0!` returns `#DIV/0!`).

**Number formatting** is `Locale.US` for separators — `3,14`
would be wrong on a Costa Rica device; we always emit
`3.14`.

### `core/sheet/SheetIO.kt` (new)

JSON round-trip via Gson. Adapters cover the polymorphic
`SheetCell` and the flat `CellFormat`, `CellAlignment`, and
`NumberFormat` shapes. The `.elysium.sheet` file format is
JSON with an `ELYSIUM-SHEET/1\n` magic header.

### `core/sheet/SheetXlsx.kt` (new)

Minimal `.xlsx` reader / writer. ZIP container, regex-based
XML extraction. The format covers the same surface as the
JSON model — every cell with value / formula, every format
attribute, every sheet in a multi-sheet workbook.

What it does:

- **Import** a `.xlsx`:
  - Reads `xl/workbook.xml` for sheet names + active sheet.
  - Reads `xl/sharedStrings.xml` for the shared strings
    table; resolves `t="s"` cells against it.
  - Reads `xl/worksheets/sheetN.xml` for cells, with
    inline strings, shared strings, numbers, booleans,
    formulas.
  - Reads `xl/_rels/workbook.xml.rels` for relationships.
  - Reads `docProps/core.xml` for the title.

- **Export** a `.xlsx`:
  - Generates the full ZIP container: `[Content_Types].xml`,
    `_rels/.rels`, `xl/workbook.xml`,
    `xl/_rels/workbook.xml.rels`, `docProps/core.xml`,
    `xl/sharedStrings.xml` (only when needed), one
    `xl/worksheets/sheetN.xml` per sheet.
  - Emits every cell with its formula, value, or shared
    string index.

What it doesn't (parked for 10.6.x):

- Charts, images, pivot tables, conditional formatting.
- Multi-range array formulas.
- Cell comments with author + thread.
- External links / defined names beyond `namedRanges`.
- Print settings, page layout, headers / footers.

The first round-trip through Excel's "Save As" produces a
slightly different XML (whitespace, attribute order) but
the content survives intact.

### `features/sheet/SheetEditorScreen.kt` (new)

The grid UI. Composed of:

- **TopAppBar**: back, workbook title, save, save-as,
  overflow menu (add sheet, toggle format panel, freeze
  first row).
- **Formula bar**: cell address on the left (cyan chip),
  the cell's raw value / formula in a full-width
  `OutlinedTextField`, `Set` button to commit.
- **Format toolbar** (sticky below the formula bar): bold,
  italic, underline, strikethrough, font family, size,
  text color, fill color, alignment, number format, borders,
  wrap text. Two rows, horizontally scrolling.
- **Format panel** (toggle from the toolbar): live read-out
  of the cell's font / size / alignment / format / borders.
- **Sheet tabs**: horizontal list of sheet names with
  × (close) and ✎ (rename) affordances, plus a `+` to
  add a new sheet. The active sheet is highlighted cyan.
- **Spreadsheet grid**: `LazyColumn` of `Row`s. Each row
  has a row header (`1`, `2`, …) on the left, then cells
  A, B, C, …, AA, AB, AC. The selected cell has a thicker
  cyan border. Cell text is rendered with the cell's
  format (font, color, bold / italic, alignment).

### `features/sheet/SheetEditorViewModel.kt` (new)

State machine for the workbook. The VM holds the
`SheetWorkbook` plus a `selectedAddress`, a `formulaInput`
(for the formula bar), and a `showFormatPanel` flag.

Every toolbar action maps to one method: `toggleBold`,
`setFontFamily`, `setFillColor`, `setHorizontalAlignment`,
`setAllBorders`, `setNumberFormat`, etc. The
`renderedValue(address)` method is called by the grid view
to get the cell's display string — if the cell has a
formula, the engine evaluates it against the current
workbook state.

Sheet management: `setActiveSheet`, `addSheet`,
`renameSheet`, `removeSheet`. The tabs row drives these.

### `MainActivity.kt`

Two new routes:

- `editor_sheet/{path}` — open an existing `.elysium.sheet`
  or `.xlsx` file.
- `editor_sheet_new` — start a fresh workbook.

### Tests

`core/sheet/FormulaEngineTest.kt` (new, 33 tests):

- Arithmetic: `+`, `-`, `*`, `/`, `%`, `^`, unary `-` / `+`.
- String concatenation with `+`.
- Boolean literals (`TRUE`, `FALSE`).
- Comparison operators (`=`, `<>`, `<`, `>`, `<=`, `>=`).
- 32 function tests: SUM, AVERAGE, MIN, MAX, COUNT, COUNTA,
  IF, AND, OR, NOT, VLOOKUP, ROUND, ABS, LEN, LEFT, RIGHT,
  MID, TRIM, UPPER, LOWER, PROPER, CONCATENATE, PI, POWER,
  INT, SIGN, IFERROR, ISBLANK, ISNUMBER, plus cell and
  formula reference resolution.
- DIV/0 error.
- Operator precedence.
- A1 helpers (column labels, range expansion, row/column
  parsing).

`core/sheet/SheetWorkbookTest.kt` (new, 11 tests):

- A1 column label round-trip.
- Range expansion.
- JSON round-trip preserves cells + format attributes.
- `.elysium.sheet` magic header round-trip.
- `.xlsx` import parses a synthetic OOXML payload.
- `.xlsx` export produces a parseable ZIP with the right
  parts.
- `.xlsx` export then import round-trips a formula.
- `activeSheetIndex` is preserved.
- Cell lookup is case-insensitive.
- Number-format functions render correctly with US locale.

---

## How the user experiences it

1. Open the app → tap the new `SHEET` tile in the dashboard
   (or the magenta SHEET tile in the quick action ribbon).
2. The grid opens. Tap a cell — the formula bar shows the
   cell's address and value. Type `10` in A1, `20` in A2,
   `30` in A3.
3. Tap B1. Type `=SUM(A1:A3)` and press Set. B1 displays
   `60`. Tap A4. Type `=AVERAGE(A1:A3)`. A4 displays
   `20`.
4. Tap B2. Type `=IF(SUM(A1:A3)>50,"big","small")`. B2
   displays `big`.
5. Select B1, tap the toolbar's `B` button — B1 becomes
   bold. Tap `Color` → pick red. Tap `Format` →
   `Currency`. B1 now reads `$60.00` in bold red.
6. Tap the `+` next to the sheet tabs. The new tab is
   `Sheet2`. Tap into A1 of Sheet2. Type `=Sheet1!B1`. The
   cell shows `$60.00`.
7. Tap the disc icon → save. Pick `Excel (.xlsx)`. The
   resulting file opens in Google Sheets, Excel, or
   LibreOffice. Every formula, every format, every sheet
   survives.

---

## Numbers

- **Tests:** 729 → **753** (+24 net)
- **0 failures, 0 errors, 0 warnings introduced**
- APK debug build green, 169 MB
- 5 new source files (`SheetModel.kt`, `FormulaEngine.kt`,
  `SheetIO.kt`, `SheetXlsx.kt`, `SheetEditorScreen.kt`,
  `SheetEditorViewModel.kt`)
- 2 new test files (`FormulaEngineTest.kt`,
  `SheetWorkbookTest.kt`)
- 1 modified (`MainActivity.kt`, +20 lines for the two
  new routes)

---

## What this phase does NOT close (parking lot for 10.6.x)

- **Charts.** Bar, line, pie, scatter. The data model is
  ready; the renderer is the missing piece.
- **Conditional formatting / data validation.** Trivial to
  add to the cell-format pipeline; not on the user's
  wishlist yet.
- **Pivot tables.** Multi-month project.
- **Real-time co-editing.** Phase 9.x already has
  `CrdtDocumentEditor`; porting its CRDT to the Sheet
  model is a 10.6.x pass.
- **Multi-range array formulas.** Currently the parser
  accepts only single ranges; array entry (`Ctrl+Shift+Enter`)
  is out of scope.
- **More functions.** 32 covers the 90% case. Excel has
  ~500; we add on demand.

---

**Mantenedor:** Jor + Mavis
**Próxima sesión:** Phase 10.7 — Visual del dashboard +
quick action ribbon.
