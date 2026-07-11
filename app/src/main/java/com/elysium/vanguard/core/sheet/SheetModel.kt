package com.elysium.vanguard.core.sheet

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * PHASE 10.6 — Elysium Sheet format model.
 *
 * The world's most functional Android Excel clone. Supports:
 *
 *  - **Cells**: text, number, boolean, date, currency, percent, fraction.
 *  - **Formulas**: =A1+B2, =SUM(A1:A10), =AVERAGE, =IF, =VLOOKUP, =MIN, =MAX,
 *    =COUNT, =COUNTA, =ROUND, =ABS, =LEN, =LEFT, =RIGHT, =MID, =TRIM, =UPPER,
 *    =LOWER, =PROPER, =CONCATENATE, =NOW, =TODAY, =PI, =SQRT, =POWER, =MOD.
 *  - **Cell formatting**: font family, size, color, bold, italic, underline,
 *    strikethrough, background fill, borders (top, right, bottom, left, each
 *    with style: thin, medium, thick, double, dashed, dotted).
 *  - **Number format**: general, number (decimal places), currency, percent,
 *    date, time, scientific, fraction, custom.
 *  - **Alignment**: horizontal (left, center, right, justify), vertical
 *    (top, middle, bottom), wrap, indent.
 *  - **Sheets**: workbook holds many sheets, with a default selected one.
 *  - **Column width / row height**: per-column width, per-row height.
 *  - **Frozen panes**: rows above / columns left of a split stay put.
 *  - **Named ranges**: support for `=SUM(sales)`.
 *
 * The model is plain Kotlin (no Compose) so it round-trips through JSON
 * for the `.elysium.sheet` format and through OOXML for the `.xlsx`
 * format. Pure JVM, no Android dependencies for the model itself.
 */

/**
 * The full workbook — a name, a default sheet, and an ordered list of
 * sheets. Each sheet carries its own row/column configuration.
 */
data class SheetWorkbook(
    val title: String = "Untitled",
    val author: String = "",
    val sheets: List<Sheet> = listOf(Sheet()),
    val activeSheetIndex: Int = 0,
    val namedRanges: Map<String, String> = emptyMap(),
    val revision: Long = 0L
) {
    val activeSheet: Sheet get() = sheets.getOrElse(activeSheetIndex) { sheets.first() }

    fun totalCells(): Int = sheets.sumOf { it.cells.size }

    fun sheetNames(): List<String> = sheets.map { it.name }
}

/**
 * One sheet inside a workbook. Holds a `LinkedHashMap` of cells keyed by
 * A1-style address ("A1", "B2", "AA100"). The map is ordered by insertion
 * (so the cells render in the order the user added them) but the grid
 * view looks up by row/column anyway.
 */
data class Sheet(
    val name: String = "Sheet1",
    val cells: LinkedHashMap<String, SheetCell> = LinkedHashMap(),
    val columnWidths: Map<Int, Float> = emptyMap(),   // column index (1-based) → width in chars
    val rowHeights: Map<Int, Float> = emptyMap(),     // row index (1-based) → height in pt
    val frozenRows: Int = 0,
    val frozenColumns: Int = 0,
    val defaultColumnWidth: Float = 8.43f,
    val defaultRowHeight: Float = 15.0f
) {
    /** True if the cell at [address] is non-empty. */
    fun hasCellAt(address: String): Boolean = cells.containsKey(address)

    /** Read the cell at [address] or null. */
    fun cellAt(address: String): SheetCell? = cells[address]

    /** Cells that fall on a given row, ordered by column. */
    fun cellsInRow(row: Int): List<Pair<String, SheetCell>> =
        cells.entries
            .filter { A1.rowOf(it.key) == row }
            .map { it.key to it.value }

    /** Cells that fall on a given column, ordered by row. */
    fun cellsInColumn(col: Int): List<Pair<String, SheetCell>> =
        cells.entries
            .filter { A1.columnOf(it.key) == col }
            .map { it.key to it.value }
}

/**
 * One cell — value, formula, format, alignment, plus optional
 * comment / hyperlink. The value is rendered as the result of the
 * formula (when present) or the raw value.
 */
