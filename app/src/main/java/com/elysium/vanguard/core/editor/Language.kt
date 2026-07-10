package com.elysium.vanguard.core.editor

/**
 * PHASE 2.7 — Languages the editor knows how to highlight.
 *
 * We detect the language from the file extension. Detection is conservative — if
 * we don't recognize the extension, we fall back to [Language.PLAIN] and skip the
 * highlighter. That keeps the regex engine cheap and the render path predictable.
 *
 * Adding a new language is two things:
 *   1. Add the enum value here
 *   2. Add a branch in [SyntaxHighlighter.tokenize]
 *
 * No DI involved — this is a pure function table.
 */
enum class Language(val displayName: String, val extensions: Set<String>) {
    PLAIN("Plain", emptySet()),
    KOTLIN("Kotlin", setOf("kt", "kts")),
    JAVA("Java", setOf("java")),
    JAVASCRIPT("JavaScript", setOf("js", "mjs", "cjs")),
    TYPESCRIPT("TypeScript", setOf("ts", "tsx", "jsx")),
    PYTHON("Python", setOf("py", "pyw")),
    JSON("JSON", setOf("json")),
    XML("XML", setOf("xml")),
    HTML("HTML", setOf("html", "htm", "xhtml")),
    MARKDOWN("Markdown", setOf("md", "markdown")),
    SQL("SQL", setOf("sql")),
    BASH("Bash", setOf("sh", "bash", "zsh"));

    companion object {
        /** Look up the language for a file path; defaults to PLAIN on no match. */
        fun forFile(path: String): Language {
            val ext = path.substringAfterLast('.', "").lowercase()
            if (ext.isEmpty()) return PLAIN
            return entries.firstOrNull { ext in it.extensions } ?: PLAIN
        }
    }
}