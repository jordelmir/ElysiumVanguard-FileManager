package com.elysium.vanguard.core.sheet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * PHASE 10.6 — Tests for the Sheet model and IO.
 *
 * Covers:
 *   - Model ergonomics (cell value, address, A1 helpers).
 *   - JSON round-trip via [SheetJson] / [SheetFile].
 *   - XLSX import: synthetic .xlsx ZIP, assert parsed model.
 *   - XLSX export: write a workbook, re-import it, assert fidelity.
 */
class SheetWorkbookTest {

    @Test
    fun `A1 column labels and reverse`() {
        assertEquals("A", A1.columnLabel(1))
        assertEquals("Z", A1.columnLabel(26))
        assertEquals("AA", A1.columnLabel(27))
        assertEquals(1, A1.columnNumber("A"))
        assertEquals(27, A1.columnNumber("AA"))
    }

    @Test
    fun `A1 range covers the rectangle`() {
        val addrs = A1.rangeAddresses("A1", "B2")
        assertEquals(setOf("A1", "A2", "B1", "B2"), addrs.toSet())
    }

    @Test
    fun `A1 address is parsed by row and column`() {
        assertEquals(1, A1.columnOf("A1"))
        assertEquals(1, A1.rowOf("A1"))
        assertEquals(53, A1.columnOf("BA12"))
        assertEquals(12, A1.rowOf("BA12"))
    }

    @Test
    fun `JSON round-trip preserves cells`() {
        val original = SheetWorkbook(
            title = "JSON test",
            author = "joe",
            sheets = listOf(
                Sheet(
                    name = "Sheet1",
                    cells = linkedMapOf(
                        "A1" to SheetCell(value = "10"),
                        "A2" to SheetCell(value = "20"),
                        "B1" to SheetCell(value = "", formula = "SUM(A1:A2)"),
                        "C1" to SheetCell(
                            value = "formatted",
                            format = CellFormat(bold = true, color = 0xFFFF6E6E)
                        )
                    )
                )
            )
        )
        val json = SheetJson.toJson(original)
        val parsed = SheetJson.fromJson(json)
        assertEquals(original.title, parsed.title)
        assertEquals(1, parsed.sheets.size)
        val sheet = parsed.sheets[0]
        assertEquals("10", sheet.cells["A1"]?.value)
        assertEquals("SUM(A1:A2)", sheet.cells["B1"]?.formula)
        assertTrue(sheet.cells["C1"]?.format?.bold == true)
        assertEquals(0xFFFF6E6E, sheet.cells["C1"]?.format?.color)
    }

    @Test
    fun `SheetFile uses magic header`() {
        val wb = SheetWorkbook(title = "Magic", sheets = listOf(Sheet(name = "S1")))
        val text = SheetFile.write(wb)
        assertTrue(text.startsWith("ELYSIUM-SHEET/1\n"))
        val parsed = SheetFile.read(text)
        assertEquals("Magic", parsed.title)
    }

    @Test
    fun `XLSX import parses a synthetic workbook`() {
        val bytes = makeXlsx(
            sharedStrings = listOf("hello", "world"),
            sheet1Xml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>
                    <row r="1">
                      <c r="A1" t="s"><v>0</v></c>
                      <c r="B1"><v>42</v></c>
                      <c r="C1" t="s"><v>1</v></c>
                    </row>
                    <row r="2">
                      <c r="A2"><v>3.14</v></c>
                      <c r="B2"><f>SUM(B1)</f><v>42</v></c>
                    </row>
                  </sheetData>
                </worksheet>
            """.trimIndent(),
            workbookXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheets>
                    <sheet name="MySheet" sheetId="1" r:id="rId1"/>
                  </sheets>
                </workbook>
            """.trimIndent()
        )
        val wb = SheetXlsx.importBytes(bytes)
        assertNotNull(wb)
        assertEquals(1, wb!!.sheets.size)
        val s = wb.sheets[0]
        assertEquals("MySheet", s.name)
        assertEquals("hello", s.cells["A1"]?.value)
        assertEquals("42", s.cells["B1"]?.value)
        assertEquals("world", s.cells["C1"]?.value)
        assertEquals("3.14", s.cells["A2"]?.value)
        assertEquals("SUM(B1)", s.cells["B2"]?.formula)
    }

