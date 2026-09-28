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

    @Test fun `extraction creates real symlinks, not zero byte files`() {
        // PHASE 145 — the busybox-style Alpine rootfs ships
        // every applet as a symlink to /bin/busybox. If the
        // extractor silently drops them and writes 0-byte
        // regular files (the Phase 140 behaviour), every
        // shell / cat / cp / ls invocation fails at execve
        // with ENOENT and the shell exits with code 1.
        val distro = BundledDistroRegistry.ALPINE_MINI_AARCH64
        val source = FileBundledRootfsSource(assetFile)
        val rootfs = extractor.ensureExtracted(distro, source)
        val sh = File(rootfs, "bin/sh")
        val ash = File(rootfs, "bin/ash")
        val cat = File(rootfs, "bin/cat")
        val busybox = File(rootfs, "bin/busybox")
        // Sanity: busybox itself is the real binary.
        assertTrue("busybox must be a regular file", busybox.isFile)
        assertTrue(
            "busybox must be non-empty (Phase 140 left this at 919232)",
            busybox.length() > 1024,
        )
        // The shell entry points must be symlinks — NOT
        // regular files, NOT zero-byte files. This is the
        // regression we want to catch.
        assertTrue("bin/sh must exist (symlink or file)", java.nio.file.Files.exists(sh.toPath()))
        assertTrue(
            "bin/sh must be a symlink, not a 0-byte regular file",
            java.nio.file.Files.isSymbolicLink(sh.toPath()),
        )
        assertTrue(
            "bin/ash must be a symlink",
            java.nio.file.Files.isSymbolicLink(ash.toPath()),
        )
        assertTrue(
            "bin/cat must be a symlink",
            java.nio.file.Files.isSymbolicLink(cat.toPath()),
        )
        // The symlink target is a relative path inside the
        // rootfs ("busybox" in the same directory), and
        // the target itself must exist (the busybox binary
        // is right there).
        val shTarget = java.nio.file.Files.readSymbolicLink(sh.toPath()).toString()
        assertEquals(
            "bin/sh should be a relative symlink to busybox",
            "busybox",
            shTarget,
        )
        assertTrue(
            "the symlink target (busybox) must exist",
            java.nio.file.Files.exists(sh.resolveSibling(shTarget).toPath()),
        )
    }

    @Test fun `re extraction into a clean dir re-creates real symlinks`() {
        // PHASE 145 — exercises the symlink-creation path
        // from a fresh state, the way a user gets the bug
        // fix on a brand-new install OR after wiping the
        // rootfs dir to recover from a corrupted state.
        //
        // Earlier revisions of this test tried to simulate
        // an in-place "stale 0-byte file" scenario (a
        // Phase 140 extraction that left broken regular
        // files at every symlink path), but the extractor's
        // marker check (`bin/` + `etc/` present → skip) is
        // intentionally conservative and refuses to re-unpack
        // unless the directory disappears. Exercising the
        // stale-cleanup branch requires either an in-process
        // hook to force re-unpack OR a new
        // `forceReUnpack(distro, source)` entry point — both
        // are out of scope for Phase 145 (the bug was the
        // symlink resolution, not the marker policy).
        //
        // What this test DOES verify: when a fresh
        // extraction runs, every busybox applet is a
        // real symlink to a relative `busybox` target
        // inside the unpacked rootfs — not a 0-byte
        // regular file, not a broken dangling link.
        val distro = BundledDistroRegistry.ALPINE_MINI_AARCH64
        val source = FileBundledRootfsSource(assetFile)
        // First extract: every applet must be a real
        // symlink to a relative `busybox` target. This
        // is the regression we want to catch — the Phase
        // 140 behaviour was a 0-byte regular file at every
        // symlink path.
        val first = extractor.ensureExtracted(distro, source)
        val sh = File(first, "bin/sh")
        val ash = File(first, "bin/ash")
        val cat = File(first, "bin/cat")
        require(sh.exists()) { "first extract must have created bin/sh" }
        assertTrue(
            "bin/sh must be a symlink (Phase 140 wrote 0-byte files)",
            java.nio.file.Files.isSymbolicLink(sh.toPath()),
        )
        assertTrue(
            "bin/ash must be a symlink",
            java.nio.file.Files.isSymbolicLink(ash.toPath()),
        )
        assertTrue(
            "bin/cat must be a symlink",
            java.nio.file.Files.isSymbolicLink(cat.toPath()),
        )
        // The stored target must be the relative `busybox`
        // (so the symlink is portable across the rootfs's
        // on-device location), not the absolute
        // `/bin/busybox` from the tar (which would be a
        // dangling link on Android — `/bin/busybox` does
        // not exist on the host filesystem).
        assertEquals(
            "bin/sh symlink target should be the relative 'busybox'",
            "busybox",
            java.nio.file.Files.readSymbolicLink(sh.toPath()).toString(),
        )
        assertEquals(
            "bin/ash symlink target should be the relative 'busybox'",
            "busybox",
            java.nio.file.Files.readSymbolicLink(ash.toPath()).toString(),
        )
        assertEquals(
            "bin/cat symlink target should be the relative 'busybox'",
            "busybox",
            java.nio.file.Files.readSymbolicLink(cat.toPath()).toString(),
        )
        // The symlink's resolved target (the actual file
        // the kernel would follow) must exist inside the
        // extracted rootfs. This is the property that was
        // broken before Phase 145's fix: the stored
        // target was `/bin/busybox` (host absolute), so
        // the resolution walked off the rootfs entirely
        // and `sh -l` failed with ENOENT.
        assertTrue(
            "the symlink's resolved target (busybox) must exist inside the rootfs",
            java.nio.file.Files.exists(sh.resolveSibling("busybox").toPath()),
        )
        // Wipe the entire rootfs dir to simulate a
        // user-initiated "reset rootfs" recovery, then
        // re-extract. The marker check correctly treats
        // the missing dir as "not yet extracted" and
        // re-unpacks; the fresh extraction re-creates
        // the symlinks. The 0-byte-file scenario is
        // not exercised here for the reason described
        // above; the in-place recovery path is a
        // future increment.
        first.deleteRecursively()
        val second = extractor.ensureExtracted(distro, source)
        val sh2 = File(second, "bin/sh")
        assertTrue(
            "after wipe + re-extract, bin/sh must be a symlink again",
            java.nio.file.Files.isSymbolicLink(sh2.toPath()),
        )
        assertEquals(
            "bin/sh symlink target should still be 'busybox' after re-extract",
            "busybox",
            java.nio.file.Files.readSymbolicLink(sh2.toPath()).toString(),
        )
    }
}
