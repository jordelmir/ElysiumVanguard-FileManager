package com.elysium.vanguard.core.runtime.distros.introspector

import java.io.File

/**
 * PHASE 9.6.3.1 — Lightweight file/object metadata for a single entry
 * inside a rootfs.
 *
 * The introspector walks the on-disk rootfs (already extracted by
 * 9.6.2's installer), producing one row per directory entry. We do NOT
 * exec anything inside the distro — the point of 9.6.3.1 is to give the
 * user a real view of what's there without booting a shell.
 *
 * Phase 9.6.3.1 — first build; intentionally minimal.
 */
data class RootfsEntry(
    /** Absolute path under the rootfs (e.g. "etc/os-release"). */
    val relativePath: String,
    /** True for directories, false for regular files and symlinks. */
    val isDirectory: Boolean,
    /** File size in bytes; 0 for directories. */
    val sizeBytes: Long,
    /** Last modified in epoch millis. */
    val lastModifiedMs: Long,
    /** True when the underlying file resolves to a symlink. */
    val isSymlink: Boolean
) {
    /** Convenience accessor used by the UI; lets us render `bin/` etc. as a folder. */
    val displayName: String get() = relativePath.substringAfterLast('/').ifEmpty { "/" }
}

/**
 * PHASE 9.6.3.1 — Subset of `/etc/os-release` parsed from the rootfs.
 *
 * Some distros omit fields; absent fields surface as nulls. We don't
 * fail the introspector when the file is missing; the UI shows a
 * "no os-release" badge and falls back to distro catalog metadata.
 *
 * Phase 9.6.3.1 — first build; intentionally minimal.
 */
data class OsRelease(
    val name: String?,
    val version: String?,
    val versionId: String?,
    val id: String?,
    val prettyName: String?,
    val homeUrl: String?
) {
    companion object {
        val UNKNOWN = OsRelease(
            name = null,
            version = null,
            versionId = null,
            id = null,
            prettyName = null,
            homeUrl = null
        )
    }
}

/**
 * PHASE 9.6.3.1 — One installed package, as reported by the package
 * manager's native database.
 *
 * The introspector reads:
 *   - Debian/Ubuntu family: `/var/lib/dpkg/status`
 *   - Alpine:              `/lib/apk/db/installed`
 *   - Arch:                `/var/lib/pacman/local/`
 *
 * The schema here is the common subset; distro-specific extras are kept
 * raw in [rawLine] for future phases to mine.
 *
 * Phase 9.6.3.1 — first build; intentionally minimal.
 */
data class InstalledPackage(
    /** Package name (e.g. "python3", "apk-tools"). */
    val name: String,
    /** Version string. May be null when the listing doesn't include it. */
    val version: String?,
    /** Free-form description pulled from the package metadata. */
    val description: String?,
    /** Original raw line from the package db. */
    val rawLine: String
)
