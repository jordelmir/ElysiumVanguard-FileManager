package com.elysium.vanguard.core.editor

/**
 * PHASE 2.7 — Syntax-highlight token kinds.
 *
 * We intentionally keep the set small. The editor renders each token with a
 * foreground color from the active theme; everything we care about for code reading
 * fits into ~12 categories. Adding more (e.g. named HTML entity types) would just
 * make the highlighter harder to maintain without changing how a user reads code.
 */
enum class TokenKind {
    KEYWORD,         // language keywords (if, for, fun, def, …)
    BUILTIN,         // language builtins (println, len, Math, …)
    STRING,          // "double quoted", 'single quoted'
    NUMBER,          // 42, 3.14, 0xFF, 1_000
    COMMENT,         // // line, /* block */
    OPERATOR,        // + - * / = == != <= >=
    PUNCTUATION,     // ( ) { } [ ] , ;
    IDENTIFIER,      // variables, function names
    TYPE,            // capitalized types, class names
    FUNCTION,        // function-call sites (foo( …))
    ANNOTATION,      // @Override, @Composable
    HTML_TAG,        // <div>, </section>
    HTML_ATTRIBUTE,  // attribute= in HTML/XML
    HEADING,         // # Markdown heading
    EMPHASIS,        // *italic*, **bold** (markdown)
    CODE_FENCE,      // ``` fence (markdown)
    LINK,            // [text](url)
    WHITESPACE,      // rendered as the default body color
    UNKNOWN          // fallback for anything we don't classify
}

/**
 * A contiguous span of text classified as one [TokenKind].
 *
 * Indices are character offsets (not code units), matching what Compose's
 * `AnnotatedString.addStringAnnotation` consumes. Ranges are half-open: [start, end).
 */
data class Token(
    val kind: TokenKind,
    val start: Int,
    val end: Int
) {
    init {
        require(end >= start) { "Token end < start: $start..$end" }
    }
    val length: Int get() = end - start
}