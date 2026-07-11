package com.elysium.vanguard.features.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BorderAll
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.elysium.vanguard.core.sheet.A1
import com.elysium.vanguard.core.sheet.BorderStyle
import com.elysium.vanguard.core.sheet.CellFormat
import com.elysium.vanguard.core.sheet.CellValue
import com.elysium.vanguard.core.sheet.Fmt
import com.elysium.vanguard.core.sheet.HorizontalAlignment
import com.elysium.vanguard.core.sheet.NumberFormat
import com.elysium.vanguard.core.sheet.Sheet
import com.elysium.vanguard.core.sheet.SheetCell
import com.elysium.vanguard.core.sheet.SheetWorkbook
import com.elysium.vanguard.core.sheet.VerticalAlignment

/**
 * PHASE 10.6 — Elysium Sheet editor screen.
 *
 * A grid of cells with a formula bar, format toolbar, and sheet
 * tabs. The grid renders columns A..Z (we cap at 26 for the
 * initial implementation; expanding to AA..ZZ is a 10.6.x pass)
 * and rows 1..N (N depends on how many rows the user has
 * actually filled).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetEditorScreen(
    onBack: () -> Unit,
    viewModel: SheetEditorViewModel = hiltViewModel()
) {
    val workbook by viewModel.workbook.collectAsState()
    val selectedAddress by viewModel.selectedAddress.collectAsState()
    val formulaInput by viewModel.formulaInput.collectAsState()
    val showFormatPanel by viewModel.showFormatPanel.collectAsState()
    val lastError by viewModel.lastError.collectAsState()

    var overflowOpen by remember { mutableStateOf(false) }
    var saveAsOpen by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color(0xFF0B0D10),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        workbook.title.ifEmpty { "Untitled workbook" },
                        color = Color(0xFFE4E7EB),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back",
                            tint = Color(0xFFE4E7EB))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.save() }) {
                        Icon(Icons.Outlined.Save, contentDescription = "Save",
                            tint = Color(0xFF61AFEF))
                    }
                    IconButton(onClick = { saveAsOpen = true }) {
                        Icon(Icons.Filled.Save, contentDescription = "Save as…",
                            tint = Color(0xFFE4E7EB))
                    }
                    Box {
                        IconButton(onClick = { overflowOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More",
                                tint = Color(0xFFE4E7EB))
                        }
                        DropdownMenu(
                            expanded = overflowOpen,
                            onDismissRequest = { overflowOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Add sheet") },
                                onClick = {
                                    viewModel.addSheet()
                                    overflowOpen = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Toggle format panel") },
                                onClick = {
                                    viewModel.toggleFormatPanel()
                                    overflowOpen = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Freeze first row") },
                                onClick = {
                                    val current = workbook.activeSheet
                                    viewModel.setFrozen(
                                        frozenRows = if (current.frozenRows > 0) 0 else 1,
                                        frozenCols = current.frozenColumns
                                    )
                                    overflowOpen = false
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F1115))
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            FormulaBar(
                address = selectedAddress ?: "",
                input = formulaInput,
                onInputChange = viewModel::setFormulaInput,
                onCommit = viewModel::commitCell
            )
            FormattingToolbar(
                viewModel = viewModel,
                onToggleFormatPanel = viewModel::toggleFormatPanel
            )
            if (showFormatPanel) {
                FormatPanel(viewModel = viewModel)
            }
            SheetTabs(
                workbook = workbook,
                onSelect = viewModel::setActiveSheet,
                onAdd = viewModel::addSheet,
                onRemove = viewModel::removeSheet,
                onRename = viewModel::renameSheet
            )
            SpreadsheetGrid(
                sheet = workbook.activeSheet,
                selectedAddress = selectedAddress,
                onSelect = viewModel::selectCell,
                renderedValue = viewModel::renderedValue,
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            )
        }
    }

    if (saveAsOpen) {
        SaveAsDialog(
            initialTitle = workbook.title,
            onDismiss = { saveAsOpen = false },
            onConfirm = { newPath ->
                viewModel.saveAs(newPath)
                saveAsOpen = false
            }
        )
    }
    lastError?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            title = { Text("Something went wrong") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissError) { Text("OK") }
            }
        )
    }
}

// ──────────────────────────────────────────────────────────────────
// Formula bar
// ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormulaBar(
    address: String,
    input: String,
    onInputChange: (String) -> Unit,
    onCommit: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0F1115))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .width(60.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF1A1F25))
                .border(1.dp, Color(0xFF2A2F35), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(address, color = Color(0xFFE4E7EB), fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold)
        }
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Value or formula (start with =)", color = Color(0xFF8B949E), fontSize = 12.sp) },
            textStyle = TextStyle(color = Color(0xFFE4E7EB), fontSize = 13.sp),
            singleLine = true,
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF61AFEF),
                unfocusedBorderColor = Color(0xFF2A2F35)
            )
        )
        TextButton(onClick = onCommit) {
            Text("Set", color = Color(0xFF61AFEF))
        }
    }
}

// ──────────────────────────────────────────────────────────────────
// Format toolbar
// ──────────────────────────────────────────────────────────────────

@Composable
private fun FormattingToolbar(
    viewModel: SheetEditorViewModel,
    onToggleFormatPanel: () -> Unit
) {
    var fontFamilyOpen by remember { mutableStateOf(false) }
    var fontSizeOpen by remember { mutableStateOf(false) }
    var textColorOpen by remember { mutableStateOf(false) }
    var fillOpen by remember { mutableStateOf(false) }
    var numberFormatOpen by remember { mutableStateOf(false) }
    var bordersOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0F1115))
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            ToolbarIcon(Icons.Filled.FormatBold, "Bold", viewModel::toggleBold)
            ToolbarIcon(Icons.Filled.FormatItalic, "Italic", viewModel::toggleItalic)
            ToolbarIcon(Icons.Filled.FormatUnderlined, "Underline", viewModel::toggleUnderline)
            ToolbarIcon(Icons.Filled.FormatStrikethrough, "Strikethrough", viewModel::toggleStrikethrough)
            ToolbarDivider()
            Box {
                TextButton(onClick = { fontFamilyOpen = true }) {
                    Text("Font", color = Color(0xFFE4E7EB), fontSize = 11.sp)
                }
                DropdownMenu(expanded = fontFamilyOpen, onDismissRequest = { fontFamilyOpen = false }) {
                    listOf("sans-serif" to "Sans Serif", "serif" to "Serif", "monospace" to "Monospace")
                        .forEach { (id, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                viewModel.setFontFamily(id)
                                fontFamilyOpen = false
                            })
                        }
                }
            }
            Box {
                TextButton(onClick = { fontSizeOpen = true }) {
                    Text("Size", color = Color(0xFFE4E7EB), fontSize = 11.sp)
                }
                DropdownMenu(expanded = fontSizeOpen, onDismissRequest = { fontSizeOpen = false }) {
                    listOf(9f, 10f, 11f, 12f, 14f, 16f, 18f, 20f, 24f, 28f, 32f)
                        .forEach { size ->
                            DropdownMenuItem(text = { Text("${size.toInt()} sp") }, onClick = {
                                viewModel.setFontSize(size)
                                fontSizeOpen = false
                            })
                        }
                }
            }
            Box {
                TextButton(onClick = { textColorOpen = true }) {
                    Text("Color", color = Color(0xFFE4E7EB), fontSize = 11.sp)
                }
                DropdownMenu(expanded = textColorOpen, onDismissRequest = { textColorOpen = false }) {
                    ColorPickerRow(
                        colors = listOf(
                            0xFFE4E7EB, 0xFF8B949E, 0xFF61AFEF, 0xFF98C379, 0xFFE5C07B,
                            0xFFFF6E6E, 0xFFC678DD, 0xFF56B6C2
                        ),
                        onPick = { c ->
                            viewModel.setTextColor(c)
                            textColorOpen = false
                        }
                    )
                }
            }
            Box {
                TextButton(onClick = { fillOpen = true }) {
                    Text("Fill", color = Color(0xFFE4E7EB), fontSize = 11.sp)
                }
                DropdownMenu(expanded = fillOpen, onDismissRequest = { fillOpen = false }) {
                    ColorPickerRow(
                        colors = listOf(
                            0xFF1F2A1F, 0xFF2A2F35, 0xFF1F2937, 0xFF3F1F22, 0xFF1F2230,
                            0xFFFFFFFF, 0xFFFFE0B2, 0xFFC8E6C9, 0xFFB3E5FC, 0x00000000
                        ),
                        onPick = { c ->
                            viewModel.setFillColor(if (c == 0x00000000L) null else c)
                            fillOpen = false
                        }
                    )
                }
            }
            ToolbarDivider()
            ToolbarIcon(Icons.Filled.FormatAlignLeft, "Left",
                { viewModel.setHorizontalAlignment(HorizontalAlignment.LEFT) })
            ToolbarIcon(Icons.Filled.FormatAlignCenter, "Center",
                { viewModel.setHorizontalAlignment(HorizontalAlignment.CENTER) })
            ToolbarIcon(Icons.Filled.FormatAlignRight, "Right",
                { viewModel.setHorizontalAlignment(HorizontalAlignment.RIGHT) })
            ToolbarIcon(Icons.Filled.FormatAlignJustify, "Justify",
                { viewModel.setHorizontalAlignment(HorizontalAlignment.JUSTIFY) })
            ToolbarDivider()
            Box {
                TextButton(onClick = { numberFormatOpen = true }) {
                    Text("Format", color = Color(0xFFE4E7EB), fontSize = 11.sp)
                }
                DropdownMenu(expanded = numberFormatOpen, onDismissRequest = { numberFormatOpen = false }) {
                    NumberFormatChoice("General", NumberFormat.GENERAL) {
                        viewModel.setNumberFormat(it); numberFormatOpen = false
                    }
                    NumberFormatChoice("Number (0 dec)", NumberFormat.NUMBER(0)) {
                        viewModel.setNumberFormat(it); numberFormatOpen = false
                    }
                    NumberFormatChoice("Number (2 dec)", NumberFormat.NUMBER(2)) {
                        viewModel.setNumberFormat(it); numberFormatOpen = false
                    }
                    NumberFormatChoice("Currency", NumberFormat.CURRENCY()) {
                        viewModel.setNumberFormat(it); numberFormatOpen = false
                    }
                    NumberFormatChoice("Percent", NumberFormat.PERCENT(0)) {
                        viewModel.setNumberFormat(it); numberFormatOpen = false
                    }
                    NumberFormatChoice("Date", NumberFormat.DATE) {
                        viewModel.setNumberFormat(it); numberFormatOpen = false
                    }
                    NumberFormatChoice("Time", NumberFormat.TIME) {
                        viewModel.setNumberFormat(it); numberFormatOpen = false
                    }
                    NumberFormatChoice("Scientific", NumberFormat.SCIENTIFIC(2)) {
                        viewModel.setNumberFormat(it); numberFormatOpen = false
                    }
                }
            }
            Box {
                TextButton(onClick = { bordersOpen = true }) {
                    Text("Borders", color = Color(0xFFE4E7EB), fontSize = 11.sp)
                }
                DropdownMenu(expanded = bordersOpen, onDismissRequest = { bordersOpen = false }) {
                    DropdownMenuItem(text = { Text("Thin all") }, onClick = {
                        viewModel.setAllBorders(BorderStyle.THIN)
                        bordersOpen = false
                    })
                    DropdownMenuItem(text = { Text("Medium all") }, onClick = {
                        viewModel.setAllBorders(BorderStyle.MEDIUM)
                        bordersOpen = false
                    })
                    DropdownMenuItem(text = { Text("Thick all") }, onClick = {
                        viewModel.setAllBorders(BorderStyle.THICK)
                        bordersOpen = false
                    })
                    DropdownMenuItem(text = { Text("Double all") }, onClick = {
                        viewModel.setAllBorders(BorderStyle.DOUBLE)
                        bordersOpen = false
                    })
                    DropdownMenuItem(text = { Text("Clear borders") }, onClick = {
                        viewModel.clearAllBorders()
                        bordersOpen = false
                    })
                }
            }
            ToolbarIcon(Icons.Filled.CheckBox, "Wrap", viewModel::toggleWrapText)
        }
    }
}

@Composable
private fun ToolbarIcon(icon: ImageVector, desc: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = desc, tint = Color(0xFFE4E7EB), modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ToolbarDivider() {
    Box(
        modifier = Modifier
            .height(18.dp)
            .width(1.dp)
            .background(Color(0xFF2A2F35))
    )
}

@Composable
private fun ColorPickerRow(colors: List<Long>, onPick: (Long) -> Unit) {
    LazyRow(
        modifier = Modifier.padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(colors) { color ->
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(color))
                    .border(1.dp, Color(0xFF2A2F35), RoundedCornerShape(4.dp))
                    .clickable { onPick(color) }
            )
        }
    }
}

@Composable
private fun NumberFormatChoice(label: String, format: NumberFormat, onClick: (NumberFormat) -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = { onClick(format) })
}

// ──────────────────────────────────────────────────────────────────
// Format panel (number format, alignment, font, …)
// ──────────────────────────────────────────────────────────────────

@Composable
private fun FormatPanel(viewModel: SheetEditorViewModel) {
    val wb by viewModel.workbook.collectAsState()
    val cell = wb.activeSheet.cellAt(viewModel.selectedAddress.collectAsState().value ?: "") ?: SheetCell()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1115))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Cell format", color = Color(0xFF61AFEF), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Font: ${cell.format.fontFamily}", color = Color(0xFFE4E7EB), fontSize = 12.sp)
                Text("Size: ${cell.format.fontSizeSp.toInt()}sp", color = Color(0xFFE4E7EB), fontSize = 12.sp)
                Text("Align: ${cell.alignment.horizontal.name}", color = Color(0xFFE4E7EB), fontSize = 12.sp)
                Text("Format: ${cell.format.numberFormat::class.simpleName}", color = Color(0xFFE4E7EB), fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    "Borders: ${if (cell.format.hasBorders()) "yes" else "no"}",
                    color = Color(0xFFE4E7EB), fontSize = 12.sp
                )
                Text(
                    "Wrap: ${if (cell.alignment.wrap) "yes" else "no"}",
                    color = Color(0xFFE4E7EB), fontSize = 12.sp
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────
// Sheet tabs
// ──────────────────────────────────────────────────────────────────

@Composable
private fun SheetTabs(
    workbook: SheetWorkbook,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
    onRename: (Int, String) -> Unit
) {
    var renameOpen by remember { mutableStateOf<Int?>(null) }
    var renameText by remember { mutableStateOf("") }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0F1115))
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(workbook.sheets.size) { index ->
                val sheet = workbook.sheets[index]
                val isActive = index == workbook.activeSheetIndex
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isActive) Color(0xFF61AFEF) else Color(0xFF2A2F35))
                        .clickable { onSelect(index) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        sheet.name,
                        color = if (isActive) Color(0xFF0B0D10) else Color(0xFFE4E7EB),
                        fontSize = 11.sp,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal
                    )
                    if (workbook.sheets.size > 1) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { onRemove(index) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "×",
                                color = if (isActive) Color(0xFF0B0D10) else Color(0xFF8B949E),
                                fontSize = 14.sp
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clickable {
                                renameOpen = index
                                renameText = sheet.name
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "✎",
                            color = if (isActive) Color(0xFF0B0D10) else Color(0xFF8B949E),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF2A2F35))
                .clickable { onAdd() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Add sheet", tint = Color(0xFFE4E7EB), modifier = Modifier.size(16.dp))
        }
    }

    renameOpen?.let { idx ->
        AlertDialog(
            onDismissRequest = { renameOpen = null },
            title = { Text("Rename sheet") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(idx, renameText)
                    renameOpen = null
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { renameOpen = null }) { Text("Cancel") }
            }
        )
    }
}

// ──────────────────────────────────────────────────────────────────
// Spreadsheet grid
// ──────────────────────────────────────────────────────────────────

private const val DEFAULT_COLS = 16
private const val DEFAULT_ROWS = 60

@Composable
private fun SpreadsheetGrid(
    sheet: Sheet,
    selectedAddress: String?,
    onSelect: (String) -> Unit,
    renderedValue: (String) -> String,
    modifier: Modifier = Modifier
) {
    // Determine the visible range: we show enough rows / columns
    // to cover everything the user has touched, plus a healthy
    // blank buffer. The lazy row/column counts make Compose
    // happy even at 1000 rows × 16 columns.
    val usedRows = sheet.cells.keys.maxOfOrNull { A1.rowOf(it) } ?: 1
    val usedCols = sheet.cells.keys.maxOfOrNull { A1.columnOf(it) } ?: 1
    val totalRows = (usedRows + 8).coerceAtLeast(DEFAULT_ROWS).coerceAtMost(1_000)
    val totalCols = (usedCols + 2).coerceAtLeast(DEFAULT_COLS).coerceAtMost(A1.userColumnCap)

    val frozenRows = sheet.frozenRows.coerceAtMost(totalRows)
    val frozenCols = sheet.frozenColumns.coerceAtMost(totalCols)

    LazyColumn(
        modifier = modifier
            .background(Color(0xFF0B0D10))
            .horizontalScroll(rememberScrollState()),
        state = androidx.compose.foundation.lazy.rememberLazyListState(),
        contentPadding = PaddingValues(2.dp)
    ) {
        items(totalRows + 1) { rowIdx ->
            val row = rowIdx + 1
            Row(modifier = Modifier.fillMaxWidth()) {
                // Row header
                RowHeaderCell(row)
                for (col in 1..totalCols) {
                    val address = A1.address(col, row)
                    val cell = sheet.cellAt(address)
                    val isSelected = address == selectedAddress
                    val isFrozen = row <= frozenRows || col <= frozenCols
                    CellView(
                        cell = cell,
                        address = address,
                        isSelected = isSelected,
                        isFrozen = isFrozen,
                        onClick = { onSelect(address) },
                        rendered = cell?.let { renderedValue(address) } ?: "",
                        widthChars = sheet.columnWidths[col] ?: 12f
                    )
                }
            }
        }
    }
}

@Composable
private fun RowHeaderCell(row: Int) {
    Box(
        modifier = Modifier
            .width(40.dp)
            .height(28.dp)
            .background(Color(0xFF0F1115))
            .border(0.5.dp, Color(0xFF2A2F35), RoundedCornerShape(0.dp))
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "$row",
            color = Color(0xFF8B949E),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun CellView(
    cell: SheetCell?,
    address: String,
    isSelected: Boolean,
    isFrozen: Boolean,
    onClick: () -> Unit,
    rendered: String,
    widthChars: Float
) {
    val format = cell?.format ?: CellFormat.DEFAULT
    val alignment = cell?.alignment
    val fillColor = format.composeFill()
    val borderColor = if (isSelected) Color(0xFF61AFEF) else Color(0xFF2A2F35)
    val borderWidth = if (isSelected) 2.dp else 0.5.dp
    Box(
        modifier = Modifier
            .width((widthChars * 8).dp.coerceAtLeast(60.dp))
            .height(28.dp)
            .background(fillColor ?: Color.Transparent)
            .border(borderWidth, borderColor)
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        contentAlignment = when (alignment?.vertical ?: VerticalAlignment.BOTTOM) {
            VerticalAlignment.TOP -> Alignment.TopCenter
            VerticalAlignment.MIDDLE -> Alignment.Center
            VerticalAlignment.BOTTOM -> Alignment.BottomCenter
        }
    ) {
        val textAlign = when (alignment?.horizontal ?: HorizontalAlignment.GENERAL) {
            HorizontalAlignment.LEFT, HorizontalAlignment.GENERAL -> TextAlign.Start
            HorizontalAlignment.CENTER -> TextAlign.Center
            HorizontalAlignment.RIGHT -> TextAlign.End
            HorizontalAlignment.JUSTIFY -> TextAlign.Start
            HorizontalAlignment.FILL -> TextAlign.Start
        }
        Text(
            text = rendered,
            color = format.composeColor(),
            fontSize = format.composeFontSize(),
            fontFamily = format.composeFontFamily(),
            fontWeight = format.composeWeight(),
            fontStyle = format.composeStyle(),
            textDecoration = format.composeDecoration(),
            textAlign = textAlign,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ──────────────────────────────────────────────────────────────────
// Save-as dialog
// ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaveAsDialog(
    initialTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var title by remember { mutableStateOf(initialTitle) }
    var format by remember { mutableStateOf("elysium.sheet") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save workbook") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("File name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormatChoice("Elysium", format == "elysium.sheet") { format = "elysium.sheet" }
                    FormatChoice("Excel (.xlsx)", format == "xlsx") { format = "xlsx" }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ext = if (format == "xlsx") "xlsx" else "elysium.sheet"
                onConfirm("${title}.${ext}")
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun FormatChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Color(0xFF61AFEF) else Color(0xFF2A2F35))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            color = if (selected) Color(0xFF0B0D10) else Color(0xFFE4E7EB),
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
