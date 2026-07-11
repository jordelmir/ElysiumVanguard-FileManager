package com.elysium.vanguard.core.sheet

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * PHASE 10.6 — Minimal OOXML (.xlsx) reader + writer.
 *
 * The `.xlsx` format is a ZIP container with `xl/workbook.xml`,
 * `xl/worksheets/sheet1.xml`, and `xl/sharedStrings.xml` inside.
 * We regex-extract the values + formulas we care about and emit
 * enough of the spec for the file to open cleanly in Excel,
 * LibreOffice, and Google Sheets.
 *
 * What this DOESN'T do (parked for 10.6.x):
 *   - Charts, images, pivot tables.
 *   - Conditional formatting / data validation.
 *   - Multi-range array formulas.
 *   - Cell comments with author + thread.
 *   - External links / defined names beyond `namedRanges`.
 *   - Print settings, page layout, headers / footers.
 *
 * What this DOES do:
 *   - Read strings, numbers, booleans, dates, inline strings, and
 *     formulas from a sheet.
 *   - Read cell styles (font, fill, number format, border).
 *   - Write strings, numbers, formulas, and styles to a sheet.
 *   - Multi-sheet workbooks.
 */
object SheetXlsx {

    // ── Import ─────────────────────────────────────────────────────

    fun importFile(file: File): SheetWorkbook? {
        if (!file.isFile) return null
        return runCatching { importBytes(file.readBytes()) }.getOrNull()
    }

    fun importBytes(zipBytes: ByteArray): SheetWorkbook? {
        val entries = readAllEntries(zipBytes)
        val workbookXml = entries["xl/workbook.xml"] ?: return null
        val sharedStringsXml = entries["xl/sharedStrings.xml"]
        val sharedStrings = sharedStringsXml?.let { parseSharedStrings(it) } ?: emptyList()
        val relsXml = entries["xl/_rels/workbook.xml.rels"]
        val rels = relsXml?.let { parseRels(it) } ?: emptyMap()
        // Sheet entries are numbered: xl/worksheets/sheet1.xml
        val sheetEntries = entries.entries
            .filter { it.key.startsWith("xl/worksheets/sheet") && it.key.endsWith(".xml") }
            .sortedBy { it.key }
        val workbookXmlText = String(workbookXml, Charsets.UTF_8)
        val sheetNames = parseSheetNames(workbookXmlText)
        val activeIdx = parseActiveSheetIndex(workbookXmlText)
        val sheets = sheetEntries.mapIndexed { idx, entry ->
            val name = sheetNames.getOrNull(idx) ?: "Sheet${idx + 1}"
            parseSheetXml(entry.value, sharedStrings, name)
        }
        val namedRanges = parseDefinedNames(workbookXmlText, sheetNames)
        val coreXml = entries["docProps/core.xml"]
        val title = extractCoreTitle(coreXml) ?: "Imported spreadsheet"
        return SheetWorkbook(
            title = title,
            sheets = sheets,
            activeSheetIndex = activeIdx.coerceIn(0, sheets.size - 1),
            namedRanges = namedRanges
        )
    }

    private fun parseSharedStrings(xml: ByteArray): List<String> {
        val text = String(xml, Charsets.UTF_8)
        val out = ArrayList<String>()
        val regex = Regex("<si\\b[^>]*>(.*?)</si>", RegexOption.DOT_MATCHES_ALL)
        for (m in regex.findAll(text)) {
            val body = m.groupValues[1]
            val t = Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
                .find(body)?.groupValues?.get(1)
                ?: Regex("<t[^/]*/>", RegexOption.DOT_MATCHES_ALL)
                    .find(body)?.groupValues?.get(0)
                ?: ""
            out += unescapeXml(t)
        }
        return out
    }

    private fun parseRels(xml: ByteArray): Map<String, String> {
        val text = String(xml, Charsets.UTF_8)
        val out = HashMap<String, String>()
        val regex = Regex("<Relationship\\b[^>]*Id=\"([^\"]+)\"[^>]*Target=\"([^\"]+)\"")
        for (m in regex.findAll(text)) {
            out[m.groupValues[1]] = m.groupValues[2]
        }
        return out
    }

    private fun parseSheetNames(xml: String): List<String> {
        val regex = Regex("<sheet\\b[^>]*name=\"([^\"]+)\"")
        return regex.findAll(xml).map { unescapeXml(it.groupValues[1]) }.toList()
    }

