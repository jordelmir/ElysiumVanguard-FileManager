package com.elysium.vanguard.core.runtime.distros.bundled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * PHASE 140 — unit tests for the bundled rootfs stack.
 *
 * The tests run against the REAL Alpine minirootfs
 * tarball in `app/src/main/assets/distros/`. Gradle's
 * `testDebugUnitTest` task does not unpack Android
 * `assets/` into a regular directory on the JVM, so we
 * point the source at the same file via its on-disk
 * path (the asset path is referenced only by the
 * production [AndroidAssetRootfsSource] — tests use
 * [FileBundledRootfsSource] to avoid the Android
 * `AssetManager`).
 *
 * The key things we verify:
 *   - Hash round-trips: extracting the real asset and
 *     recomputing SHA-256 matches the pinned value.
 *   - Idempotency: calling `ensureExtracted` twice
 *     does not re-unpack.
 *   - Marker check: a half-extracted rootfs (without
 *     `bin/` or `etc/`) is treated as not-yet-extracted
 *     and re-unpacked.
 *   - Mismatch: a tampered source throws
 *     [BundledRootfsError.HashMismatch].
 *   - FHS layout: the unpacked rootfs has the expected
 *     structure (`bin/busybox`, `etc/apk/repositories`,
 *     `lib/ld-musl-aarch64.so.1`).
 */
class BundledRootfsExtractorTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var baseDir: File
    private lateinit var extractor: BundledRootfsExtractor

    private val assetFile: File by lazy {
        // Path is relative to the working directory of the
        // test runner, which is the module root for
        // `testDebugUnitTest`.
        File("src/main/assets/distros/alpine-mini-aarch64.tar")
    }

    @Before fun setUp() {
        baseDir = tmp.newFolder("bundled-rootfs-base")
        extractor = BundledRootfsExtractor(baseDir)
    }

    @Test fun `extracts the real Alpine minirootfs with hash match`() {
        assertTrue("Alpine asset must exist at ${assetFile.absolutePath}", assetFile.isFile)
        val distro = BundledDistroRegistry.ALPINE_MINI_AARCH64
        val source = FileBundledRootfsSource(assetFile)
        val extracted = extractor.ensureExtracted(distro, source)
        assertTrue("Extracted dir must be a directory", extracted.isDirectory)
        assertTrue("bin/ must exist", File(extracted, "bin").isDirectory)
        assertTrue("etc/ must exist", File(extracted, "etc").isDirectory)
        assertTrue("lib/ must exist", File(extracted, "lib").isDirectory)
        // Busybox is the canonical Alpine binary.
        val busybox = File(extracted, "bin/busybox")
        assertTrue("bin/busybox must exist (Alpine uses busybox)", busybox.isFile)
        // The musl loader.
        val loader = File(extracted, "lib/ld-musl-aarch64.so.1")
        assertTrue("lib/ld-musl-aarch64.so.1 must exist", loader.isFile)
    }

    @Test fun `extraction is idempotent — second call returns the same path without re-unpacking`() {
        val distro = BundledDistroRegistry.ALPINE_MINI_AARCH64
        val source = FileBundledRootfsSource(assetFile)
        val first = extractor.ensureExtracted(distro, source)
        // Touch a file to detect re-unpack.
        val marker = File(first, "user-marker.txt")
        marker.writeText("user data")
        val second = extractor.ensureExtracted(distro, source)
        assertEquals("Second call must return the same path", first, second)
        assertTrue("Marker must survive the second call (idempotent)", marker.isFile)
        assertEquals("Marker contents must be intact", "user data", marker.readText())
    }

    @Test fun `marker check treats half-extracted dir as not-yet-extracted`() {
        val distro = BundledDistroRegistry.ALPINE_MINI_AARCH64
        val source = FileBundledRootfsSource(assetFile)
        val first = extractor.ensureExtracted(distro, source)
        // Sabotage: delete the bin/ directory. The marker
        // check should detect this and re-unpack.
        File(first, "bin").deleteRecursively()
        val second = extractor.ensureExtracted(distro, source)
        // Re-unpack re-creates bin/. The path may be the
        // same but the contents are reset.
        assertTrue("Re-unpack must re-create bin/", File(second, "bin").isDirectory)
        assertTrue("Re-unpack must re-create bin/busybox", File(second, "bin/busybox").isFile)
    }

    @Test fun `hash mismatch throws typed error`() {
        val distro = BundledDistroRegistry.ALPINE_MINI_AARCH64
        val source = FileBundledRootfsSource(assetFile)
        // Sabotage: wrap the source to lie about its hash.
        val lyingSource = object : BundledRootfsSource {
            override fun openStream() = source.openStream()
            override val sizeBytes: Long = source.sizeBytes
        }
        val bogusDistro = distro.copy(sha256 = "0".repeat(64))
        try {
            extractor.ensureExtracted(bogusDistro, lyingSource)
            fail("Expected HashMismatch to be thrown")
        } catch (e: BundledRootfsError.HashMismatch) {
            assertEquals("alpine-mini", e.distroId)
            assertNotNull("expectedHash should be populated", e.expectedHash)
            assertNotNull("actualHash should be populated", e.actualHash)
            // The actual hash is the real SHA-256 of the asset.
            assertEquals(distro.sha256, e.actualHash)
        }
    }

    @Test fun `size mismatch throws typed error`() {
        val distro = BundledDistroRegistry.ALPINE_MINI_AARCH64
        val source = FileBundledRootfsSource(assetFile)
        // Lie about the size.
        val lyingSize = source.sizeBytes + 1
        val lyingSource = object : BundledRootfsSource {
            override fun openStream() = source.openStream()
            override val sizeBytes: Long = lyingSize
        }
        try {
            extractor.ensureExtracted(distro, lyingSource)
            fail("Expected HashMismatch to be thrown (size)")
        } catch (e: BundledRootfsError.HashMismatch) {
            assertEquals("alpine-mini", e.distroId)
            assertNotNull("expectedSize should be populated", e.expectedSize)
            assertNotNull("actualSize should be populated", e.actualSize)
            // expectedSize comes from distro.sizeBytes, the
            // pinned truth.
            assertEquals(distro.sizeBytes, e.expectedSize)
            // actualSize comes from totalRead; the stream
            // really has distro.sizeBytes bytes, regardless
            // of what the wrapper lies about.
            assertEquals(distro.sizeBytes, e.actualSize)
        }
    }

    @Test fun `registry returns the alpine entry by id`() {
        val entry = BundledDistroRegistry.findById("alpine-mini")
        assertNotNull("alpine-mini must be in the catalog", entry)
        assertEquals("alpine", entry!!.family)
        assertEquals("aarch64", entry.architecture)
        assertEquals("3.20.3", entry.version)
    }

    @Test fun `registry returns null for unknown id`() {
        assertNull(BundledDistroRegistry.findById("nonexistent-distro"))
    }

    @Test fun `catalog has the pinned sha256 for alpine-mini`() {
        // This test pins the hash to the value in
        // BundledDistroRegistry. If somebody updates the
        // registry without updating the asset (or vice
        // versa), the test in `hash mismatch throws
        // typed error` will fail on the real extractor
        // call. Belt + suspenders.
        val entry = BundledDistroRegistry.findById("alpine-mini")!!
        assertEquals(64, entry.sha256.length)
        assertTrue(
            "sha256 must be lowercase hex",
            entry.sha256.all { it in '0'..'9' || it in 'a'..'f' }
        )
    }
}
