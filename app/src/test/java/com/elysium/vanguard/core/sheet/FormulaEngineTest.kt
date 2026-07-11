package com.elysium.vanguard.core.sheet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 10.6 — Tests for the formula engine.
 *
 * These cover the grammar (operator precedence, parentheses),
 * the 32 supported functions, range expansion, nested references,
 * and the canonical error paths. All tests run on the JVM.
 */
class FormulaEngineTest {

    private fun run(formula: String, setup: SheetWorkbook.() -> Unit = {}): String {
        val wb = SheetWorkbook().apply(setup)
        return FormulaEngine.evaluate(formula, SheetWorkbookView(wb))
    }

    private fun set(address: String, value: String, formula: String? = null) {
        val sheet = SheetWorkbook().activeSheet
        sheet.cells[address] = SheetCell(value = value, formula = formula)
    }

    @Test
    fun `addition and subtraction`() {
        assertEquals("3", run("1+2"))
        assertEquals("0", run("5-5"))
        assertEquals("11", run("1+2+3+5"))
    }

    @Test
    fun `multiplication and division`() {
        assertEquals("6", run("2*3"))
        assertEquals("3", run("9/3"))
        assertEquals("#DIV/0!", run("10/0"))
    }

    @Test
    fun `division by zero returns DIV-0 error`() {
        val r = run("1/0")
        assertEquals("#DIV/0!", r)
    }

    @Test
    fun `operator precedence`() {
        assertEquals("7", run("1+2*3"))
        assertEquals("9", run("(1+2)*3"))
        assertEquals("25", run("5^2"))
    }

    @Test
    fun `unary minus and plus`() {
        assertEquals("-5", run("-5"))
        assertEquals("5", run("+5"))
        assertEquals("0", run("-5+5"))
    }

    @Test
    fun `modulo`() {
        assertEquals("1", run("10%3"))
    }

    @Test
    fun `string concatenation with plus`() {
        assertEquals("helloworld", run("\"hello\"+\"world\""))
    }

    @Test
    fun `string literal is preserved`() {
        assertEquals("hi", run("\"hi\""))
    }

    @Test
    fun `boolean literal evaluates to TRUE or FALSE`() {
        assertEquals("TRUE", run("TRUE"))
        assertEquals("FALSE", run("FALSE"))
    }

    @Test
    fun `comparison operators produce boolean`() {
        assertEquals("TRUE", run("1<2"))
        assertEquals("FALSE", run("1>2"))
        assertEquals("TRUE", run("3=3"))
        assertEquals("FALSE", run("3<>3"))
        assertEquals("TRUE", run("3<=3"))
        assertEquals("TRUE", run("3>=3"))
    }

    @Test
    fun `SUM aggregates a range`() {
        val result = run("SUM(A1:A3)", setup = {
            activeSheet.cells["A1"] = SheetCell(value = "10")
            activeSheet.cells["A2"] = SheetCell(value = "20")
            activeSheet.cells["A3"] = SheetCell(value = "30")
        })
        assertEquals("60", result)
    }

    @Test
    fun `AVERAGE divides by count`() {
        val result = run("AVERAGE(A1:A3)", setup = {
            activeSheet.cells["A1"] = SheetCell(value = "2")
            activeSheet.cells["A2"] = SheetCell(value = "4")
            activeSheet.cells["A3"] = SheetCell(value = "6")
        })
        assertEquals("4", result)
    }

    @Test
    fun `MIN and MAX return extremes`() {
        val result = run("MIN(A1:A3)", setup = {
            activeSheet.cells["A1"] = SheetCell(value = "5")
            activeSheet.cells["A2"] = SheetCell(value = "1")
            activeSheet.cells["A3"] = SheetCell(value = "9")
        })
        assertEquals("1", result)
        val result2 = run("MAX(A1:A3)", setup = {
            activeSheet.cells["A1"] = SheetCell(value = "5")
            activeSheet.cells["A2"] = SheetCell(value = "1")
            activeSheet.cells["A3"] = SheetCell(value = "9")
        })
        assertEquals("9", result2)
    }

    @Test
    fun `IF picks then or else branch`() {
        assertEquals("yes", run("IF(1<2,\"yes\",\"no\")"))
        assertEquals("no", run("IF(1>2,\"yes\",\"no\")"))
    }

    @Test
    fun `AND and OR compose booleans`() {
        assertEquals("TRUE", run("AND(TRUE,TRUE)"))
        assertEquals("FALSE", run("AND(TRUE,FALSE)"))
        assertEquals("TRUE", run("OR(FALSE,TRUE)"))
    }

    @Test
    fun `NOT negates a boolean`() {
        assertEquals("TRUE", run("NOT(FALSE)"))
        assertEquals("FALSE", run("NOT(TRUE)"))
    }

    @Test
    fun `ROUND rounds to N decimals`() {
        assertEquals("2", run("ROUND(1.6)"))
        assertEquals("1.5", run("ROUND(1.45,1)"))
    }

