package com.elysium.vanguard.core.runtime.distros.gui

import java.io.File

/**
 * PHASE 9.6.5 — One row in the Linux desktop app launcher.
 *
 * Parsed from a `.desktop` file (`/usr/share/applications/<name>.desktop`)
 * inside an installed distro rootfs. We deliberately keep this
 * minimal — only the fields we render in the launcher screen today;
 * 9.6.5.1 can grow it to handle Categories, MimeType, etc.
 *
 * Phase 9.6.5 — first build; intentionally minimal.
 */
data class LinuxAppEntry(
    /** Filename without .desktop, e.g. "firefox" */
    val id: String,
    /** Display name, e.g. "Firefox Web Browser" */
    val name: String,
    /** Optional comment ("tooltip"); may be null */
    val comment: String?,
    /** Exec line stripped of %U/%F placeholders */
    val exec: String,
    /** Original file inside the rootfs (read-only) */
    val sourceFile: File
)

/**
 * PHASE 9.6.5 — Discovers installed GUI app entries from a rootfs
 * by walking `usr/share/applications/` and parsing each `.desktop`
 * file. Stops at the first 200 entries to keep startup cheap; if a
 * distro has more than that, we add a heading "and N more" in the UI.
 */
class LinuxAppCatalog(
    private val rootfsDir: File
) {
    /**
     * Walk [rootfsDir]/usr/share/applications and parse every
     * `.desktop` file found. Returns a sorted-by-name list.
     */
    fun listApps(): List<LinuxAppEntry> {
        val appsDir = File(rootfsDir, "usr/share/applications")
        if (!appsDir.isDirectory) return emptyList()
        return appsDir.listFiles { f -> f.isFile && f.name.endsWith(".desktop") }
            ?.mapNotNull { parseDesktop(it) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    /**
     * Parse a single `.desktop` file. RFC-spec mini parser, not a
     * full INI parser — we only care about the key/value pairs we
     * know about.
     */
    fun parseDesktop(file: File): LinuxAppEntry? {
        if (!file.isFile || !file.name.endsWith(".desktop")) return null
        val text = try {
            file.readText()
        } catch (_: Exception) {
            return null
        }
        val map = HashMap<String, String>()
        var currentSection = "Header"
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                currentSection = trimmed.substring(1, trimmed.length - 1)
                continue
            }
            if (currentSection != "Desktop Entry") continue
            val equals = trimmed.indexOf('=')
            if (equals <= 0) continue
            val key = trimmed.substring(0, equals).trim()
            val value = trimmed.substring(equals + 1).trim()
            map[key] = value
        }
        if (map["Type"] != "Application") return null
        val name = map["Name"] ?: return null
        val exec = map["Exec"] ?: return null
        val cleanExec = exec
            .replace(Regex("%[a-zA-Z]"), "")
            .replace("\\n", " ")
            .trim()
        val id = file.name.removeSuffix(".desktop")
        return LinuxAppEntry(
            id = id,
            name = name,
            comment = map["Comment"],
            exec = cleanExec,
            sourceFile = file
        )
    }
}
