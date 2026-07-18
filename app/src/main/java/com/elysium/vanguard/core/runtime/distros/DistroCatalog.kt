package com.elysium.vanguard.core.runtime.distros

/**
 * PHASE 9.6.2 — Catalog of installable Linux distributions.
 *
 * The catalog is hand-curated for 9.6.2: each entry points to an official
 * tarball (rootfs) that we extract into the app's private storage. We do
 * NOT bundle any rootfs in the APK — every distro is downloaded from its
 * official mirror on first install. This matches Termux's proot-distro
 * model and our "we don't bloat the APK" stance from 2026-07-09.
 *
 * Mirrors used:
 *  - Debian: deb.debian.org/debian (the global CDN)
 *  - Ubuntu: cdimage.ubuntu.com/ubuntu-base (image-only)
 *  - Alpine: dl-cdn.alpinelinux.org/alpine (rootfs tarball)
 *  - Arch: archlinuxarm.org (mirror via os.archlinuxarm.org)
 *  - Fedora: dl.fedoraproject.org/pub/fedora/linux/releases/.../Docker
 *  - Kali: cdn.kali.org (offensive-security build roots)
 *  - openSUSE: download.opensuse.org/distribution/leap/.../images
 *
 * Sizes are approximate (rootfs after extraction). They're honest ranges
 * the user can use to decide before downloading.
 *
 * Phase 9.6.2 — first build; intentionally minimal.
 */
object DistroCatalog {

    /**
     * `const val` must be visible before any reference; it lives here,
     * before `ALL`, because `const` is inlined at compile time. Constants
     * also can't reference non-const expressions, hence the literal
     * `1024 * 1024` rather than `1 shl 20`.
     */
    private const val MB: Long = 1024L * 1024L

    val ALL: List<Distro> = listOf(
        Distro(
            id = "debian-stable",
            displayName = "Debian Stable",
            family = DistroFamily.DEBIAN,
            version = "13 (Trixie)",
            approxSizeBytes = 380L * MB,
            minAndroidVersion = 26,
            rootfsUrl = "https://github.com/termux/proot-distro/releases/download/v4.29.0/debian-trixie-aarch64-pd-v4.29.0.tar.xz",
            rootfsKind = RootfsKind.TarXz,
            bootstrapCommand = null,
            packageManager = "apt",
            homepage = "https://www.debian.org/",
            sha256 = "3834a11cbc6496935760bdc20cca7e2c25724d0cd8f5e4926da8fd5ca1857918",
            stripComponents = 1
        ),
        Distro(
            id = "ubuntu-noble",
            displayName = "Ubuntu 24.04 LTS",
            family = DistroFamily.DEBIAN,
            version = "24.04 (Noble)",
            approxSizeBytes = 420L * MB,
            minAndroidVersion = 26,
            rootfsUrl = "https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            rootfsKind = RootfsKind.TarGz,
            bootstrapCommand = null,
            packageManager = "apt",
            homepage = "https://ubuntu.com/",
            sha256 = "04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2"
        ),
        Distro(
            id = "alpine-latest",
            displayName = "Alpine Linux",
            family = DistroFamily.MUSL,
            version = "3.24.0",
            approxSizeBytes = 60L * MB,
            minAndroidVersion = 26,
            rootfsUrl = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.0-aarch64.tar.gz",
            rootfsKind = RootfsKind.TarGz,
            bootstrapCommand = null,
            packageManager = "apk",
            homepage = "https://alpinelinux.org/",
            sha256 = "4b8cd66a6688b2a87276c39843ed89c3a06d9534fc6a5823c586aff2696c1f2a"
        ),
        Distro(
            id = "arch-arm",
            displayName = "Arch Linux ARM",
            family = DistroFamily.ARCH,
            version = "rolling",
            approxSizeBytes = 350L * MB,
            minAndroidVersion = 26,
            rootfsUrl = "https://github.com/termux/proot-distro/releases/download/v4.34.2/archlinux-aarch64-pd-v4.34.2.tar.xz",
            rootfsKind = RootfsKind.TarXz,
            bootstrapCommand = null,
            packageManager = "pacman",
            homepage = "https://archlinuxarm.org/",
            sha256 = "dabc2382ddcb725969cf7b9e2f3b102ec862ea6e0294198a30c71e9a4b837f81",
            stripComponents = 1
        )
    )

