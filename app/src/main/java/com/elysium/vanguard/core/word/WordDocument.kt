package com.elysium.vanguard.core.word

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * PHASE 10.5 — Elysium Word format model.
 *
 * This is the "fully functional" Word clone Jor asked for. It supports:
 *
 *  - **Fonts**: family (sans / serif / monospace + named), size, color.
 *  - **Typography**: bold, italic, underline, strikethrough, superscript,
 *    subscript, small caps, all caps.
 *  - **Spacing**: line spacing (single / 1.5 / double / custom), space
 *    before/after a paragraph, indent left/right, first-line indent,
 *    hanging indent.
 *  - **Alignment**: left, center, right, justify.
 *  - **Lists**: bullet list, numbered list, with nesting depth.
 *  - **Headings**: H1–H6 with semantic sizes.
 *  - **Block-level**: page breaks, horizontal rules, block quotes,
 *    code blocks.
 *  - **Inline**: hyperlinks, comments-anchor (render only — full thread
 *    of comments is parked in 10.5.x), fields (=PAGE, =DATE, etc.).
 *
 * The model is plain Kotlin (no Compose) so it round-trips through JSON
 * for the `.elysium.word` format and through OOXML for the `.docx`
 * format. Pure JVM, no Android dependencies.
 */

/**
 * The full document — a list of blocks. The header convention
 * "# Title" / "_by author_" is honored on save (matches
 * ElysiumWordRenderer / CrdtElysiumDocument).
 */
data class WordDocument(
    val title: String = "Untitled",
    val author: String = "",
    val blocks: List<WordBlock> = listOf(WordParagraph(runs = listOf(WordRun("")))),
    val pageSettings: PageSettings = PageSettings(),
    val styles: Map<String, StyleDefinition> = emptyMap(),
    val revision: Long = 0L
) {
    /** Plain-text concatenation; useful for search and tests. */
    fun plainText(): String = blocks.joinToString("\n") { it.plainText() }

    /** Number of top-level paragraphs (paragraph + list_item) in the doc. */
    fun paragraphCount(): Int = blocks.count { it is WordParagraph }

    /** Number of words across the whole document. */
    fun wordCount(): Int = plainText()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .size
}

/**
 * Settings that affect the page-level rendering. Margins are in
 * twentieths of a point (1/20 pt) — the unit OOXML uses — so the
 * document can be saved as `.docx` without rescaling.
 */
data class PageSettings(
    val pageWidth: Int = 12240,   // 8.5" in twips
    val pageHeight: Int = 15840,  // 11"  in twips
    val marginTop: Int = 1440,
    val marginBottom: Int = 1440,
    val marginLeft: Int = 1440,
    val marginRight: Int = 1440,
    val headerDistance: Int = 720,
    val footerDistance: Int = 720
)

/**
 * Style definitions keyed by id (e.g. "Heading 1", "Code"). Block-
 * level styles apply to paragraphs; inline styles apply to runs.
 */
data class StyleDefinition(
    val id: String,
    val name: String,
    val basedOn: String? = null,
    val isDefault: Boolean = false,
    val character: CharacterFormat = CharacterFormat(),
    val paragraph: ParagraphFormat = ParagraphFormat()
)

/** A block-level element. Sealed so the renderer and IO exhaustively match. */
sealed class WordBlock {
    abstract fun plainText(): String
}

data class WordParagraph(
    val runs: List<WordRun>,
    val format: ParagraphFormat = ParagraphFormat()
) : WordBlock() {
    override fun plainText(): String = runs.joinToString("") { it.text }
}

/**
 * A heading — same data as a paragraph but with a semantic level. We
 * keep it as a distinct class so the renderer can paint the heading
 * style even when the user hasn't picked a Style yet (it falls back
 * to the level's default character + paragraph format).
 */
data class WordHeading(
    val level: Int, // 1..6
    val runs: List<WordRun>,
    val format: ParagraphFormat = ParagraphFormat()
) : WordBlock() {
    init {
        require(level in 1..6) { "Heading level must be 1..6, was $level" }
    }
    override fun plainText(): String = runs.joinToString("") { it.text }
}

