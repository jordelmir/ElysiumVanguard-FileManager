package com.elysium.vanguard.core.runtime.distros

import java.io.File

/**
 * PHASE 9.6.3.3 — Reads a custom rootfs's `manifest.json` back into a
 * [Distro] object so it can appear alongside catalog entries in the
 * runtime screen.
 *
 * The manifest written by [CustomRootfsInstaller.writeManifest] only
 * stores primitive fields (strings + numbers); this parser is a
 * tiny, deliberate, hand-rolled JSON reader. Pulling in a real JSON
 * library for this single use would bloat the APK for no real win;
 * the format is also much simpler than what Gson/Codepath supports
 * round-trip.
 *
 * Limitations:
 *   - Only top-level string and number fields. We don't need objects,
 *     arrays, or nested values for the manifest.
 *   - The display name is recovered as-is; if the user installs the
 *     same URL twice the id collides — that's intentional: Phase
 *     9.6.3.3 collapses duplicate installs rather than allowing two
 *     copies of the same rootfs on disk.
 *
 * Phase 9.6.3.3 — first build; intentionally minimal.
 */
class CustomManifestParser {

    /**
     * Parse the [manifest] file into a [Distro] the same way the
     * catalog stores them. Returns null when [manifest] is missing,
     * unreadable, or doesn't contain the minimum required fields
     * (`distroId` and a non-empty `rootfsUrl`).
     */
    fun parse(manifest: File): Distro? {
        if (!manifest.isFile) return null
        val text = try {
            manifest.readText()
        } catch (_: Exception) {
            return null
        }
        return parseText(text)
    }

    /**
     * Pure-text variant. Used by tests and by [parse].
     *
     * The parser is line-oriented but also handles JSON-on-one-line
     * (Pretty-printed manifests split into multiple lines; compact
     * manifests from our installer land on a single line). We split
     * the whole document into key/value pairs first.
     */
    fun parseText(text: String): Distro? {
        val map = HashMap<String, String>()
        // Flatten one-line JSON by splitting on commas not inside quotes.
        val flat = flattenJsonPairs(text)
        for (line in flat) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val colon = trimmed.indexOf(':')
            if (colon <= 0) continue
            val key = trimmed.substring(0, colon).trim().trim('"')
            val rawValue = trimmed.substring(colon + 1).trim()
            val value = normalizeValue(rawValue.trimEnd(','))
            if (key.isNotEmpty() && key != "{") {
                map[key] = value
            }
        }
        val id = map["distroId"] ?: return null
        val url = map["rootfsUrl"] ?: return null
        if (id.isBlank() || url.isBlank()) return null
        val kindName = map["rootfsKind"] ?: "Custom"
        val rootfsKind = parseKind(kindName)
        val family = parseFamily(map["family"] ?: map["id"])
        val pkg = map["packageManager"] ?: "apt"
        val display = map["displayName"]?.takeIf { it.isNotBlank() } ?: id
        val version = map["version"]?.takeIf { it.isNotBlank() } ?: "custom"
        val approx = map["bytesWritten"]?.toLongOrNull() ?: 0L
        return Distro(
            id = id,
            displayName = display,
            family = family,
            version = version,
            approxSizeBytes = approx,
            minAndroidVersion = 26,
            rootfsUrl = url,
            rootfsKind = rootfsKind,
            bootstrapCommand = null,
            packageManager = pkg,
            homepage = map["homepage"] ?: url
        )
    }

    /**
     * Split a possibly-one-line JSON object into per-pair lines. We
     * honor quoted strings so commas inside strings don't split.
     */
    private fun flattenJsonPairs(text: String): List<String> {
        val out = ArrayList<String>()
        val buf = StringBuilder()
        var inQuotes = false
        // Normalize newlines into spaces so multi-line JSON behaves
        // like a single comma-delimited line for our splitter.
        val normalized = text.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
        // Skip a leading "{" if present.
        var start = 0
        while (start < normalized.length && normalized[start].isWhitespace()) start++
        if (start < normalized.length && normalized[start] == '{') start++
        var i = start
        while (i < normalized.length) {
            val c = normalized[i]
            if (c == '"' && (i == 0 || normalized[i - 1] != '\\')) {
                inQuotes = !inQuotes
                buf.append(c)
                i++
            } else if (c == ',' && !inQuotes) {
                if (buf.isNotBlank()) out += buf.toString().trim()
                buf.setLength(0)
                i++
            } else {
                buf.append(c)
                i++
            }
        }
        if (buf.isNotBlank()) out += buf.toString().trim()
        return out
    }

    private fun parseKind(name: String): RootfsKind {
        return when (name) {
            "TarGz", "Tgz" -> RootfsKind.TarGz
            "TarXz" -> RootfsKind.TarXz
            "Tar" -> RootfsKind.Custom
            "Custom" -> RootfsKind.Custom
            else -> RootfsKind.Custom
        }
    }

    /**
     * Best-effort family inference from the OS id we found at install
     * time, falling back to DEBIAN as the most permissive default.
     */
    private fun parseFamily(id: String?): DistroFamily {
        if (id == null) return DistroFamily.DEBIAN
        return when (id.lowercase()) {
            "alpine", "void" -> DistroFamily.MUSL
            "arch" -> DistroFamily.ARCH
            else -> DistroFamily.DEBIAN
        }
    }

    private fun stripWrappingQuotes(value: String): String {
        var v = value.trim()
        // Strip surrounding brackets that survived the pair-split:
        // braces, brackets, quotes. Loop because nested isn't possible
        // but we still want idempotence.
        for (chars in listOf("[]", "{}")) {
            if (v.length >= 2 && v.startsWith(chars[0]) && v.endsWith(chars[1])) {
                v = v.substring(1, v.length - 1)
            }
        }
        if (v.length >= 2 && ((v.startsWith('"') && v.endsWith('"')) ||
                (v.startsWith('\'') && v.endsWith('\'')))
        ) {
            v = v.substring(1, v.length - 1)
        }
        return v
    }

    /**
     * Best-effort cleanup of a JSON-value fragment: strips wrapping
     * quotes (always — leading AND trailing when present), trailing
     * braces/brackets, and any stray commas. The parser needs robust
     * handling of mismatched line terminators; this is where we
     * paper over them.
     */
    private fun normalizeValue(value: String): String {
        var v = value.trim()
        // Strip a trailing close-brace or close-bracket — that's the
        // tail end of the outermost JSON object surviving into a
        // value-side.
        while (v.endsWith(']') || v.endsWith('}')) {
            v = v.dropLast(1).trim()
        }
        // Strip wrapping quotes always, even if unbalanced (e.g.,
        // "arch" left dangling without a trailing quote because of a
        // trailing brace).
        if (v.startsWith('"')) v = v.substring(1).trim()
        if (v.endsWith('"')) v = v.dropLast(1).trim()
        return v
    }
}