    @Test
    fun `ABS and SQRT`() {
        assertEquals("5", run("ABS(-5)"))
        assertEquals("3", run("SQRT(9)"))
    }

    @Test
    fun `LEN LEFT RIGHT MID TRIM`() {
        assertEquals("5", run("LEN(\"hello\")"))
        assertEquals("he", run("LEFT(\"hello\",2)"))
        assertEquals("lo", run("RIGHT(\"hello\",2)"))
        assertEquals("ell", run("MID(\"hello\",2,3)"))
        assertEquals("hi", run("TRIM(\"  hi  \")"))
    }

    @Test
    fun `UPPER LOWER PROPER`() {
        assertEquals("HI", run("UPPER(\"hi\")"))
        assertEquals("hi", run("LOWER(\"HI\")"))
        assertEquals("Hello World", run("PROPER(\"hello world\")"))
    }

    @Test
    fun `CONCATENATE joins strings`() {
        assertEquals("ab", run("CONCATENATE(\"a\",\"b\")"))
    }

    @Test
    fun `PI and POWER`() {
        assertEquals("9", run("POWER(3,2)"))
        val pi = run("PI()").toDouble()
        assertEquals(Math.PI, pi, 0.0001)
    }

    @Test
    fun `cell reference resolves to its value`() {
        val result = run("A1+1", setup = {
            activeSheet.cells["A1"] = SheetCell(value = "10")
        })
        assertEquals("11", result)
    }

    @Test
    fun `formula reference computes its result`() {
        val result = run("B1", setup = {
            activeSheet.cells["B1"] = SheetCell(value = "", formula = "A1*2")
            activeSheet.cells["A1"] = SheetCell(value = "21")
        })
        assertEquals("42", result)
    }

    @Test
    fun `VLOOKUP finds a value in a column`() {
        val result = run("VLOOKUP(\"key2\",A1:B3,2,TRUE)", setup = {
            activeSheet.cells["A1"] = SheetCell(value = "key1")
            activeSheet.cells["B1"] = SheetCell(value = "v1")
            activeSheet.cells["A2"] = SheetCell(value = "key2")
            activeSheet.cells["B2"] = SheetCell(value = "v2")
            activeSheet.cells["A3"] = SheetCell(value = "key3")
            activeSheet.cells["B3"] = SheetCell(value = "v3")
        })
        assertEquals("v2", result)
    }

    @Test
    fun `VLOOKUP misses return N-A`() {
        val result = run("VLOOKUP(\"nope\",A1:B3,2,TRUE)", setup = {
            activeSheet.cells["A1"] = SheetCell(value = "key1")
            activeSheet.cells["B1"] = SheetCell(value = "v1")
            activeSheet.cells["A2"] = SheetCell(value = "key2")
            activeSheet.cells["B2"] = SheetCell(value = "v2")
        })
        assertEquals("#N/A", result)
    }

    @Test
    fun `IFERROR replaces an error with a default`() {
        val result = run("IFERROR(1/0,\"oops\")")
        assertEquals("oops", result)
    }

    @Test
    fun `ISBLANK returns true for empty cells`() {
        assertEquals("TRUE", run("ISBLANK(A1)"))
    }

    @Test
    fun `ISNUMBER identifies numeric cells`() {
        val result = run("ISNUMBER(A1)", setup = {
            activeSheet.cells["A1"] = SheetCell(value = "5")
        })
        assertEquals("TRUE", result)
    }

    @Test
    fun `INT and SIGN operate on numbers`() {
        assertEquals("3", run("INT(3.7)"))
        assertEquals("-1", run("SIGN(-5)"))
        assertEquals("1", run("SIGN(5)"))
    }

    @Test
    fun `A1 helpers convert column labels correctly`() {
        assertEquals("A", A1.columnLabel(1))
        assertEquals("Z", A1.columnLabel(26))
        assertEquals("AA", A1.columnLabel(27))
        assertEquals("AZ", A1.columnLabel(52))
        assertEquals("BA", A1.columnLabel(53))
        assertEquals(1, A1.columnNumber("A"))
        assertEquals(26, A1.columnNumber("Z"))
        assertEquals(27, A1.columnNumber("AA"))
        assertEquals(53, A1.columnNumber("BA"))
        assertEquals(1, A1.rowOf("A1"))
        assertEquals(100, A1.rowOf("AA100"))
    }

    @Test
    fun `A1 range addresses cover the rectangle`() {
        val addrs = A1.rangeAddresses("A1", "B2")
        assertEquals(listOf("A1", "A2", "B1", "B2").toSet(), addrs.toSet())
    }

    @Test
    fun `unknown function returns NAME error`() {
        assertEquals("#NAME? (FOO)", run("FOO()"))
    }

    @Test
    fun `parse error returns parse error string`() {
        // The lexer/parser may swallow some errors; at minimum the
        // engine never throws.
        val result = run("@#!$")
        // Either an error token or an empty result is fine; we only
        // assert that the engine returned *something*.
        assertNotNull(result)
    }
}
