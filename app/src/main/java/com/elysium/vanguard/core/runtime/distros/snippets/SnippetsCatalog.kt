package com.elysium.vanguard.core.runtime.distros.snippets

import java.io.File

/**
 * PHASE 9.6.13 — A user-saved snippet (same shape as [BashSnippet]
 * but with the library field "user" so the merge with [BundledSnippetLibrary.ALL]
 * can distinguish the two sources later).
 */
data class UserSnippet(
    val id: String,
    val title: String,
    val category: String, // we keep it as String so custom categories work
    val body: String,
    val description: String? = null,
    val savedAtMs: Long = System.currentTimeMillis()
)

/**
 * PHASE 9.6.13 — Persistent storage for user snippets.
 *
 * Stores the list at `<baseDir>/snippets.json` in a hand-rolled JSON
 * shape that's friendly to a future export / share flow.
 *
 * Phase 9.6.13 — first build; intentionally minimal.
 */
class SnippetsCatalog(private val baseDir: File) {

    private val file: File get() = File(baseDir, "snippets.json")

    fun list(): List<UserSnippet> {
        if (!file.isFile) return emptyList()
        return parseSnippets(file.readText())
    }

    fun save(snippet: UserSnippet): SnippetsCatalog {
        val all = list().toMutableList()
        val idx = all.indexOfFirst { it.id == snippet.id }
        if (idx >= 0) all[idx] = snippet else all += snippet
        writeSnippets(all)
        return this
    }

    fun delete(id: String): SnippetsCatalog {
        val all = list().filterNot { it.id == id }
        writeSnippets(all)
        return this
    }

    private fun writeSnippets(snippets: List<UserSnippet>) {
        if (!baseDir.exists()) baseDir.mkdirs()
        val sb = StringBuilder()
        sb.append("{\"snippets\":[")
        for ((i, s) in snippets.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append('{')
            sb.append("\"id\":").append(jsonString(s.id)).append(',')
            sb.append("\"title\":").append(jsonString(s.title)).append(',')
            sb.append("\"category\":").append(jsonString(s.category)).append(',')
            sb.append("\"body\":").append(jsonString(s.body)).append(',')
            sb.append("\"description\":").append(jsonString(s.description)).append(',')
            sb.append("\"savedAtMs\":").append(s.savedAtMs)
            sb.append('}')
        }
        sb.append("]}")
        file.writeText(sb.toString())
    }

    private fun jsonString(value: String?): String {
        if (value == null) return "null"
        val escaped = value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    private fun parseSnippets(text: String): List<UserSnippet> {
        val key = "\"snippets\":["
        val idx = text.indexOf(key)
        if (idx < 0) return emptyList()
        val start = idx + key.length
        val end = findMatchingBracket(text, start)
        if (end < 0) return emptyList()
        // Walk comma-separated `{...}` objects in [start, end)
        val out = ArrayList<UserSnippet>()
        var i = start
        var depth = 0
        var currentStart = -1
        while (i < end) {
            when (text[i]) {
                '{' -> { if (depth == 0) currentStart = i; depth++ }
                '}' -> {
                    depth--
                    if (depth == 0 && currentStart >= 0) {
                        val obj = text.substring(currentStart, i + 1)
                        out += snippetFromObject(obj)
                        currentStart = -1
                    }
                }
                ',' -> {}
            }
            i++
        }
        return out
    }

    private fun findMatchingBracket(text: String, from: Int): Int {
        var depth = 0
        var i = from
        while (i < text.length) {
            when (text[i]) {
                '[' -> depth++
                ']' -> {
                    // Close only if we have an open bracket. If we are
                    // already at depth 0, this `]` is the one we want.
                    if (depth == 0) return i
                    depth--
                }
            }
            i++
        }
        return -1
    }

    private fun snippetFromObject(obj: String): UserSnippet {
        fun stringField(name: String): String? {
            val key = "\"$name\":"
            val idx = obj.indexOf(key)
            if (idx < 0) return null
            var i = idx + key.length
            // Skip whitespace.
            while (i < obj.length && obj[i].isWhitespace()) i++
            if (i >= obj.length) return null
            // Null literal.
            if (obj.startsWith("null", i)) return null
            if (obj[i] != '"') {
                // Numeric / unquoted value — read until terminator.
                var end = i
                while (end < obj.length && obj[end] !in charArrayOf(',', '}')) end++
                return obj.substring(i, end).trim()
            }
            // Quoted string — read until matching unescaped `"`.
            i++
            val sb = StringBuilder()
            while (i < obj.length) {
                val c = obj[i]
                if (c == '\\' && i + 1 < obj.length) {
                    when (val next = obj[i + 1]) {
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        else -> { sb.append(c); sb.append(next) }
                    }
                    i += 2
                } else if (c == '"') {
                    return sb.toString()
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }

        val id = stringField("id") ?: "anon-${System.nanoTime()}"
        val title = stringField("title") ?: id
        val category = stringField("category") ?: "shell"
        val body = stringField("body") ?: ""
        val description = stringField("description")
        val savedAtMs = stringField("savedAtMs")?.toLongOrNull() ?: System.currentTimeMillis()
        return UserSnippet(
            id = id,
            title = title,
            category = category,
            body = body,
            description = description,
            savedAtMs = savedAtMs
        )
    }
}
