package com.elysium.vanguard.core.runtime.distros

import java.io.File
import java.io.IOException

/**
 * Phase 12.1 — install the Elysium Vanguard Linux identity overlay
 * into a freshly extracted rootfs.
 *
 * Master order §11 calls the runtime distribution "Elysium Vanguard
 * Linux". ADR-003 records the decision to ship this as an
 * `os-release` overlay on top of the upstream distro (Debian /
 * Ubuntu / Alpine / Arch) rather than a from-scratch distribution.
 *
 * The overlay writes four artifacts inside the rootfs:
 *
 *   - `/etc/os-release.d/elysium.conf` — the `ID=elysium`,
 *     `ID_LIKE=debian`, `PRETTY_NAME="Elysium Vanguard Linux"`
 *     markers. `systemd` and most distro-detect tools concatenate
 *     files in this directory, so the upstream `/etc/os-release`
 *     survives `apt` upgrades.
 *   - `/etc/elysium/VERSION` — the platform version
 *     (e.g. `1.0.0-TITAN+12.1`).
 *   - `/etc/elysium/BASE_DISTRO` — the upstream distro identifier
 *     (e.g. `debian-stable-13`).
 *   - `/etc/elysium/CHANNEL` — the release channel
 *     (`stable` / `beta` / `nightly`).
 *
 * The overlay is **idempotent**: re-running it overwrites the four
 * files in place. There is no "if missing, write" branching; the
 * installer calls this exactly once per install, and future updates
 * call it again with the new version.
 *
 * The overlay is **reversible**: [remove] deletes the four
 * artifacts (best-effort; missing files are not an error). The
 * upstream `/etc/os-release` is untouched, so removing the overlay
 * returns the guest to its vanilla state.
 */
class ElysiumOsReleaseOverlay(
    private val elysiumVersion: String,
    private val baseDistro: String,
    private val channel: Channel
) {
    enum class Channel(val id: String) {
        STABLE("stable"),
        BETA("beta"),
        NIGHTLY("nightly")
    }

    /**
     * Apply the overlay to [rootfsDir]. Returns the four files
     * that were written (or overwritten).
     *
     * Throws [IOException] if any of the writes fail. The caller
     * is expected to be inside the installer's `try` block — a
     * failed overlay leaves the rootfs in a partial state that
     * the installer's `stagingDir.deleteRecursively()` recovers.
     */
    fun apply(rootfsDir: File): AppliedOverlay {
        require(rootfsDir.isDirectory) { "rootfsDir is not a directory: $rootfsDir" }
        val osReleaseFile = writeOsReleaseOverlay(rootfsDir)
        val versionFile = writeElysiumMetadata(rootfsDir, "VERSION", elysiumVersion)
        val baseFile = writeElysiumMetadata(rootfsDir, "BASE_DISTRO", baseDistro)
        val channelFile = writeElysiumMetadata(rootfsDir, "CHANNEL", channel.id)
        return AppliedOverlay(
            osRelease = osReleaseFile,
            version = versionFile,
            baseDistro = baseFile,
            channel = channelFile
        )
    }

    /**
     * Remove the overlay from [rootfsDir]. Best-effort: missing
     * files are not an error. Used for rollback paths and for
     * users who explicitly opt out of the Elysium identity.
     */
    fun remove(rootfsDir: File) {
        File(rootfsDir, "etc/os-release.d/elysium.conf").delete()
        File(rootfsDir, "etc/elysium/VERSION").delete()
        File(rootfsDir, "etc/elysium/BASE_DISTRO").delete()
        File(rootfsDir, "etc/elysium/CHANNEL").delete()
    }

    private fun writeOsReleaseOverlay(rootfsDir: File): File {
        val target = File(rootfsDir, "etc/os-release.d/elysium.conf")
        target.parentFile?.mkdirs()
        target.writeText(
            """
            # Elysium Vanguard Linux overlay
            # ADR-003 — installed by Elysium Vanguard at provisioning time.
            # Lives in /etc/os-release.d/ so it survives upstream /etc/os-release
            # rewrites by apt / dnf / apk / pacman.
            NAME="Elysium Vanguard Linux"
            ID=elysium
            ID_LIKE=debian
            PRETTY_NAME="Elysium Vanguard Linux"
            VARIANT="Android Runtime Edition"
            HOME_URL="https://elysium.vanguard/"
            SUPPORT_URL="https://elysium.vanguard/support"
            BUG_REPORT_URL="https://elysium.vanguard/issues"
            ELYSIUM_VERSION=$elysiumVersion
            ELYSIUM_BASE=$baseDistro
            ELYSIUM_CHANNEL=${channel.id}

            """.trimIndent() + "\n"
        )
        return target
    }

    private fun writeElysiumMetadata(rootfsDir: File, name: String, value: String): File {
        val target = File(rootfsDir, "etc/elysium/$name")
        target.parentFile?.mkdirs()
        target.writeText(value + "\n")
        return target
    }

    /** The set of files that [apply] created or replaced. */
    data class AppliedOverlay(
        val osRelease: File,
        val version: File,
        val baseDistro: File,
        val channel: File
    )
}
