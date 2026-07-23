package com.elysium.vanguard.features.sheet

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elysium.vanguard.core.sheet.A1
import com.elysium.vanguard.core.sheet.BorderSide
import com.elysium.vanguard.core.sheet.BorderStyle
import com.elysium.vanguard.core.sheet.CellAlignment
import com.elysium.vanguard.core.sheet.CellFormat
import com.elysium.vanguard.core.sheet.FillPattern
import com.elysium.vanguard.core.sheet.FormulaEngine
import com.elysium.vanguard.core.sheet.HorizontalAlignment
import com.elysium.vanguard.core.sheet.NumberFormat
import com.elysium.vanguard.core.sheet.Sheet
import com.elysium.vanguard.core.sheet.SheetCell
import com.elysium.vanguard.core.sheet.SheetWorkbook
import com.elysium.vanguard.core.sheet.SheetWorkbookView
import com.elysium.vanguard.core.sheet.SheetJson
import com.elysium.vanguard.core.sheet.SheetFile
import com.elysium.vanguard.core.sheet.SheetXlsx
import com.elysium.vanguard.core.sheet.VerticalAlignment
import com.elysium.vanguard.core.sheet.formulaCell
import com.elysium.vanguard.core.sheet.numberCell
import com.elysium.vanguard.core.sheet.textCell
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * PHASE 10.6 — Elysium Sheet editor ViewModel.
 *
 * Holds the [SheetWorkbook] in memory plus a few pieces of UI state
 * (selected cell, sheet index, formula input, panel visibility).
 * Persists to `.elysium.sheet` (JSON) or `.xlsx` (OOXML) on save.
 *
 * Editing model:
 *  - The user types into the formula bar; the value is committed
 *    with [commitCell] when they press Enter or tap elsewhere.
 *  - Format changes (bold, color, …) apply to the current cell.
 *  - Formulas are stored verbatim; the [FormulaEngine] evaluates
 *    them lazily on render and on demand.
 */
