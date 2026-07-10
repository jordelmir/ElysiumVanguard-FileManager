package com.elysium.vanguard.core.office

/**
 * PHASE 9.8.3 — Minimal RFC 4180-ish CSV parser.
 *
 * Supports:
 *   - Comma-separated fields
 *   - Quoted fields with embedded commas / newlines
 *   - Doubled quotes inside quoted fields (`""`) as escaped quotes
 *   - Empty fields (between separators)
 *
 * Phase 9.8.3 — first build; intentionally minimal.
 */
object CsvParser {

    fun parse(csv: ByteArray): ElysiumSheet {
        val text = csv.toString(Charsets.UTF_8)
        val rows = ArrayList<List<String>>()
        var i = 0
        while (i <= text.length) {
            val row = parseRow(text, i) ?: break
            rows += row.fields
            i = row.afterIndex
        }
        // Find the column count of the longest row.
        val cols = rows.maxOfOrNull { it.size } ?: 0
        val padded = rows.map { row ->
            if (row.size < cols) row + List(cols - row.size) { "" } else row.take(cols)
        }
        val cells = padded.map { row ->
            row.map { if (it.isEmpty()) null else it }
        }
        val rowCount = cells.size
        val colCount = if (rowCount == 0) 0 else cells[0].size
        // Pad to a 1x1 minimum so the sheet is always renderable.
        return if (rowCount == 0 || colCount == 0) {
            ElysiumSheet(1, 1, listOf(listOf(null)))
        } else {
            ElysiumSheet(rowCount, colCount, cells)
        }
    }

    private data class RowResult(val fields: List<String>, val afterIndex: Int)

    private fun parseRow(text: String, start: Int): RowResult? {
        if (start >= text.length) return null
        val fields = ArrayList<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = start
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' && inQuotes -> {
                    // Either end-of-field quote, or "" escape.
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        current.append('"')
                        i += 2
                    } else {
                        inQuotes = false
                        i += 1
                    }
                }
                c == '"' && !inQuotes && current.isEmpty() -> {
                    inQuotes = true
                    i += 1
                }
                c == ',' && !inQuotes -> {
                    fields += current.toString()
                    current.setLength(0)
                    i += 1
                }
                c == '\n' && !inQuotes -> {
                    fields += current.toString()
                    val nextStart = i + 1
                    return RowResult(fields, nextStart)
                }
                c == '\r' && !inQuotes -> {
                    // CRLF or standalone CR — consume the LF if present.
                    fields += current.toString()
                    val nextStart = if (i + 1 < text.length && text[i + 1] == '\n') i + 2 else i + 1
                    return RowResult(fields, nextStart)
                }
                else -> {
                    current.append(c)
                    i += 1
                }
            }
        }
        // Reached EOF without a newline. Treat as end of the current row.
        if (current.isNotEmpty() || fields.isNotEmpty()) {
            fields += current.toString()
            return RowResult(fields, i)
        }
        return null
    }
}

/**
 * PHASE 9.8.3 — Serialize [ElysiumSheet] back to CSV bytes.
 *
 * Empty cells render as empty fields. Quoting is applied when a field
 * contains a comma, double-quote, or newline.
 *
 * Phase 9.8.3 — first build; intentionally minimal.
 */
object CsvSerializer {

    fun serialize(sheet: ElysiumSheet): ByteArray {
        val sb = StringBuilder()
        for ((rowIdx, row) in sheet.cells.withIndex()) {
            if (rowIdx > 0) sb.append('\n')
            for ((colIdx, cell) in row.withIndex()) {
                if (colIdx > 0) sb.append(',')
                val text = cell ?: ""
                if (text.contains(',') || text.contains('"') || text.contains('\n')) {
                    sb.append('"').append(text.replace("\"", "\"\"")).append('"')
                } else {
                    sb.append(text)
                }
            }
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }
}