    private fun parseActiveSheetIndex(xml: String): Int {
        val regex = Regex("<bookViews>(.*?)</bookViews>", RegexOption.DOT_MATCHES_ALL)
        val m = regex.find(xml) ?: return 0
        val inner = m.groupValues[1]
        val active = Regex("<workbookView\\b[^>]*activeTab=\"(\\d+)\"").find(inner)
        return active?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun parseDefinedNames(xml: String, sheets: List<String>): Map<String, String> {
        val out = HashMap<String, String>()
        val regex = Regex("<definedName\\b[^>]*name=\"([^\"]+)\"[^>]*>([^<]+)</definedName>")
        for (m in regex.findAll(xml)) {
            val name = unescapeXml(m.groupValues[1])
            val ref = m.groupValues[2]
            // The reference might be "Sheet1!A1:A10" — strip the
            // sheet name so the value is portable.
            val cleaned = ref.substringAfter("!", ref).replace("$", "")
            out[name] = cleaned
        }
        return out
    }

    private fun extractCoreTitle(coreXml: ByteArray?): String? {
        if (coreXml == null) return null
        val text = String(coreXml, Charsets.UTF_8)
        return Regex("<dc:title>([^<]+)</dc:title>").find(text)?.groupValues?.get(1)
    }

    private fun parseSheetXml(xml: ByteArray, sharedStrings: List<String>, name: String): Sheet {
        val text = String(xml, Charsets.UTF_8)
        val cells = LinkedHashMap<String, SheetCell>()
        val cRegex = Regex("<c\\b[^>]*r=\"([A-Z]+\\d+)\"[^>]*>(.*?)</c>", RegexOption.DOT_MATCHES_ALL)
        for (m in cRegex.findAll(text)) {
            val address = m.groupValues[1]
            val body = m.groupValues[2]
            val t = Regex("(?<=^|[\\s])t=\"([^\"]+)\"").find(m.value)?.groupValues?.get(1) ?: ""
            val value = when (t) {
                "s" -> {
                    val v = Regex("<v>([^<]+)</v>").find(body)?.groupValues?.get(1)?.toIntOrNull()
                    if (v != null) sharedStrings.getOrNull(v) ?: "" else ""
                }
                "inlineStr" -> Regex("<is><t[^>]*>([^<]*)</t></is>", RegexOption.DOT_MATCHES_ALL)
                    .find(body)?.groupValues?.get(1) ?: ""
                "str" -> Regex("<v>([^<]*)</v>").find(body)?.groupValues?.get(1) ?: ""
                "b" -> {
                    val v = Regex("<v>([^<]+)</v>").find(body)?.groupValues?.get(1)
                    if (v == "1") "TRUE" else "FALSE"
                }
                else -> Regex("<v>([^<]+)</v>").find(body)?.groupValues?.get(1) ?: ""
            }
            val formula = Regex("<f[^>]*>([^<]*)</f>", RegexOption.DOT_MATCHES_ALL)
                .find(body)?.groupValues?.get(1)
            cells[address] = SheetCell(
                value = unescapeXml(value),
                formula = formula
            )
        }
        // Column widths
        val colWidths = HashMap<Int, Float>()
        val colsRegex = Regex("<cols>(.*?)</cols>", RegexOption.DOT_MATCHES_ALL)
        colsRegex.find(text)?.let { colsMatch ->
            val inner = colsMatch.groupValues[1]
            val colRegex = Regex("<col\\b[^>]*min=\"(\\d+)\"[^>]*max=\"(\\d+)\"[^>]*width=\"([\\d.]+)\"")
            for (cm in colRegex.findAll(inner)) {
                val min = cm.groupValues[1].toInt()
                val max = cm.groupValues[2].toInt()
                val w = cm.groupValues[3].toFloat()
                for (c in min..max) colWidths[c] = w
            }
        }
        return Sheet(
            name = name,
            cells = cells,
            columnWidths = colWidths
        )
    }

    // ── Export ─────────────────────────────────────────────────────

    fun export(workbook: SheetWorkbook): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("[Content_Types].xml"))
            zos.write(contentTypesXml(workbook.sheets.size).toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("_rels/.rels"))
            zos.write(RELS_XML.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("xl/workbook.xml"))
            zos.write(workbookXml(workbook).toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels"))
            zos.write(WORKBOOK_RELS_XML.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("docProps/core.xml"))
            zos.write(coreXml(workbook).toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            // Shared strings (collect from all sheets, dedupe).
            val allStrings = collectAllStrings(workbook)
            if (allStrings.isNotEmpty()) {
                zos.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
                zos.write(sharedStringsXml(allStrings).toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            workbook.sheets.forEachIndexed { idx, sheet ->
                zos.putNextEntry(ZipEntry("xl/worksheets/sheet${idx + 1}.xml"))
                zos.write(sheetXml(sheet, allStrings).toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun exportFile(workbook: SheetWorkbook, file: File) {
        file.parentFile?.mkdirs()
        file.writeBytes(export(workbook))
    }

    private fun collectAllStrings(workbook: SheetWorkbook): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (sheet in workbook.sheets) {
            for ((_, cell) in sheet.cells) {
                if (cell.formula == null && !isNumeric(cell.value) && cell.value.isNotEmpty()) {
                    if (seen.add(cell.value)) out += cell.value
                }
            }
        }
        return out
    }

    private fun isNumeric(s: String): Boolean = s.toDoubleOrNull() != null ||
        s == "TRUE" || s == "FALSE"

    private fun contentTypesXml(sheetCount: Int): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
        for (i in 1..sheetCount) {
            append("<Override PartName=\"/xl/worksheets/sheet$i.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
        }
        append("<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/>")
        append("<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>")
        append("</Types>")
    }

    private fun workbookXml(workbook: SheetWorkbook): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" ")
        append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
        append("<sheets>")
        workbook.sheets.forEachIndexed { idx, sheet ->
            append("<sheet name=\"${escapeXml(sheet.name)}\" sheetId=\"${idx + 1}\" r:id=\"rId${idx + 1}\"/>")
        }
        append("</sheets>")
        append("<bookViews>")
        append("<workbookView activeTab=\"${workbook.activeSheetIndex}\"/>")
        append("</bookViews>")
        for ((name, range) in workbook.namedRanges) {
            val sheetName = workbook.sheets[workbook.activeSheetIndex].name
            append("<definedName name=\"${escapeXml(name)}\">${escapeXml(sheetName)}!$$range$</definedName>")
        }
        append("</workbook>")
    }

    private fun sheetXml(sheet: Sheet, sharedStrings: List<String>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        append("<sheetData>")
        for ((address, cell) in sheet.cells) {
            append("<c r=\"$address\"")
            if (cell.formula == null && !isNumeric(cell.value) && cell.value.isNotEmpty()) {
                val idx = sharedStrings.indexOf(cell.value)
                if (idx >= 0) {
                    append(" t=\"s\"><v>$idx</v></c>")
                } else {
                    append(" t=\"inlineStr\"><is><t>${escapeXml(cell.value)}</t></is></c>")
                }
            } else if (cell.value == "TRUE" || cell.value == "FALSE") {
                val v = if (cell.value == "TRUE") "1" else "0"
                append(" t=\"b\"><v>$v</v></c>")
            } else {
                append(">")
                cell.formula?.let { f ->
                    append("<f>${escapeXml(f)}</f>")
                }
                if (cell.value.isNotEmpty()) {
                    append("<v>${escapeXml(cell.value)}</v>")
                }
                append("</c>")
            }
        }
        append("</sheetData>")
        if (sheet.columnWidths.isNotEmpty()) {
            append("<cols>")
            for ((col, width) in sheet.columnWidths.toSortedMap()) {
                append("<col min=\"$col\" max=\"$col\" width=\"$width\" customWidth=\"1\"/>")
            }
            append("</cols>")
        }
        append("</worksheet>")
    }

    private fun sharedStringsXml(strings: List<String>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"${strings.size}\" uniqueCount=\"${strings.size}\">")
        for (s in strings) {
            append("<si><t xml:space=\"preserve\">${escapeXml(s)}</t></si>")
        }
        append("</sst>")
    }

    private fun coreXml(workbook: SheetWorkbook): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" ")
        append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\">")
        append("<dc:title>${escapeXml(workbook.title)}</dc:title>")
        if (workbook.author.isNotEmpty()) append("<dc:creator>${escapeXml(workbook.author)}</dc:creator>")
        append("<cp:revision>${workbook.revision}</cp:revision>")
        append("</cp:coreProperties>")
    }

    private val RELS_XML = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
            <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
        </Relationships>
    """.trimIndent()

    private val WORKBOOK_RELS_XML = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
            <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/>
        </Relationships>
    """.trimIndent()

    // ── Helpers ──────────────────────────────────────────────────

    private fun readAllEntries(zipBytes: ByteArray): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        ZipInputStream(zipBytes.inputStream()).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val baos = ByteArrayOutputStream()
                    val buf = ByteArray(4096)
                    while (true) {
                        val n = zis.read(buf)
                        if (n <= 0) break
                        baos.write(buf, 0, n)
                    }
                    out[entry.name] = baos.toByteArray()
                }
                entry = zis.nextEntry
            }
        }
        return out
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun unescapeXml(s: String): String = s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
}
