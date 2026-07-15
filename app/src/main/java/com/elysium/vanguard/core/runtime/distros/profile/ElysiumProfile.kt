package com.elysium.vanguard.core.runtime.distros.profile

/**
 * Phase 12.3 — the four Elysium Vanguard Linux profiles.
 *
 * Master order §11.4 names four profiles that pick which desktop
 * environment (or none) the guest ships. Each profile is a fixed
 * pair:
 *
 *   1. A list of upstream packages installed during provisioning
 *      (e.g. `xfce4`, `xfce4-terminal`). These come from the
 *      distro's own repository.
 *   2. An [layerId] of an Elysium-provided SystemLayer
 *      (Phase 12.2) carrying the Elysium-branded configuration
 *      for that profile (panel layout, terminal config, branding).
 *
 * The pairing is fixed at compile time so a user can't pick
 * "balanced packages + desktop config" by accident. New profiles
 * (or variants) get a new enum entry; they don't get options on
 * existing entries.
 *
 * Memory and disk footprint per profile are documented in
 * [estimatedRssMb] / [estimatedDiskMb]. The UI surfaces these
 * before the user commits to a profile.
 */
enum class ElysiumProfile(
    val id: String,
    val displayName: String,
    val description: String,
    /** Upstream packages, fed to the distro's package manager verbatim. */
    val packages: List<String>,
    /** SystemLayer that carries the Elysium-specific config (panel, terminal, etc.). */
    val layerId: String,
    val layerVersion: String,
    val estimatedRssMb: Int,
    val estimatedDiskMb: Int
) {
    LITE(
        id = "lite",
        displayName = "Elysium Lite",
        description = "Openbox + LXTerminal + PCManFM. Minimum overhead, " +
            "suited for older devices and battery-constrained sessions.",
        packages = listOf("openbox", "obconf", "lxterminal", "pcmanfm", "tint2"),
        layerId = "elysium-profile-lite",
        layerVersion = "1.0.0",
        estimatedRssMb = 120,
        estimatedDiskMb = 380
    ),
    BALANCED(
        id = "balanced",
        displayName = "Elysium Balanced",
        description = "XFCE4 + Thunar + Xfce4-terminal. Full feature set, " +
            "the default pick for a phone-as-desktop session.",
        packages = listOf(
            "xfce4", "xfce4-session", "xfce4-panel", "xfce4-terminal",
            "thunar", "thunar-archive-plugin", "thunar-volman"
        ),
        layerId = "elysium-profile-balanced",
        layerVersion = "1.0.0",
        estimatedRssMb = 320,
        estimatedDiskMb = 720
    ),
    DESKTOP(
        id = "desktop",
        displayName = "Elysium Desktop",
        description = "LXQt + PCManFM-Qt. Touch-friendly for tablet and " +
            "external-monitor sessions; ships a full Qt-based panel.",
        packages = listOf(
            "lxqt", "lxqt-panel", "lxqt-session", "qterminal",
            "pcmanfm-qt", "lximage-qt"
        ),
        layerId = "elysium-profile-desktop",
        layerVersion = "1.0.0",
        estimatedRssMb = 480,
        estimatedDiskMb = 980
    ),
    HEADLESS(
        id = "headless",
        displayName = "Elysium Headless",
        description = "No graphical environment. Servers, daemons, " +
            "headless development, CI workers. Smallest footprint.",
        packages = emptyList(),
        layerId = "elysium-profile-headless",
        layerVersion = "1.0.0",
        estimatedRssMb = 40,
        estimatedDiskMb = 200
    );

    /**
     * True when the profile ships a graphical environment. The
     * headless profile returns false; every other profile returns
     * true. The UI uses this to decide whether to enable the
     * "Open Desktop" action after a session is open.
     */
    val isGraphical: Boolean get() = this != HEADLESS

    companion object {
        /** The default profile when the user does not pick one. */
        val DEFAULT: ElysiumProfile = BALANCED

        /**
         * Look up a profile by its [id]. Returns null when the
         * id is unknown — callers should fall back to [DEFAULT]
         * rather than crashing.
         */
        fun fromId(id: String?): ElysiumProfile? =
            entries.firstOrNull { it.id == id }
    }
}