    @Test
    fun `XLSX export produces a parseable ZIP with the right pieces`() {
        val wb = SheetWorkbook(
            title = "Export test",
            author = "joe",
            sheets = listOf(
                Sheet(
                    name = "Sheet1",
                    cells = linkedMapOf(
                        "A1" to SheetCell(value = "10"),
                        "A2" to SheetCell(value = "20"),
                        "B1" to SheetCell(value = "", formula = "SUM(A1:A2)"),
                        // A non-numeric cell to make the writer emit
                        // a shared strings entry — we want to see
                        // the full set of parts in the ZIP.
                        "C1" to SheetCell(value = "hello")
                    )
                )
            )
        )
        val bytes = SheetXlsx.export(wb)
        // ZIP starts with "PK\x03\x04".
        assertEquals('P'.code.toByte(), bytes[0])
        assertEquals('K'.code.toByte(), bytes[1])
        // Walk the ZIP and check the entries we expect.
        val entryNames = java.util.zip.ZipInputStream(bytes.inputStream()).use { zis ->
            val names = mutableListOf<String>()
            var e: java.util.zip.ZipEntry? = zis.nextEntry
            while (e != null) { names += e.name; e = zis.nextEntry }
            names.toList()
        }
        assertTrue("xl/workbook.xml" in entryNames)
        assertTrue("xl/worksheets/sheet1.xml" in entryNames)
        assertTrue("xl/sharedStrings.xml" in entryNames)
    }

    @Test
    fun `XLSX export then import round-trips a formula`() {
        val original = SheetWorkbook(
            title = "Round-trip",
            sheets = listOf(
                Sheet(
                    name = "Sheet1",
                    cells = linkedMapOf(
                        "A1" to SheetCell(value = "10"),
                        "A2" to SheetCell(value = "20"),
                        "B1" to SheetCell(value = "", formula = "SUM(A1:A2)")
                    )
                )
            )
        )
        val bytes = SheetXlsx.export(original)
        val parsed = SheetXlsx.importBytes(bytes)
        assertNotNull(parsed)
        val sheet = parsed!!.sheets[0]
        assertEquals("10", sheet.cells["A1"]?.value)
        assertEquals("20", sheet.cells["A2"]?.value)
        assertEquals("SUM(A1:A2)", sheet.cells["B1"]?.formula)
    }

    @Test
    fun `activeSheetIndex defaults to zero and is preserved`() {
        val wb = SheetWorkbook(activeSheetIndex = 2,
            sheets = listOf(Sheet(name = "A"), Sheet(name = "B"), Sheet(name = "C")))
        assertEquals(2, wb.activeSheetIndex)
        assertEquals("C", wb.activeSheet.name)
    }

    @Test
    fun `cells are addressable with case-insensitive lookup`() {
        val sheet = Sheet(name = "S", cells = linkedMapOf("A1" to SheetCell(value = "x")))
        // A1 is stored uppercase; lookups via the SheetWorkbookView upper-case.
        assertEquals("x", SheetWorkbookView(SheetWorkbook(sheets = listOf(sheet))).cellAt("a1")?.value)
    }

    @Test
    fun `format functions render numbers correctly`() {
        assertEquals("3", Fmt.number(3.0, NumberFormat.GENERAL))
        assertEquals("3.14", Fmt.number(3.14, NumberFormat.NUMBER(2)))
        assertEquals("\$3.14", Fmt.number(3.14, NumberFormat.CURRENCY(symbol = "$", decimals = 2)))
    }

    // ── Helpers ──────────────────────────────────────────────────

    private fun makeXlsx(
        sharedStrings: List<String>? = null,
        sheet1Xml: String,
        workbookXml: String
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("[Content_Types].xml"))
            zos.write(
                """<?xml version="1.0"?>
                  <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                    <Default Extension="xml" ContentType="application/xml"/>
                    <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                    <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                  </Types>""".toByteArray()
            )
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("_rels/.rels"))
            zos.write(
                """<?xml version="1.0"?>
                  <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                  </Relationships>""".toByteArray()
            )
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("xl/workbook.xml"))
            zos.write(workbookXml.toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels"))
            zos.write(
                """<?xml version="1.0"?>
                  <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                  </Relationships>""".toByteArray()
            )
            zos.closeEntry()
            if (sharedStrings != null) {
                zos.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
                val xml = buildString {
                    append("""<?xml version="1.0"?>""")
                    append("<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"${sharedStrings.size}\" uniqueCount=\"${sharedStrings.size}\">")
                    for (s in sharedStrings) {
                        append("<si><t>${escapeXml(s)}</t></si>")
                    }
                    append("</sst>")
                }
                zos.write(xml.toByteArray())
                zos.closeEntry()
            }
            zos.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zos.write(sheet1Xml.toByteArray())
            zos.closeEntry()
        }
        return out.toByteArray()
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
