package com.elysium.vanguard.core.runtime.distros.bundled

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
 * PHASE 140 — Hilt module wiring for the bundled rootfs
 * stack.
 *
 * Production bindings:
 *   - [BundledDistroRegistry] is an `object` (no
 *     constructor), so the catalog is provided as-is.
 *   - [BundledRootfsExtractor] is a `@Singleton`
 *     scoped to the [bundledRootfsBase] directory
 *     (`<filesDir>/distros/bundled`).
 *
 * Tests construct the extractor directly with a temp
 * dir, bypassing this module.
 */
@Module
@InstallIn(SingletonComponent::class)
object BundledRootfsModule {

    /** Name qualifier for the bundled-rootfs base directory. */
    const val QUALIFIER_BUNDLED_ROOTFS_BASE: String = "bundled_rootfs_base"

    @Provides
    @Singleton
    @Named(QUALIFIER_BUNDLED_ROOTFS_BASE)
    fun provideBundledRootfsBase(@ApplicationContext context: Context): File {
        val base = File(context.filesDir, "distros/bundled")
        if (!base.exists()) base.mkdirs()
        return base
    }

    @Provides
    @Singleton
    fun provideBundledRootfsExtractor(
        @Named(QUALIFIER_BUNDLED_ROOTFS_BASE) base: File,
    ): BundledRootfsExtractor = BundledRootfsExtractor(base)

    @Provides
    fun provideBundledDistroRegistry(): BundledDistroRegistry =
        // The registry is a stateless `object`; the
        // provider is just a Hilt-friendly surface.
        BundledDistroRegistry
}

/**
 * Hilt EntryPoint for Composable-only contexts (e.g.
 * the desktop body Composables) to resolve the bundled
 * rootfs stack. Same pattern as
 * [com.elysium.vanguard.core.recent.RecentFileRepositoryEntryPoint].
 */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface BundledRootfsEntryPoint {
    fun bundledRootfsExtractor(): BundledRootfsExtractor
    fun bundledDistroRegistry(): BundledDistroRegistry
}

private fun bundledRootfsFor(context: Context): BundledRootfsExtractor {
    val app = context.applicationContext
    val entryPoint = dagger.hilt.android.EntryPointAccessors.fromApplication(
        app,
        BundledRootfsEntryPoint::class.java,
    )
    return entryPoint.bundledRootfsExtractor()
}

@androidx.compose.runtime.Composable
fun rememberBundledRootfsExtractor(): BundledRootfsExtractor {
    val context = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember(context) {
        bundledRootfsFor(context)
    }
}
