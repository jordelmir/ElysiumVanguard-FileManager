package com.elysium.vanguard.core.runtime.distros.launcher

import java.io.File
import com.elysium.vanguard.core.runtime.network.GuestDnsConfigProvider

/**
 * PHASE 9.6.3 — Picks the best [DistroLauncher] for a given rootfs.
 *
 * Resolution order:
 *
 *   1. If `libproot.so` is present in any known location AND the binary
 *      has been registered with a [NativeProotLauncher], use that.
 *   2. Otherwise fall back to [JailedDistroLauncher].
 *
 * The resolution is intentionally a pure function over the inputs so it
 * can be unit-tested without touching the filesystem: tests pass a fake
 * [DistroLauncherRegistry] and verify selection.
 *
 * Why this lives in its own object: Phase 9.6.3.1 will add a third
 * launcher type (Termux-side binary detection), and we want the
 * selection logic to be in one obvious place rather than spread across
 * DistroManager and DistroScreen.
 *
 * Phase 9.6.3 — first build; intentionally minimal.
 */
object LauncherResolution {

    /**
     * Resolve the best launcher for [rootfsDir]. Returns the launcher and
     * a one-line reason string the UI can display in debug builds.
     */
    fun resolve(
        rootfsDir: File,
        registry: DistroLauncherRegistry = DistroLauncherRegistry.empty()
    ): LauncherPick {
        for (launcher in registry.candidates()) {
            if (launcher.isAvailable(rootfsDir)) {
                return LauncherPick(launcher, "selected ${launcher.kind}")
            }
        }
        val jailed = JailedDistroLauncher()
        return LauncherPick(jailed, "fallback to jailed shell (no native runtime)")
    }

    /**
     * Variant that bypasses any registry and forces the jailed shell.
     * Used by tests that want the deterministic path.
     */
    fun forceJailed(): LauncherPick =
        LauncherPick(JailedDistroLauncher(), "forced jailed (test)")
}

/**
 * Wraps a launcher and a one-line reason it was chosen. Tiny value type
 * so callers can pattern-match either field.
 *
 * Phase 9.6.3 — first build; intentionally minimal.
 */
data class LauncherPick(
    val launcher: DistroLauncher,
    val reason: String
)

/**
 * Registry of launcher implementations; the [DistroManager] wires the
 * real one with the right ABIs. Tests inject a custom instance.
 *
 * Phase 9.6.3 — first build; intentionally minimal.
 */
class DistroLauncherRegistry(
    private val launchers: List<DistroLauncher>
) {
    fun candidates(): List<DistroLauncher> = launchers

    companion object {
        /**
         * Empty registry; resolution falls back to the jailed shell.
         * Use this in tests.
         */
        fun empty(): DistroLauncherRegistry = DistroLauncherRegistry(emptyList())

        /**
         * Production registry. Native PRoot wins whenever the payload is
         * present; Direct-Exec remains the compatible fallback for a rootfs
         * whose shell can run without PRoot.
         */
        fun production(
            supportedAbis: Set<String>,
            nativeLibrary: ProotNativeLibrary? = null,
            prootTmpDir: File? = null,
            mounts: List<com.elysium.vanguard.core.runtime.bridge.MountEntry> = emptyList()
        ): DistroLauncherRegistry = production(
            supportedAbis = supportedAbis,
            nativeLibrary = nativeLibrary,
            prootTmpDir = prootTmpDir,
            mounts = mounts,
                guestDnsConfigProvider = GuestDnsConfigProvider.NONE
        )

        /** Production registry with workspace mounts resolved per rootfs at launch time. */
        fun production(
            supportedAbis: Set<String>,
            nativeLibrary: ProotNativeLibrary?,
            prootTmpDir: File?,
            mountsProvider: (File) -> List<com.elysium.vanguard.core.runtime.bridge.MountEntry>,
            guestDnsConfigProvider: GuestDnsConfigProvider
        ): DistroLauncherRegistry =
            DistroLauncherRegistry(
                listOf(
                    NativeProotLauncher(
                        bundledAbis = supportedAbis,
                        nativeLibrary = nativeLibrary,
                        runtimeTmpDir = prootTmpDir,
                        additionalMountsProvider = mountsProvider,
                        guestDnsConfigProvider = guestDnsConfigProvider
                    ),
                    DirectExecDistroLauncher(),
                    JailedDistroLauncher()
                )
            )

        /** Explicit five-argument overload for runtime-only services such as guest DNS. */
        fun production(
            supportedAbis: Set<String>,
            nativeLibrary: ProotNativeLibrary?,
            prootTmpDir: File?,
            mounts: List<com.elysium.vanguard.core.runtime.bridge.MountEntry>,
            guestDnsConfigProvider: GuestDnsConfigProvider
        ): DistroLauncherRegistry = production(
            supportedAbis = supportedAbis,
            nativeLibrary = nativeLibrary,
            prootTmpDir = prootTmpDir,
            mountsProvider = { mounts },
            guestDnsConfigProvider = guestDnsConfigProvider
        )
    }
}
