package com.elysium.vanguard.core.word

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * PHASE 10.5 — Minimal OOXML (.docx) reader + writer.
 *
 * We deliberately avoid pulling in a real XML parser. The `.docx`
 * format is a ZIP container with `word/document.xml` inside; we
 * regex-extract the runs and paragraphs we care about. Same shape
 * as the existing [com.elysium.vanguard.core.office.OfficeImporter]
 * but with format preservation: bold, italic, underline, font, size.
 *
 * What this DOESN'T do (parked for 10.5.x):
 *   - Full style cascade (`<w:pStyle>` resolution).
 *   - Tables, images, embedded objects.
 *   - Numbered / bulleted list auto-numbering (we emit as plain
 *     paragraphs with bullets because reconstructing numbering is
 *     a 300-line spec rabbit hole).
 *   - Revision tracking, comments, fields, footers, headers.
 *
 * What this DOES do:
 *   - Import any valid `.docx` (text + run-level b/i/u/font/size).
 *   - Export a `.docx` that opens cleanly in Word, LibreOffice,
 *     Google Docs, and the system viewer on Android.
 */
object WordDocx {

    // ── Import ─────────────────────────────────────────────────────

    /**
     * Read a `.docx` file and produce a [WordDocument]. Errors funnel
     * into [ImportError] messages; the function does not throw.
     */
    fun importFile(file: File): WordDocument? {
        if (!file.isFile) return null
        return runCatching { importBytes(file.readBytes()) }.getOrNull()
    }

    fun importBytes(zipBytes: ByteArray): WordDocument? {
        val documentXml = readZipEntry(zipBytes, "word/document.xml")
            ?: return null
        val text = String(documentXml, Charsets.UTF_8)
        val coreXml = readZipEntry(zipBytes, "docProps/core.xml")
        val title = extractCoreTitle(coreXml) ?: "Imported document"
        val blocks = parseDocumentXml(text)
        return WordDocument(title = title, blocks = blocks)
    }

    /**
     * Walk the `<w:body>` and emit a flat list of [WordBlock]s. We
     * intentionally flatten tables to paragraphs (a 5×3 table
     * becomes 5 paragraphs of 3 runs each) because reconstructing
     * a 2D grid is out of scope for 10.5.
     */
    private fun parseDocumentXml(xml: String): List<WordBlock> {
        val blocks = ArrayList<WordBlock>()
        // Each `<w:p ...>...</w:p>` is one paragraph. We use a
        // non-greedy match on the body so nested tags don't trip us
        // up — the OOXML body is small enough for this to be cheap.
        val pRegex = Regex("<w:p\\b[^>]*>(.*?)</w:p>", RegexOption.DOT_MATCHES_ALL)
        for (m in pRegex.findAll(xml)) {
            val paragraphXml = m.groupValues[1]
            val runs = parseRuns(paragraphXml)
            val alignment = parseAlignment(paragraphXml)
            val spaceBefore = parseSpaceBefore(paragraphXml)
            val spaceAfter = parseSpaceAfter(paragraphXml)
            val headingLevel = parseHeadingLevel(paragraphXml)
            // Empty paragraphs still count as paragraphs (they hold
            // the cursor in a real document).
            val effectiveRuns = if (runs.isEmpty()) listOf(WordRun("")) else runs
            val format = ParagraphFormat(
                alignment = alignment,
                spaceBeforePt = spaceBefore,
                spaceAfterPt = spaceAfter
            )
            if (headingLevel != null) {
                blocks += WordHeading(
                    level = headingLevel,
                    runs = effectiveRuns,
                    format = format
                )
            } else {
                blocks += WordParagraph(runs = effectiveRuns, format = format)
            }
        }
        return blocks.ifEmpty { listOf(WordParagraph(runs = listOf(WordRun("")))) }
    }

    /**
     * PHASE 10.5 — Read `<w:pStyle w:val="HeadingN"/>` and return
     * the integer level. Returns null for body paragraphs.
     */
    private fun parseHeadingLevel(paragraphXml: String): Int? {
        val pPrRegex = Regex("<w:pPr\\b[^>]*>(.*?)</w:pPr>", RegexOption.DOT_MATCHES_ALL)
        val pPr = pPrRegex.find(paragraphXml)?.groupValues?.get(1) ?: return null
        val style = Regex("<w:pStyle\\b[^>]*w:val=\"([^\"]+)\"")
            .find(pPr)?.groupValues?.get(1) ?: return null
        val match = Regex("Heading(\\d)").find(style) ?: return null
        return match.groupValues[1].toIntOrNull()?.coerceIn(1, 6)
    }