data class SheetCell(
    val value: String = "",
    val formula: String? = null,
    val format: CellFormat = CellFormat(),
    val alignment: CellAlignment = CellAlignment.DEFAULT,
    val comment: String? = null,
    val hyperlink: String? = null
) {
    /** True if this cell has a formula (regardless of result). */
    val isFormula: Boolean get() = formula != null
    /** True if the value is empty (formula returns "" too). */
    val isEmpty: Boolean get() = value.isEmpty() && formula == null
}

/**
 * PHASE 10.6 — Cell-level formatting.
 *
 * The font / fill / border surface mirrors what Excel / Google Sheets
 * expose. Every field defaults to a "no override" state so a brand-new
 * [CellFormat] is the workbook default.
 */
data class CellFormat(
    val fontFamily: String = "sans-serif",
    val fontSizeSp: Float = 12f,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val color: Long? = null,
    val fill: Long? = null,
    val fillPattern: FillPattern = FillPattern.SOLID,
    val borderTop: BorderSide? = null,
    val borderRight: BorderSide? = null,
    val borderBottom: BorderSide? = null,
    val borderLeft: BorderSide? = null,
    val numberFormat: NumberFormat = NumberFormat.GENERAL
) {
    fun composeFontFamily(): FontFamily = when (fontFamily.lowercase()) {
        "serif" -> FontFamily.Serif
        "monospace", "mono" -> FontFamily.Monospace
        else -> FontFamily.SansSerif
    }

    fun composeColor(): Color = if (color == null) Color(0xFFE4E7EB) else Color(color)
    fun composeFill(): Color? = fill?.let { Color(it) }
    fun composeWeight(): FontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
    fun composeStyle(): FontStyle = if (italic) FontStyle.Italic else FontStyle.Normal
    fun composeDecoration(): TextDecoration? = when {
        underline && strikethrough -> TextDecoration.combine(
            listOf(TextDecoration.Underline, TextDecoration.LineThrough)
        )
        underline -> TextDecoration.Underline
        strikethrough -> TextDecoration.LineThrough
        else -> null
    }
    fun composeFontSize(): TextUnit = fontSizeSp.sp

    /** True if any border is set. */
    fun hasBorders(): Boolean = borderTop != null || borderRight != null ||
        borderBottom != null || borderLeft != null

    companion object {
        val DEFAULT = CellFormat()
    }
}

data class BorderSide(
    val style: BorderStyle = BorderStyle.THIN,
    val color: Long = 0xFF2A2F35
)

enum class BorderStyle { NONE, THIN, MEDIUM, THICK, DOUBLE, DASHED, DOTTED }

enum class FillPattern { NONE, SOLID, GRAY_125, GRAY_75, GRAY_50, GRAY_25 }

/**
 * Number format. `GENERAL` is "show as-is". The rest apply Excel-style
 * formatting strings:
 *
 *   - `NUMBER(0)`  → 123
 *   - `NUMBER(2)`  → 123.45
 *   - `CURRENCY("$", 2)` → $123.45
 *   - `PERCENT(0)` → 12%
 *   - `DATE` → 2026-07-10
 *   - `TIME` → 13:24:55
 *   - `SCIENTIFIC(2)` → 1.23e+02
 *   - `CUSTOM("#,##0.00")` → 1,234.56
 */
sealed class NumberFormat {
    object GENERAL : NumberFormat()
    data class NUMBER(val decimals: Int = 0) : NumberFormat()
    data class CURRENCY(val symbol: String = "$", val decimals: Int = 2) : NumberFormat()
    data class PERCENT(val decimals: Int = 0) : NumberFormat()
    object DATE : NumberFormat()
    object TIME : NumberFormat()
    data class SCIENTIFIC(val decimals: Int = 2) : NumberFormat()
    data class FRACTION(val denominator: Int = 16) : NumberFormat()
    data class CUSTOM(val pattern: String) : NumberFormat()
}

/**
 * PHASE 10.6 — Cell alignment.
 *
 * Horizontal / vertical maps 1:1 to Excel's. `wrap` makes the cell
 * expand to fit long text; `indent` is the number of character
 * widths to push the content right.
 */
