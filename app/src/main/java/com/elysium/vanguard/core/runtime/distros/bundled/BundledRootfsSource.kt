package com.elysium.vanguard.core.runtime.distros.bundled

import java.io.File
import java.io.InputStream

/**
 * PHASE 140 — narrow seam for reading a bundled asset's bytes.
 *
 * The production [BundledRootfsExtractor] resolves a
 * [BundledDistro] to a [BundledRootfsSource] by opening the
 * APK's `AssetManager`. Tests inject a tiny in-memory or
 * temp-file-backed implementation so the extraction logic
 * is unit-testable on a plain JVM (no Android `Context`,
 * no `AssetManager`).
 *
 * The seam is intentionally narrow — just an [openStream] +
 * [size] pair — so the contract is easy to fake and the
 * extractor is the only place that knows about `AssetManager`.
 */
interface BundledRootfsSource {
    /** Opens the asset bytes for reading. Caller closes. */
    fun openStream(): InputStream

    /**
     * Total size in bytes. The extractor uses this for
     * progress reporting and as a sanity check (the bytes
     * off the stream must equal this size).
     */
    val sizeBytes: Long
}

/**
 * Production source backed by a [File] on the host
 * filesystem. Used by tests and by the dev-mode launcher
 * (the `assets/` tree is not accessible from a host JVM
 * without an Android `Context`).
 */
class FileBundledRootfsSource(
    private val file: File,
) : BundledRootfsSource {
    init {
        require(file.isFile) { "Bundled rootfs asset is not a file: $file" }
    }

    override fun openStream(): InputStream = file.inputStream()
    override val sizeBytes: Long = file.length()
}