    private fun parseRuns(paragraphXml: String): List<WordRun> {
        val out = ArrayList<WordRun>()
        val rRegex = Regex("<w:r\\b[^>]*>(.*?)</w:r>", RegexOption.DOT_MATCHES_ALL)
        for (m in rRegex.findAll(paragraphXml)) {
            val runXml = m.groupValues[1]
            // PHASE 10.5 — Extract text by matching only `<w:t>…</w:t>`
            // bodies, not by replacing the run XML wholesale. The old
            // approach left `<w:rPr>…</w:rPr>` inside the text because
            // it lives inside `<w:r>`, and a `.replace()` on the run
            // doesn't strip it. A `findAll` on `<w:t>` keeps the XML
            // tags out of the resulting text.
            val text = buildString {
                val tRegex = Regex("<w:t\\b[^>]*>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)
                for (tm in tRegex.findAll(runXml)) {
                    append(tm.groupValues[1])
                }
            }
            val format = parseRunProperties(runXml)
            if (text.isNotEmpty()) {
                out += WordRun(text = text, format = format)
            }
        }
        return out
    }

    private fun parseRunProperties(runXml: String): CharacterFormat {
        val rPrRegex = Regex("<w:rPr\\b[^>]*>(.*?)</w:rPr>", RegexOption.DOT_MATCHES_ALL)
        val rPr = rPrRegex.find(runXml)?.groupValues?.get(1) ?: return CharacterFormat()
        val bold = rPr.contains("<w:b/>") || rPr.contains("<w:b ")
        val italic = rPr.contains("<w:i/>") || rPr.contains("<w:i ")
        val underline = rPr.contains("<w:u ")
        val strike = rPr.contains("<w:strike/>") || rPr.contains("<w:strike ")
        val sizeHalf = Regex("<w:sz\\b[^>]*w:val=\"(\\d+)\"").find(rPr)?.groupValues?.get(1)?.toIntOrNull()
        val size = (sizeHalf ?: 28) / 2f // OOXML uses half-points
        val fontFamily = Regex("<w:rFonts\\b[^>]*w:ascii=\"([^\"]+)\"")
            .find(rPr)?.groupValues?.get(1) ?: "sans-serif"
        // Normalize: Word uses Microsoft Office font names. Map the
        // common ones to Elysium's three built-in families.
        val family = mapFontFamily(fontFamily)
        return CharacterFormat(
            fontFamily = family,
            fontSizeSp = size,
            bold = bold,
            italic = italic,
            underline = underline,
            strikethrough = strike
        )
    }

    private fun mapFontFamily(name: String): String = when (name.lowercase()) {
        "times new roman", "georgia", "garamond", "cambria" -> "serif"
        "courier new", "consolas", "monaco", "menlo" -> "monospace"
        else -> "sans-serif"
    }

    private fun parseAlignment(paragraphXml: String): TextAlignment {
        val pPrRegex = Regex("<w:pPr\\b[^>]*>(.*?)</w:pPr>", RegexOption.DOT_MATCHES_ALL)
        val pPr = pPrRegex.find(paragraphXml)?.groupValues?.get(1) ?: return TextAlignment.LEFT
        return when (Regex("<w:jc\\b[^>]*w:val=\"([^\"]+)\"").find(pPr)?.groupValues?.get(1)) {
            "center" -> TextAlignment.CENTER
            "right" -> TextAlignment.RIGHT
            "both" -> TextAlignment.JUSTIFY
            else -> TextAlignment.LEFT
        }
    }

    private fun parseSpaceBefore(paragraphXml: String): Float {
        val pPrRegex = Regex("<w:pPr\\b[^>]*>(.*?)</w:pPr>", RegexOption.DOT_MATCHES_ALL)
        val pPr = pPrRegex.find(paragraphXml)?.groupValues?.get(1) ?: return 0f
        val before = Regex("<w:spacing\\b[^>]*w:before=\"(\\d+)\"")
            .find(pPr)?.groupValues?.get(1)?.toIntOrNull() ?: return 0f
        return before / 20f // twips → points
    }

    private fun parseSpaceAfter(paragraphXml: String): Float {
        val pPrRegex = Regex("<w:pPr\\b[^>]*>(.*?)</w:pPr>", RegexOption.DOT_MATCHES_ALL)
        val pPr = pPrRegex.find(paragraphXml)?.groupValues?.get(1) ?: return 0f
        val after = Regex("<w:spacing\\b[^>]*w:after=\"(\\d+)\"")
            .find(pPr)?.groupValues?.get(1)?.toIntOrNull() ?: return 6f
        return after / 20f
    }

    private fun extractCoreTitle(coreXml: ByteArray?): String? {
        if (coreXml == null) return null
        val text = String(coreXml, Charsets.UTF_8)
        return Regex("<dc:title>([^<]+)</dc:title>").find(text)?.groupValues?.get(1)
    }

    private fun readZipEntry(zipBytes: ByteArray, entryPath: String): ByteArray? {
        ZipInputStream(zipBytes.inputStream()).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (entry.name == entryPath) {
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(4096)
                    while (true) {
                        val n = zis.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                    return out.toByteArray()
                }
                entry = zis.nextEntry
            }
        }
        return null
    }