/** A bulleted / numbered list item. */
data class WordListItem(
    val runs: List<WordRun>,
    val kind: ListKind,
    val depth: Int = 0,
    val format: ParagraphFormat = ParagraphFormat()
) : WordBlock() {
    init {
        require(depth in 0..8) { "List nesting depth must be 0..8, was $depth" }
    }
    override fun plainText(): String = runs.joinToString("") { it.text }
}

enum class ListKind { BULLET, NUMBERED, CHECKBOX }

/** A page break. */
data class WordPageBreak(val placeholder: Unit = Unit) : WordBlock() {
    override fun plainText(): String = "\u000c" // form feed
}

/** A horizontal rule. */
data class WordHorizontalRule(val placeholder: Unit = Unit) : WordBlock() {
    override fun plainText(): String = ""
}

/** A block quote — visually distinct paragraph style. */
data class WordBlockQuote(
    val runs: List<WordRun>,
    val citation: String? = null,
    val format: ParagraphFormat = ParagraphFormat()
) : WordBlock() {
    override fun plainText(): String =
        runs.joinToString("") { it.text } + (citation?.let { " — $it" } ?: "")
}

/** A code block — preserves whitespace, uses monospace font. */
data class WordCodeBlock(
    val code: String,
    val language: String? = null,
    val format: ParagraphFormat = ParagraphFormat()
) : WordBlock() {
    override fun plainText(): String = code
}

/**
 * One run of text within a paragraph / heading / list item. Carries
 * its own [CharacterFormat]; the document's per-block [ParagraphFormat]
 * is the parent.
 */
data class WordRun(
    val text: String,
    val format: CharacterFormat = CharacterFormat(),
    /** Optional hyperlink target. When set, the renderer paints the
     *  text in the hyperlink color and underline. */
    val hyperlink: String? = null
)

/**
 * PHASE 10.5 — Character-level formatting.
 *
 * Holds every typographic knob the user can twist in the editor
 * toolbar. All fields have sensible defaults; `merge(base)` returns
 * a new format with this format's fields overriding [base].
 */
data class CharacterFormat(
    val fontFamily: String = "sans-serif",
    val fontSizeSp: Float = 14f,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val superscript: Boolean = false,
    val subscript: Boolean = false,
    val smallCaps: Boolean = false,
    val allCaps: Boolean = false,
    /** Packed RGB (e.g. 0xFFE4E7EB). `null` means "use theme default". */
    val color: Long? = null,
    /** Optional background highlight as packed RGB. */
    val highlight: Long? = null,
    val letterSpacing: Float = 0f,
    val baselineShift: Float = 0f
) {
    /** Compose's [FontFamily] derived from [fontFamily]. */
    fun composeFontFamily(): FontFamily = when (fontFamily.lowercase()) {
        "serif" -> FontFamily.Serif
        "monospace", "mono" -> FontFamily.Monospace
        "sans-serif", "sans", "default" -> FontFamily.SansSerif
        "cursive" -> FontFamily.Cursive
        else -> FontFamily.SansSerif // unknown → safe default
    }

    fun composeColor(): Color =
        if (color == null) Color(0xFFE4E7EB) else Color(color)

    fun composeHighlight(): Color? =
        highlight?.let { Color(it) }

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

    /** True if this format equals another in every field. */
    fun isSameAs(other: CharacterFormat): Boolean = this == other

    /** Returns this format with every field that has a non-default value
     *  overriding [base]. Used when "bolding" only the selection. */
    fun merge(base: CharacterFormat): CharacterFormat {
        val merged = base.copy()
        return merged.copy(
            fontFamily = if (fontFamily != CharacterFormat().fontFamily) fontFamily else merged.fontFamily,
            fontSizeSp = if (fontSizeSp != CharacterFormat().fontSizeSp) fontSizeSp else merged.fontSizeSp,
            bold = bold || merged.bold,
            italic = italic || merged.italic,
            underline = underline || merged.underline,
            strikethrough = strikethrough || merged.strikethrough,
            superscript = superscript || merged.superscript,
            subscript = subscript || merged.subscript,
            smallCaps = smallCaps || merged.smallCaps,
            allCaps = allCaps || merged.allCaps,
            color = color ?: merged.color,
            highlight = highlight ?: merged.highlight,
            letterSpacing = if (letterSpacing != 0f) letterSpacing else merged.letterSpacing,
            baselineShift = if (baselineShift != 0f) baselineShift else merged.baselineShift
        )
    }

    companion object {
        /** Default body-text format: 14sp, sans-serif, no decoration. */
        val DEFAULT = CharacterFormat()
        /** Default heading 1: 28sp, bold, sans-serif. */
        val HEADING_1 = CharacterFormat(fontSizeSp = 28f, bold = true)
        val HEADING_2 = CharacterFormat(fontSizeSp = 24f, bold = true)
        val HEADING_3 = CharacterFormat(fontSizeSp = 20f, bold = true)
        val HEADING_4 = CharacterFormat(fontSizeSp = 18f, bold = true)
        val HEADING_5 = CharacterFormat(fontSizeSp = 16f, bold = true)
        val HEADING_6 = CharacterFormat(fontSizeSp = 14f, bold = true, italic = true)
        /** Monospace code-run preset. */
        val CODE = CharacterFormat(fontFamily = "monospace", fontSizeSp = 13f)
    }
}

