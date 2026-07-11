package com.elysium.vanguard.core.sheet

/**
 * PHASE 10.6 — Elysium Sheet formula engine.
 *
 * A small-but-real spreadsheet formula evaluator. The supported
 * grammar:
 *
 *   expression     = comparison
 *   comparison     = additive (('=' | '<>' | '<' | '>' | '<=' | '>=') additive)?
 *   additive       = multiplicative (('+' | '-') multiplicative)*
 *   multiplicative = power (('*' | '/' | '%') power)*
 *   power          = unary ('^' unary)?
 *   unary          = ('-' | '+')? primary
 *   primary        = NUMBER
 *                  | STRING
 *                  | BOOLEAN
 *                  | ERROR
 *                  | IDENT '(' args ')'
 *                  | CELL (':' CELL)?                // single ref or range
 *                  | '(' expression ')'
 *
 * Functions supported (32 of them, see [FunctionRegistry]):
 *
 *   SUM, AVERAGE, MIN, MAX, COUNT, COUNTA, IF, AND, OR, NOT,
 *   VLOOKUP, ROUND, ABS, LEN, LEFT, RIGHT, MID, TRIM,
 *   UPPER, LOWER, PROPER, CONCATENATE, NOW, TODAY, PI,
 *   SQRT, POWER, MOD, INT, SIGN, ROUNDDOWN, ROUNDUP, REPT,
 *   VALUE, TEXT, IFERROR, ISBLANK, ISNUMBER, ISTEXT
 *
 * Errors are strings prefixed with "#" and are propagated through
 * arithmetic (so `=A1 + #DIV/0!` returns `#DIV/0!`).
 *
 * The engine is a pure-JVM module; it does not depend on Android
 * types. Pass in a [WorkbookView] for cell lookups.
 */
object FormulaEngine {

    /**
     * Evaluate [formula] (without the leading "=") in the context of
     * [view]. The returned string is what the cell should display.
     */
    fun evaluate(formula: String, view: WorkbookView): String {
        if (formula.isEmpty()) return ""
        val trimmed = formula.trim()
        if (trimmed.startsWith("=")) return evaluate(trimmed.removePrefix("="), view)
        val tokens = try {
            Lexer(trimmed).tokenize()
        } catch (e: FormulaError) {
            return e.message ?: "#ERROR!"
        }
        val parser = Parser(tokens)
        val ast = try {
            parser.parseExpression()
        } catch (e: FormulaError) {
            return e.message ?: "#ERROR!"
        }
        if (!parser.atEnd()) {
            // Trailing tokens → probably a parse mistake.
            return "#PARSE!"
        }
        return try {
            val result = ast.eval(view)
            CellValue.toDisplayString(result, NumberFormat.GENERAL)
        } catch (e: FormulaError) {
            e.message ?: "#ERROR!"
        }
    }

    /** Detect the leading "=" and validate the expression is a formula. */
    fun isFormula(s: String): Boolean = s.startsWith("=") && s.length > 1
}

// ── Value model ───────────────────────────────────────────────────

/** A formula value. Strings, numbers, booleans, errors. */
sealed class CellValue {
    data class Num(val v: Double) : CellValue()
    data class Str(val s: String) : CellValue()
    data class Bool(val b: Boolean) : CellValue()
    data class Err(val message: String) : CellValue()

    companion object {
        fun toDisplayString(v: CellValue, format: NumberFormat): String = when (v) {
            is Num -> formatNumber(v.v, format)
            is Str -> v.s
            is Bool -> if (v.b) "TRUE" else "FALSE"
            is Err -> v.message
        }
    }
}

object Fmt {
    /** Format a double using the supplied [NumberFormat]. */
    fun number(v: Double, format: NumberFormat): String = formatNumber(v, format)
}