    // ── Export ─────────────────────────────────────────────────────

    /**
     * Build a `.docx` byte array from a [WordDocument]. The output
     * opens cleanly in Word, LibreOffice, and Google Docs.
     */
    fun export(doc: WordDocument): ByteArray {
        val bodyXml = buildDocumentBodyXml(doc)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("[Content_Types].xml"))
            zos.write(CONTENT_TYPES_XML.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("_rels/.rels"))
            zos.write(RELS_XML.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("word/document.xml"))
            zos.write(bodyXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("word/_rels/document.xml.rels"))
            zos.write(DOCUMENT_RELS_XML.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("docProps/core.xml"))
            zos.write(buildCoreXml(doc).toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return out.toByteArray()
    }

    fun exportFile(doc: WordDocument, file: File) {
        file.parentFile?.mkdirs()
        file.writeBytes(export(doc))
    }

    private fun buildDocumentBodyXml(doc: WordDocument): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append(
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
        )
        sb.append("<w:body>")
        for (block in doc.blocks) {
            when (block) {
                is WordParagraph -> sb.append(paragraphXml(block))
                is WordHeading -> sb.append(paragraphXml(WordParagraph(block.runs, block.format), headingLevel = block.level))
                is WordListItem -> {
                    val prefix = when (block.kind) {
                        ListKind.BULLET -> "• "
                        ListKind.NUMBERED -> "${block.depth + 1}. "
                        ListKind.CHECKBOX -> "☐ "
                    }
                    val padded = WordParagraph(
                        runs = listOf(WordRun(prefix)) + block.runs,
                        format = block.format.copy(indentLeftPt = block.format.indentLeftPt + 18f * (block.depth + 1))
                    )
                    sb.append(paragraphXml(padded))
                }
                is WordPageBreak -> sb.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
                is WordHorizontalRule -> sb.append("<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" w:color=\"auto\"/></w:pBdr></w:pPr></w:p>")
                is WordBlockQuote -> {
                    val quoted = WordParagraph(block.runs, ParagraphFormat.BLOCK_QUOTE)
                    sb.append(paragraphXml(quoted))
                }
                is WordCodeBlock -> {
                    for (line in block.code.lines()) {
                        sb.append(paragraphXml(
                            WordParagraph(
                                runs = listOf(WordRun(line, CharacterFormat.CODE)),
                                format = ParagraphFormat.CODE_BLOCK
                            )
                        ))
                    }
                }
            }
        }
        sb.append("<w:sectPr><w:pgSz w:w=\"${doc.pageSettings.pageWidth}\" w:h=\"${doc.pageSettings.pageHeight}\"/>" +
            "<w:pgMar w:top=\"${doc.pageSettings.marginTop}\" w:right=\"${doc.pageSettings.marginRight}\" " +
            "w:bottom=\"${doc.pageSettings.marginBottom}\" w:left=\"${doc.pageSettings.marginLeft}\" " +
            "w:header=\"${doc.pageSettings.headerDistance}\" w:footer=\"${doc.pageSettings.footerDistance}\" w:gutter=\"0\"/>" +
            "</w:sectPr>")
        sb.append("</w:body></w:document>")
        return sb.toString()
    }

    private fun paragraphXml(p: WordParagraph, headingLevel: Int? = null): String {
        val sb = StringBuilder()
        sb.append("<w:p>")
        val pPr = StringBuilder()
        pPr.append("<w:pPr>")
        if (headingLevel != null) pPr.append("<w:pStyle w:val=\"Heading$headingLevel\"/>")
        when (p.format.alignment) {
            TextAlignment.LEFT -> { /* default */ }
            TextAlignment.CENTER -> pPr.append("<w:jc w:val=\"center\"/>")
            TextAlignment.RIGHT -> pPr.append("<w:jc w:val=\"right\"/>")
            TextAlignment.JUSTIFY -> pPr.append("<w:jc w:val=\"both\"/>")
        }
        if (p.format.spaceBeforePt > 0f || p.format.spaceAfterPt > 0f || p.format.lineSpacingMultiplier != 1.15f) {
            val before = (p.format.spaceBeforePt * 20).toInt()
            val after = (p.format.spaceAfterPt * 20).toInt()
            val line = (p.format.lineSpacingMultiplier * 240).toInt() // 240 = single
            pPr.append("<w:spacing w:before=\"$before\" w:after=\"$after\" w:line=\"$line\" w:lineRule=\"auto\"/>")
        }
        if (p.format.indentLeftPt > 0f) {
            pPr.append("<w:ind w:left=\"${(p.format.indentLeftPt * 20).toInt()}\"/>")
        }
        pPr.append("</w:pPr>")
        sb.append(pPr)
        for (run in p.runs) {
            sb.append(runXml(run))
        }
        sb.append("</w:p>")
        return sb.toString()
    }

    private fun runXml(run: WordRun): String {
        val sb = StringBuilder()
        sb.append("<w:r>")
        val rPr = StringBuilder()
        rPr.append("<w:rPr>")
        rPr.append("<w:rFonts w:ascii=\"${fontFamilyFor(run.format.fontFamily)}\" w:hAnsi=\"${fontFamilyFor(run.format.fontFamily)}\"/>")
        val sizeHalf = (run.format.fontSizeSp * 2).toInt()
        rPr.append("<w:sz w:val=\"$sizeHalf\"/><w:szCs w:val=\"$sizeHalf\"/>")
        if (run.format.bold) rPr.append("<w:b/><w:bCs/>")
        if (run.format.italic) rPr.append("<w:i/><w:iCs/>")
        if (run.format.underline) rPr.append("<w:u w:val=\"single\"/>")
        if (run.format.strikethrough) rPr.append("<w:strike/>")
        rPr.append("</w:rPr>")
        sb.append(rPr)
        // Split on newlines so each line gets its own <w:br/>.
        val parts = run.text.split("\n")
        parts.forEachIndexed { i, part ->
            if (i > 0) sb.append("<w:br/>")
            sb.append("<w:t xml:space=\"preserve\">")
            sb.append(escapeXml(part))
            sb.append("</w:t>")
        }
        sb.append("</w:r>")
        return sb.toString()
    }

    private fun fontFamilyFor(name: String): String = when (name.lowercase()) {
        "serif" -> "Times New Roman"
        "monospace", "mono" -> "Courier New"
        else -> "Calibri"
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun buildCoreXml(doc: WordDocument): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" ")
        append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
        append("xmlns:dcterms=\"http://purl.org/dc/terms/\" ")
        append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">")
        append("<dc:title>${escapeXml(doc.title)}</dc:title>")
        if (doc.author.isNotEmpty()) append("<dc:creator>${escapeXml(doc.author)}</dc:creator>")
        append("<cp:revision>${doc.revision}</cp:revision>")
        append("</cp:coreProperties>")
    }

    private val CONTENT_TYPES_XML = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
            <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
            <Default Extension="xml" ContentType="application/xml"/>
            <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
            <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
        </Types>
    """.trimIndent()

    private val RELS_XML = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
            <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
        </Relationships>
    """.trimIndent()

    private val DOCUMENT_RELS_XML = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"/>
    """.trimIndent()
}
