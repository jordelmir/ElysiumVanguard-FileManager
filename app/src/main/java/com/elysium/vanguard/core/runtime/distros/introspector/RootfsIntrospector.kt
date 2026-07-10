package com.elysium.vanguard.core.runtime.distros.introspector

import com.elysium.vanguard.core.runtime.distros.DistroFamily
import java.io.File
import java.nio.file.Files as NioFiles

/**
 * PHASE 9.6.3.1 — Reads facts about an installed rootfs without booting
 * it.
 *
 * What we extract:
 *   - Directory entries up to a configurable depth (default 3).
 *   - The standard `/etc/os-release` file (pretty name, version, id).
 *   - The package manager's database (dpkg / apk / pacman).
 *
 * Why this matters: Phase 9.6.3 brings a process launcher that can land
 * the user inside a distro. Before they tap "Open" they want to know
 * "what is in here?". Bootstrapping apt/apk/pacman takes network + a
 * working ELF environment we don't have yet (9.6.3.1 proot), but the
 * `dpkg/status` text file is plain text — we can parse it in Kotlin
 * without ever running a binary.
 *
 * The introspector is also the building block for 9.6.3.2 snapshot
 * listings and 9.6.3.3 "diff two rootfs" comparisons.
 *
 * Phase 9.6.3.1 — first build; intentionally minimal.
 */