private fun formatNumber(v: Double, format: NumberFormat): String = when (format) {
    NumberFormat.GENERAL -> {
        if (v == v.toLong().toDouble() && kotlin.math.abs(v) < 1e15) v.toLong().toString()
        else v.toString()
    }
    is NumberFormat.NUMBER -> String.format(java.util.Locale.US, "%.${format.decimals}f", v)
    is NumberFormat.CURRENCY -> {
        val sign = if (v < 0) "-" else ""
        val abs = kotlin.math.abs(v)
        "$sign${format.symbol}${String.format(java.util.Locale.US, "%.${format.decimals}f", abs)}"
    }
    is NumberFormat.PERCENT -> String.format(java.util.Locale.US, "%.${format.decimals}f%%", v * 100)
    NumberFormat.DATE -> "1970-01-01" // cell needs a real date; placeholder
    NumberFormat.TIME -> "00:00:00"
    is NumberFormat.SCIENTIFIC -> String.format(java.util.Locale.US, "%.${format.decimals}e", v)
    is NumberFormat.FRACTION -> {
        // crude: render the integer part + a fraction with the requested denominator
        val whole = kotlin.math.floor(v).toLong()
        val frac = (v - whole)
        val n = kotlin.math.round(frac * format.denominator).toInt()
        if (n == 0) whole.toString()
        else if (whole == 0L) "$n/${format.denominator}"
        else "$whole $n/${format.denominator}"
    }
    is NumberFormat.CUSTOM -> v.toString() // custom patterns: out of scope
}

// ── Workbook view ─────────────────────────────────────────────────

/**
 * Read-only view of a workbook for the formula engine to look up
 * cells. Implementations are tiny; production uses [SheetWorkbookView].
 */
interface WorkbookView {
    fun cellAt(address: String): SheetCell?
    /** Return the union of cell values in [range] (A1:B2 style). */
    fun rangeValues(range: String): List<CellValue>
}

class SheetWorkbookView(private val workbook: SheetWorkbook) : WorkbookView {
    override fun cellAt(address: String): SheetCell? =
        workbook.activeSheet.cellAt(address.uppercase())

    override fun rangeValues(range: String): List<CellValue> {
        val parts = range.split(":")
        if (parts.size != 2) return emptyList()
        val addresses = A1.rangeAddresses(parts[0], parts[1])
        return addresses.mapNotNull { addr ->
            cellAt(addr)?.let { c ->
                when {
                    c.isFormula -> c.value.takeIf { it.isNotEmpty() }?.let { parseValue(it) }
                    else -> parseValue(c.value)
                }
            }
        }
    }

    private fun parseValue(s: String): CellValue = when {
        s.isEmpty() -> CellValue.Num(Double.NaN)
        s == "TRUE" -> CellValue.Bool(true)
        s == "FALSE" -> CellValue.Bool(false)
        s.startsWith("#") -> CellValue.Err(s)
        else -> s.toDoubleOrNull()?.let { CellValue.Num(it) } ?: CellValue.Str(s)
    }
}

// ── Lexer ─────────────────────────────────────────────────────────

private class Lexer(private val src: String) {
    private var pos = 0

    fun tokenize(): List<Token> {
        val out = ArrayList<Token>()
        while (pos < src.length) {
            val c = src[pos]
            when {
                c.isWhitespace() -> pos++
                c.isDigit() || (c == '.' && pos + 1 < src.length && src[pos + 1].isDigit()) -> {
                    out += readNumber()
                }
                c == '"' -> out += readString()
                c == '#' -> out += readError()
                c.isLetter() -> {
                    val tok = readIdentOrCell()
                    // Distinguish: an ident followed by '(' is a function.
                    if (tok.type == TokenType.IDENT && peek() == '(') {
                        out += tok.copy(type = TokenType.FUNC)
                    } else {
                        out += tok
                    }
                }
                else -> {
                    out += readOperator()
                }
            }
        }
        out += Token(TokenType.EOF, "")
        return out
    }

    private fun peek(): Char? = if (pos < src.length) src[pos] else null

