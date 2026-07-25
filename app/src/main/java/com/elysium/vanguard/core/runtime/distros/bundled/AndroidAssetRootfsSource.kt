package com.elysium.vanguard.core.runtime.distros.bundled

import android.content.res.AssetManager
import java.io.IOException
import java.io.InputStream

/**
 * PHASE 140 — production [BundledRootfsSource] backed by
 * the APK's [AssetManager].
 *
 * The extractor never sees the `AssetManager` directly;
 * it talks to the [BundledRootfsSource] interface so the
 * hash + extraction logic is testable on a plain JVM.
 *
 * The size is read from the asset's descriptor — that is
 * the size the platform recorded at build time and is
 * stable across runs.
 */
class AndroidAssetRootfsSource(
    private val assets: AssetManager,
    private val assetPath: String,
) : BundledRootfsSource {

    override fun openStream(): InputStream = try {
        assets.open(assetPath)
    } catch (e: IOException) {
        throw BundledRootfsError.AssetNotFound(
            distroId = assetPath.substringAfterLast('/'),
            assetPath = assetPath,
        )
    }

    /**
     * The size is the file size on disk at build time.
     * We resolve it lazily via [AssetManager.openFd] so a
     * missing asset throws the same typed error as the
     * stream open.
     */
    override val sizeBytes: Long = try {
        assets.openFd(assetPath).use { fd -> fd.length }
    } catch (e: IOException) {
        throw BundledRootfsError.AssetNotFound(
            distroId = assetPath.substringAfterLast('/'),
            assetPath = assetPath,
        )
    }
}
