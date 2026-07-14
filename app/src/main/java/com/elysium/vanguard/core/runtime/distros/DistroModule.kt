package com.elysium.vanguard.core.runtime.distros

import android.content.Context
import com.elysium.vanguard.core.runtime.distros.custom.CustomRootfsInstaller
import com.elysium.vanguard.core.runtime.distros.custom.CustomRootfsPipeline
import com.elysium.vanguard.core.runtime.distros.custom.CustomRootfsValidator
import com.elysium.vanguard.core.runtime.distros.launcher.DistroLauncherRegistry
import com.elysium.vanguard.core.runtime.distros.launcher.LauncherResolver
import com.elysium.vanguard.core.runtime.distros.launcher.LauncherResolution
import com.elysium.vanguard.core.runtime.distros.launcher.ProotNativeLibrary
import com.elysium.vanguard.core.runtime.network.AndroidGuestDnsConfigProvider
import com.elysium.vanguard.core.runtime.network.AndroidGuestDnsObserver
import com.elysium.vanguard.core.runtime.network.GuestDnsConfigProvider
import com.elysium.vanguard.core.runtime.network.GuestDnsObserver
import com.elysium.vanguard.core.runtime.bridge.RuntimeWorkspaceMountRegistry
import com.elysium.vanguard.core.runtime.distros.snapshot.RootfsSnapshot
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * PHASE 9.6.3 — Hilt module for the runtime/distros subsystem.
 *
 * Phase 9.6.3.2 expanded the module to also provide the custom rootfs
 * pipeline, the snapshot creator, and a default [DistroHttpDownloader].
 *
 * Phase 9.6.3 — first build; intentionally minimal.
 */
@Module
@InstallIn(SingletonComponent::class)
object DistroModule {

    /**
     * Application context here is fine because the manager's only
     * filesystem dependency is the app-private `filesDir/distros` dir,
     * which lives across the Activity lifecycle.
     */
    @Provides
    @Singleton
    fun provideDistroManager(
        @ApplicationContext context: Context,
        downloader: DistroHttpDownloader,
        launcherResolver: LauncherResolver
    ): DistroManager {
        val baseDir = java.io.File(context.filesDir, "distros").apply { if (!exists()) mkdirs() }
        return DistroManager(
            baseDir = baseDir,
            downloader = downloader,
            launcherResolver = launcherResolver
        )
    }

    /**
     * PHASE 9.6.3.2 — Expose the HTTP downloader at the singleton
     * scope so the [CustomRootfsInstaller] (also in this module) can
     * receive it via Hilt injection. The HTTP client is stateless —
     * one shared instance per process is fine.
     */
    @Provides
    @Singleton
    fun provideDistroHttpDownloader(): DistroHttpDownloader = RealDistroHttpDownloader()

    /**
     * Exposed separately so tests can replace the resolver without
     * rebuilding the manager.
     */
    @Provides
    @Singleton
    fun provideLauncherResolver(registry: DistroLauncherRegistry): LauncherResolver =
        LauncherResolver { rootfs -> LauncherResolution.resolve(rootfs, registry) }

    @Provides
    @Singleton
    fun provideProotNativeLibrary(@ApplicationContext context: Context): ProotNativeLibrary =
        ProotNativeLibrary.default(
            abis = currentSupportedAbis(),
            nativeLibraryDir = context.applicationInfo.nativeLibraryDir?.let { java.io.File(it) },
            userProotDir = java.io.File(context.filesDir, "proot")
        )

    /**
     * The launcher registry used by the resolution path; exposed so
     * future launchers (Termux-style binary detection, etc.) can be
     * registered here at install time instead of touching the manager.
     */
    @Provides
    @Singleton
    fun provideLauncherRegistry(
        @ApplicationContext context: Context,
        nativeLibrary: ProotNativeLibrary,
        workspaceMounts: RuntimeWorkspaceMountRegistry,
        guestDnsObserver: GuestDnsObserver
    ): DistroLauncherRegistry {
        return DistroLauncherRegistry.production(
            supportedAbis = currentSupportedAbis(),
            nativeLibrary = nativeLibrary,
            prootTmpDir = java.io.File(context.cacheDir, "proot"),
            mountsProvider = workspaceMounts::mountsForRootfs,
            guestDnsConfigProvider = guestDnsObserver
        )
    }

    /**
     * Reactive DNS source. Backed by the production [AndroidGuestDnsObserver]
     * so the PRoot guest follows the device's active network. The class is
     * process-wide; consumers that want to react to changes collect the
     * [GuestDnsObserver.observe] flow.
     */
    @Provides
    @Singleton
    fun provideGuestDnsObserver(@ApplicationContext context: Context): GuestDnsObserver =
        AndroidGuestDnsObserver(context)

    /**
     * Back-compat alias for callers that still depend on the one-shot
     * [GuestDnsConfigProvider] interface (e.g. distros outside the
     * [com.elysium.vanguard.core.runtime.distros.launcher.NativeProotLauncher]
     * happy path). The reactive observer is also a provider.
     */
    @Provides
    @Singleton
    fun provideGuestDnsConfigProvider(observer: GuestDnsObserver): GuestDnsConfigProvider = observer

    /**
     * PHASE 9.6.3.2 — The custom rootfs installer. Uses the
     * [com.elysium.vanguard.core.runtime.distros.DistroHttpDownloader]
     * we already vendored in 9.6.2.
     */
    @Provides
    @Singleton
    fun provideCustomRootfsInstaller(
        downloader: DistroHttpDownloader
    ): CustomRootfsInstaller = CustomRootfsInstaller(downloader = downloader)

    /**
     * PHASE 9.6.3.2 — The full pipeline (validator + installer) wired
     * for one-call install. The custom rootfs screen depends on this.
     */
    @Provides
    @Singleton
    fun provideCustomRootfsPipeline(
        validator: CustomRootfsValidator,
        installer: CustomRootfsInstaller
    ): CustomRootfsPipeline = CustomRootfsPipeline(
        validator = validator,
        installer = installer
    )

    /**
     * PHASE 9.6.3.2 — Bind the validator at the singleton scope so the
     * UI screen can validate a URL without re-creating one each time.
     */
    @Provides
    @Singleton
    fun provideCustomRootfsValidator(): CustomRootfsValidator = CustomRootfsValidator()

    /**
     * PHASE 9.6.3.2 — Default snapshot creator. The Inspect screen
     * gets its own instance per query because we want to keep the
     * `baseDir` setup explicit and not require knowing it from
     * anywhere else.
     */
    @Provides
    @Singleton
    fun provideRootfsSnapshotFactory(@ApplicationContext context: Context): RootfsSnapshotFactory {
        val baseDir = java.io.File(context.filesDir, "distros")
        return RootfsSnapshotFactory(baseDir)
    }

    private fun currentSupportedAbis(): Set<String> {
        // Phase 9.6.3: device ABI probing is cheap; we report an empty set
        // until 9.6.3.1 vendors libproot.so per ABI.
        val abis = android.os.Build.SUPPORTED_ABIS ?: emptyArray()
        return abis.toSet()
    }
}

/**
 * PHASE 9.6.3.2 — Tiny factory that ties [RootfsSnapshot] to a base
 * directory; Hilt provides a single instance for the whole app.
 */
class RootfsSnapshotFactory(private val baseDir: java.io.File) {
    fun create(): RootfsSnapshot = RootfsSnapshot(baseDir)
}