    private fun readNumber(): Token {
        val start = pos
        while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
        return Token(TokenType.NUMBER, src.substring(start, pos))
    }

    private fun readString(): Token {
        pos++ // opening "
        val sb = StringBuilder()
        while (pos < src.length && src[pos] != '"') {
            if (src[pos] == '\\' && pos + 1 < src.length) {
                sb.append(src[pos + 1])
                pos += 2
            } else {
                sb.append(src[pos])
                pos++
            }
        }
        if (pos < src.length) pos++ // closing "
        return Token(TokenType.STRING, sb.toString())
    }

    private fun readError(): Token {
        val start = pos
        while (pos < src.length && src[pos] != ' ' && src[pos] != ')' && src[pos] != ',') pos++
        return Token(TokenType.ERROR, src.substring(start, pos))
    }

    private fun readIdentOrCell(): Token {
        val start = pos
        while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) pos++
        val text = src.substring(start, pos)
        return when {
            text == "TRUE" || text == "FALSE" -> Token(TokenType.BOOL, text)
            // An A1 cell reference: letters then digits
            text.matches(Regex("[A-Z]+\\d+")) -> Token(TokenType.CELL, text)
            // A range's second half ("A1" inside "A1:B2") — context decides.
            text.matches(Regex("[A-Z]+\\d+")) -> Token(TokenType.CELL, text)
            else -> Token(TokenType.IDENT, text)
        }
    }

    private fun readOperator(): Token {
        val c = src[pos]
        val next = if (pos + 1 < src.length) src[pos + 1] else ' '
        val two = "$c$next"
        when (two) {
            "<=", ">=", "<>" -> { pos += 2; return Token(TokenType.OP, two) }
        }
        when (c) {
            '+', '-', '*', '/', '^', '(', ')', ',', '=', '<', '>' -> {
                pos++
                return Token(TokenType.OP, c.toString())
            }
            '%' -> { pos++; return Token(TokenType.OP, "%") }
        }
        pos++
        return Token(TokenType.OP, c.toString())
    }
}

private enum class TokenType { NUMBER, STRING, BOOL, ERROR, IDENT, FUNC, CELL, OP, EOF }

private data class Token(val type: TokenType, val text: String) {
    override fun toString() = "$type($text)"
    fun copy(type: TokenType): Token = Token(type, text)
}

// ── Parser ────────────────────────────────────────────────────────

private class Parser(private val tokens: List<Token>) {
    private var pos = 0

    fun atEnd(): Boolean = tokens[pos].type == TokenType.EOF

    fun parseExpression(): AstNode = parseComparison()

    private fun parseComparison(): AstNode {
        var left = parseAdditive()
        while (atOp("<=", ">=", "<>", "=", "<", ">")) {
            val op = tokens[pos].text
            pos++
            val right = parseAdditive()
            left = AstNode.Compare(left, op, right)
        }
        return left
    }

    private fun parseAdditive(): AstNode {
        var left = parseMultiplicative()
        while (atOp("+", "-")) {
            val op = tokens[pos].text
            pos++
            val right = parseMultiplicative()
            left = AstNode.BinOp(left, op, right)
        }
        return left
    }

    private fun parseMultiplicative(): AstNode {
        var left = parsePower()
        while (atOp("*", "/", "%")) {
            val op = tokens[pos].text
            pos++
            val right = parsePower()
            left = AstNode.BinOp(left, op, right)
        }
        return left
    }

    private fun parsePower(): AstNode {
        val left = parseUnary()
        if (atOp("^")) {
            pos++
            val right = parseUnary()
            return AstNode.BinOp(left, "^", right)
        }
        return left
    }

    private fun parseUnary(): AstNode {
        return if (atOp("-", "+")) {
            val op = tokens[pos].text
            pos++
            AstNode.UnaryOp(op, parseUnary())
        } else parsePrimary()
    }

