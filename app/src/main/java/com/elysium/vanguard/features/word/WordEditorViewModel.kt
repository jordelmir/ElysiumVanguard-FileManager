package com.elysium.vanguard.features.word

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elysium.vanguard.core.word.CharacterFormat
import com.elysium.vanguard.core.word.ParagraphFormat
import com.elysium.vanguard.core.word.TextAlignment
import com.elysium.vanguard.core.word.WordBlock
import com.elysium.vanguard.core.word.WordBlockQuote
import com.elysium.vanguard.core.word.WordCodeBlock
import com.elysium.vanguard.core.word.WordDocx
import com.elysium.vanguard.core.word.WordDocument
import com.elysium.vanguard.core.word.WordFile
import com.elysium.vanguard.core.word.WordHeading
import com.elysium.vanguard.core.word.WordHorizontalRule
import com.elysium.vanguard.core.word.WordListItem
import com.elysium.vanguard.core.word.WordPageBreak
import com.elysium.vanguard.core.word.WordParagraph
import com.elysium.vanguard.core.word.WordRun
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
 * PHASE 10.5 — Elysium Word editor ViewModel.
 *
 * The editor is a list-of-blocks model: every [WordBlock] is a
 * paragraph / heading / list item / etc. with a list of runs. The
 * state here is just the [WordDocument] plus a few UI affordances
 * (selection, panel visibility). We keep it minimal so the screen
 * can drive the model with simple copy() calls.
 *
 * The model lives in `core/word/WordDocument.kt` so the same data
 * class is what the IO layer reads / writes.
 */