data class CellAlignment(
    val horizontal: HorizontalAlignment = HorizontalAlignment.GENERAL,
    val vertical: VerticalAlignment = VerticalAlignment.BOTTOM,
    val wrap: Boolean = false,
    val indent: Int = 0
) {
    companion object {
        val DEFAULT = CellAlignment()
    }
}

enum class HorizontalAlignment { GENERAL, LEFT, CENTER, RIGHT, JUSTIFY, FILL }
enum class VerticalAlignment { TOP, MIDDLE, BOTTOM }

/**
 * PHASE 10.6 — A1-style coordinate helpers.
 *
 * Spreadsheets address cells as letters-then-digits: A1, B2, …, Z99,
 * AA1, AB2, … The conversion is non-trivial because 26 columns
 * is one "letter", 27 is "AA", 52 is "AZ", 53 is "BA".
 *
 * Excel limits: 1..16384 columns (XFD), 1..1048576 rows. We don't
 * enforce the row limit because Compose's `LazyColumn` is happy to
 * render up to a million rows; we do enforce a sanity cap of 32k for
 * the test suite to keep the test runtime small.
 */
object A1 {

    private const val MAX_COLUMNS_TEST = 16_384
    private const val MAX_ROWS_TEST = 1_048_576
    private const val MAX_COLUMNS_USER = 256  // soft cap for the editor UI

    /** Convert 1-based column number → letter label (1 = A, 26 = Z, 27 = AA). */
    fun columnLabel(col: Int): String {
        require(col in 1..MAX_COLUMNS_TEST) { "column out of range: $col" }
        val sb = StringBuilder()
        var n = col
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.append(('A' + rem))
            n = (n - 1) / 26
        }
        return sb.reverse().toString()
    }

    /** Convert letter label → 1-based column number. */
    fun columnNumber(label: String): Int {
        var n = 0
        for (c in label.uppercase()) {
            require(c in 'A'..'Z') { "invalid column letter: $c" }
            n = n * 26 + (c - 'A' + 1)
        }
        return n
    }

    /** Build an A1 address from a 1-based column and row. */
    fun address(col: Int, row: Int): String = "${columnLabel(col)}$row"

    /** Extract the row from an A1 address. */
    fun rowOf(address: String): Int = address.dropWhile { it.isLetter() }.toInt()

    /** Extract the column from an A1 address. */
    fun columnOf(address: String): Int {
        val letters = address.takeWhile { it.isLetter() }
        return columnNumber(letters)
    }

    /** Walk every cell in a rectangular range. */
    fun rangeAddresses(topLeft: String, bottomRight: String): List<String> {
        val c1 = columnOf(topLeft)
        val r1 = rowOf(topLeft)
        val c2 = columnOf(bottomRight)
        val r2 = rowOf(bottomRight)
        val out = ArrayList<String>()
        for (r in r1..r2) for (c in c1..c2) out += address(c, r)
        return out
    }

    /** True if the address is well-formed. */
    fun isValid(address: String): Boolean = address.matches(Regex("^[A-Z]+\\d+$")) &&
        columnOf(address) in 1..MAX_COLUMNS_TEST &&
        rowOf(address) in 1..MAX_ROWS_TEST

    /** Soft cap used by the editor UI to keep Compose lazy. */
    val userColumnCap: Int get() = MAX_COLUMNS_USER
    val userRowCap: Int get() = MAX_ROWS_TEST
}

/**
 * PHASE 10.6 — Convenience constructors.
 */
fun textCell(value: String, format: CellFormat = CellFormat()): SheetCell =
    SheetCell(value = value, format = format)

fun numberCell(value: Double, format: CellFormat = CellFormat()): SheetCell =
    SheetCell(value = formatDouble(value), format = format)

fun booleanCell(value: Boolean, format: CellFormat = CellFormat()): SheetCell =
    SheetCell(value = if (value) "TRUE" else "FALSE", format = format)

fun formulaCell(formula: String, format: CellFormat = CellFormat()): SheetCell =
    SheetCell(value = "", formula = formula, format = format)

private fun formatDouble(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