    private fun parsePrimary(): AstNode {
        val t = tokens[pos]
        when (t.type) {
            TokenType.NUMBER -> { pos++; return AstNode.Number(t.text.toDouble()) }
            TokenType.STRING -> { pos++; return AstNode.StringLit(t.text) }
            TokenType.BOOL -> {
                pos++
                return AstNode.BoolLit(t.text == "TRUE")
            }
            TokenType.ERROR -> { pos++; return AstNode.ErrorLit(t.text) }
            TokenType.FUNC -> {
                pos++ // consume FUNC
                expect("(")
                val args = ArrayList<AstNode>()
                if (!atOp(")")) {
                    args += parseExpression()
                    while (atOp(",")) { pos++; args += parseExpression() }
                }
                expect(")")
                return AstNode.Call(t.text, args)
            }
            TokenType.CELL -> {
                pos++
                if (atOp(":")) {
                    pos++
                    val endTok = tokens[pos]
                    require(endTok.type == TokenType.CELL) { "Expected cell after ':'" }
                    pos++
                    return AstNode.Range("${t.text}:${endTok.text}")
                }
                return AstNode.CellRef(t.text)
            }
            TokenType.OP -> {
                if (t.text == "(") {
                    pos++
                    val inner = parseExpression()
                    expect(")")
                    return inner
                }
                if (t.text == "-") { pos++; return AstNode.UnaryOp("-", parsePrimary()) }
            }
            else -> throw FormulaError("#PARSE! at '${t.text}'")
        }
        throw FormulaError("#PARSE! at '${t.text}'")
    }

    private fun atOp(vararg ops: String): Boolean {
        val t = tokens[pos]
        return t.type == TokenType.OP && ops.contains(t.text)
    }

    private fun expect(op: String) {
        val t = tokens[pos]
        if (t.type != TokenType.OP || t.text != op) {
            throw FormulaError("#PARSE! expected '$op' got '${t.text}'")
        }
        pos++
    }
}

// ── AST ───────────────────────────────────────────────────────────

private sealed class AstNode {
    abstract fun eval(view: WorkbookView): CellValue

    data class Number(val v: Double) : AstNode() {
        override fun eval(view: WorkbookView) = CellValue.Num(v)
    }
    data class StringLit(val s: String) : AstNode() {
        override fun eval(view: WorkbookView) = CellValue.Str(s)
    }
    data class BoolLit(val b: Boolean) : AstNode() {
        override fun eval(view: WorkbookView) = CellValue.Bool(b)
    }
    data class ErrorLit(val msg: String) : AstNode() {
        override fun eval(view: WorkbookView) = CellValue.Err(msg)
    }
    data class CellRef(val address: String) : AstNode() {
        override fun eval(view: WorkbookView): CellValue {
            val cell = view.cellAt(address) ?: return CellValue.Num(0.0)
            if (cell.formula != null) {
                // Recurse to evaluate the formula. We DON'T cache here
                // — caching is a separate pass (Phase 10.6.x). For
                // 10.6 the recursion is bounded by the recursion depth
                // limit of the JVM, which is plenty for sane sheets.
                val value = FormulaEngine.evaluate(cell.formula, view)
                return parseValue(value)
            }
            return parseValue(cell.value)
        }
    }
    data class Range(val expr: String) : AstNode() {
        override fun eval(view: WorkbookView) = CellValue.Str("range $expr") // unused; ranges are only used inside function calls
    }
    data class BinOp(val left: AstNode, val op: String, val right: AstNode) : AstNode() {
        override fun eval(view: WorkbookView): CellValue {
            val a = left.eval(view)
            if (a is CellValue.Err) return a
            val b = right.eval(view)
            if (b is CellValue.Err) return b
            return when (op) {
                "+" -> add(a, b)
                "-" -> sub(a, b)
                "*" -> mul(a, b)
                "/" -> div(a, b)
                "%" -> mod(a, b)
                "^" -> pow(a, b)
                else -> CellValue.Err("#UNKNOWN OP $op")
            }
        }
    }
    data class Compare(val left: AstNode, val op: String, val right: AstNode) : AstNode() {
        override fun eval(view: WorkbookView): CellValue {
            val a = left.eval(view)
            val b = right.eval(view)
            if (a is CellValue.Err) return a
            if (b is CellValue.Err) return b
            val cmp = when {
                a is CellValue.Num && b is CellValue.Num -> a.v.compareTo(b.v)
                else -> a.toString().compareTo(b.toString())
            }
            val result = when (op) {
                "=" -> cmp == 0
                "<>" -> cmp != 0
                "<" -> cmp < 0
                ">" -> cmp > 0
                "<=" -> cmp <= 0
                ">=" -> cmp >= 0
                else -> false
            }
            return CellValue.Bool(result)
        }
    }
    data class UnaryOp(val op: String, val expr: AstNode) : AstNode() {
        override fun eval(view: WorkbookView): CellValue {
            val v = expr.eval(view)
            if (v is CellValue.Err) return v
            return when (op) {
                "-" -> when (v) {
                    is CellValue.Num -> CellValue.Num(-v.v)
                    else -> CellValue.Err("#VALUE!")
                }
                "+" -> v
                else -> v
            }
        }
    }
    data class Call(val name: String, val args: List<AstNode>) : AstNode() {
        override fun eval(view: WorkbookView): CellValue = FunctionRegistry.invoke(name, args, view)
    }
}