@HiltViewModel
class SheetEditorViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    @dagger.hilt.android.qualifiers.ApplicationContext
    private val context: android.content.Context,
) : ViewModel() {

    /**
     * The directory where new `.elysium.sheet` / `.xlsx` files are
     * saved when the user picks a relative file name in the
     * "Save as..." dialog. PHASE 116 — the previous code passed a
     * relative path to [java.io.File], which landed in the app's
     * read-only working directory and threw `EROFS`. The relative
     * name is now anchored under `context.filesDir/documents/`.
     */
    private val saveRoot: java.io.File
        get() = java.io.File(context.filesDir, "documents")

    private val initialPath: String? =
        savedStateHandle.get<String>("path")?.takeIf { it.isNotEmpty() }

    private val _workbook = MutableStateFlow(SheetWorkbook())
    val workbook: StateFlow<SheetWorkbook> = _workbook.asStateFlow()

    /** Currently selected cell address (e.g. "A1"). Empty when no selection. */
    private val _selectedAddress = MutableStateFlow<String?>("A1")
    val selectedAddress: StateFlow<String?> = _selectedAddress.asStateFlow()

    /** Formula bar input. */
    private val _formulaInput = MutableStateFlow("")
    val formulaInput: StateFlow<String> = _formulaInput.asStateFlow()

    /** Whether the format panel is shown. */
    private val _showFormatPanel = MutableStateFlow(false)
    val showFormatPanel: StateFlow<Boolean> = _showFormatPanel.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** Path the workbook was loaded from, if any. */
    var currentPath: String? = null
        private set

    init {
        if (initialPath != null) loadFromPath(initialPath)
    }

    // ── I/O ────────────────────────────────────────────────────────

    fun loadFromPath(path: String) {
        viewModelScope.launch {
            val file = File(path)
            val loaded = withContext(Dispatchers.IO) {
                when {
                    path.endsWith(".xlsx", true) -> SheetXlsx.importFile(file)
                    path.endsWith(".elysium.sheet", true) -> SheetFile.readFile(file)
                    else -> null
                }
            }
            if (loaded != null) {
                _workbook.value = loaded
                currentPath = path
                _selectedAddress.value = "A1"
                refreshFormulaInputFromSelection()
            } else {
                _lastError.value = "Could not load: $path"
            }
        }
    }

    fun save() {
        val path = currentPath ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(path)
                    when {
                        path.endsWith(".xlsx", true) -> SheetXlsx.exportFile(_workbook.value, file)
                        else -> SheetFile.writeFile(file, _workbook.value)
                    }
                }
            }.onFailure { _lastError.value = "Save failed: ${it.message}" }
        }
    }

    fun saveAs(path: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    // PHASE 116 — resolve the path against `saveRoot`
                    // when the user picks a relative file name. An
                    // absolute path is used as-is. Without this, the
                    // previous code passed the relative name to
                    // [java.io.File], which landed in the app's
                    // read-only working directory and threw
                    // `EROFS (Read-only file system)`.
                    val file = if (java.io.File(path).isAbsolute) {
                        java.io.File(path)
                    } else {
                        java.io.File(saveRoot, path)
                    }
                    val absolute = file.absolutePath
                    when {
                        absolute.endsWith(".xlsx", true) -> SheetXlsx.exportFile(_workbook.value, file)
                        else -> SheetFile.writeFile(file, _workbook.value)
                    }
                }
            }.onSuccess {
                currentPath = java.io.File(saveRoot, path).absolutePath
            }.onFailure { _lastError.value = "Save failed: ${it.message}" }
        }
    }

    fun dismissError() {
        _lastError.value = null
    }

    // ── Selection ──────────────────────────────────────────────────

    fun selectCell(address: String) {
        if (!A1.isValid(address)) return
        _selectedAddress.value = address.uppercase()
        refreshFormulaInputFromSelection()
    }

    fun selectCell(col: Int, row: Int) {
        selectCell(A1.address(col, row))
    }

    private fun refreshFormulaInputFromSelection() {
        val addr = _selectedAddress.value ?: return
        val cell = currentSheet().cellAt(addr) ?: run {
            _formulaInput.value = ""
            return
        }
        _formulaInput.value = cell.formula ?: cell.value
    }

    // ── Sheet navigation ───────────────────────────────────────────

    fun setActiveSheet(index: Int) {
        if (index !in _workbook.value.sheets.indices) return
        _workbook.value = _workbook.value.copy(activeSheetIndex = index)
        _selectedAddress.value = "A1"
        refreshFormulaInputFromSelection()
    }

    fun addSheet(name: String = "Sheet${_workbook.value.sheets.size + 1}") {
        val updated = _workbook.value.sheets.toMutableList()
        updated += Sheet(name = name)
        _workbook.value = _workbook.value.copy(
            sheets = updated,
            activeSheetIndex = updated.lastIndex
        )
    }

    fun renameSheet(index: Int, name: String) {
        if (index !in _workbook.value.sheets.indices) return
        val updated = _workbook.value.sheets.toMutableList()
        updated[index] = updated[index].copy(name = name)
        _workbook.value = _workbook.value.copy(sheets = updated)
    }

    fun removeSheet(index: Int) {
        if (_workbook.value.sheets.size <= 1) return
        val updated = _workbook.value.sheets.toMutableList()
        updated.removeAt(index)
        val newActive = (index - 1).coerceAtLeast(0)
        _workbook.value = _workbook.value.copy(
            sheets = updated,
            activeSheetIndex = newActive
        )
    }

    // ── Cell editing ───────────────────────────────────────────────

    fun setFormulaInput(text: String) {
        _formulaInput.value = text
    }

    /**
     * Commit the formula bar's current text to the selected cell.
     *  - If text starts with `=`, it's stored as a formula.
     *  - Otherwise, the raw text is stored as the cell value.
     */
    fun commitCell() {
        val addr = _selectedAddress.value ?: return
        val raw = _formulaInput.value
        setCell(addr, if (raw.startsWith("=")) SheetCell(value = "", formula = raw) else SheetCell(value = raw))
    }

    fun setCell(address: String, cell: SheetCell) {
        val sheet = currentSheet()
        val updated = LinkedHashMap(sheet.cells)
        if (cell.isEmpty) {
            updated.remove(address)
        } else {
            updated[address] = cell
        }
        replaceSheet(sheet.copy(cells = updated))
    }

    fun setCellValue(address: String, value: String) {
        val existing = currentSheet().cellAt(address)
        setCell(address, (existing ?: SheetCell()).copy(value = value))
    }

    fun setCellFormula(address: String, formula: String) {
        val existing = currentSheet().cellAt(address)
        setCell(address, (existing ?: SheetCell()).copy(formula = formula.removePrefix("=")))
    }

    fun clearCell(address: String) {
        val sheet = currentSheet()
        if (!sheet.hasCellAt(address)) return
        val updated = LinkedHashMap(sheet.cells)
        updated.remove(address)
        replaceSheet(sheet.copy(cells = updated))
        if (_selectedAddress.value == address) {
            _formulaInput.value = ""
        }
    }

    fun moveSelection(deltaCol: Int, deltaRow: Int) {
        val addr = _selectedAddress.value ?: return
        val col = A1.columnOf(addr)
        val row = A1.rowOf(addr)
        selectCell((col + deltaCol).coerceAtLeast(1), (row + deltaRow).coerceAtLeast(1))
    }

    // ── Format operations (apply to selected cell) ────────────────

    fun applyFormatToSelected(transform: (CellFormat) -> CellFormat) {
        val addr = _selectedAddress.value ?: return
        val sheet = currentSheet()
        val cell = sheet.cellAt(addr) ?: SheetCell()
        setCell(addr, cell.copy(format = transform(cell.format)))
    }

    fun toggleBold() = applyFormatToSelected { it.copy(bold = !it.bold) }
    fun toggleItalic() = applyFormatToSelected { it.copy(italic = !it.italic) }
    fun toggleUnderline() = applyFormatToSelected { it.copy(underline = !it.underline) }
    fun toggleStrikethrough() = applyFormatToSelected { it.copy(strikethrough = !it.strikethrough) }

    fun setFontFamily(family: String) = applyFormatToSelected { it.copy(fontFamily = family) }
    fun setFontSize(size: Float) = applyFormatToSelected { it.copy(fontSizeSp = size) }
    fun setTextColor(color: Long?) = applyFormatToSelected { it.copy(color = color) }
    fun setFillColor(color: Long?) = applyFormatToSelected { it.copy(fill = color, fillPattern = if (color == null) FillPattern.NONE else FillPattern.SOLID) }

    fun setNumberFormat(format: NumberFormat) = applyFormatToSelected { it.copy(numberFormat = format) }

    fun setHorizontalAlignment(alignment: HorizontalAlignment) {
        val addr = _selectedAddress.value ?: return
        val cell = currentSheet().cellAt(addr) ?: SheetCell()
        setCell(addr, cell.copy(alignment = cell.alignment.copy(horizontal = alignment)))
    }

    fun setVerticalAlignment(alignment: VerticalAlignment) {
        val addr = _selectedAddress.value ?: return
        val cell = currentSheet().cellAt(addr) ?: SheetCell()
        setCell(addr, cell.copy(alignment = cell.alignment.copy(vertical = alignment)))
    }

    fun toggleWrapText() {
        val addr = _selectedAddress.value ?: return
        val cell = currentSheet().cellAt(addr) ?: SheetCell()
        setCell(addr, cell.copy(alignment = cell.alignment.copy(wrap = !cell.alignment.wrap)))
    }

    fun setAllBorders(style: BorderStyle) {
        val addr = _selectedAddress.value ?: return
        val cell = currentSheet().cellAt(addr) ?: SheetCell()
        val side = BorderSide(style = style)
        setCell(addr, cell.copy(
            format = cell.format.copy(
                borderTop = side, borderRight = side, borderBottom = side, borderLeft = side
            )
        ))
    }

    fun clearAllBorders() {
        val addr = _selectedAddress.value ?: return
        val cell = currentSheet().cellAt(addr) ?: SheetCell()
        setCell(addr, cell.copy(
            format = cell.format.copy(borderTop = null, borderRight = null, borderBottom = null, borderLeft = null)
        ))
    }

    fun toggleFormatPanel() {
        _showFormatPanel.value = !_showFormatPanel.value
    }

    // ── Column / row sizing ────────────────────────────────────────

    fun setColumnWidth(col: Int, width: Float) {
        val sheet = currentSheet()
        val widths = sheet.columnWidths.toMutableMap()
        widths[col] = width
        replaceSheet(sheet.copy(columnWidths = widths))
    }

    fun setRowHeight(row: Int, height: Float) {
        val sheet = currentSheet()
        val heights = sheet.rowHeights.toMutableMap()
        heights[row] = height
        replaceSheet(sheet.copy(rowHeights = heights))
    }

    fun setFrozen(frozenRows: Int, frozenCols: Int) {
        val sheet = currentSheet()
        replaceSheet(sheet.copy(frozenRows = frozenRows, frozenColumns = frozenCols))
    }

    // ── Helpers ───────────────────────────────────────────────────

    private fun currentSheet(): Sheet = _workbook.value.activeSheet

    private fun replaceSheet(newSheet: Sheet) {
        val updated = _workbook.value.sheets.toMutableList()
        updated[_workbook.value.activeSheetIndex] = newSheet
        _workbook.value = _workbook.value.copy(
            sheets = updated,
            revision = _workbook.value.revision + 1
        )
    }

    /**
     * Evaluate the formula at [address] (if any) and return the
     * display string. Used by the cell renderer.
     */
    fun renderedValue(address: String): String {
        val cell = currentSheet().cellAt(address) ?: return ""
        if (cell.formula == null) return cell.value
        return FormulaEngine.evaluate(cell.formula, SheetWorkbookView(_workbook.value))
    }
}
