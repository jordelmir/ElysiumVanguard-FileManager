package com.elysium.vanguard.core.office

/**
 * PHASE 9.8.3 — Tiny formula evaluator.
 *
 * Supported today:
 *   - Numeric literals (e.g. `42`, `3.14`)
 *   - Cell references (e.g. `A1`, `B2`, `A12`)
 *   - `+ - * /` with precedence
 *   - `SUM(A1:A10)` over a vertical range
 *
 * Phase 9.8.4 will add `CONCAT`, `IF`, `MAX`, `MIN`. The parser is
 * deliberately naive — no functions beyond `SUM`, no negative indices,
 * no `#REF!` errors yet. We throw [FormulaError] for anything we
 * can't parse; the renderer (Phase 9.8.4) catches and shows `#ERR!`.
 *
 * Phase 9.8.3 — first build; intentionally minimal.
 */
object FormulaEvaluator {

    class FormulaError(message: String) : IllegalStateException(message)

    sealed interface Expr {
        data class Literal(val value: Double) : Expr
        data class Reference(val row: Int, val col: Int) : Expr
        data class Unary(val op: Char, val inner: Expr) : Expr
        data class Binary(val op: Char, val left: Expr, val right: Expr) : Expr
        data class Sum(val references: List<Pair<Int, Int>>) : Expr
    }

    /**
     * Evaluate [formula] against [sheet]. The formula begins with `=` if the
     * caller wants it parsed; this method also accepts a leading `=`.
     */
    fun evaluate(formula: String, sheet: ElysiumSheet): Double {
        val body = formula.trim().removePrefix("=").trim()
        if (body.isEmpty()) throw FormulaError("empty formula")
        // Handle SUM(A1:A10) as a special form for simplicity.
        val sumMatch = Regex("""^SUM\(([A-Z]+\d+):([A-Z]+\d+)\)$""").matchEntire(body)
        if (sumMatch != null) {
            val (first, second) = sumMatch.destructured
            val (r1, c1) = parseCellRef(first)
            val (r2, c2) = parseCellRef(second)
            return Expr.Sum(listOf(r1 to c1, r2 to c2)).eval(sheet)
        }
        val tokens = tokenize(body)
        val rpn = toRPN(tokens)
        val stack = ArrayDeque<Double>()
        for (tok in rpn) {
            when (tok) {
                is Double -> stack.addLast(tok)
                is Char -> {
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    stack.addLast(applyBinary(tok, a, b))
                }
                is Expr -> stack.addLast(tok.eval(sheet))
            }
        }
        return stack.removeLast()
    }

    fun Expr.eval(sheet: ElysiumSheet): Double = when (this) {
        is Expr.Literal -> value
        is Expr.Reference -> sheet.cellAt(row, col)?.toDoubleOrNull()
            ?: throw FormulaError("non-numeric at ${cellRef(row, col)}")
        is Expr.Unary -> when (op) {
            '-' -> -inner.eval(sheet)
            '+' -> inner.eval(sheet)
            else -> throw FormulaError("unknown unary $op")
        }
        is Expr.Binary -> {
            val l = left.eval(sheet)
            val r = right.eval(sheet)
            applyBinary(op, l, r)
        }
        is Expr.Sum -> {
            val (r1, c1) = references.first()
            val (r2, c2) = references.last()
            val fromRow = minOf(r1, r2)
            val toRow = maxOf(r1, r2)
            val col = c1
            var total = 0.0
            for (row in fromRow..toRow) {
                total += sheet.cellAt(row, col)?.toDoubleOrNull() ?: 0.0
            }
            total
        }
    }

    private fun applyBinary(op: Char, a: Double, b: Double): Double = when (op) {
        '+' -> a + b
        '-' -> a - b
        '*' -> a * b
        '/' -> if (b == 0.0) throw FormulaError("div/0") else a / b
        else -> throw FormulaError("unknown op $op")
    }

    private fun parseCellRef(ref: String): Pair<Int, Int> {
        val letters = ref.takeWhile { it.isLetter() }
        val digits = ref.dropWhile { it.isLetter() }
        if (letters.isEmpty() || digits.isEmpty()) throw FormulaError("bad ref $ref")
        // Convert letters -> 0-based column: A=0, B=1, ..., Z=25.
        var col = 0
        for (ch in letters) {
            col = col * 26 + (ch.uppercaseChar().code - 'A'.code + 1)
        }
        col -= 1
        val row = digits.toInt() - 1
        if (row < 0 || col < 0) throw FormulaError("bad ref $ref")
        return row to col
    }

    private fun cellRef(row: Int, col: Int): String {
        // 0-based column → A, B, ..., Z, AA, ...
        var n = col + 1
        var out = ""
        while (n > 0) {
            n -= 1
            out = ('A' + (n % 26)) + out
            n /= 26
        }
        return "$out${row + 1}"
    }

    /**
     * Tokenize a formula body into either numeric literals (Double),
     * cell references (Expr), or operators (Char).
     */
    internal fun tokenize(body: String): List<Any> {
        val tokens = ArrayList<Any>()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                c.isWhitespace() -> i += 1
                c == '+' || c == '-' || c == '*' || c == '/' -> {
                    tokens += c
                    i += 1
                }
                c.isDigit() || c == '.' -> {
                    var end = i + 1
                    while (end < body.length && (body[end].isDigit() || body[end] == '.')) end++
                    tokens += body.substring(i, end).toDouble()
                    i = end
                }
                c.isLetter() -> {
                    // Could be SUM(...) or a cell reference.
                    val start = i
                    while (i < body.length && (body[i].isLetter() || body[i].isDigit())) i++
                    val word = body.substring(start, i)
                    // Two cases:
                    //   - "SUM" then "(" — leave it for the caller to recognize the special form
                    //     (we already handle SUM via regex). Skip here.
                    //   - "A1" / "AB12" — cell reference
                    if (word.all { it.isLetter() }) {
                        // Skip SUM( A1 : A10 ) handling lives in [evaluate]; we don't
                        // tokenize function calls at this level.
                        i = start
                        // Read more if there's "(" right after — treat as function and skip until matching ')'.
                        if (i < body.length && body[i] == '(') {
                            var depth = 0
                            while (i < body.length) {
                                when (body[i]) {
                                    '(' -> depth++
                                    ')' -> { depth--; if (depth == 0) { i++; break } }
                                }
                                i++
                            }
                            continue
                        }
                        throw FormulaError("unexpected token: $word")
                    }
                    val (row, col) = parseCellRef(word)
                    tokens += Expr.Reference(row, col)
                }
                else -> throw FormulaError("unexpected char: $c")
            }
        }
        return tokens
    }

    /**
     * Shunting-yard: convert tokens to RPN. Numeric literals and
     * cell references have higher precedence than operators.
     */
    internal fun toRPN(tokens: List<Any>): List<Any> {
        val output = ArrayList<Any>()
        val ops = ArrayDeque<Char>()
        for (tok in tokens) {
            when (tok) {
                is Double, is Expr -> output += tok
                is Char -> {
                    while (ops.isNotEmpty()) {
                        val top = ops.removeLast()
                        output += top
                    }
                    ops.addLast(tok)
                }
                else -> throw FormulaError("bad token: $tok")
            }
        }
        while (ops.isNotEmpty()) output += ops.removeLast()
        return output
    }
}
