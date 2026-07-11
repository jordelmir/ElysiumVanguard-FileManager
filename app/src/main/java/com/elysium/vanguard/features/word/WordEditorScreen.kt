package com.elysium.vanguard.features.word

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Title
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.elysium.vanguard.core.word.CharacterFormat
import com.elysium.vanguard.core.word.ListKind
import com.elysium.vanguard.core.word.ParagraphFormat
import com.elysium.vanguard.core.word.TextAlignment
import com.elysium.vanguard.core.word.WordBlock
import com.elysium.vanguard.core.word.WordBlockQuote
import com.elysium.vanguard.core.word.WordCodeBlock
import com.elysium.vanguard.core.word.WordDocument
import com.elysium.vanguard.core.word.WordHeading
import com.elysium.vanguard.core.word.WordHorizontalRule
import com.elysium.vanguard.core.word.WordListItem
import com.elysium.vanguard.core.word.WordPageBreak
import com.elysium.vanguard.core.word.WordParagraph
import com.elysium.vanguard.core.word.WordRun

/**
 * PHASE 10.5 — Elysium Word editor screen.
 *
 * Top-of-funnel UX for the world's most functional Android Word
 * clone. The screen is composed of:
 *
 *  - **TopAppBar**: back, title field, save.
 *  - **Formatting toolbar** (sticky below app bar): bold, italic,
 *    underline, strikethrough, font family picker, font size
 *    picker, color palette, alignment, lists, headings, block
 *    transforms.
 *  - **Document body** (LazyColumn): one row per [WordBlock] with
 *    the right typography. Tapping a block selects it.
 *  - **Format panel** (toggle from toolbar): line spacing, space
 *    before/after, indent.
 *  - **Overflow menu**: more transforms (page break, HR, code
 *    block, block quote).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordEditorScreen(
    onBack: () -> Unit,
    viewModel: WordEditorViewModel = hiltViewModel()
) {
    val doc by viewModel.doc.collectAsState()
    val selectedBlock by viewModel.selectedBlock.collectAsState()
    val showFormatPanel by viewModel.showFormatPanel.collectAsState()
    val lastError by viewModel.lastError.collectAsState()

    var overflowOpen by remember { mutableStateOf(false) }
    var saveAsOpen by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color(0xFF0B0D10),
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = doc.title,
                        onValueChange = viewModel::setTitle,
                        textStyle = TextStyle(
                            color = Color(0xFFE4E7EB),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 8.dp),
                        placeholder = {
                            Text(
                                "Untitled document",
                                color = Color(0xFF8B949E),
                                fontSize = 14.sp
                            )
                        },
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF61AFEF),
                            unfocusedBorderColor = Color(0xFF2A2F35)
                        )
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
                                text = { Text("Insert page break") },
                                onClick = {
                                    viewModel.insertPageBreak()
                                    overflowOpen = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Insert horizontal rule") },
                                onClick = {
                                    viewModel.insertHorizontalRule()
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
            FormattingToolbar(
                viewModel = viewModel,
                selectedIndex = selectedBlock,
                onToggleFormatPanel = viewModel::toggleFormatPanel
            )
            if (showFormatPanel) {
                FormatPanel(viewModel = viewModel)
            }
            WordDocumentBody(
                doc = doc,
                selectedBlock = selectedBlock,
                onSelectBlock = viewModel::selectBlock,
                onAppendText = viewModel::appendText,
                onAddParagraph = viewModel::newParagraphAfterSelected,
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            )
            AuthorAndStatusBar(
                author = doc.author,
                onAuthorChange = viewModel::setAuthor,
                wordCount = doc.wordCount(),
                paragraphCount = doc.paragraphCount()
            )
        }
    }

    if (saveAsOpen) {
        SaveAsDialog(
            initialTitle = doc.title,
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
// Formatting toolbar
// ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormattingToolbar(
    viewModel: WordEditorViewModel,
    selectedIndex: Int,
    onToggleFormatPanel: () -> Unit
) {
    var fontFamilyOpen by remember { mutableStateOf(false) }
    var fontSizeOpen by remember { mutableStateOf(false) }
    var colorPickerOpen by remember { mutableStateOf(false) }
    var headingsOpen by remember { mutableStateOf(false) }
    var listOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0F1115))
            .padding(vertical = 6.dp)
    ) {
        // Row 1: bold / italic / underline / strike / font family / size / color
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ToolbarIcon(Icons.Filled.FormatBold, "Bold", viewModel::toggleBold)
            ToolbarIcon(Icons.Filled.FormatItalic, "Italic", viewModel::toggleItalic)
            ToolbarIcon(Icons.Filled.FormatUnderlined, "Underline", viewModel::toggleUnderline)
            ToolbarIcon(Icons.Filled.FormatStrikethrough, "Strikethrough", viewModel::toggleStrikethrough)
            ToolbarDivider()
            Box {
                TextButton(onClick = { fontFamilyOpen = true }) {
                    Text("Font", color = Color(0xFFE4E7EB), fontSize = 12.sp)
                }
                DropdownMenu(
                    expanded = fontFamilyOpen,
                    onDismissRequest = { fontFamilyOpen = false }
                ) {
                    listOf(
                        "sans-serif" to "Sans Serif",
                        "serif" to "Serif",
                        "monospace" to "Monospace",
                        "cursive" to "Cursive"
                    ).forEach { (id, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                viewModel.setFontFamily(id)
                                fontFamilyOpen = false
                            }
                        )
                    }
                }
            }
            Box {
                TextButton(onClick = { fontSizeOpen = true }) {
                    Text("Size", color = Color(0xFFE4E7EB), fontSize = 12.sp)
                }
                DropdownMenu(
                    expanded = fontSizeOpen,
                    onDismissRequest = { fontSizeOpen = false }
                ) {
                    listOf(9f, 10f, 11f, 12f, 14f, 16f, 18f, 20f, 24f, 28f, 32f, 40f, 56f)
                        .forEach { size ->
                            DropdownMenuItem(
                                text = { Text("${size.toInt()} sp") },
                                onClick = {
                                    viewModel.setFontSize(size)
                                    fontSizeOpen = false
                                }
                            )
                        }
                }
            }
            Box {
                TextButton(onClick = { colorPickerOpen = true }) {
                    Text("Color", color = Color(0xFFE4E7EB), fontSize = 12.sp)
                }
                DropdownMenu(
                    expanded = colorPickerOpen,
                    onDismissRequest = { colorPickerOpen = false }
                ) {
                    ColorSwatchRow(viewModel)
                }
            }
            ToolbarDivider()
            ToolbarIcon(Icons.Filled.FormatAlignLeft, "Align left",
                { viewModel.setAlignment(TextAlignment.LEFT) })
            ToolbarIcon(Icons.Filled.FormatAlignCenter, "Align center",
                { viewModel.setAlignment(TextAlignment.CENTER) })
            ToolbarIcon(Icons.Filled.FormatAlignRight, "Align right",
                { viewModel.setAlignment(TextAlignment.RIGHT) })
            ToolbarIcon(Icons.Filled.FormatAlignJustify, "Justify",
                { viewModel.setAlignment(TextAlignment.JUSTIFY) })
        }
        // Row 2: lists, headings, transforms
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box {
                TextButton(onClick = { listOpen = true }) {
                    Text("Lists", color = Color(0xFFE4E7EB), fontSize = 12.sp)
                }
                DropdownMenu(
                    expanded = listOpen,
                    onDismissRequest = { listOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Bullet list") },
                        onClick = {
                            viewModel.convertBlockToListItem(selectedIndex, ListKind.BULLET)
                            listOpen = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Numbered list") },
                        onClick = {
                            viewModel.convertBlockToListItem(selectedIndex, ListKind.NUMBERED)
                            listOpen = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Checkbox") },
                        onClick = {
                            viewModel.convertBlockToListItem(selectedIndex, ListKind.CHECKBOX)
                            listOpen = false
                        }
                    )
                }
            }
            Box {
                TextButton(onClick = { headingsOpen = true }) {
                    Text("Heading", color = Color(0xFFE4E7EB), fontSize = 12.sp)
                }
                DropdownMenu(
                    expanded = headingsOpen,
                    onDismissRequest = { headingsOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Heading 1") },
                        onClick = {
                            viewModel.convertBlockToHeading(selectedIndex, 1)
                            headingsOpen = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Heading 2") },
                        onClick = {
                            viewModel.convertBlockToHeading(selectedIndex, 2)
                            headingsOpen = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Heading 3") },
                        onClick = {
                            viewModel.convertBlockToHeading(selectedIndex, 3)
                            headingsOpen = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Heading 4") },
                        onClick = {
                            viewModel.convertBlockToHeading(selectedIndex, 4)
                            headingsOpen = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Heading 5") },
                        onClick = {
                            viewModel.convertBlockToHeading(selectedIndex, 5)
                            headingsOpen = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Heading 6") },
                        onClick = {
                            viewModel.convertBlockToHeading(selectedIndex, 6)
                            headingsOpen = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Body") },
                        onClick = {
                            viewModel.convertBlockToParagraph(selectedIndex)
                            headingsOpen = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Block quote") },
                        onClick = {
                            viewModel.convertBlockToBlockQuote(selectedIndex)
                            headingsOpen = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Code block") },
                        onClick = {
                            viewModel.convertBlockToCodeBlock(selectedIndex)
                            headingsOpen = false
                        }
                    )
                }
            }
            ToolbarIcon(Icons.AutoMirrored.Filled.FormatListBulleted, "Bullets",
                { viewModel.convertBlockToListItem(selectedIndex, ListKind.BULLET) })
            ToolbarIcon(Icons.Filled.FormatListNumbered, "Numbered",
                { viewModel.convertBlockToListItem(selectedIndex, ListKind.NUMBERED) })
            ToolbarIcon(Icons.Filled.CheckBox, "Checkbox",
                { viewModel.convertBlockToListItem(selectedIndex, ListKind.CHECKBOX) })
            ToolbarIcon(Icons.Filled.Code, "Code",
                { viewModel.convertBlockToCodeBlock(selectedIndex) })
            ToolbarIcon(Icons.Filled.Title, "Block quote",
                { viewModel.convertBlockToBlockQuote(selectedIndex) })
            ToolbarDivider()
            ToolbarIcon(Icons.Filled.ArrowUpward, "Move up",
                { viewModel.moveBlockUp(selectedIndex) })
            ToolbarIcon(Icons.Filled.ArrowDownward, "Move down",
                { viewModel.moveBlockDown(selectedIndex) })
            ToolbarIcon(Icons.Filled.Delete, "Delete",
                { viewModel.deleteBlock(selectedIndex) })
        }
    }
}

@Composable
private fun ToolbarIcon(icon: ImageVector, desc: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = desc, tint = Color(0xFFE4E7EB), modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ToolbarDivider() {
    Box(
        modifier = Modifier
            .height(20.dp)
            .width(1.dp)
            .background(Color(0xFF2A2F35))
    )
}

@Composable
private fun ColorSwatchRow(viewModel: WordEditorViewModel) {
    val swatches = listOf(
        0xFFE4E7EB, 0xFF8B949E, 0xFF61AFEF, 0xFF98C379, 0xFFE5C07B,
        0xFFFF6E6E, 0xFFC678DD, 0xFF56B6C2, 0xFFD19A66, 0xFFBE5046,
        0xFF2A2F35
    )
    Row(
        modifier = Modifier
            .padding(8.dp)
            .width((swatches.size * 28).dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        swatches.forEach { color ->
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color(color))
                    .border(1.dp, Color(0xFF2A2F35), CircleShape)
                    .clickable { viewModel.setTextColor(color) }
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────
// Format panel (line spacing, indent, …)
// ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormatPanel(viewModel: WordEditorViewModel) {
    val doc by viewModel.doc.collectAsState()
    val selectedBlock = doc.blocks.getOrNull(viewModel.selectedBlock.value.let { selectedIndex(it) })
    val para = selectedBlock as? WordParagraph
    var lineSpacing by remember { mutableStateOf(para?.format?.lineSpacingMultiplier ?: 1.15f) }
    var spaceBefore by remember { mutableStateOf(para?.format?.spaceBeforePt ?: 0f) }
    var spaceAfter by remember { mutableStateOf(para?.format?.spaceAfterPt ?: 6f) }
    var indentLeft by remember { mutableStateOf(para?.format?.indentLeftPt ?: 0f) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1115))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Paragraph format", color = Color(0xFF61AFEF), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            SliderRow("Line spacing", lineSpacing, 0.8f..3.0f, "%.2fx") {
                lineSpacing = it
                viewModel.setLineSpacing(it)
            }
            SliderRow("Space before", spaceBefore, 0f..48f, "%.0f pt") {
                spaceBefore = it
                viewModel.setSpaceBefore(it)
            }
            SliderRow("Space after", spaceAfter, 0f..48f, "%.0f pt") {
                spaceAfter = it
                viewModel.setSpaceAfter(it)
            }
            SliderRow("Indent left", indentLeft, 0f..96f, "%.0f pt") {
                indentLeft = it
                viewModel.setIndentLeft(it)
            }
        }
    }
}

private fun selectedIndex(i: Int) = i

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    formatter: String,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = Color(0xFFE4E7EB), fontSize = 12.sp)
            Text(formatter.format(value), color = Color(0xFF8B949E), fontSize = 12.sp)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = Color(0xFF61AFEF),
                activeTrackColor = Color(0xFF61AFEF),
                inactiveTrackColor = Color(0xFF2A2F35)
            )
        )
    }
}

// ──────────────────────────────────────────────────────────────────
// Document body
// ──────────────────────────────────────────────────────────────────

@Composable
private fun WordDocumentBody(
    doc: WordDocument,
    selectedBlock: Int,
    onSelectBlock: (Int) -> Unit,
    onAppendText: (String, CharacterFormat?) -> Unit,
    onAddParagraph: (CharacterFormat) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .background(Color(0xFF0B0D10))
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        itemsIndexed(doc.blocks, key = { _, b -> b.id() }) { index, block ->
            BlockRow(
                block = block,
                selected = index == selectedBlock,
                onClick = { onSelectBlock(index) },
                onAppend = { text, fmt -> onAppendText(text, fmt) }
            )
        }
        item {
            AddBlockBar(onAddParagraph = onAddParagraph)
        }
    }
}

private fun WordBlock.id(): String = when (this) {
    is WordParagraph -> "p-${runs.hashCode()}-${format.hashCode()}"
    is WordHeading -> "h$level-${runs.hashCode()}"
    is WordListItem -> "l${kind.name}-${depth}-${runs.hashCode()}"
    is WordPageBreak -> "pb"
    is WordHorizontalRule -> "hr"
    is WordBlockQuote -> "bq-${runs.hashCode()}"
    is WordCodeBlock -> "cb-${code.hashCode()}"
}

@Composable
private fun BlockRow(
    block: WordBlock,
    selected: Boolean,
    onClick: () -> Unit,
    onAppend: (String, CharacterFormat?) -> Unit
) {
    val borderColor = if (selected) Color(0xFF61AFEF) else Color.Transparent
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        when (block) {
            is WordParagraph -> ParagraphText(block, selected)
            is WordHeading -> HeadingText(block, selected)
            is WordListItem -> ListItemText(block, selected)
            is WordPageBreak -> PageBreakIndicator()
            is WordHorizontalRule -> HorizontalRuleIndicator()
            is WordBlockQuote -> BlockQuoteText(block, selected)
            is WordCodeBlock -> CodeBlockText(block, selected)
        }
    }
}

@Composable
private fun ParagraphText(p: WordParagraph, selected: Boolean) {
    val annotated = buildRunsAnnotated(p.runs)
    androidx.compose.material3.Text(
        text = annotated,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = p.format.spaceAfterPt.dp / 2),
        textAlign = p.format.alignment.toTextAlign(),
        lineHeight = (p.format.lineSpacingMultiplier * 16).sp
    )
}

@Composable
private fun HeadingText(h: WordHeading, selected: Boolean) {
    val defaultFormat = when (h.level) {
        1 -> CharacterFormat.HEADING_1
        2 -> CharacterFormat.HEADING_2
        3 -> CharacterFormat.HEADING_3
        4 -> CharacterFormat.HEADING_4
        5 -> CharacterFormat.HEADING_5
        else -> CharacterFormat.HEADING_6
    }
    val runs = h.runs.map { it.copy(format = defaultFormat.merge(it.format)) }
    val annotated = buildRunsAnnotated(runs)
    androidx.compose.material3.Text(
        text = annotated,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        textAlign = h.format.alignment.toTextAlign()
    )
}

@Composable
private fun ListItemText(item: WordListItem, selected: Boolean) {
    val prefix = when (item.kind) {
        ListKind.BULLET -> "• "
        ListKind.NUMBERED -> "${item.depth + 1}. "
        ListKind.CHECKBOX -> "☐ "
    }
    val combined = listOf(WordRun(prefix)) + item.runs
    val annotated = buildRunsAnnotated(combined)
    androidx.compose.material3.Text(
        text = annotated,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (12f * (item.depth + 1)).dp, top = 2.dp, bottom = 2.dp)
    )
}

@Composable
private fun PageBreakIndicator() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .height(1.dp)
                .weight(1f)
                .background(Color(0xFF2A2F35))
        )
        Spacer(Modifier.width(8.dp))
        Text("PAGE BREAK", color = Color(0xFF8B949E), fontSize = 10.sp)
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .height(1.dp)
                .weight(1f)
                .background(Color(0xFF2A2F35))
        )
    }
}

@Composable
private fun HorizontalRuleIndicator() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0xFF2A2F35))
    )
}

@Composable
private fun BlockQuoteText(b: WordBlockQuote, selected: Boolean) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .heightIn(min = 32.dp)
                .background(Color(0xFF61AFEF))
        )
        Spacer(Modifier.width(8.dp))
        Column {
            val annotated = buildRunsAnnotated(b.runs)
            androidx.compose.material3.Text(text = annotated, modifier = Modifier.fillMaxWidth())
            b.citation?.let {
                Spacer(Modifier.height(2.dp))
                Text("— $it", color = Color(0xFF8B949E), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun CodeBlockText(c: WordCodeBlock, selected: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0F1115))
            .border(1.dp, Color(0xFF2A2F35), RoundedCornerShape(4.dp))
            .padding(8.dp)
    ) {
        androidx.compose.material3.Text(
            text = c.code,
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFFE4E7EB),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
    }
}

private fun buildRunsAnnotated(
    runs: List<WordRun>
): AnnotatedString = buildAnnotatedString {
    runs.forEach { run ->
        val color = run.format.composeColor()
        withStyle(
            SpanStyle(
                color = color,
                fontSize = run.format.composeFontSize(),
                fontFamily = run.format.composeFontFamily(),
                fontWeight = run.format.composeWeight(),
                fontStyle = run.format.composeStyle(),
                textDecoration = run.format.composeDecoration(),
                letterSpacing = run.format.letterSpacing.sp
            )
        ) {
            append(run.text)
        }
    }
}

private fun TextAlignment.toTextAlign(): TextAlign = when (this) {
    TextAlignment.LEFT -> TextAlign.Start
    TextAlignment.CENTER -> TextAlign.Center
    TextAlignment.RIGHT -> TextAlign.End
    TextAlignment.JUSTIFY -> TextAlign.Justify
}

@Composable
private fun AddBlockBar(onAddParagraph: (CharacterFormat) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        TextButton(onClick = { onAddParagraph(CharacterFormat()) }) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = Color(0xFF61AFEF))
            Spacer(Modifier.width(4.dp))
            Text("Add paragraph", color = Color(0xFF61AFEF))
        }
    }
}

// ──────────────────────────────────────────────────────────────────
// Author + status
// ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthorAndStatusBar(
    author: String,
    onAuthorChange: (String) -> Unit,
    wordCount: Int,
    paragraphCount: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0F1115))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        OutlinedTextField(
            value = author,
            onValueChange = onAuthorChange,
            placeholder = { Text("Author", color = Color(0xFF8B949E), fontSize = 11.sp) },
            textStyle = TextStyle(color = Color(0xFFE4E7EB), fontSize = 11.sp),
            singleLine = true,
            modifier = Modifier.width(180.dp)
        )
        Text(
            "$wordCount words · $paragraphCount paragraphs",
            color = Color(0xFF8B949E),
            fontSize = 10.sp
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
    var format by remember { mutableStateOf("elysium.word") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save document") },
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChipChoice("Elysium", format == "elysium.word") { format = "elysium.word" }
                    FilterChipChoice("Word (.docx)", format == "docx") { format = "docx" }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ext = if (format == "docx") "docx" else "elysium.word"
                onConfirm("${title}.${ext}")
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun FilterChipChoice(label: String, selected: Boolean, onClick: () -> Unit) {
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
