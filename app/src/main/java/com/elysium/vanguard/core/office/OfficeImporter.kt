package com.elysium.vanguard.core.office

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * PHASE 9.16 — Importer for non-Elysium Office documents.
 *
 * Supports:
 *   - `.docx` (OOXML word processing): reads `word/document.xml`,
 *     extracts `<w:t>` text runs joined with spaces; `<w:p>`
 *     paragraph elements become `\n` separators.
 *   - `.odt` (OpenDocument text): reads `content.xml`, strips XML
 *     tags; `<text:p>` elements become `\n` separators.
 *   - `.odp` (OpenDocument presentation): reads `content.xml`,
 *     groups text by `<draw:page>` slide; each slide's text becomes
 *     one Elysium Slide.
 *
 * We deliberately do not add an XML parser dependency. Instead we
 * rely on the on-disk format being well-formed enough to extract
 * text from a small set of known tags via [Regex]. This is enough
 * for the "bring my real `.docx` into Elysium Word" use case
 * without pulling a 200 KB XML library.
 *
 * Phase 9.16 — first build; intentionally minimal.
 */
object OfficeImporter {

    /**
     * Outcome of an [importToElysium] call. We never throw — every
     * error path is funneled into [Result.Failure] so callers can
     * surface a clean message without try/catch noise.
     */
    sealed interface Result {
        data class Success(val document: ElysiumDocument) : Result
        data class Failure(val reason: String) : Result
    }

    /**
     * Read [file] and return its best-effort [ElysiumDocument]
     * representation. Dispatches on the file extension:
     *   - `.docx` → [ElysiumDocument.Kind.WORD]
     *   - `.odt`  → [ElysiumDocument.Kind.WORD]
     *   - `.odp`  → [ElysiumDocument.Kind.DECK]
     *
     * Anything else is a [Result.Failure]. The caller decides
     * whether to surface the error or try a different importer.
     */
    fun importToElysium(file: File): Result {
        if (!file.isFile) return Result.Failure("File not found: ${file.path}")
        val bytes = runCatching { file.readBytes() }
            .getOrElse { return Result.Failure("Cannot read file: ${it.message}") }
        val name = file.name.lowercase()
        return when {
            name.endsWith(".docx") -> importDocx(bytes)
            name.endsWith(".odt") -> importOdt(bytes)
            name.endsWith(".odp") -> importOdp(bytes)
            else -> Result.Failure("Unsupported extension: ${file.name}")
        }
    }