private fun parseValue(s: String): CellValue = when {
    s.isEmpty() -> CellValue.Num(0.0)
    s == "TRUE" -> CellValue.Bool(true)
    s == "FALSE" -> CellValue.Bool(false)
    s.startsWith("#") -> CellValue.Err(s)
    else -> s.toDoubleOrNull()?.let { CellValue.Num(it) } ?: CellValue.Str(s)
}

private fun toNum(v: CellValue): Double = when (v) {
    is CellValue.Num -> v.v
    is CellValue.Str -> v.s.toDoubleOrNull() ?: 0.0
    is CellValue.Bool -> if (v.b) 1.0 else 0.0
    is CellValue.Err -> 0.0
}

private fun toBool(v: CellValue): Boolean = when (v) {
    is CellValue.Bool -> v.b
    is CellValue.Num -> v.v != 0.0
    is CellValue.Str -> v.s.isNotEmpty() && v.s != "FALSE" && v.s != "0"
    is CellValue.Err -> false
}

private fun toStr(v: CellValue): String = when (v) {
    is CellValue.Num -> v.v.toString()
    is CellValue.Bool -> if (v.b) "TRUE" else "FALSE"
    is CellValue.Str -> v.s
    is CellValue.Err -> v.message
}

private fun add(a: CellValue, b: CellValue): CellValue = when {
    a is CellValue.Num && b is CellValue.Num -> CellValue.Num(a.v + b.v)
    a is CellValue.Str || b is CellValue.Str -> CellValue.Str(toStr(a) + toStr(b))
    else -> CellValue.Err("#VALUE!")
}

private fun sub(a: CellValue, b: CellValue): CellValue {
    val x = toNum(a); val y = toNum(b); return CellValue.Num(x - y)
}

private fun mul(a: CellValue, b: CellValue): CellValue {
    val x = toNum(a); val y = toNum(b); return CellValue.Num(x * y)
}

private fun div(a: CellValue, b: CellValue): CellValue {
    val x = toNum(a); val y = toNum(b)
    if (y == 0.0) return CellValue.Err("#DIV/0!")
    return CellValue.Num(x / y)
}

private fun mod(a: CellValue, b: CellValue): CellValue {
    val x = toNum(a); val y = toNum(b)
    if (y == 0.0) return CellValue.Err("#DIV/0!")
    return CellValue.Num(x % y)
}

private fun pow(a: CellValue, b: CellValue): CellValue {
    val x = toNum(a); val y = toNum(b)
    return CellValue.Num(Math.pow(x, y))
}