@HiltViewModel
class WordEditorViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val initialPath: String? =
        savedStateHandle.get<String>("path")?.takeIf { it.isNotEmpty() }

    private val _doc = MutableStateFlow(WordDocument())
    val doc: StateFlow<WordDocument> = _doc.asStateFlow()

    /** The block index the caret / selection is in. -1 when no block. */
    private val _selectedBlock = MutableStateFlow(0)
    val selectedBlock: StateFlow<Int> = _selectedBlock.asStateFlow()

    /** The run index within the selected block, or 0. */
    private val _selectedRun = MutableStateFlow(0)
    val selectedRun: StateFlow<Int> = _selectedRun.asStateFlow()

    /** Whether the formatting panel is shown. */
    private val _showFormatPanel = MutableStateFlow(false)
    val showFormatPanel: StateFlow<Boolean> = _showFormatPanel.asStateFlow()

    /** Most recent error from save / load. */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** The path the document was loaded from, if any. */
    var currentPath: String? = null
        private set

    init {
        if (initialPath != null) {
            loadFromPath(initialPath)
        }
    }

    // ── I/O ────────────────────────────────────────────────────────

    fun loadFromPath(path: String) {
        viewModelScope.launch {
            val file = File(path)
            val loaded = withContext(Dispatchers.IO) {
                when {
                    path.endsWith(".docx", true) -> WordDocx.importFile(file)
                    path.endsWith(".elysium.word", true) -> WordFile.readFile(file)
                    else -> null
                }
            }
            if (loaded != null) {
                _doc.value = loaded
                currentPath = path
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
                        path.endsWith(".docx", true) -> WordDocx.exportFile(_doc.value, file)
                        else -> WordFile.writeFile(file, _doc.value)
                    }
                }
            }.onFailure { _lastError.value = "Save failed: ${it.message}" }
        }
    }

    fun saveAs(path: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(path)
                    when {
                        path.endsWith(".docx", true) -> WordDocx.exportFile(_doc.value, file)
                        else -> WordFile.writeFile(file, _doc.value)
                    }
                }
            }.onSuccess {
                currentPath = path
            }.onFailure { _lastError.value = "Save failed: ${it.message}" }
        }
    }

    // ── Document-level mutations ───────────────────────────────────

    fun setTitle(title: String) {
        _doc.value = _doc.value.copy(title = title)
    }

    fun setAuthor(author: String) {
        _doc.value = _doc.value.copy(author = author)
    }

    fun toggleFormatPanel() {
        _showFormatPanel.value = !_showFormatPanel.value
    }

    fun dismissError() {
        _lastError.value = null
    }

    // ── Block-level operations ─────────────────────────────────────

    fun selectBlock(index: Int) {
        if (index < 0 || index >= _doc.value.blocks.size) return
        _selectedBlock.value = index
        _selectedRun.value = 0
    }

    fun setBlocks(blocks: List<WordBlock>) {
        _doc.value = _doc.value.copy(blocks = blocks, revision = _doc.value.revision + 1)
    }

    fun replaceBlock(index: Int, block: WordBlock) {
        if (index !in _doc.value.blocks.indices) return
        val updated = _doc.value.blocks.toMutableList()
        updated[index] = block
        _doc.value = _doc.value.copy(blocks = updated, revision = _doc.value.revision + 1)
    }

    fun insertBlockAfter(index: Int, block: WordBlock) {
        val updated = _doc.value.blocks.toMutableList()
        val insertAt = (index + 1).coerceAtMost(updated.size)
        updated.add(insertAt, block)
        _doc.value = _doc.value.copy(blocks = updated, revision = _doc.value.revision + 1)
        _selectedBlock.value = insertAt
    }

    fun deleteBlock(index: Int) {
        if (_doc.value.blocks.size <= 1) return
        val updated = _doc.value.blocks.toMutableList()
        updated.removeAt(index)
        _doc.value = _doc.value.copy(blocks = updated, revision = _doc.value.revision + 1)
        _selectedBlock.value = (index - 1).coerceAtLeast(0)
    }

    fun moveBlockUp(index: Int) {
        if (index <= 0) return
        val updated = _doc.value.blocks.toMutableList()
        val swap = updated[index]
        updated[index] = updated[index - 1]
        updated[index - 1] = swap
        _doc.value = _doc.value.copy(blocks = updated, revision = _doc.value.revision + 1)
        _selectedBlock.value = index - 1
    }

    fun moveBlockDown(index: Int) {
        if (index >= _doc.value.blocks.size - 1) return
        val updated = _doc.value.blocks.toMutableList()
        val swap = updated[index]
        updated[index] = updated[index + 1]
        updated[index + 1] = swap
        _doc.value = _doc.value.copy(blocks = updated, revision = _doc.value.revision + 1)
        _selectedBlock.value = index + 1
    }

    // ── Block-type transformers ────────────────────────────────────

    fun convertBlockToHeading(index: Int, level: Int) {
        if (index !in _doc.value.blocks.indices) return
        val block = _doc.value.blocks[index]
        val runs = when (block) {
            is WordParagraph -> block.runs
            is WordHeading -> block.runs
            is WordListItem -> block.runs
            is WordBlockQuote -> block.runs
            else -> listOf(WordRun(""))
        }
        val heading = WordHeading(
            level = level,
            runs = runs,
            format = ParagraphFormat.HEADING
        )
        replaceBlock(index, heading)
    }

    fun convertBlockToParagraph(index: Int) {
        if (index !in _doc.value.blocks.indices) return
        val block = _doc.value.blocks[index]
        val runs = when (block) {
            is WordParagraph -> block.runs
            is WordHeading -> block.runs
            is WordListItem -> block.runs
            is WordBlockQuote -> block.runs
            is WordCodeBlock -> block.code.lines().map { WordRun(it, CharacterFormat.CODE) }
            else -> listOf(WordRun(""))
        }
        replaceBlock(index, WordParagraph(runs = runs, format = ParagraphFormat.DEFAULT))
    }

    fun convertBlockToListItem(index: Int, kind: com.elysium.vanguard.core.word.ListKind) {
        if (index !in _doc.value.blocks.indices) return
        val block = _doc.value.blocks[index]
        val runs = when (block) {
            is WordParagraph -> block.runs
            is WordHeading -> block.runs
            is WordListItem -> block.runs
            is WordBlockQuote -> block.runs
            else -> listOf(WordRun(""))
        }
        replaceBlock(index, WordListItem(runs = runs, kind = kind))
    }

    fun convertBlockToBlockQuote(index: Int) {
        if (index !in _doc.value.blocks.indices) return
        val block = _doc.value.blocks[index]
        val runs = when (block) {
            is WordParagraph -> block.runs
            is WordHeading -> block.runs
            is WordListItem -> block.runs
            is WordBlockQuote -> block.runs
            else -> listOf(WordRun(""))
        }
        replaceBlock(index, WordBlockQuote(runs = runs, format = ParagraphFormat.BLOCK_QUOTE))
    }

    fun convertBlockToCodeBlock(index: Int, language: String? = null) {
        if (index !in _doc.value.blocks.indices) return
        val block = _doc.value.blocks[index]
        val code = when (block) {
            is WordParagraph -> block.plainText()
            is WordHeading -> block.plainText()
            is WordListItem -> block.plainText()
            is WordCodeBlock -> block.code
            else -> ""
        }
        replaceBlock(index, WordCodeBlock(code = code, language = language, format = ParagraphFormat.CODE_BLOCK))
    }

    fun insertPageBreak() {
        insertBlockAfter(_selectedBlock.value, WordPageBreak())
    }

    fun insertHorizontalRule() {
        insertBlockAfter(_selectedBlock.value, WordHorizontalRule())
    }

    // ── Run-level mutations (selection editing) ────────────────────

    /**
     * Apply [format] to all runs in the currently selected block.
     * Useful for "select paragraph, bold everything".
     */
    fun applyFormatToSelectedBlock(format: CharacterFormat) {
        val index = _selectedBlock.value
        if (index !in _doc.value.blocks.indices) return
        val block = _doc.value.blocks[index]
        if (block !is WordParagraph) return
        val newRuns = block.runs.map { run -> run.copy(format = format.merge(run.format)) }
        replaceBlock(index, block.copy(runs = newRuns))
    }

    /** Apply bold to the selected block. */
    fun toggleBold() {
        val block = currentBlock() as? WordParagraph ?: return
        val allBold = block.runs.all { it.format.bold }
        val newRuns = block.runs.map { it.copy(format = it.format.copy(bold = !allBold)) }
        replaceBlock(_selectedBlock.value, block.copy(runs = newRuns))
    }

    fun toggleItalic() {
        val block = currentBlock() as? WordParagraph ?: return
        val allItalic = block.runs.all { it.format.italic }
        val newRuns = block.runs.map { it.copy(format = it.format.copy(italic = !allItalic)) }
        replaceBlock(_selectedBlock.value, block.copy(runs = newRuns))
    }

    fun toggleUnderline() {
        val block = currentBlock() as? WordParagraph ?: return
        val allUnderline = block.runs.all { it.format.underline }
        val newRuns = block.runs.map { it.copy(format = it.format.copy(underline = !allUnderline)) }
        replaceBlock(_selectedBlock.value, block.copy(runs = newRuns))
    }

    fun toggleStrikethrough() {
        val block = currentBlock() as? WordParagraph ?: return
        val allStrike = block.runs.all { it.format.strikethrough }
        val newRuns = block.runs.map { it.copy(format = it.format.copy(strikethrough = !allStrike)) }
        replaceBlock(_selectedBlock.value, block.copy(runs = newRuns))
    }

    fun setFontFamily(family: String) {
        val block = currentBlock() as? WordParagraph ?: return
        val newRuns = block.runs.map { it.copy(format = it.format.copy(fontFamily = family)) }
        replaceBlock(_selectedBlock.value, block.copy(runs = newRuns))
    }

    fun setFontSize(sizeSp: Float) {
        val block = currentBlock() as? WordParagraph ?: return
        val newRuns = block.runs.map { it.copy(format = it.format.copy(fontSizeSp = sizeSp)) }
        replaceBlock(_selectedBlock.value, block.copy(runs = newRuns))
    }

    fun setTextColor(color: Long?) {
        val block = currentBlock() as? WordParagraph ?: return
        val newRuns = block.runs.map { it.copy(format = it.format.copy(color = color)) }
        replaceBlock(_selectedBlock.value, block.copy(runs = newRuns))
    }

    // ── Paragraph-format mutations ─────────────────────────────────

    fun setAlignment(alignment: TextAlignment) {
        val block = currentBlock()
        if (block is WordParagraph) {
            replaceBlock(_selectedBlock.value, block.copy(format = block.format.copy(alignment = alignment)))
        } else if (block is WordHeading) {
            replaceBlock(_selectedBlock.value, block.copy(format = block.format.copy(alignment = alignment)))
        } else if (block is WordListItem) {
            replaceBlock(_selectedBlock.value, block.copy(format = block.format.copy(alignment = alignment)))
        }
    }

    fun setLineSpacing(multiplier: Float) {
        val block = currentBlock() as? WordParagraph ?: return
        replaceBlock(_selectedBlock.value, block.copy(format = block.format.copy(lineSpacingMultiplier = multiplier)))
    }

    fun setSpaceBefore(pts: Float) {
        val block = currentBlock() as? WordParagraph ?: return
        replaceBlock(_selectedBlock.value, block.copy(format = block.format.copy(spaceBeforePt = pts)))
    }

    fun setSpaceAfter(pts: Float) {
        val block = currentBlock() as? WordParagraph ?: return
        replaceBlock(_selectedBlock.value, block.copy(format = block.format.copy(spaceAfterPt = pts)))
    }

    fun setIndentLeft(pts: Float) {
        val block = currentBlock() as? WordParagraph ?: return
        replaceBlock(_selectedBlock.value, block.copy(format = block.format.copy(indentLeftPt = pts)))
    }

    // ── Text editing ──────────────────────────────────────────────

    /**
     * Append [text] to the run at [runIndex] of the currently selected
     * block, creating a new run when the block is empty.
     */
    fun appendText(text: String, format: CharacterFormat? = null) {
        val index = _selectedBlock.value
        if (index !in _doc.value.blocks.indices) return
        val block = _doc.value.blocks[index]
        if (block is WordParagraph) {
            val newRuns = if (block.runs.isEmpty()) {
                listOf(WordRun(text, format ?: CharacterFormat()))
            } else {
                val last = block.runs.last()
                val merged = last.copy(
                    text = last.text + text,
                    format = format?.merge(last.format) ?: last.format
                )
                block.runs.dropLast(1) + merged
            }
            replaceBlock(index, block.copy(runs = newRuns))
        } else {
            // Any non-paragraph block becomes a paragraph carrying the text.
            replaceBlock(index, WordParagraph(runs = listOf(WordRun(text, format ?: CharacterFormat()))))
        }
    }

    fun clearSelectedBlock() {
        val index = _selectedBlock.value
        if (index !in _doc.value.blocks.indices) return
        val block = _doc.value.blocks[index]
        if (block is WordParagraph) {
            replaceBlock(index, block.copy(runs = listOf(WordRun(""))))
        } else {
            replaceBlock(index, WordParagraph(runs = listOf(WordRun(""))))
        }
    }

    fun newParagraphAfterSelected(format: CharacterFormat = CharacterFormat()) {
        insertBlockAfter(_selectedBlock.value, WordParagraph(runs = listOf(WordRun("", format))))
    }

    // ── Helpers ───────────────────────────────────────────────────

    private fun currentBlock(): WordBlock? = _doc.value.blocks.getOrNull(_selectedBlock.value)
}
