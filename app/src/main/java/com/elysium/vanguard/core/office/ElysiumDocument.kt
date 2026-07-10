package com.elysium.vanguard.core.office

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * PHASE 9.8.1 — Elysium's native document format (`.elysium`).
 *
 * Three shapes today:
 *
 *   - `.elysium.word`  — body text as UTF-8 plaintext + a `style.json`
 *     companion descriptor (font, fontSize, etc.).
 *   - `.elysium.sheet` — `cells.csv` with comma-separated values +
 *     a `style.json` for column widths and styling hints.
 *   - `.elysium.deck`  — `slides.json` (Array of {title, body}) +
 *     a `style.json` companion.
 *
 * All three are ZIP files with a [common container format]:
 *
 *     /manifest.json   — { kind, version, appId }
 *     /style.json      — flavor-specific styling hints
 *     /<payload>       — body content (utf-8 text or csv or json)
 *
 * The format is open: round-trip with `unzip` and `zip` is supported,
 * so users can author documents in plain text and open them with
 * Elysium Word / Sheet / Deck.
 *
 * Phase 9.8.1 — first build; intentionally minimal.
 */
class ElysiumDocument(
    val kind: Kind,
    val style: StyleHints,
    val body: ByteArray
) {
    enum class Kind(val extension: String) {
        WORD(".elysium.word"),
        SHEET(".elysium.sheet"),
        DECK(".elysium.deck")
    }

    data class StyleHints(
        val font: String = "monospace",
        val fontSizePt: Int = 12,
        val theme: String = "sovereign-dark",
        val extras: Map<String, String> = emptyMap()
    ) {
        fun toJson(): String = buildString {
            append("{\"font\":\"").append(font).append("\",")
            append("\"fontSizePt\":").append(fontSizePt).append(',')
            append("\"theme\":\"").append(theme).append("\",")
            if (extras.isNotEmpty()) {
                append("\"extras\":{")
                append(extras.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" })
                append("}")
            }
            append("}")
        }
    }

    /**
     * Serialize to bytes (a ZIP file). Round-trip safe.
     */
    fun toBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(
                """{"kind":"${kind.extension}","version":1,"appId":"elysium-vanguard"}""".toByteArray()
            )
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("style.json"))
            zos.write(style.toJson().toByteArray())
            zos.closeEntry()

            val payloadEntry = when (kind) {
                Kind.WORD -> "body.txt"
                Kind.SHEET -> "cells.csv"
                Kind.DECK -> "slides.json"
            }
            zos.putNextEntry(ZipEntry(payloadEntry))
            zos.write(body)
            zos.closeEntry()
        }
        return out.toByteArray()
    }

    /**
     * Persist to a file on disk. Overwrites any existing file.
     */
    fun writeTo(file: File) {
        file.writeBytes(toBytes())
    }

    companion object {
        /**
         * Read a `.elysium.*` from bytes (round-trip with [toBytes]).
         */
        fun fromBytes(bytes: ByteArray): ElysiumDocument {
            var manifestJson: String? = null
            var styleJson: String? = null
            var bodyBytes: ByteArray? = null
            var bodyEntryName = "body.txt"

            ZipInputStream(bytes.inputStream()).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val entryName = entry.name
                    val payload = zis.readBytes()
                    when (entryName) {
                        "manifest.json" -> manifestJson = String(payload, Charsets.UTF_8)
                        "style.json" -> styleJson = String(payload, Charsets.UTF_8)
                        "body.txt", "cells.csv", "slides.json" -> {
                            bodyBytes = payload
                            bodyEntryName = entryName
                        }
                    }
                    entry = zis.nextEntry
                }
            }

            val manifest = parseManifest(manifestJson ?: error("missing manifest.json"))
            val style = parseStyle(styleJson)
            val body = bodyBytes ?: error("missing body payload")
            return ElysiumDocument(
                kind = manifest.kind,
                style = style,
                body = body
            )
        }

        data class Manifest(val kind: Kind, val version: Int, val appId: String)

        private fun parseManifest(text: String): Manifest {
            val kindName = text.substringAfter("\"kind\":\"").substringBefore('"')
            val version = text.substringAfter("\"version\":").substringBefore(',').toIntOrNull() ?: 1
            val appId = text.substringAfter("\"appId\":\"").substringBefore('"')
            val kind = Kind.values().firstOrNull { it.extension == kindName }
                ?: error("unknown kind $kindName")
            return Manifest(kind, version, appId)
        }

        private fun parseStyle(text: String?): StyleHints {
            if (text == null) return StyleHints()
            val font = text.substringAfter("\"font\":\"").substringBefore('"')
            val fontSize = text.substringAfter("\"fontSizePt\":").substringBefore(',').toIntOrNull() ?: 12
            val theme = text.substringAfter("\"theme\":\"").substringBefore('"')
            return StyleHints(font = font, fontSizePt = fontSize, theme = theme)
        }
    }
}
