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
 * Phase 9.6.3 — first build; intentionally minimal.
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
 * PHASE 9.6.3 — Default resolver; does the actual device probing.
 *
 * Two flavors are exposed:
 *   - [DEFAULT] always falls back to the jailed shell (Phase 9.6.3 ships
 *     this; Phase 9.6.3.1 will probe for libproot.so and use that when
 *     present).
 *   - [JAILED] always returns the jailed shell (tests).
 *
 * Phase 9.6.3 — first build; intentionally minimal.
 */
object LauncherResolutionResolver {

    /**
     * Production resolver; for 9.6.3 it is identical to [JAILED].
     * 9.6.3.1 swaps this for an ABI-aware probe.
     */
    val DEFAULT: LauncherResolver = LauncherResolver { rootfs ->
        JAILED.resolve(rootfs)
    }

    /**
     * Returns the jailed shell regardless of device state. 9.6.3's
     * `isAvailable()` always reports `false` for the proot launcher
     * (binary not vendored yet) so this collapses to the same path
     * anyway, but exposing it as a constant keeps the code honest.
     */
    val JAILED: LauncherResolver = LauncherResolver { rootfs ->
        LauncherPick(
            launcher = JailedDistroLauncher(),
            reason = "jailed shell (no native proot in 9.6.3)"
        )
    }
}
