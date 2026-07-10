package com.elysium.vanguard.core.runtime.distros

import com.elysium.vanguard.core.runtime.distros.introspector.InstalledPackage
import com.elysium.vanguard.core.runtime.distros.introspector.OsRelease
import com.elysium.vanguard.core.runtime.distros.introspector.RootfsEntry

/**
 * PHASE 9.6.3.2 — One pre-built snapshot of an installed rootfs.
 *
 * The manager introspects inside [DistroManager.introspect] on the
 * calling thread (read-only) and hands back this fully-resolved DTO;
 * the UI consumes it without ever needing to know about
 * [com.elysium.vanguard.core.runtime.distros.introspector.RootfsIntrospector].
 *
 * The DTO is intentionally tiny: `entries` are capped at depth 3
 * (`RootfsIntrospector.entries(maxDepth = 3)`); the UI can request
 * deeper walks later by extending the manager's contract.
 *
 * Phase 9.6.3.2 — first build; intentionally minimal.
 */
data class RootfsIntrospectorSnapshot(
    val osRelease: OsRelease,
    val entries: List<RootfsEntry>,
    val packages: List<InstalledPackage>
) {
    val hasOsRelease: Boolean get() = osRelease != OsRelease.UNKNOWN
    val hasPackages: Boolean get() = packages.isNotEmpty()

    /** Human-readable summary used by the Inspect screen title. */
    val summary: String
        get() = when {
            hasOsRelease -> osRelease.prettyName ?: osRelease.name ?: osRelease.id ?: "unknown"
            else -> "no os-release detected"
        }
}
