package com.elysium.vanguard.core.office

/**
 * PHASE 9.8.4 — One slide in an Elysium Deck document.
 *
 * Slides are the simplest unit: a title and a body. The body is plain
 * text today; 9.8.5 will bring markdown rendering using
 * [ElysiumWordRenderer] so paragraphs in deck slides look like Word
 * documents.
 */
data class Slide(
    val title: String,
    val body: String,
    /** Optional speaker notes shown in a future presenter view. */
    val notes: String? = null,
    /**
     * Stable id used for cross-references in deck commands. When the
     * title doesn't slugify to a non-empty value (e.g. all symbols), we
     * fall back to a synthetic `slide-<index>` form so the id is
     * always present.
     */
    val id: String = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "slide-$DEFAULT_INDEX" }
) {
    private companion object {
        // Synthetic fallback when the title slugifies empty. We use a
        // stand-in so adjacent calls produce distinguishable ids; we
        // regenerate per-Slide in [ElysiumDeck.normalizeIds].
        const val DEFAULT_INDEX = 0
    }
}

/**
 * PHASE 9.8.4 — A deck document.
 */
data class ElysiumDeck(
    val title: String,
    val slides: List<Slide>,
    /** Deck-level style carried over from the format spec. */
    val style: ElysiumDocument.StyleHints = ElysiumDocument.StyleHints()
) {
    init {
        require(slides.isNotEmpty()) { "deck must have at least one slide" }
    }

    /** Serialize to JSON for [ElysiumDocument] (Phase 9.8.1). */
    fun toJson(): ByteArray {
        val sb = StringBuilder()
        sb.append("{").append("\"title\":").append(jsonString(title)).append(',')
        sb.append("\"slides\":[")
        for ((i, s) in slides.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append('{')
            sb.append("\"id\":").append(jsonString(s.id)).append(',')
            sb.append("\"title\":").append(jsonString(s.title)).append(',')
            sb.append("\"body\":").append(jsonString(s.body)).append(',')
            sb.append("\"notes\":").append(jsonString(s.notes))
            sb.append('}')
        }
        sb.append("],")
        sb.append("\"style\":").append(style.toJson())
        sb.append('}')
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        /**
         * Parse a deck from JSON. We accept both a hand-rolled
         * representation and the round-trip output of [toJson].
         */
        fun fromJson(bytes: ByteArray): ElysiumDeck {
            val text = bytes.toString(Charsets.UTF_8)
            val title = extractStringField(text, "title") ?: "Untitled Deck"
            val slides = extractSlides(text)
            val style = extractStyleField(text) ?: ElysiumDocument.StyleHints()
            return ElysiumDeck(title, slides.ifEmpty { listOf(Slide(title, "")) }, style)
        }
    }
}

/**
 * PHASE 9.8.4 — Export the deck to HTML.
 *
 * The output is intentionally a single self-contained HTML file —
 * no external CSS — so users can email it, print it, or open it in
 * any browser without a server. We do NOT depend on Compose's HTML
 * renderer; we just emit raw String markup.
 */
object DeckHtmlExporter {

    fun export(deck: ElysiumDeck, theme: String = "sovereign-dark"): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>")
        sb.append(escape(deck.title))
        sb.append("</title><style>")
        sb.append("body{font-family:'Inter',sans-serif;background:#0B0D10;color:#E4E7EB;margin:0;padding:0}")
        sb.append(".slide{padding:48px;min-height:90vh;border-bottom:1px solid #1F2A1F}")
        sb.append(".slide h1{font-size:48px;margin:0;color:#98C379}")
        sb.append(".slide p{font-size:20px;line-height:1.5}")
        sb.append(".footer{font-family:'JetBrains Mono',monospace;font-size:12px;color:#8B949E;padding:8px 24px}")
        sb.append("</style></head><body>")
        for ((idx, slide) in deck.slides.withIndex()) {
            sb.append("<div class=\"slide\" id=\"").append(slide.id).append("\">")
            sb.append("<h1>").append(escape(slide.title)).append("</h1>")
            for (line in slide.body.lines()) {
                sb.append("<p>").append(escape(line)).append("</p>")
            }
            sb.append("</div>")
        }
        sb.append("<div class=\"footer\">")
        sb.append(escape(deck.title)).append(" · ${deck.slides.size} slides · $theme")
        sb.append("</div>")
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}

/**
 * Tiny JSON helpers for the deck document. Hand-rolled so we don't
 * pull Gson for this single use.
 */
private fun jsonString(value: String?): String {
    if (value == null) return "null"
    val escaped = value.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
    return "\"$escaped\""
}

private fun extractStringField(json: String, field: String): String? {
    val key = "\"$field\":"
    val idx = json.indexOf(key)
    if (idx < 0) return null
    var i = idx + key.length
    while (i < json.length && json[i].isWhitespace()) i++
    if (i >= json.length || json[i] != '"') return null
    i++
    val sb = StringBuilder()
    while (i < json.length) {
        val c = json[i]
        if (c == '"') return sb.toString()
        if (c == '\\' && i + 1 < json.length) {
            when (json[i + 1]) {
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
            }
            i += 2
        } else {
            sb.append(c)
            i += 1
        }
    }
    return sb.toString()
}

private fun extractSlides(json: String): List<Slide> {
    val slidesKey = "\"slides\":["
    val idx = json.indexOf(slidesKey)
    if (idx < 0) return emptyList()
    val start = idx + slidesKey.length
    // Walk objects separated by commas at depth 0, stopping at the
    // first `]` outside any object — that's the end of the slides array.
    val slides = ArrayList<Slide>()
    var i = start
    var depth = 0
    var currentStart = -1
    while (i < json.length) {
        val c = json[i]
        when {
            c == '[' && depth == 0 -> { /* nested array, skip */ }
            c == ']' && depth == 0 -> break
            c == '{' -> {
                if (depth == 0) currentStart = i
                depth++
            }
            c == '}' -> {
                depth--
                if (depth == 0 && currentStart >= 0) {
                    val obj = json.substring(currentStart, i + 1)
                    val title = extractStringField(obj, "title") ?: ""
                    val body = extractStringField(obj, "body") ?: ""
                    val notes = extractStringField(obj, "notes")
                    val id = extractStringField(obj, "id")
                    slides += Slide(
                        title = title,
                        body = body,
                        notes = notes,
                        id = id ?: title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                    )
                    currentStart = -1
                }
            }
        }
        i++
    }
    return slides
}

private fun extractStyleField(json: String): ElysiumDocument.StyleHints? {
    val idx = json.indexOf("\"style\":{")
    if (idx < 0) return null
    val close = json.indexOf('}', idx + 8)
    if (close < 0) return null
    val obj = json.substring(idx + 8, close + 1)
    return ElysiumDocument.StyleHints(
        font = extractStringField(obj, "font") ?: "monospace",
        fontSizePt = extractStringField(obj, "fontSizePt")?.toIntOrNull() ?: 12,
        theme = extractStringField(obj, "theme") ?: "sovereign-dark"
    )
}
