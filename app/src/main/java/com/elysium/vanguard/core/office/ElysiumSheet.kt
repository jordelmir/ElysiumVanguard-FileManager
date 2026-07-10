package com.elysium.vanguard.core.office

/**
 * PHASE 9.8.3 — In-memory representation of a sheet.
 *
 * The cells are stored as a `List<List<String?>>` where `cells[row][col]`
 * is the cell at the given coordinate. Missing cells are `null`.
 * Numeric coercion: we keep strings internally; downstream renderers
 * (or [FormulaEvaluator]) parse to numbers when needed.
 *
 * Phase 9.8.3 — first build; intentionally minimal.
 */
data class ElysiumSheet(
    val rows: Int,
    val cols: Int,
    val cells: List<List<String?>>
) {
    init {
        require(cells.size == rows) {
            "cells.size (${cells.size}) doesn't match rows ($rows)"
        }
        for ((idx, row) in cells.withIndex()) {
            require(row.size == cols) {
                "cells[$idx].size (${row.size}) doesn't match cols ($cols)"
            }
        }
    }

    fun cellAt(row: Int, col: Int): String? =
        if (row in 0 until rows && col in 0 until cols) cells[row][col] else null

    /**
     * Serialize to CSV bytes for [ElysiumDocument] (Phase 9.8.1).
     */
    fun toCsv(): ByteArray = CsvSerializer.serialize(this)

    /**
     * Replace a single cell and return a fresh sheet. Out-of-bounds
     * coordinates are ignored (returning the same sheet).
     */
    fun withCell(row: Int, col: Int, value: String?): ElysiumSheet {
        if (row !in 0 until rows || col !in 0 until cols) return this
        val newCells = cells.map { it.toMutableList() }.toMutableList()
        newCells[row][col] = value
        return copy(cells = newCells.map { it.toList() })
    }

    companion object {
        fun empty(rows: Int, cols: Int): ElysiumSheet =
            ElysiumSheet(rows, cols, List(rows) { List(cols) { null } })

        fun fromCsv(csv: ByteArray): ElysiumSheet = CsvParser.parse(csv)
    }
}