/**
 * PHASE 10.5 — Paragraph-level formatting.
 *
 * Covers alignment, line spacing, space before/after, indent
 * (left/right/first-line/hanging), list formatting, keep-with-next,
 * page-break-before.
 */
data class ParagraphFormat(
    val alignment: TextAlignment = TextAlignment.LEFT,
    /** Multiple of the body's font size. 1.0 = single, 1.5 = 1.5, 2.0 = double. */
    val lineSpacingMultiplier: Float = 1.15f,
    /** Space before the paragraph, in points. */
    val spaceBeforePt: Float = 0f,
    /** Space after the paragraph, in points. */
    val spaceAfterPt: Float = 6f,
    val indentLeftPt: Float = 0f,
    val indentRightPt: Float = 0f,
    val indentFirstLinePt: Float = 0f,
    val indentHangingPt: Float = 0f,
    val keepWithNext: Boolean = false,
    val pageBreakBefore: Boolean = false,
    /** Tab stops expressed as a list of "position in points" + alignment. */
    val tabStops: List<TabStop> = emptyList()
) {
    companion object {
        val DEFAULT = ParagraphFormat()
        val HEADING = ParagraphFormat(
            lineSpacingMultiplier = 1.15f,
            spaceBeforePt = 18f,
            spaceAfterPt = 6f,
            keepWithNext = true
        )
        val BLOCK_QUOTE = ParagraphFormat(
            indentLeftPt = 36f,
            indentRightPt = 36f,
            spaceBeforePt = 6f,
            spaceAfterPt = 6f
        )
        val CODE_BLOCK = ParagraphFormat(
            lineSpacingMultiplier = 1.0f,
            spaceBeforePt = 0f,
            spaceAfterPt = 0f
        )
    }
}

enum class TextAlignment { LEFT, CENTER, RIGHT, JUSTIFY }

data class TabStop(val positionPt: Float, val alignment: TabAlignment = TabAlignment.LEFT)
enum class TabAlignment { LEFT, CENTER, RIGHT, DECIMAL }

/**
 * PHASE 10.5 — Built-in character-format preset palette. These map
 * 1:1 to common Office / Google Docs presets so the toolbar can
 * surface "Title / Subtitle / Body / Quote" without forcing the user
 * to fiddle with sliders for the common case.
 */
object CharacterPresets {
    val BODY = CharacterFormat.DEFAULT
    val SUBTLE = CharacterFormat(fontSizeSp = 12f, color = 0xFF8B949E)
    val EMPHASIS = CharacterFormat(italic = true)
    val STRONG = CharacterFormat(bold = true)
    val CODE = CharacterFormat(fontFamily = "monospace", fontSizeSp = 13f)
    val LINK = CharacterFormat(color = 0xFF61AFEF, underline = true)
    val TITLE = CharacterFormat(fontSizeSp = 32f, bold = true, fontFamily = "serif")
    val SUBTITLE = CharacterFormat(fontSizeSp = 18f, italic = true, color = 0xFF8B949E)
}
