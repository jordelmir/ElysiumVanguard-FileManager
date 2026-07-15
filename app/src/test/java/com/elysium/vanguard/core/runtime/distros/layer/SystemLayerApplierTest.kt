package com.elysium.vanguard.core.runtime.distros.layer

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Phase 12.2 — applier tests.
 *
 * Each test:
 *   1. Builds a real tar.gz tarball on disk (no mocking of the
 *      filesystem layer — the contract is *the bytes on disk*).
 *   2. Hashes it with SHA-256 to compute the expected digest.
 *   3. Constructs a [SystemLayer] and asks the applier to apply
 *      it to a temp rootfs.
 *   4. Asserts on the resulting directory's contents.
 */
class SystemLayerApplierTest {

    @Test
    fun `apply extracts the layer tarball into elysium-layer-id`() {
        val (rootfs, tarball) = prepareFixture("elysium-cli", "1.0.0", listOf(
            TarEntry("opt/elysium/bin/elysium", "#!/bin/sh\necho hello\n"),
            TarEntry("opt/elysium/lib/version.txt", "1.0.0\n")
        ))
        try {
            val layer = makeLayer("elysium-cli", "1.0.0", tarball)
            val applier = SystemLayerApplier()

            val applied = applier.apply(layer, rootfs)

            assertTrue(applied.isDirectory)
            assertTrue(File(applied, "opt/elysium/bin/elysium").isFile)
            assertTrue(File(applied, "opt/elysium/lib/version.txt").isFile)
            assertEquals(
                "1.0.0\n",
                File(applied, "opt/elysium/lib/version.txt").readText()
            )
        } finally {
            rootfs.deleteRecursively()
            tarball.delete()
        }
    }

    @Test
    fun `apply overwrites an existing layer with the same id`() {
        val (rootfs, tarballV1) = prepareFixture("cli", "1.0.0", listOf(
            TarEntry("opt/cli/version.txt", "1.0.0\n")
        ))
        try {
            // Sanity: the tarball is non-empty.
            assertTrue("tarball must exist", tarballV1.isFile)
            assertTrue("tarball must have bytes", tarballV1.length() > 0)
            // Pre-place a v0.5 layer directory so we can verify the
            // v1 apply replaces it. mkdirs the parents the JVM
            // file API requires.
            val oldDir = File(rootfs, "elysium-layer-cli")
            oldDir.mkdirs()
            File(oldDir, "opt/cli").mkdirs()
            File(oldDir, "opt/cli/version.txt").writeText("0.5\n")
            val oldExtra = File(oldDir, "opt/cli/legacy.txt")
            oldExtra.writeText("kept-by-v0.5\n")
            // The applier extracts with stripComponents=0, then
            // deletes the old live layer and renames staging in.
            // This is destructive by design: a new version of a
            // layer is a full replacement, not a delta. Files that
            // the new tarball doesn't carry are dropped; preserving
            // them is the snapshot/restore path's job.
            val applier = SystemLayerApplier()
            applier.apply(makeLayer("cli", "1.0.0", tarballV1), rootfs)

            val layerDir = File(rootfs, "elysium-layer-cli")
            assertTrue(
                "layer dir must exist after apply",
                layerDir.isDirectory
            )
            assertTrue(
                "v1 file must be present after apply",
                File(rootfs, "elysium-layer-cli/opt/cli/version.txt").isFile
            )
            assertEquals(
                "1.0.0\n",
                File(rootfs, "elysium-layer-cli/opt/cli/version.txt").readText()
            )
            // v0.5 file is gone because the new apply deletes the
            // old live layer. Callers that need to preserve files
            // across upgrades must snapshot first (see
            // [SystemLayerUpdater]).
            assertFalse(
                "v0.5 file must be gone (apply is a full replacement)",
                oldExtra.isFile
            )
        } finally {
            rootfs.deleteRecursively()
            tarballV1.delete()
        }
    }

    @Test
    fun `apply rejects a tarball whose sha256 does not match`() {
        val (rootfs, tarball) = prepareFixture("cli", "1.0.0", listOf(
            TarEntry("opt/cli/version.txt", "1.0.0\n")
        ))
        try {
            // Wrong hash on purpose.
            val wrongLayer = SystemLayer(
                id = "cli",
                displayName = "CLI",
                version = "1.0.0",
                tarball = tarball,
                sha256 = "0".repeat(64)
            )
            val applier = SystemLayerApplier()

            try {
                applier.apply(wrongLayer, rootfs)
                fail("expected LayerHashMismatch")
            } catch (mismatch: LayerHashMismatch) {
                assertEquals(wrongLayer.id, mismatch.layer.id)
                assertEquals(wrongLayer.sha256, mismatch.expected)
                assertTrue("actual hash should be the real one", mismatch.actual.matches(Regex("[0-9a-f]{64}")))
            }
            // The rootfs must not have been touched.
            assertTrue(
                "no live layer dir on hash mismatch",
                File(rootfs, "elysium-layer-cli").let { !it.exists() }
            )
        } finally {
            rootfs.deleteRecursively()
            tarball.delete()
        }
    }

