package com.elysium.vanguard.core.runtime.distros

/**
 * PHASE 9.6.3.3 — One row in the merged runtime catalog.
 *
 * Combines a catalog-official [Distro] (or a custom one parsed from a
 * `manifest.json`) with its install state. The RuntimeScreen renders
 * one row per [DistroCatalog.ALL] entry, plus one row per custom
 * rootfs installed on disk.
 *
 * Why a wrapper rather than a marker on [Distro] itself: [Distro] is
 * a pure-data object that should remain cacheable / sharable across
 * the runtime; the install state lives for the lifetime of one UI
 * render and should not pollute the catalog.
 *
 * Phase 9.6.3.3 — first build; intentionally minimal.
 */
data class EffectiveCatalogRow(
    val distro: Distro,
    val isInstalled: Boolean,
    val isHealthy: Boolean,
    val isCustom: Boolean,
    val installation: DistroInstallation?
)