    /**
     * Same as [importToElysium] but takes a byte array. Useful for
     * tests where we construct a minimal ZIP+XML in memory.
     */
    fun importBytesToElysium(name: String, bytes: ByteArray): Result {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".docx") -> importDocx(bytes)
            lower.endsWith(".odt") -> importOdt(bytes)
            lower.endsWith(".odp") -> importOdp(bytes)
            else -> Result.Failure("Unsupported extension: $name")
        }
    }

    // -------- OOXML (.docx) --------

    private fun importDocx(zipBytes: ByteArray): Result {
        val documentXml = readZipEntry(zipBytes, "word/document.xml")
            ?: return Result.Failure("docx missing word/document.xml")
        val text = String(documentXml, Charsets.UTF_8)

        // Paragraphs first: split on </w:p> and <w:p/> boundaries,
        // then within each paragraph extract <w:t>...</w:t> text
        // runs joined by spaces. Each surviving paragraph is
        // placed on its own line so headings, body, and list
        // items stay distinguishable.
        val paragraphs = splitParagraphsDocx(text)
        if (paragraphs.isEmpty()) return Result.Failure("docx has no paragraphs")
        val body = paragraphs.joinToString(separator = "\n") { paragraph ->
            extractRuns(paragraph).joinToString(separator = " ")
        }
        return Result.Success(
            ElysiumDocument(
                kind = ElysiumDocument.Kind.WORD,
                style = ElysiumDocument.StyleHints(),
                body = body.toByteArray(Charsets.UTF_8)
            )
        )
    }

    /**
     * Top-level paragraph boundary detection. We treat `<w:p>` /
     * `<w:p ...>` / `</w:p>` / `<w:p/>` as paragraph fences and
     * cut the document into paragraph-sized chunks.
     */
    private fun splitParagraphsDocx(text: String): List<String> {
        val result = ArrayList<String>()
        var depth = 0
        var currentStart = -1
        var i = 0
        while (i < text.length) {
            if (text.regionMatches(i, "<w:p>", 0, 5) ||
                (text.length > i + 5 && text.regionMatches(i, "<w:p ", 0, 5) && text[i + 5] != '/')
            ) {
                if (depth == 0) currentStart = i
                val endOfOpen = text.indexOf('>', i)
                if (endOfOpen < 0) return result
                i = endOfOpen + 1
                depth++
            } else if (text.regionMatches(i, "</w:p>", 0, 6)) {
                if (depth > 0) depth--
                if (depth == 0 && currentStart >= 0) {
                    result += text.substring(currentStart, i + 6)
                    currentStart = -1
                }
                i += 6
            } else if (text.regionMatches(i, "<w:p/>", 0, 6) ||
                text.regionMatches(i, "<w:p />", 0, 7)
            ) {
                // `<w:p/>` is 6 chars; `<w:p />` is 7. Track
                // length precisely so a length mismatch doesn't
                // silently swallow the self-closing form.
                val len = if (text.regionMatches(i, "<w:p/>", 0, 6)) 6 else 7
                if (depth == 0) result += text.substring(i, i + len)
                i += len
            } else {
                i++
            }
        }
        return result
    }

    /**
     * Inside a `<w:p>...</w:p>` chunk, pull every `<w:t>...</w:t>`
     * text run.
     */
    private fun extractRuns(paragraphXml: String): List<String> {
        val runs = ArrayList<String>()
        val tag = Regex("<w:t(?:\\s[^>]*)?>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)
        for (m in tag.findAll(paragraphXml)) {
            val raw = m.groupValues[1]
            // Common XML entities we expect in `.docx` text.
            runs += decodeXmlEntities(raw)
        }
        return runs
    }

    // -------- ODF text (.odt) --------

    private fun importOdt(zipBytes: ByteArray): Result {
        val contentXml = readZipEntry(zipBytes, "content.xml")
            ?: return Result.Failure("odt missing content.xml")
        val text = String(contentXml, Charsets.UTF_8)
        val paragraphs = splitParagraphsOdf(text)
        if (paragraphs.isEmpty()) return Result.Failure("odt has no paragraphs")
        val body = paragraphs.joinToString(separator = "\n") { p ->
            stripTags(p).trim()
        }
        return Result.Success(
            ElysiumDocument(
                kind = ElysiumDocument.Kind.WORD,
                style = ElysiumDocument.StyleHints(),
                body = body.toByteArray(Charsets.UTF_8)
            )
        )
    }

    private fun splitParagraphsOdf(text: String): List<String> {
        val result = ArrayList<String>()
        var depth = 0
        var currentStart = -1
        var i = 0
        // Length bookkeeping: `<text:p>` = 8, `</text:p>` = 9,
        // `<text:p/>` = 9, `<text:p ` (with space) = 8.
        val openLen = 8
        val closeLen = 9
        val selfCloseLen = 9
        while (i < text.length) {
            if (text.regionMatches(i, "<text:p>", 0, openLen) ||
                (text.length > i + openLen && text.regionMatches(i, "<text:p ", 0, openLen) && text[i + openLen] != '/')
            ) {
                if (depth == 0) currentStart = i
                val endOfOpen = text.indexOf('>', i)
                if (endOfOpen < 0) return result
                i = endOfOpen + 1
                depth++
            } else if (text.regionMatches(i, "</text:p>", 0, closeLen)) {
                if (depth > 0) depth--
                if (depth == 0 && currentStart >= 0) {
                    result += text.substring(currentStart, i + closeLen)
                    currentStart = -1
                }
                i += closeLen
            } else if (text.regionMatches(i, "<text:p/>", 0, selfCloseLen)) {
                if (depth == 0) result += text.substring(i, i + selfCloseLen)
                i += selfCloseLen
            } else {
                i++
            }
        }
        return result
    }

    // -------- ODF presentation (.odp) --------

    private fun importOdp(zipBytes: ByteArray): Result {
        val contentXml = readZipEntry(zipBytes, "content.xml")
            ?: return Result.Failure("odp missing content.xml")
        val text = String(contentXml, Charsets.UTF_8)

        // Slides are <draw:page>...</draw:page> chunks. Within each
        // slide, paragraphs are <text:p>...</text:p> chunks whose
        // visible text is everything between the tags after we
        // strip any nested XML.
        val slides = ArrayList<Slide>()
        val pageOpen = "<draw:page".toRegex(RegexOption.DOT_MATCHES_ALL)
        for (pageMatch in pageOpen.findAll(text)) {
            val startOpen = pageMatch.range.first
            val endOfOpen = text.indexOf('>', startOpen)
            if (endOfOpen < 0) continue
            val close = text.indexOf("</draw:page>", endOfOpen)
            if (close < 0) {
                // No explicit close — read to end of document.
                slides += slideFromChunk(text.substring(endOfOpen + 1))
            } else {
                slides += slideFromChunk(text.substring(endOfOpen + 1, close))
            }
        }
        if (slides.isEmpty()) {
            // No <draw:page> elements at all — fall back to treating
            // the document as a single-slide deck so the user sees
            // the imported text.
            val allParagraphs = splitParagraphsOdf(text)
            val first = allParagraphs.firstOrNull()?.let { stripTags(it).trim() }
                ?: "Imported Deck"
            slides += Slide(title = first, body = "")
        }
        val deck = ElysiumDeck(
            title = slides.first().title,
            slides = slides,
            style = ElysiumDocument.StyleHints()
        )
        return Result.Success(
            ElysiumDocument(
                kind = ElysiumDocument.Kind.DECK,
                style = ElysiumDocument.StyleHints(),
                body = deck.toJson()
            )
        )
    }

    /**
     * Build a [Slide] from the body of a `<draw:page>` chunk: the
     * slide's title is the first paragraph (stripped); its body
     * is the remaining paragraphs joined with `\n`.
     */
    private fun slideFromChunk(chunk: String): Slide {
        val stripped = splitParagraphsOdf(chunk)
            .map { stripTags(it).trim() }
            .filter { it.isNotEmpty() }
        val title = stripped.firstOrNull() ?: "Slide"
        val body = stripped.drop(1).joinToString(separator = "\n")
        return Slide(title = title, body = body)
    }

    // -------- helpers --------

    /**
     * Pull a single entry's bytes out of a ZIP container. Returns
     * `null` if [entryName] is not present.
     */
    private fun readZipEntry(zipBytes: ByteArray, entryName: String): ByteArray? {
        ZipInputStream(zipBytes.inputStream()).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (entry.name == entryName) {
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(4096)
                    var n = zis.read(buf)
                    while (n > 0) {
                        out.write(buf, 0, n)
                        n = zis.read(buf)
                    }
                    return out.toByteArray()
                }
                entry = zis.nextEntry
            }
        }
        return null
    }

    /**
     * Strip all XML tags from [xml], leaving only the visible
     * text. Entities (`&amp;`, `&lt;`, …) are decoded.
     */
    private fun stripTags(xml: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < xml.length) {
            val c = xml[i]
            if (c == '<') {
                val close = xml.indexOf('>', i)
                if (close < 0) return decodeXmlEntities(sb.toString())
                i = close + 1
            } else {
                sb.append(c)
                i++
            }
        }
        return decodeXmlEntities(sb.toString())
    }

    /**
     * Decode the XML entities we expect to encounter in Office
     * documents (`&amp;`, `&lt;`, `&gt;`, `&quot;`, `&apos;`, and
     * numeric references like `&#10;`).
     */
    private fun decodeXmlEntities(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '&') {
                val close = text.indexOf(';', i)
                if (close in (i + 1)..(i + 8)) {
                    val entity = text.substring(i + 1, close)
                    val decoded: String? = when (entity) {
                        "amp" -> "&"
                        "lt" -> "<"
                        "gt" -> ">"
                        "quot" -> "\""
                        "apos" -> "'"
                        else -> {
                            if (entity.startsWith("#")) {
                                val inner = entity.substring(1)
                                val codePoint = if (inner.startsWith("x") || inner.startsWith("X")) {
                                    inner.substring(1).toIntOrNull(16)
                                } else {
                                    inner.toIntOrNull()
                                }
                                codePoint?.toChar()?.toString()
                            } else null
                        }
                    }
                    if (decoded != null) {
                        sb.append(decoded)
                        i = close + 1
                        continue
                    }
                }
                sb.append(c)
                i++
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
