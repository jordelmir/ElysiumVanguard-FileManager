package com.elysium.vanguard.core.runtime.runner

import com.elysium.vanguard.core.runtime.distros.DistroInstallation
import com.elysium.vanguard.core.runtime.distros.launcher.LauncherPick

/**
 * Phase 30 — the narrow seam the [SessionRunner] needs from the
 * distro layer.
 *
 * The runtime's full [com.elysium.vanguard.core.runtime.distros.DistroManager]
 * owns a wide surface: install / remove / introspect / snapshot /
 * catalog. The runner only needs two facts:
 *
 *   - is distro `id` installed (and where is its rootfs)?
 *   - which [com.elysium.vanguard.core.runtime.distros.launcher.DistroLauncher]
 *     should run that distro on this device?
 *
 * We capture just those two questions in this interface so the
 * runner is JVM-testable end-to-end without standing up a real
 * rootfs + installer pipeline. Tests pass a hand-rolled fake;
 * production wires the real [com.elysium.vanguard.core.runtime.distros.DistroManager]
 * (which implements the interface).
 *
 * Per the engineering rule: "capture only the values the JVM-
 * testable code reads". The runner does not care about the
 * distro catalog, the install lifecycle, the snapshots, or the
 * introspector. It just needs `findInstalled` + `launcherFor`.
 */
interface DistroSessionBackend {
    fun findInstalled(id: String): DistroInstallation?
    fun launcherFor(id: String): LauncherPick?
}
