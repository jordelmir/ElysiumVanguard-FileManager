package com.elysium.vanguard.core.runtime.distros.launcher

import java.io.File

/**
 * PHASE 9.6.3 — Strategy for picking a [DistroLauncher] for a rootfs.
 *
 * Sits between [com.elysium.vanguard.core.runtime.distros.DistroManager]
 * (which knows what's installed) and the concrete launcher
 * implementations. Tests can inject a deterministic [staticResolver]
 * for repeatable behavior.
 *
 * Phase 10.4 — the default resolver now uses the production registry
 * (Native-Proot → Direct-Exec → Jailed). A rootfs containing a
 * runnable shell lands on Direct-Exec instead of the Jailed shell.
 */
fun interface LauncherResolver {
    fun resolve(rootfs: File): LauncherPick

    companion object {
        /**
         * Picks [LauncherResolutionResolver.JAILED] unconditionally.
         * Useful in unit tests that want to test the launcher contract
         * without exercising the device's ABI table.
         */
        val staticResolver: LauncherResolver = LauncherResolver { rootfs ->
            LauncherResolutionResolver.JAILED.resolve(rootfs)
        }
    }
}

/**
 * PHASE 9.6.3 / 10.4 — Default resolver; does the actual device probing.
 *
 * Two flavors are exposed:
 *   - [DEFAULT] now goes through the production registry, which prefers
 *     Direct-Exec (Phase 10.4) over the Jailed shell whenever the
 *     rootfs has a runnable shell binary. Native-Proot is selected whenever
 *     the bundled payload and rootfs pass their availability checks.
 *   - [JAILED] always returns the jailed shell (tests).
 */
object LauncherResolutionResolver {

    /**
     * Production resolver. Phase 10.4: uses the production registry
     * (Native-Proot, Direct-Exec, Jailed) so a real shell inside the
     * rootfs actually runs. The registry walks candidates in order and
     * returns the first one whose `isAvailable` is true.
     */
    val DEFAULT: LauncherResolver = LauncherResolver { rootfs ->
        LauncherResolution.resolve(rootfs, DistroLauncherRegistry.production(emptySet()))
    }

    /**
     * Returns the jailed shell regardless of device state. Tests use
     * this to pin down the Jailed code path; production never picks it
     * unless both Direct-Exec and Native-Proot are unavailable.
     */
    val JAILED: LauncherResolver = LauncherResolver { _ ->
        LauncherPick(
            launcher = JailedDistroLauncher(),
            reason = "jailed shell (no native proot in 9.6.3)"
        )
    }
}