    /**
     * Find a catalog entry by its [Distro.id]. Returns null when the user
     * asks for a distro we don't know about — caller decides whether to
     * fall back to a "custom rootfs" dialog (Phase 9.6.3) or surface an
     * error.
     */
    fun find(id: String): Distro? = ALL.firstOrNull { it.id == id }

    /** Size in bytes for a distro, or 0 if we don't know it. */
    fun approxSizeBytes(id: String): Long = find(id)?.approxSizeBytes ?: 0L

    /** Human-readable size, e.g. "60 MB". */
    fun approxSizeDisplay(id: String): String = (find(id)?.approxSizeBytes ?: 0L).displayByteSize()

    /**
     * Total bytes across all distros the user could install. Used by the
     * runtime screen to show "X distros would consume Y GB total".
     */
    val totalCatalogSizeBytes: Long = ALL.sumOf { it.approxSizeBytes }
}

/**
 * One catalog row. The fields are read-only for the catalog's lifetime;
 * installation state (downloaded, size on disk, last opened) lives in
 * [DistroInstallation] (Room).
 *
 * Phase 9.6.2 — first build; intentionally minimal.
 */
data class Distro(
    val id: String,
    val displayName: String,
    val family: DistroFamily,
    val version: String,
    /** Best-effort size estimate for the extracted rootfs; can be off by ±20%. */
    val approxSizeBytes: Long,
    val minAndroidVersion: Int,
    /** URL to the official rootfs artifact. */
    val rootfsUrl: String,
    /** What kind of artifact we're downloading. */
    val rootfsKind: RootfsKind,
    /**
     * Bootstrap command, only set for distros whose rootfs is not a single
     * tarball but instead built via [debootstrap]/equivalent. Phase 9.6.2
     * sets this only for Debian; Phase 9.6.3 will use it.
     */
    val bootstrapCommand: List<String>?,
    val packageManager: String,
    val homepage: String,
    /** Optional lowercase SHA-256 of the downloaded archive. */
    val sha256: String? = null,
    /** Number of archive path segments removed before extraction. */
    val stripComponents: Int = 0,
    /** Desktop environment to configure after rootfs extraction. */
    val desktopProfile: DesktopProfile = DesktopProfile.TTY
)

/** Convenience alias for a Distro that ships with an XFCE desktop. */
typealias XfceDistro = Distro

/** Supported distro families. Each carries the install strategy. */
enum class DistroFamily {
    /** Debian-derived: apt/dpkg. */
    DEBIAN,
    /** musl-based: apk. */
    MUSL,
    /** Arch: pacman. */
    ARCH
}

/** What kind of artifact are we downloading for the rootfs? */
enum class RootfsKind {
    /** A single gzipped tar archive; the simplest install path. */
    TarGz,
    /** A single unzipped tar archive (rare). */
    TarXz,
    /** A netboot mini ISO; needs a debootstrap-style step on device. */
    BootstrapTarball,
    /** A container/OVA-style blob. Phase 9.6.3. */
    DockerLayer,
    /** Custom: user-supplied URL. */
    Custom
}

/**
 * Format a byte count as a short human-readable string. Used in the catalog
 * UI's "size" column. We avoid locale-specific formatting because it
 * doesn't add value here.
 */
fun Long.displayByteSize(): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var size = this.toDouble()
    var i = 0
    while (size >= 1024.0 && i < units.lastIndex) {
        size /= 1024.0
        i += 1
    }
    return "${size.toInt()} ${units[i]}"
}