// ── Function registry ─────────────────────────────────────────────

private object FunctionRegistry {

    fun invoke(name: String, args: List<AstNode>, view: WorkbookView): CellValue {
        val n = name.uppercase()
        return when (n) {
            "SUM" -> numericReduce(args, view, 0.0) { acc, v -> acc + v }
            "AVERAGE", "AVG" -> {
                val values = numericArgs(args, view)
                if (values.isEmpty()) CellValue.Err("#DIV/0!")
                else CellValue.Num(values.average())
            }
            "MIN" -> {
                val values = numericArgs(args, view)
                if (values.isEmpty()) CellValue.Num(0.0) else CellValue.Num(values.min())
            }
            "MAX" -> {
                val values = numericArgs(args, view)
                if (values.isEmpty()) CellValue.Num(0.0) else CellValue.Num(values.max())
            }
            "COUNT" -> CellValue.Num(numericArgs(args, view).size.toDouble())
            "COUNTA" -> CellValue.Num(nonEmptyArgs(args, view).size.toDouble())
            "IF" -> {
                if (args.size < 2 || args.size > 3) return CellValue.Err("#VALUE!")
                val cond = toBool(args[0].eval(view))
                if (cond) args[1].eval(view) else (args.getOrNull(2)?.eval(view) ?: CellValue.Bool(false))
            }
            "AND" -> CellValue.Bool(args.all { toBool(it.eval(view)) })
            "OR" -> CellValue.Bool(args.any { toBool(it.eval(view)) })
            "NOT" -> {
                if (args.size != 1) return CellValue.Err("#VALUE!")
                CellValue.Bool(!toBool(args[0].eval(view)))
            }
            "VLOOKUP" -> vlookup(args, view)
            "ROUND" -> {
                if (args.size !in 1..2) return CellValue.Err("#VALUE!")
                val v = toNum(args[0].eval(view))
                val d = if (args.size == 2) toNum(args[1].eval(view)).toInt() else 0
                val mul = Math.pow(10.0, d.toDouble())
                CellValue.Num(Math.round(v * mul) / mul)
            }
            "ROUNDDOWN" -> {
                if (args.size !in 1..2) return CellValue.Err("#VALUE!")
                val v = toNum(args[0].eval(view))
                val d = if (args.size == 2) toNum(args[1].eval(view)).toInt() else 0
                val mul = Math.pow(10.0, d.toDouble())
                CellValue.Num(Math.floor(v * mul) / mul)
            }
            "ROUNDUP" -> {
                if (args.size !in 1..2) return CellValue.Err("#VALUE!")
                val v = toNum(args[0].eval(view))
                val d = if (args.size == 2) toNum(args[1].eval(view)).toInt() else 0
                val mul = Math.pow(10.0, d.toDouble())
                CellValue.Num(Math.ceil(v * mul) / mul)
            }
            "ABS" -> CellValue.Num(kotlin.math.abs(toNum(args[0].eval(view))))
            "INT" -> CellValue.Num(kotlin.math.floor(toNum(args[0].eval(view))))
            "SIGN" -> CellValue.Num(kotlin.math.sign(toNum(args[0].eval(view))))
            "LEN" -> CellValue.Num(toStr(args[0].eval(view)).length.toDouble())
            "LEFT" -> {
                if (args.size !in 1..2) return CellValue.Err("#VALUE!")
                val s = toStr(args[0].eval(view))
                val n = if (args.size == 2) toNum(args[1].eval(view)).toInt() else 1
                CellValue.Str(s.take(n))
            }
            "RIGHT" -> {
                if (args.size !in 1..2) return CellValue.Err("#VALUE!")
                val s = toStr(args[0].eval(view))
                val n = if (args.size == 2) toNum(args[1].eval(view)).toInt() else 1
                CellValue.Str(s.takeLast(n))
            }
            "MID" -> {
                if (args.size != 3) return CellValue.Err("#VALUE!")
                val s = toStr(args[0].eval(view))
                val start = toNum(args[1].eval(view)).toInt().coerceAtLeast(1) - 1
                val len = toNum(args[2].eval(view)).toInt()
                if (start >= s.length) CellValue.Str("") else CellValue.Str(s.substring(start, (start + len).coerceAtMost(s.length)))
            }
            "TRIM" -> CellValue.Str(toStr(args[0].eval(view)).trim())
            "UPPER" -> CellValue.Str(toStr(args[0].eval(view)).uppercase())
            "LOWER" -> CellValue.Str(toStr(args[0].eval(view)).lowercase())
            "PROPER" -> CellValue.Str(toStr(args[0].eval(view)).split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } })
            "REPT" -> {
                if (args.size != 2) return CellValue.Err("#VALUE!")
                val s = toStr(args[0].eval(view))
                val n = toNum(args[1].eval(view)).toInt().coerceAtLeast(0)
                CellValue.Str(s.repeat(n))
            }
            "CONCATENATE", "CONCAT" -> CellValue.Str(args.joinToString("") { toStr(it.eval(view)) })
            "NOW" -> {
                val ms = System.currentTimeMillis()
                CellValue.Num(ms.toDouble())
            }
            "TODAY" -> {
                val ms = System.currentTimeMillis()
                CellValue.Num((ms / 86_400_000L).toDouble() * 86_400_000.0)
            }
            "PI" -> CellValue.Num(Math.PI)
            "SQRT" -> CellValue.Num(Math.sqrt(toNum(args[0].eval(view))))
            "POWER" -> {
                if (args.size != 2) return CellValue.Err("#VALUE!")
                CellValue.Num(Math.pow(toNum(args[0].eval(view)), toNum(args[1].eval(view))))
            }
            "MOD" -> {
                if (args.size != 2) return CellValue.Err("#VALUE!")
                val a = toNum(args[0].eval(view)); val b = toNum(args[1].eval(view))
                if (b == 0.0) CellValue.Err("#DIV/0!") else CellValue.Num(a % b)
            }
            "VALUE" -> {
                val s = toStr(args[0].eval(view))
                s.toDoubleOrNull()?.let { CellValue.Num(it) } ?: CellValue.Err("#VALUE!")
            }
            "TEXT" -> {
                // Excel TEXT: format a number as text. We only support
                // the trivial "always" case; the pattern parameter is
                // accepted but ignored (the spec is huge).
                if (args.size !in 1..2) return CellValue.Err("#VALUE!")
                CellValue.Str(toStr(args[0].eval(view)))
            }
            "IFERROR" -> {
                if (args.size !in 1..2) return CellValue.Err("#VALUE!")
                val v = args[0].eval(view)
                if (v is CellValue.Err) args[1].eval(view) else v
            }
            "ISBLANK" -> {
                if (args.size != 1) return CellValue.Err("#VALUE!")
                val cell = view.cellAt((args[0] as? AstNode.CellRef)?.address ?: "")
                CellValue.Bool(cell == null || cell.isEmpty)
            }
            "ISNUMBER" -> CellValue.Bool(args[0].eval(view) is CellValue.Num)
            "ISTEXT" -> CellValue.Bool(args[0].eval(view) is CellValue.Str)
            else -> CellValue.Err("#NAME? ($name)")
        }
    }

    /**
     * Reduce a list of args to a single number, where each arg can
     * be a number / boolean / string (parsed) or a range (expanded).
     */
    private fun numericReduce(args: List<AstNode>, view: WorkbookView, init: Double, op: (Double, Double) -> Double): CellValue {
        var acc = init
        for (arg in args) {
            when (arg) {
                is AstNode.Range -> for (v in view.rangeValues(arg.expr)) {
                    if (v is CellValue.Err) return v
                    if (v is CellValue.Num) acc = op(acc, v.v)
                }
                else -> {
                    val v = arg.eval(view)
                    if (v is CellValue.Err) return v
                    acc = op(acc, toNum(v))
                }
            }
        }
        return CellValue.Num(acc)
    }

    private fun numericArgs(args: List<AstNode>, view: WorkbookView): List<Double> {
        val out = ArrayList<Double>()
        for (arg in args) {
            when (arg) {
                is AstNode.Range -> for (v in view.rangeValues(arg.expr)) {
                    if (v is CellValue.Num) out += v.v
                }
                else -> {
                    val v = arg.eval(view)
                    if (v is CellValue.Num) out += v.v
                }
            }
        }
        return out
    }

    private fun nonEmptyArgs(args: List<AstNode>, view: WorkbookView): List<CellValue> {
        val out = ArrayList<CellValue>()
        for (arg in args) {
            when (arg) {
                is AstNode.Range -> for (v in view.rangeValues(arg.expr)) {
                    if (v !is CellValue.Str || v.s.isNotEmpty()) out += v
                }
                else -> {
                    val v = arg.eval(view)
                    if (v !is CellValue.Str || v.s.isNotEmpty()) out += v
                }
            }
        }
        return out
    }

    private fun vlookup(args: List<AstNode>, view: WorkbookView): CellValue {
        if (args.size < 3) return CellValue.Err("#VALUE!")
        val needle = args[0].eval(view)
        if (needle is CellValue.Err) return needle
        val rangeArg = args[1]
        if (rangeArg !is AstNode.Range) return CellValue.Err("#REF!")
        val colIndex = toNum(args[2].eval(view)).toInt()
        val exact = if (args.size >= 4) toBool(args[3].eval(view)) else true
        val cells = A1.rangeAddresses(
            rangeArg.expr.substringBefore(":"),
            rangeArg.expr.substringAfter(":")
        )
        // VLOOKUP scans the first column of the range.
        val firstColStart = A1.columnOf(rangeArg.expr.substringBefore(":"))
        val firstColEnd = A1.columnOf(rangeArg.expr.substringBefore(":"))
        val targetCol = firstColStart + colIndex - 1
        val rowStart = A1.rowOf(rangeArg.expr.substringBefore(":"))
        val rowEnd = A1.rowOf(rangeArg.expr.substringAfter(":"))
        for (row in rowStart..rowEnd) {
            val keyAddr = A1.address(firstColStart, row)
            val keyVal = view.cellAt(keyAddr)?.let { parseValue(it.value) } ?: continue
            if (exact) {
                if (compareEqual(needle, keyVal)) {
                    val targetAddr = A1.address(targetCol, row)
                    return view.cellAt(targetAddr)?.let { parseValue(it.value) }
                        ?: CellValue.Str("")
                }
            } else {
                if (compareLessOrEqual(keyVal, needle) &&
                    (row == rowEnd || compareGreater(needle, parseValue(view.cellAt(A1.address(firstColStart, row + 1))?.value ?: "")))
                ) {
                    val targetAddr = A1.address(targetCol, row)
                    return view.cellAt(targetAddr)?.let { parseValue(it.value) }
                        ?: CellValue.Str("")
                }
            }
        }
        return CellValue.Err("#N/A")
    }

    private fun compareEqual(a: CellValue, b: CellValue): Boolean = when {
        a is CellValue.Num && b is CellValue.Num -> a.v == b.v
        a is CellValue.Str && b is CellValue.Str -> a.s == b.s
        else -> toStr(a) == toStr(b)
    }

    private fun compareLessOrEqual(a: CellValue, b: CellValue): Boolean = when {
        a is CellValue.Num && b is CellValue.Num -> a.v <= b.v
        else -> toStr(a) <= toStr(b)
    }

    private fun compareGreater(a: CellValue, b: CellValue): Boolean = when {
        a is CellValue.Num && b is CellValue.Num -> a.v > b.v
        else -> toStr(a) > toStr(b)
    }
}

class FormulaError(message: String) : RuntimeException(message)
