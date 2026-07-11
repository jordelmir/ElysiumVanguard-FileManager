package com.elysium.vanguard.core.word

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.lang.reflect.Type

/**
 * PHASE 10.5 — JSON adapter for [WordDocument].
 *
 * `.elysium.word` is the canonical Elysium on-disk format. It is a
 * single JSON object that round-trips losslessly. We keep the schema
 * in this file so changes are visible alongside the model.
 *
 * Schema (compact form):
 * ```
 * {
 *   "title": "...",
 *   "author": "...",
 *   "revision": 7,
 *   "page": { ... PageSettings ... },
 *   "styles": { "Heading 1": { ... StyleDefinition ... } },
 *   "blocks": [
 *     { "type": "paragraph", "runs": [ ... ], "format": { ... } },
 *     { "type": "heading", "level": 1, "runs": [ ... ] },
 *     { "type": "list_item", "kind": "bullet", "depth": 0, "runs": [ ... ] },
 *     { "type": "page_break" },
 *     { "type": "horizontal_rule" },
 *     { "type": "block_quote", "runs": [ ... ], "citation": "..." },
 *     { "type": "code_block", "code": "...", "language": "kotlin" }
 *   ]
 * }
 * ```
 *
 * Run and CharacterFormat use a flat shape so users can hand-edit
 * the JSON in a text editor without learning a deep object graph.
 */
object WordJson {
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(WordBlock::class.java, BlockAdapter)
        .registerTypeAdapter(CharacterFormat::class.java, CharacterFormatAdapter)
        .registerTypeAdapter(ParagraphFormat::class.java, ParagraphFormatAdapter)
        .setPrettyPrinting()
        .create()

    fun toJson(doc: WordDocument): String = gson.toJson(doc)
    fun fromJson(text: String): WordDocument = gson.fromJson(text, WordDocument::class.java)

    // ── Adapters ──────────────────────────────────────────────────

    private object BlockAdapter : JsonSerializer<WordBlock>, JsonDeserializer<WordBlock> {
        override fun serialize(src: WordBlock, type: Type, ctx: JsonSerializationContext): JsonElement {
            val o = JsonObject()
            when (src) {
                is WordParagraph -> {
                    o.addProperty("type", "paragraph")
                    o.add("runs", ctx.serialize(src.runs))
                    o.add("format", ctx.serialize(src.format))
                }
                is WordHeading -> {
                    o.addProperty("type", "heading")
                    o.addProperty("level", src.level)
                    o.add("runs", ctx.serialize(src.runs))
                    o.add("format", ctx.serialize(src.format))
                }
                is WordListItem -> {
                    o.addProperty("type", "list_item")
                    o.addProperty("kind", src.kind.name.lowercase())
                    o.addProperty("depth", src.depth)
                    o.add("runs", ctx.serialize(src.runs))
                    o.add("format", ctx.serialize(src.format))
                }
                is WordPageBreak -> o.addProperty("type", "page_break")
                is WordHorizontalRule -> o.addProperty("type", "horizontal_rule")
                is WordBlockQuote -> {
                    o.addProperty("type", "block_quote")
                    o.add("runs", ctx.serialize(src.runs))
                    src.citation?.let { o.addProperty("citation", it) }
                    o.add("format", ctx.serialize(src.format))
                }
                is WordCodeBlock -> {
                    o.addProperty("type", "code_block")
                    o.addProperty("code", src.code)
                    src.language?.let { o.addProperty("language", it) }
                    o.add("format", ctx.serialize(src.format))
                }
            }
            return o
        }

        override fun deserialize(json: JsonElement, type: Type, ctx: JsonDeserializationContext): WordBlock {
            val o = json.asJsonObject
            fun runs(): List<WordRun> = ctx.deserialize<Array<WordRun>>(o.get("runs"), Array<WordRun>::class.java).toList()
            fun format(): ParagraphFormat =
                ctx.deserialize<ParagraphFormat>(o.get("format"), ParagraphFormat::class.java)
            return when (val t = o.get("type")?.asString) {
                "paragraph" -> WordParagraph(runs = runs(), format = format())
                "heading" -> WordHeading(
                    level = o.get("level").asInt,
                    runs = runs(),
                    format = format()
                )
                "list_item" -> WordListItem(
                    runs = runs(),
                    kind = runCatching { ListKind.valueOf((o.get("kind")?.asString ?: "bullet").uppercase()) }
                        .getOrDefault(ListKind.BULLET),
                    depth = o.get("depth")?.asInt ?: 0,
                    format = format()
                )
                "page_break" -> WordPageBreak()
                "horizontal_rule" -> WordHorizontalRule()
                "block_quote" -> WordBlockQuote(
                    runs = runs(),
                    citation = o.get("citation")?.takeUnless { it.isJsonNull }?.asString,
                    format = format()
                )
                "code_block" -> WordCodeBlock(
                    code = o.get("code").asString,
                    language = o.get("language")?.takeUnless { it.isJsonNull }?.asString,
                    format = format()
                )
                else -> throw IllegalArgumentException("Unknown block type: $t")
            }
        }
    }

