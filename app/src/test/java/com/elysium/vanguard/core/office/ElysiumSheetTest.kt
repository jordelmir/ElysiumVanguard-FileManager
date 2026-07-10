package com.elysium.vanguard.core.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 9.8.3 — Tests for the Sheet / CSV / formula stack.
 */
class ElysiumSheetTest {

    @Test
    fun `empty sheet is 1x1 with no cells`() {
        val sheet = ElysiumSheet.empty(1, 1)
        assertEquals(1, sheet.rows)
        assertEquals(1, sheet.cols)
        assertNull(sheet.cellAt(0, 0))
    }

    @Test
    fun `withCell replaces a value`() {
        val sheet = ElysiumSheet.empty(2, 2).withCell(0, 0, "hello").withCell(1, 1, "world")
        assertEquals("hello", sheet.cellAt(0, 0))
        assertEquals("world", sheet.cellAt(1, 1))
    }

    @Test
    fun `withCell out of bounds returns the same sheet`() {
        val s = ElysiumSheet.empty(2, 2)
        assertTrue(s === s.withCell(99, 99, "x"))
    }

    @Test
    fun `CSV parse handles plain rows`() {
        val sheet = ElysiumSheet.fromCsv("name,age\nada,36\njor,32".toByteArray())
        assertEquals(3, sheet.rows)
        assertEquals(2, sheet.cols)
        assertEquals("name", sheet.cellAt(0, 0))
        assertEquals("36", sheet.cellAt(1, 1))
    }

    @Test
    fun `CSV parse handles quoted fields with embedded commas`() {
        val sheet = ElysiumSheet.fromCsv(
            "name,note\n\"ada, lisp\",\"high-level\"\n".toByteArray()
        )
        assertEquals("ada, lisp", sheet.cellAt(1, 0))
        assertEquals("high-level", sheet.cellAt(1, 1))
    }

    @Test
    fun `CSV parse handles doubled-quote escapes`() {
        val sheet = ElysiumSheet.fromCsv(
            "name\n\"she said \"\"hi\"\"\"\n".toByteArray()
        )
        assertEquals("she said \"hi\"", sheet.cellAt(1, 0))
    }

    @Test
    fun `CSV parse handles CRLF line endings`() {
        val sheet = ElysiumSheet.fromCsv("a,b\r\n1,2\r\n3,4\r\n".toByteArray())
        assertEquals(3, sheet.rows)
        assertEquals("4", sheet.cellAt(2, 1))
    }

    @Test
    fun `CSV parse pads short rows`() {
        val sheet = ElysiumSheet.fromCsv("a,b,c\n1\n4,5,6".toByteArray())
        // 3 rows (header + 1 empty + last)
        assertEquals(3, sheet.rows)
        assertEquals("c", sheet.cellAt(0, 2))
        // The row "1" has only one field; the rest are null.
        assertEquals("1", sheet.cellAt(1, 0))
        assertNull(sheet.cellAt(1, 1))
    }

    @Test
    fun `CSV round trip preserves simple values`() {
        val original = ElysiumSheet.empty(2, 3)
            .withCell(0, 0, "name").withCell(0, 1, "age")
            .withCell(1, 0, "ada").withCell(1, 1, "36")
        val csv = original.toCsv()
        val parsed = ElysiumSheet.fromCsv(csv)
        assertEquals(original.rows, parsed.rows)
        assertEquals("ada", parsed.cellAt(1, 0))
        assertEquals("36", parsed.cellAt(1, 1))
    }

    @Test
    fun `CSV round trip preserves quoted commas`() {
        val original = ElysiumSheet.empty(2, 1)
            .withCell(0, 0, "math, science")
            .withCell(1, 0, "lit, drama")
        val csv = original.toCsv()
        val parsed = ElysiumSheet.fromCsv(csv)
        assertEquals("math, science", parsed.cellAt(0, 0))
        assertEquals("lit, drama", parsed.cellAt(1, 0))
    }

    @Test
    fun `CSV serialize quotes a field with a newline`() {
        val sheet = ElysiumSheet.empty(1, 1)
            .withCell(0, 0, "line1\nline2")
        val csv = String(sheet.toCsv(), Charsets.UTF_8)
        assertTrue(csv.startsWith("\"line1\nline2\""))
    }

    // ----- Formula tests -----

    @Test
    fun `formula evaluator handles simple addition`() {
        val sheet = ElysiumSheet.empty(2, 1)
            .withCell(0, 0, "10")
            .withCell(1, 0, "32")
        val result = FormulaEvaluator.evaluate("=A1+A2", sheet)
        assertEquals(42.0, result, 0.0001)
    }

    @Test
    fun `formula evaluator handles subtraction and multiplication`() {
        val sheet = ElysiumSheet.empty(2, 1)
            .withCell(0, 0, "10")
            .withCell(1, 0, "5")
        assertEquals(5.0, FormulaEvaluator.evaluate("=A1-A2", sheet), 0.0001)
        assertEquals(50.0, FormulaEvaluator.evaluate("=A1*A2", sheet), 0.0001)
    }

    @Test
    fun `formula evaluator handles division`() {
        val sheet = ElysiumSheet.empty(2, 1)
            .withCell(0, 0, "100")
            .withCell(1, 0, "5")
        assertEquals(20.0, FormulaEvaluator.evaluate("=A1/A2", sheet), 0.0001)
    }

    @Test
    fun `formula evaluator handles SUM over a vertical range`() {
        val sheet = ElysiumSheet.empty(5, 1)
            .withCell(0, 0, "1")
            .withCell(1, 0, "2")
            .withCell(2, 0, "3")
            .withCell(3, 0, "4")
        assertEquals(10.0, FormulaEvaluator.evaluate("=SUM(A1:A4)", sheet), 0.0001)
    }

    @Test
    fun `formula evaluator produces FormulaError on bad input`() {
        val sheet = ElysiumSheet.empty(1, 1)
        try {
            FormulaEvaluator.evaluate("=", sheet)
            throw AssertionError("expected FormulaError")
        } catch (e: FormulaEvaluator.FormulaError) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun `formula evaluator chokes on division by zero`() {
        val sheet = ElysiumSheet.empty(2, 1)
            .withCell(0, 0, "1")
            .withCell(1, 0, "0")
        try {
            FormulaEvaluator.evaluate("=A1/A2", sheet)
            throw AssertionError("expected FormulaError")
        } catch (e: FormulaEvaluator.FormulaError) {
            assertTrue(e.message!!.contains("div"))
        }
    }
}
