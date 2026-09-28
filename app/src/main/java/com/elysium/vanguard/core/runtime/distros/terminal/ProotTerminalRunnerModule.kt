package com.elysium.vanguard.core.runtime.distros.terminal

import android.app.Application
import com.elysium.vanguard.core.runtime.distros.bundled.AndroidAssetRootfsSource
import com.elysium.vanguard.core.runtime.distros.bundled.BundledDistro
import com.elysium.vanguard.core.runtime.distros.bundled.BundledDistroRegistry
import com.elysium.vanguard.core.runtime.distros.bundled.BundledRootfsExtractor
import com.elysium.vanguard.core.runtime.distros.bundled.BundledRootfsSource
import com.elysium.vanguard.core.runtime.distros.launcher.ProotLocation
import com.elysium.vanguard.core.runtime.distros.launcher.ProotNativeLibrary
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PHASE 141 — Hilt wiring for the [ProotTerminalRunner].
 *
 * Production bindings:
 *   - The runner is a `@Singleton` and is created lazily
 *     on first request. The constructor wires the proot
 *     location, the bundled distro, the extractor, and
 *     the proot library dir.
 *   - The [AssetManagerSourceProvider] is registered
 *     with a factory that builds an
 *     [AndroidAssetRootfsSource] from the application
 *     context's `AssetManager`.
 *
 * Tests bypass this module and construct the runner
 * directly + register a `FileBundledRootfsSource` factory
 * in `@Before`.
 */
@Module
@InstallIn(SingletonComponent::class)
object ProotTerminalRunnerModule {

    /**
     * Typed factory Hilt can inject. Hilt does not like
     * `Function1<...>` wildcards in module signatures,
     * so we wrap the lambda in a class.
     */
    @Singleton
    class BundledRootfsSourceFactory @Inject constructor(
        private val application: Application,
    ) {
        fun create(distro: BundledDistro): BundledRootfsSource =
            // PHASE 145 — pass the pinned uncompressed size
            // because the AAPT2-deflated asset cannot be
            // opened via [AssetManager.openFd] (it throws
            // `FileNotFoundException: ... it is probably
            // compressed`). See [AndroidAssetRootfsSource]
            // kdoc for the full story.
            AndroidAssetRootfsSource(
                assets = application.assets,
                assetPath = distro.assetPath,
                knownSizeBytes = distro.sizeBytes,
            )
    }

    @Provides
    @Singleton
    fun provideBundledRootfsSourceFactory(
        application: Application,
    ): BundledRootfsSourceFactory = BundledRootfsSourceFactory(application)

    /**
     * Default-bundled proot location. We probe the
     * platform's native library dir (where the APK
     * extracts `libproot.so` to at install time). The
     * abi list passed in is `arm64-v8a` — the only ABI
     * the platform supports for the bundled proot
     * binary.
     */
    @Provides
    @Singleton
    fun provideProotLocation(application: Application): ProotLocation {
        val nativeDir = application.applicationInfo.nativeLibraryDir
        val detector = ProotNativeLibrary(
            bundledAbis = setOf("arm64-v8a"),
            nativeLibraryDir = File(nativeDir),
            userProotDir = null,
            termuxProotCandidates = ProotNativeLibrary.DEFAULT_TERMUX_PROBES,
        )
        return detector.location
            ?: throw IllegalStateException(
                "Proot binary not found. Bundled arm64-v8a expected at " +
                    "${nativeDir}/libproot.so. The APK may be missing the .so."
            )
    }

    @Provides
    @Singleton
    fun provideProotTerminalRunner(
        prootLocation: ProotLocation,
        extractor: BundledRootfsExtractor,
        sourceFactory: BundledRootfsSourceFactory,
        application: Application,
    ): ProotTerminalRunner {
        // Register the source factory so the runner can
        // resolve the bundled asset on first `start()`.
        AssetManagerSourceProvider.register { distro -> sourceFactory.create(distro) }
        return ProotTerminalRunner(
            prootLocation = prootLocation,
            distro = BundledDistroRegistry.ALPINE_MINI_AARCH64,
            extractor = extractor,
            prootLibraryDir = File(prootLocation.path.parentFile?.absolutePath
                ?: application.applicationInfo.nativeLibraryDir),
        )
    }
}

/**
 * Hilt EntryPoint for Composable-only contexts to
 * resolve the [ProotTerminalRunner]. Same pattern as
 * the Phase 139 / 140 EntryPoints.
 */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface ProotTerminalRunnerEntryPoint {
    fun prootTerminalRunner(): ProotTerminalRunner
}

@androidx.compose.runtime.Composable
fun rememberProotTerminalRunner(): ProotTerminalRunner {
    val context = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember(context) {
        val app = context.applicationContext
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            app,
            ProotTerminalRunnerEntryPoint::class.java,
        ).prootTerminalRunner()
    }
}
