package com.elysium.vanguard.core.runtime.wine

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Named
import javax.inject.Singleton

/**
 * PHASE 144 — Hilt module wiring for the Wine + Box64
 * foundation.
 *
 * Production bindings:
 *   - [ElysiumWineStackDetector] is a `@Singleton` — the
 *     probe is a pure filesystem walk; caching the result
 *     across the app lifetime is the right call. (A future
 *     increment may add a "rescan" action for the user to
 *     trigger after installing a Wine build under
 *     `<filesDir>/wine/`.)
 *   - The Wine prefixes base directory
 *     (`<filesDir>/wine-prefixes/`) is provided as a
 *     `@Named("wine_prefixes_base") File` so a test or a
 *     future runtime can swap the base without rewriting
 *     the builder.
 *   - [WineLaunchSpecBuilder] is a `@Singleton` — the
 *     builder is stateless; the singleton is just a
 *     Hilt-friendly surface.
 *
 * The module is the production bridge from the typed Wine
 * types to the Hilt-managed DI graph. Tests construct the
 * detector + builder directly with a temp dir, bypassing
 * this module (same pattern as Phase 140's
 * `BundledRootfsModule`).
 */
@Module
@InstallIn(SingletonComponent::class)
object WineModule {

    /** Name qualifier for the Wine prefixes base directory. */
    const val QUALIFIER_WINE_PREFIXES_BASE: String = "wine_prefixes_base"

    @Provides
    @Singleton
    fun provideElysiumWineStackDetector(
        @ApplicationContext context: Context,
    ): ElysiumWineStackDetector {
        // The native library dir is where Android extracts
        // the APK's bundled .so files (libwine.so, libbox64.so
        // — once Phase 145+ cross-compiles them). applicationInfo
        // is the typed seam; on a JVM test this would not be
        // available, so the detector takes a `File?` (null
        // when no nativeLibraryDir exists, e.g. on the JVM
        // test classpath).
        val nativeLibraryDir: File? = try {
            context.applicationInfo.nativeLibraryDir
                ?.let(::File)
        } catch (_: Throwable) {
            null
        }
        val userWineDir: File = File(context.filesDir, "wine")
        if (!userWineDir.exists()) userWineDir.mkdirs()
        return ElysiumWineStackDetector(
            nativeLibraryDir = nativeLibraryDir,
            userWineDir = userWineDir,
        )
    }

    @Provides
    @Singleton
    @Named(QUALIFIER_WINE_PREFIXES_BASE)
    fun provideWinePrefixesBase(@ApplicationContext context: Context): File {
        val base = File(context.filesDir, "wine-prefixes")
        if (!base.exists()) base.mkdirs()
        return base
    }

    @Provides
    @Singleton
    fun provideWineStack(detector: ElysiumWineStackDetector): WineStack? =
        // The provider returns `WineStack?` because the
        // detector is `null`-able (the binaries may not be
        // installed yet). A consumer that requires the stack
        // (e.g. [WineLaunchSpecBuilder]) checks for null and
        // surfaces a typed error.
        detector.stack

    @Provides
    @Singleton
    fun provideWineLaunchSpecBuilder(
        detector: ElysiumWineStackDetector,
        @Named(QUALIFIER_WINE_PREFIXES_BASE) prefixesBase: File,
    ): WineLaunchSpecBuilder {
        // The builder requires a non-null WineStack. If the
        // detector did not find wine, we construct a
        // placeholder stack pointing at non-existent paths;
        // every builder call will fail with a typed
        // [WineLaunchSpecError.WineBinaryMissing] until the
        // user installs Wine. This keeps the Hilt graph
        // honest (every `@Provides` returns a non-null
        // value).
        val stack = detector.stack
            ?: WineStack(
                winePath = File("/dev/null/elysium-wine-not-installed"),
                box64Path = null,
                box86Path = null,
            )
        return WineLaunchSpecBuilder(
            wineStack = stack,
            prefixesBaseDir = prefixesBase,
        )
    }
}

/**
 * Hilt EntryPoint for Composable-only contexts (e.g. the
 * desktop body Composables) to resolve the Wine foundation.
 * Same pattern as
 * [com.elysium.vanguard.core.runtime.distros.bundled.BundledRootfsEntryPoint]
 * (Phase 140).
 */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface WineModuleEntryPoint {
    fun elysiumWineStackDetector(): ElysiumWineStackDetector
    fun wineStack(): WineStack?
    fun wineLaunchSpecBuilder(): WineLaunchSpecBuilder
}

private fun wineStackFor(context: Context): WineStack? {
    val app = context.applicationContext
    val entryPoint = dagger.hilt.android.EntryPointAccessors.fromApplication(
        app,
        WineModuleEntryPoint::class.java,
    )
    return entryPoint.wineStack()
}

private fun elysiumDetectorFor(context: Context): ElysiumWineStackDetector {
    val app = context.applicationContext
    val entryPoint = dagger.hilt.android.EntryPointAccessors.fromApplication(
        app,
        WineModuleEntryPoint::class.java,
    )
    return entryPoint.elysiumWineStackDetector()
}

private fun wineBuilderFor(context: Context): WineLaunchSpecBuilder {
    val app = context.applicationContext
    val entryPoint = dagger.hilt.android.EntryPointAccessors.fromApplication(
        app,
        WineModuleEntryPoint::class.java,
    )
    return entryPoint.wineLaunchSpecBuilder()
}

/**
 * Composable helper: resolve the typed [WineStack] from the
 * Hilt graph. Returns `null` when Wine is not installed —
 * Composables branch on the null case to show "Wine not
 * detected" UI.
 */
@androidx.compose.runtime.Composable
fun rememberWineStack(): WineStack? {
    val context = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember(context) {
        wineStackFor(context)
    }
}

/**
 * Composable helper: resolve the [ElysiumWineStackDetector]
 * (carries the [ElysiumWineStackDetector.wineLocation] +
 * [ElysiumWineStackDetector.box64Location] for diagnostics).
 * Always non-null; the detector itself is always present,
 * but its [ElysiumWineStackDetector.stack] may be null.
 */
@androidx.compose.runtime.Composable
fun rememberElysiumWineStackDetector(): ElysiumWineStackDetector {
    val context = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember(context) {
        elysiumDetectorFor(context)
    }
}

/**
 * Composable helper: resolve the [WineLaunchSpecBuilder].
 * Always non-null; builder calls surface a typed
 * [WineLaunchSpecError] when the underlying stack is
 * incomplete.
 */
@androidx.compose.runtime.Composable
fun rememberWineLaunchSpecBuilder(): WineLaunchSpecBuilder {
    val context = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember(context) {
        wineBuilderFor(context)
    }
}