class RootfsIntrospector(
    /** The actual on-disk rootfs directory (`<baseDir>/<id>/rootfs/`). */
    private val rootfsDir: File
) {
    /**
     * Walk [rootfsDir] up to [maxDepth] levels deep. The result is a
     * flat list (no recursion in the data structure) that the UI can
     * group by parent in one pass.
     */
    fun entries(maxDepth: Int = 3): List<RootfsEntry> {
        require(rootfsDir.isDirectory) { "rootfsDir is not a directory: $rootfsDir" }
        require(maxDepth >= 1) { "maxDepth must be >= 1" }
        val out = ArrayList<RootfsEntry>()
        walk(rootfsDir, depth = 0, maxDepth = maxDepth, out = out)
        return out
    }

    /**
     * Parse [rootfsDir]/etc/os-release. Returns [OsRelease.UNKNOWN]
     * when the file is missing or malformed; introspector callers can
     * treat unknown as "we don't know much about this distro yet".
     */
    fun osRelease(): OsRelease {
        val file = File(rootfsDir, "etc/os-release")
        if (!file.isFile) return OsRelease.UNKNOWN
        val map = HashMap<String, String>()
        try {
            file.useLines { lines ->
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                    val eq = trimmed.indexOf('=')
                    if (eq <= 0) continue
                    val key = trimmed.substring(0, eq).trim()
                    val rawValue = trimmed.substring(eq + 1).trim()
                    val value = unquote(rawValue)
                    map[key] = value
                }
            }
        } catch (_: Exception) {
            return OsRelease.UNKNOWN
        }
        return OsRelease(
            name = map["NAME"],
            version = map["VERSION"],
            versionId = map["VERSION_ID"],
            id = map["ID"],
            prettyName = map["PRETTY_NAME"],
            homeUrl = map["HOME_URL"]
        )
    }

    /**
     * Best-effort enumeration of installed packages. The introspection
     * strategy depends on the distro family, which we infer from a
     * matching catalog entry or from a marker file inside the rootfs.
     */
    fun installedPackages(): List<InstalledPackage> {
        val family = inferFamily()
        return when (family) {
            DistroFamily.DEBIAN -> readDpkgStatus()
            DistroFamily.MUSL -> readApkInstalled()
            DistroFamily.ARCH -> readPacmanLocal()
            null -> emptyList()
        }
    }

    /**
     * Resolve the distro family from the catalog when possible; falls
     * back to OS markers for custom rootfs that didn't come from the
     * catalog.
     */
    private fun inferFamily(): DistroFamily? {
        // We don't know the distro id here (this class is given only
        // a File); we probe the rootfs for telltale files.
        return when {
            File(rootfsDir, "var/lib/dpkg/status").isFile -> DistroFamily.DEBIAN
            File(rootfsDir, "lib/apk/db/installed").isFile -> DistroFamily.MUSL
            File(rootfsDir, "var/lib/pacman/local").isDirectory -> DistroFamily.ARCH
            else -> null
        }
    }

    /**
     * Read the Debian package status file. The format is RFC 822-ish:
     *
     *   Package: python3
     *   Version: 3.11.2-1
     *   Description: ...
     *
     * Each stanza is blank-line separated; we split on blank lines and
     * parse each into an [InstalledPackage].
     */
    internal fun readDpkgStatus(): List<InstalledPackage> {
        val file = File(rootfsDir, "var/lib/dpkg/status")
        if (!file.isFile) return emptyList()
        val text = try {
            file.readText()
        } catch (_: Exception) {
            return emptyList()
        }
        val out = ArrayList<InstalledPackage>()
        for (stanza in text.split("\n\n")) {
            val pkg = parseDpkgStanza(stanza) ?: continue
            out += pkg
        }
        return out
    }

    private fun parseDpkgStanza(stanza: String): InstalledPackage? {
        if (stanza.isBlank()) return null
        var name: String? = null
        var version: String? = null
        var description: String? = null
        for (line in stanza.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val colon = trimmed.indexOf(':')
            if (colon <= 0) continue
            val key = trimmed.substring(0, colon).trim()
            val value = trimmed.substring(colon + 1).trim()
            when (key) {
                "Package" -> name = value
                "Version" -> version = value
                "Description" -> {
                    description = if (description == null) value else description + "; " + value
                }
            }
        }
        val n = name ?: return null
        return InstalledPackage(
            name = n,
            version = version,
            description = description,
            rawLine = stanza.lineSequence().firstOrNull() ?: ""
        )
    }

    /**
     * Read Alpine's installed APK database. The file uses
     *
     *   P:musl
     *   V:1.2.4-r0
     *   o:...
     *   m:...
     *   t:...
     *   c:...
     *   D:...
     *   p:...
     *
     * Stanzas separated by blank lines. We only care about P, V, and C
     * for the basic listing.
     */
    internal fun readApkInstalled(): List<InstalledPackage> {
        val file = File(rootfsDir, "lib/apk/db/installed")
        if (!file.isFile) return emptyList()
        val text = try {
            file.readText()
        } catch (_: Exception) {
            return emptyList()
        }
        val out = ArrayList<InstalledPackage>()
        for (stanza in text.split("\n\n")) {
            val pkg = parseApkStanza(stanza) ?: continue
            out += pkg
        }
        return out
    }

    private fun parseApkStanza(stanza: String): InstalledPackage? {
        if (stanza.isBlank()) return null
        var name: String? = null
        var version: String? = null
        var description: String? = null
        for (line in stanza.lineSequence()) {
            if (line.length < 2) continue
            val key = line[0]
            val value = line.substring(2).trim()
            when (key) {
                'P' -> name = value
                'V' -> version = value
                'c' -> description = value
            }
        }
        val n = name ?: return null
        return InstalledPackage(
            name = n,
            version = version,
            description = description,
            rawLine = stanza.lineSequence().firstOrNull() ?: ""
        )
    }

    /**
     * Read Arch's package database. Each package lives in
     * `var/lib/pacman/local/<name>-<version>/desc`. We only need the
     * `desc` file with `%NAME%`, `%VERSION%`, `%DESC%`.
     */
    internal fun readPacmanLocal(): List<InstalledPackage> {
        val dir = File(rootfsDir, "var/lib/pacman/local")
        if (!dir.isDirectory) return emptyList()
        val out = ArrayList<InstalledPackage>()
        dir.listFiles()?.forEach { pkgDir ->
            val desc = File(pkgDir, "desc")
            if (!desc.isFile) return@forEach
            val text = try {
                desc.readText()
            } catch (_: Exception) {
                return@forEach
            }
            val map = parseArchDesc(text)
            val name = map["NAME"] ?: return@forEach
            val version = map["VERSION"]
            val description = map["DESC"]
            out += InstalledPackage(
                name = name,
                version = version,
                description = description,
                rawLine = "Arch package: $name $version"
            )
        }
        return out.sortedBy { it.name.lowercase() }
    }

    /**
     * Parse an Arch `desc` file. Sections are `%NAME%`, `%VERSION%`, etc.
     * separated by blank lines.
     */
    private fun parseArchDesc(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        var currentKey: String? = null
        for (line in text.lineSequence()) {
            if (line.startsWith("%") && line.endsWith("%") && line.length > 2) {
                val candidate = line.substring(1, line.length - 1)
                // Some `desc` files have `%INSTALL DATE%` etc.; we only
                // care about short, single-word keys.
                if (candidate.isNotBlank() && !candidate.contains(' ')) {
                    currentKey = candidate
                    out[currentKey] = ""
                } else {
                    currentKey = null
                }
                continue
            }
            if (currentKey == null) continue
            val trimmed = line.trim()
            // Blank lines inside a section are just section separators,
            // not content. Skip them instead of polluting the value.
            if (trimmed.isEmpty()) continue
            val existing = out[currentKey]
            out[currentKey] = if (existing.isNullOrEmpty()) trimmed else existing + "\n" + trimmed
        }
        return out
    }

    private fun walk(
        node: File,
        depth: Int,
        maxDepth: Int,
        out: MutableList<RootfsEntry>
    ) {
        val children = node.listFiles() ?: return
        for (child in children) {
            val entry = if (NioFiles.isSymbolicLink(child.toPath())) {
                RootfsEntry(
                    relativePath = relativePath(child),
                    isDirectory = child.isDirectory,
                    sizeBytes = 0L,
                    lastModifiedMs = child.lastModified(),
                    isSymlink = true
                )
            } else {
                RootfsEntry(
                    relativePath = relativePath(child),
                    isDirectory = child.isDirectory,
                    sizeBytes = if (child.isFile) child.length() else 0L,
                    lastModifiedMs = child.lastModified(),
                    isSymlink = false
                )
            }
            out += entry
            if (entry.isDirectory && depth + 1 < maxDepth) {
                walk(child, depth + 1, maxDepth, out)
            }
        }
    }

    private fun relativePath(file: File): String {
        val abs = file.absolutePath
        val root = rootfsDir.absolutePath
        return if (abs.length > root.length) abs.substring(root.length + 1) else ""
    }

    private fun unquote(s: String): String {
        if (s.length < 2) return s
        return if ((s.startsWith('"') && s.endsWith('"')) ||
            (s.startsWith('\'') && s.endsWith('\''))
        ) s.substring(1, s.length - 1) else s
    }
}
