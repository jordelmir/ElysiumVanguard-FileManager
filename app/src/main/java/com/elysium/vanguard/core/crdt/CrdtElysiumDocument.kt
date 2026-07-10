package com.elysium.vanguard.core.crdt

import com.elysium.vanguard.core.office.ElysiumDocument
import com.elysium.vanguard.core.office.ElysiumDeck
import com.elysium.vanguard.core.office.ElysiumSheet
import com.elysium.vanguard.core.office.ElysiumWordRenderer
import com.elysium.vanguard.core.office.FormulaEvaluator
import com.elysium.vanguard.core.office.CsvParser

/**
 * PHASE 9.9.7 — A CRDT-backed Elysium document.
 *
 * Wraps [ElysiumDocument] so the document's metadata (title,
 * author, theme, font, fontSize) lives in a [CrdtDoc] and the
 * body lives in a [CrdtSequence]. The wrapper exposes:
 *
 *   - [toElysiumDocument] — render the CRDT state back into a
 *     plain [ElysiumDocument] for save-to-disk or wire-up with
 *     the existing [ElysiumDocumentViewer].
 *   - [merge] — combine two CrdtElysiumDocuments into one,
 *     preserving all metadata edits and body edits on both sides.
 *
 * Phase 9.9.7 — first build; intentionally minimal.
 */
class CrdtElysiumDocument(
    val kind: ElysiumDocument.Kind,
    val metadata: CrdtDoc,
    val body: CrdtSequence,
    val nodeId: String
) {

    /**
     * Render the CRDT state back into a plain [ElysiumDocument].
     */
    fun toElysiumDocument(): ElysiumDocument {
        val title = metadata.get("title") ?: ""
        val author = metadata.get("author") ?: ""
        val theme = metadata.get("theme") ?: "sovereign-dark"
        val font = metadata.get("font") ?: "monospace"
        val fontSize = metadata.get("fontSizePt")?.toIntOrNull() ?: 12
        // Merge title+author into a single "byline" line at the
        // top of the body so the existing rendering layer doesn't
        // need to know about metadata. We use markdown-ish
        // heading: "# title" then "by author" then a blank line.
        val header = buildList {
            if (title.isNotEmpty()) add("# $title")
            if (author.isNotEmpty()) add("_by ${author}_")
            if (isNotEmpty()) add("")
        }.joinToString(separator = "\n")
        val bodyText = body.value().joinToString(separator = "")
        val renderedBody = if (header.isEmpty()) bodyText else "$header\n$bodyText"
        return ElysiumDocument(
            kind = kind,
            style = ElysiumDocument.StyleHints(
                font = font,
                fontSizePt = fontSize,
                theme = theme
            ),
            body = renderedBody.toByteArray(Charsets.UTF_8)
        )
    }

    /**
     * Merge another [CrdtElysiumDocument] into this one. The merge
     * is symmetric in shape: both sides' metadata and body edits
     * survive. Returns `this` (now containing the union).
     */
    fun merge(other: CrdtElysiumDocument): CrdtElysiumDocument {
        check(kind == other.kind) {
            "cannot merge different document kinds: $kind vs ${other.kind}"
        }
        metadata.merge(other.metadata)
        body.merge(other.body)
        return this
    }

    companion object {
        /**
         * Wrap an existing [ElysiumDocument] into a CRDT-backed
         * one. The metadata fields (title, author, theme, font,
         * fontSize) are extracted from the body and/or style and
         * seeded into a fresh [CrdtDoc].
         *
         * If [clock] is provided, it's used for issuing HLCs so
         * the caller can continue a clock state across multiple
         * operations. Otherwise a fresh clock is created.
         */
        fun fromElysiumDocument(
            doc: ElysiumDocument,
            nodeId: String,
            clock: HlcClock? = null
        ): CrdtElysiumDocument {
            val localClock = clock ?: HlcClock(nodeId)
            val metadata = CrdtDoc()
            val body = CrdtSequence()
            val bodyText = doc.body.toString(Charsets.UTF_8)
            // Try to extract a "# title" header from the body.
            val lines = bodyText.split('\n')
            var startLine = 0
            if (lines.isNotEmpty() && lines[0].startsWith("# ")) {
                metadata.apply(
                    CrdtOp.SetProperty(localClock.issue(), "title", lines[0].removePrefix("# "))
                )
                startLine = 1
            }
            // Optional "_by author_" line.
            if (lines.size > startLine && lines[startLine].startsWith("_by ") &&
                lines[startLine].endsWith("_")
            ) {
                val author = lines[startLine].removePrefix("_by ").removeSuffix("_")
                metadata.apply(CrdtOp.SetProperty(localClock.issue(), "author", author))
                startLine++
            }
            // Skip the blank separator line, if any.
            if (lines.size > startLine && lines[startLine].isBlank()) {
                startLine++
            }
            // Whatever remains is the body.
            val remainingBody = lines.drop(startLine).joinToString(separator = "\n")
            metadata.apply(CrdtOp.SetProperty(localClock.issue(), "theme", doc.style.theme))
            metadata.apply(CrdtOp.SetProperty(localClock.issue(), "font", doc.style.font))
            metadata.apply(CrdtOp.SetProperty(localClock.issue(), "fontSizePt", doc.style.fontSizePt.toString()))
            // Insert the body character-by-character so each
            // character has its own HLC; this gives the finest
            // granularity for collaborative edits.
            for (ch in remainingBody) {
                body.apply(CrdtSeqOp.Insert(localClock.issue(), ch.toString()))
            }
            return CrdtElysiumDocument(
                kind = doc.kind,
                metadata = metadata,
                body = body,
                nodeId = nodeId
            )
        }
    }
}