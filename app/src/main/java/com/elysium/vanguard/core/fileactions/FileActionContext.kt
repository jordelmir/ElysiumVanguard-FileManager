package com.elysium.vanguard.core.fileactions

/**
 * Phase 93 — the **context** the
 * [FileActionResolver] reads to decide which
 * actions to offer for a file.
 *
 * The context is a passive value (built by the
 * ViewModel; passed into the resolver). The
 * resolver does not call into the distro
 * manager or the VM manager directly — the
 * context is a snapshot of the relevant state
 * at the time of the resolution. This makes
 * the resolver JVM-testable (a test builds a
 * fixed context; the resolver returns the
 * expected action list).
 *
 * **Why a context, not a callback?** The
 * resolver is pure: same file + same context
 * → same action list. A test can assert
 * behavior without spinning up a distro
 * manager or a VM manager. The Hilt-injected
 * ViewModel builds the context from the live
 * `DistroManager` + `WindowsVmManager` +
 * GitOps + SMB / WebDAV credentials.
 */
data class FileActionContext(
    val linuxDistros: List<LinuxDistroTarget> = emptyList(),
    val windowsVms: List<WindowsVmTarget> = emptyList(),
    val preferredLinuxDistroId: String? = null,
    val preferredWindowsVmId: String? = null,
    val gitRemotes: List<String> = emptyList(),
    val knownSmbShares: List<String> = emptyList(),
    val knownWebDavUrls: List<String> = emptyList(),
) {
    /**
     * The Linux distros the platform knows about.
     * Each entry carries the distro's id, the
     * human-readable name, and the package
     * manager family (so the resolver can
     * match a `.deb` to an `apt`-based distro
     * and a `.rpm` to a `dnf`-based distro).
     */
    val linuxDistrosByPackageManager: Map<LinuxPackageManager, List<LinuxDistroTarget>>
        get() = linuxDistros.groupBy { it.packageManager }

    /**
     * A single Linux distro's id / name / PM.
     * The PM is a coarse enum (the resolver
     * only needs to know "is this apt / dnf /
     * pacman / apk?").
     */
    data class LinuxDistroTarget(
        val id: String,
        val name: String,
        val packageManager: LinuxPackageManager,
    )

    data class WindowsVmTarget(
        val id: String,
        val name: String,
        val isRunning: Boolean,
    )
}

/**
 * The coarse package-manager family a Linux
 * distro uses. The resolver uses this enum to
 * match a `.deb` to an `APT`-based distro, a
 * `.rpm` to a `DNF`-based distro, and a
 * `.pkg.tar.zst` to a `PACMAN`-based distro.
 */
enum class LinuxPackageManager {
    APT,    // Debian, Ubuntu
    DNF,    // Fedora, RHEL, openSUSE
    PACMAN, // Arch, Manjaro
    APK,    // Alpine
    ELEVATOR, // Elysium Vanguard Linux (custom, Phase 7+)
}