    @Test
    fun `apply requires a real tarball on disk`() {
        val (rootfs, _) = prepareFixture("cli", "1.0.0", emptyList())
        try {
            try {
                SystemLayer(
                    id = "cli",
                    displayName = "CLI",
                    version = "1.0.0",
                    tarball = File("/no/such/elysium/layer.tar.gz"),
                    sha256 = "0".repeat(64)
                )
                fail("expected IllegalArgumentException from the layer's init block")
            } catch (expected: IllegalArgumentException) {
                // The init block rejects the missing file.
            }
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `apply requires a real destination directory`() {
        val (_, tarball) = prepareFixture("cli", "1.0.0", listOf(
            TarEntry("opt/cli/version.txt", "1.0.0\n")
        ))
        try {
            val layer = makeLayer("cli", "1.0.0", tarball)
            try {
                SystemLayerApplier().apply(layer, File("/no/such/elysium/rootfs"))
                fail("expected IllegalArgumentException")
            } catch (expected: IllegalArgumentException) {
                // The applier rejects the missing dest dir.
            }
        } finally {
            tarball.delete()
        }
    }

    @Test
    fun `apply cleans up the staging directory on failure mid-extract`() {
        // We simulate a failure by giving the applier an extractor
        // that always throws. The applier must delete the staging
        // dir so a crashed apply does not leak half-extracted files.
        val (rootfs, tarball) = prepareFixture("cli", "1.0.0", listOf(
            TarEntry("opt/cli/version.txt", "1.0.0\n")
        ))
        try {
            val layer = makeLayer("cli", "1.0.0", tarball)
            val exploding = SystemLayerApplier(
                extractor = com.elysium.vanguard.core.runtime.distros.DistroRootfsExtractor().also {
                    // We can't easily inject a failing extractor; the
                    // existing impl can fail on a real bad input.
                    // Instead, point at a real (well-formed) tarball
                    // that we then corrupt by truncating.
                }
            )
            // Simpler: hand-construct a SystemLayer whose tarball is
            // a truncated archive (the extractor will throw). The
            // SHA-256 we declare is the *real* one so the hash check
            // passes; the corruption triggers during extraction.
            val truncated = Files.createTempFile("elysium-trunc", ".tar.gz").toFile()
            tarball.copyTo(truncated, overwrite = true)
            truncated.writeBytes(truncated.readBytes().copyOfRange(0, 16))
            val truncatedHash = sha256Hex(truncated)
            val corrupted = SystemLayer(
                id = "cli",
                displayName = "CLI",
                version = "1.0.0",
                tarball = truncated,
                sha256 = truncatedHash
            )
            try {
                exploding.apply(corrupted, rootfs)
                fail("expected IOException from corrupt tarball")
            } catch (expected: IOException) {
                // Staging must be cleaned up.
                assertTrue(
                    "_apply.part must be removed after failed extract",
                    !File(rootfs, "_apply.part").exists()
                )
            }
            truncated.delete()
        } finally {
            rootfs.deleteRecursively()
            tarball.delete()
        }
    }

    // --- helpers ---

    private data class TarEntry(val path: String, val content: String)

    private fun prepareFixture(
        id: String,
        version: String,
        entries: List<TarEntry>
    ): Pair<File, File> {
        val rootfs = Files.createTempDirectory("elysium-layer-apply").toFile()
        val tarball = Files.createTempFile("elysium-$id-$version", ".tar.gz").toFile()
        writeTarGz(tarball, entries)
        return rootfs to tarball
    }

    private fun makeLayer(id: String, version: String, tarball: File): SystemLayer =
        SystemLayer(
            id = id,
            displayName = id,
            version = version,
            tarball = tarball,
            sha256 = sha256Hex(tarball)
        )

    private fun writeTarGz(out: File, entries: List<TarEntry>) {
        // Use Apache Commons Compress to build a well-formed tar.gz;
        // the same library the production extractor reads with.
        // Round-tripping the same library in the test guarantees
        // header compatibility (mode, chksum, ustar magic, etc.).
        GzipCompressorOutputStream(out.outputStream().buffered()).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                for (entry in entries) {
                    val tarEntry = TarArchiveEntry(entry.path)
                    tarEntry.size = entry.content.toByteArray(Charsets.UTF_8).size.toLong()
                    tar.putArchiveEntry(tarEntry)
                    tar.write(entry.content.toByteArray(Charsets.UTF_8))
                    tar.closeArchiveEntry()
                }
            }
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
