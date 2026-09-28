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
 * ## Why [knownSizeBytes] is a constructor param (PHASE 145)
 *
 * Phase 140 used [AssetManager.openFd] to read the
 * `sizeBytes` lazily. **That breaks on compressed assets**:
 * AAPT2 deflates the bundled `.tar` in the APK (the asset
 * is 4.2 MB on disk, 9.1 MB uncompressed), and `openFd()`
 * throws `FileNotFoundException: This file can not be
 * opened as a file descriptor; it is probably compressed`
 * for any deflated entry. The fix: take the expected
 * uncompressed size as a constructor parameter (the
 * platform-recorded size is the same value the
 * [BundledDistro.sizeBytes] field already pins), and only
 * use `assets.open()` for the actual stream.
 */
class AndroidAssetRootfsSource(
    private val assets: AssetManager,
    private val assetPath: String,
    private val knownSizeBytes: Long,
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
     * The size of the *uncompressed* asset. We pin this
     * at build time (see [BundledDistro.sizeBytes]) instead
     * of reading it from the APK at runtime because the
     * deflate-compressed assets cannot be opened via
     * [AssetManager.openFd] — see the class kdoc.
     */
    override val sizeBytes: Long = knownSizeBytes
}