    private object CharacterFormatAdapter : JsonSerializer<CharacterFormat>, JsonDeserializer<CharacterFormat> {
        override fun serialize(src: CharacterFormat, type: Type, ctx: JsonSerializationContext): JsonElement {
            val o = JsonObject()
            o.addProperty("font", src.fontFamily)
            o.addProperty("size", src.fontSizeSp)
            o.addProperty("bold", src.bold)
            o.addProperty("italic", src.italic)
            o.addProperty("underline", src.underline)
            o.addProperty("strike", src.strikethrough)
            o.addProperty("super", src.superscript)
            o.addProperty("sub", src.subscript)
            o.addProperty("smallCaps", src.smallCaps)
            o.addProperty("allCaps", src.allCaps)
            src.color?.let { o.addProperty("color", "0x%08X".format(it)) }
            src.highlight?.let { o.addProperty("highlight", "0x%08X".format(it)) }
            if (src.letterSpacing != 0f) o.addProperty("letterSpacing", src.letterSpacing)
            if (src.baselineShift != 0f) o.addProperty("baselineShift", src.baselineShift)
            return o
        }
        override fun deserialize(json: JsonElement, type: Type, ctx: JsonDeserializationContext): CharacterFormat {
            val o = json.asJsonObject
            return CharacterFormat(
                fontFamily = o.get("font")?.takeUnless { it.isJsonNull }?.asString ?: "sans-serif",
                fontSizeSp = o.get("size")?.asFloat ?: 14f,
                bold = o.get("bold")?.asBoolean ?: false,
                italic = o.get("italic")?.asBoolean ?: false,
                underline = o.get("underline")?.asBoolean ?: false,
                strikethrough = o.get("strike")?.asBoolean ?: false,
                superscript = o.get("super")?.asBoolean ?: false,
                subscript = o.get("sub")?.asBoolean ?: false,
                smallCaps = o.get("smallCaps")?.asBoolean ?: false,
                allCaps = o.get("allCaps")?.asBoolean ?: false,
                color = o.get("color")?.takeUnless { it.isJsonNull }?.asString?.let(::parseHex),
                highlight = o.get("highlight")?.takeUnless { it.isJsonNull }?.asString?.let(::parseHex),
                letterSpacing = o.get("letterSpacing")?.asFloat ?: 0f,
                baselineShift = o.get("baselineShift")?.asFloat ?: 0f
            )
        }

        private fun parseHex(s: String): Long {
            val cleaned = s.removePrefix("0x").removePrefix("0X")
            return cleaned.toLong(16)
        }
    }

    private object ParagraphFormatAdapter : JsonSerializer<ParagraphFormat>, JsonDeserializer<ParagraphFormat> {
        override fun serialize(src: ParagraphFormat, type: Type, ctx: JsonSerializationContext): JsonElement {
            val o = JsonObject()
            o.addProperty("align", src.alignment.name.lowercase())
            o.addProperty("line", src.lineSpacingMultiplier)
            o.addProperty("before", src.spaceBeforePt)
            o.addProperty("after", src.spaceAfterPt)
            o.addProperty("left", src.indentLeftPt)
            o.addProperty("right", src.indentRightPt)
            o.addProperty("first", src.indentFirstLinePt)
            o.addProperty("hanging", src.indentHangingPt)
            o.addProperty("keepNext", src.keepWithNext)
            o.addProperty("pageBreak", src.pageBreakBefore)
            if (src.tabStops.isNotEmpty()) {
                o.add("tabs", ctx.serialize(src.tabStops))
            }
            return o
        }
        override fun deserialize(json: JsonElement, type: Type, ctx: JsonDeserializationContext): ParagraphFormat {
            val o = json.asJsonObject
            return ParagraphFormat(
                alignment = runCatching {
                    TextAlignment.valueOf((o.get("align")?.asString ?: "left").uppercase())
                }.getOrDefault(TextAlignment.LEFT),
                lineSpacingMultiplier = o.get("line")?.asFloat ?: 1.15f,
                spaceBeforePt = o.get("before")?.asFloat ?: 0f,
                spaceAfterPt = o.get("after")?.asFloat ?: 6f,
                indentLeftPt = o.get("left")?.asFloat ?: 0f,
                indentRightPt = o.get("right")?.asFloat ?: 0f,
                indentFirstLinePt = o.get("first")?.asFloat ?: 0f,
                indentHangingPt = o.get("hanging")?.asFloat ?: 0f,
                keepWithNext = o.get("keepNext")?.asBoolean ?: false,
                pageBreakBefore = o.get("pageBreak")?.asBoolean ?: false,
                tabStops = o.get("tabs")?.let {
                    (ctx.deserialize(it, Array<TabStop>::class.java)
                        ?: arrayOf<TabStop>()).toList()
                } ?: emptyList()
            )
        }
    }
}

/**
 * PHASE 10.5 — Top-level helper: open a `.elysium.word` file (or a
 * blank document when the path doesn't exist) and save it back.
 *
 * The format lives at `core/word/WordDocument.kt`. We deliberately
 * keep these helpers thin so the model stays pure-Kotlin and JVM-
 * testable.
 */
object WordFile {
    private val magic = "ELYSIUM-WORD/1\n"

    fun read(text: String): WordDocument {
        val payload = if (text.startsWith(magic)) text.removePrefix(magic) else text
        return WordJson.fromJson(payload)
    }

    fun write(doc: WordDocument): String = magic + WordJson.toJson(doc)

    fun readFile(path: java.io.File): WordDocument = read(path.readText())
    fun writeFile(path: java.io.File, doc: WordDocument) {
        path.parentFile?.mkdirs()
        path.writeText(write(doc))
    }
}

/**
 * PHASE 10.5 — Plain-text round-trip: take plain text and return a
 * document where every line is a paragraph. Useful for "open as
 * plain text" or for feeding a `.txt` file into the editor without
 * losing structure.
 */
fun String.toWordDocument(title: String = "Imported text"): WordDocument {
    val blocks = lineSequence()
        .map { line ->
            WordParagraph(
                runs = listOf(WordRun(line.ifEmpty { "" })),
                format = ParagraphFormat.DEFAULT
            )
        }
        .toList()
        .ifEmpty { listOf(WordParagraph(runs = listOf(WordRun("")))) }
    return WordDocument(title = title, blocks = blocks)
}
