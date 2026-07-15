package com.elysium.vanguard.core.runtime.distros.layer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Phase 12.2 — snapshot + updater tests.
 *
 * The [SystemLayerUpdater] orchestrates the apply of a manifest:
 * for each layer, it snapshots the previous live version (if any),
 * applies the new version, and prunes old snapshots. The
 * [SystemLayerSnapshot] can also roll back to a previous version
 * with downgrade protection.
 *
 * Tests run on real tarballs built with Apache Commons Compress so
 * the header format matches what [com.elysium.vanguard.core.runtime.distros.DistroRootfsExtractor]
 * expects in production.
 */
class SystemLayerUpdaterTest {

    @Test
    fun `apply a single layer and verify it lands in the rootfs`() {
        val rootfs = newRootfs()
        val tarball = newTarGz("cli-1.0.0", listOf(
            TarEntry("opt/cli/version.txt", "1.0.0\n"),
            TarEntry("opt/cli/elysium", "#!/bin/sh\n")
        ))
        try {
            val manifest = SystemLayerManifest(
                version = 1,
                channel = UpdateChannel.STABLE,
                generatedAtMs = 1_000L,
                layers = listOf(makeLayer("cli", "1.0.0", tarball))
            )
            val applied = SystemLayerUpdater().apply(manifest, rootfs)
            assertEquals(1, applied.size)
            assertEquals("1.0.0\n",
                File(rootfs, "elysium-layer-cli/opt/cli/version.txt").readText())
        } finally {
            cleanup(rootfs, tarball)
        }
    }

    @Test
    fun `apply a second layer and snapshot the first`() {
        val rootfs = newRootfs()
        val cliV1 = newTarGz("cli-1.0.0", listOf(
            TarEntry("opt/cli/version.txt", "1.0.0\n")
        ))
        try {
            // First apply: cli v1.
            val manifest1 = manifestOf(makeLayer("cli", "1.0.0", cliV1))
            SystemLayerUpdater().apply(manifest1, rootfs)
            assertTrue(
                File(rootfs, "elysium-layer-cli/opt/cli/version.txt").isFile
            )

            // Second apply: cli v2. The updater must have snapshotted
            // the v1 directory before the v2 apply.
            val cliV2 = newTarGz("cli-2.0.0", listOf(
                TarEntry("opt/cli/version.txt", "2.0.0\n")
            ))
            val manifest2 = manifestOf(makeLayer("cli", "2.0.0", cliV2))
            SystemLayerUpdater().apply(manifest2, rootfs)

            // The snapshot directory must have at least one entry.
            val snapshots = File(rootfs, "_snapshots").listFiles()
            assertNotNull("snapshots dir must exist", snapshots)
            assertTrue(
                "at least one snapshot expected; got ${snapshots!!.toList()}",
                snapshots.any { it.name.startsWith("cli@") }
            )
            // Live layer is v2.
            assertEquals(
                "2.0.0\n",
                File(rootfs, "elysium-layer-cli/opt/cli/version.txt").readText()
            )
            cliV2.delete()
        } finally {
            cleanup(rootfs, cliV1)
        }
    }

    @Test
    fun `apply with hash mismatch aborts before mutating the live layer`() {
        val rootfs = newRootfs()
        val tarball = newTarGz("cli-1.0.0", listOf(
            TarEntry("opt/cli/version.txt", "1.0.0\n")
        ))
        try {
            // Place a v0 layer first.
            File(rootfs, "elysium-layer-cli").mkdirs()
            File(rootfs, "elysium-layer-cli/opt/cli").mkdirs()
            File(rootfs, "elysium-layer-cli/opt/cli/version.txt").writeText("0.5\n")

            // Now declare a layer with a wrong hash.
            val wrong = SystemLayer(
                id = "cli",
                displayName = "CLI",
                version = "1.0.0",
                tarball = tarball,
                sha256 = "f".repeat(64)
            )
            val manifest = manifestOf(wrong)
            try {
                SystemLayerUpdater().apply(manifest, rootfs)
                fail("expected LayerHashMismatch")
            } catch (mismatch: LayerHashMismatch) {
                assertEquals("cli", mismatch.layer.id)
            }
            // The v0 layer must still be intact.
            assertEquals(
                "0.5\n",
                File(rootfs, "elysium-layer-cli/opt/cli/version.txt").readText()
            )
        } finally {
            cleanup(rootfs, tarball)
        }
    }

    @Test
    fun `apply multiple layers in declared order`() {
        val rootfs = newRootfs()
        val cli = newTarGz("cli", listOf(
            TarEntry("opt/cli/version.txt", "cli-1\n")
        ))
        val bridges = newTarGz("bridges", listOf(
            TarEntry("opt/elysium/bridges/version.txt", "bridges-1\n")
        ))
        try {
            val manifest = SystemLayerManifest(
                version = 1,
                channel = UpdateChannel.STABLE,
                generatedAtMs = 1_000L,
                layers = listOf(
                    makeLayer("cli", "1.0.0", cli),
                    makeLayer("bridges", "1.0.0", bridges)
                )
            )
            val applied = SystemLayerUpdater().apply(manifest, rootfs)
            assertEquals(2, applied.size)
            assertEquals("cli-1\n",
                File(rootfs, "elysium-layer-cli/opt/cli/version.txt").readText())
            assertEquals("bridges-1\n",
                File(rootfs, "elysium-layer-bridges/opt/elysium/bridges/version.txt").readText())
        } finally {
            cleanup(rootfs, cli)
            bridges.delete()
        }
    }

    @Test
    fun `snapshot prune keeps the latest N entries per layer`() {
        val rootfs = newRootfs()
        val v1 = newTarGz("cli-1.0.0", listOf(TarEntry("v", "1\n")))
        val v2 = newTarGz("cli-2.0.0", listOf(TarEntry("v", "2\n")))
        val v3 = newTarGz("cli-3.0.0", listOf(TarEntry("v", "3\n")))
        try {
            // Apply v1, v2, v3 in sequence. Each one creates a snapshot
            // of the previous live layer.
            val updater = SystemLayerUpdater(keepLatestSnapshots = 1)
            updater.apply(manifestOf(makeLayer("cli", "1.0.0", v1)), rootfs)
            updater.apply(manifestOf(makeLayer("cli", "2.0.0", v2)), rootfs)
            updater.apply(manifestOf(makeLayer("cli", "3.0.0", v3)), rootfs)

            val snapshots = File(rootfs, "_snapshots").listFiles { f -> f.isDirectory }
                ?.filter { it.name.startsWith("cli@") }
                ?: emptyList()
            // keepLatest=1 means we keep the most recent snapshot
            // (taken before v3 was applied, i.e. v2). The v1 snapshot
            // is pruned.
            assertEquals(
                "exactly one snapshot must remain after prune",
                1,
                snapshots.size
            )
            assertTrue(
                "the surviving snapshot must be the v2 one",
                snapshots.first().name.contains("2.0.0")
            )
        } finally {
            cleanup(rootfs, v1)
            v2.delete()
            v3.delete()
        }
    }

    @Test
    fun `rollback restores the most recent snapshot`() {
        val rootfs = newRootfs()
        val v1 = newTarGz("cli-1.0.0", listOf(TarEntry("v", "1\n")))
        val v2 = newTarGz("cli-2.0.0", listOf(TarEntry("v", "2\n")))
        try {
            val updater = SystemLayerUpdater(keepLatestSnapshots = 5)
            updater.apply(manifestOf(makeLayer("cli", "1.0.0", v1)), rootfs)
            updater.apply(manifestOf(makeLayer("cli", "2.0.0", v2)), rootfs)

            // Pass null for the live-sha check: we want a blind
            // restore from the most recent snapshot. (The strict
            // downgrade check is opt-in; see
            // [SystemLayerSnapshot.restore] for details.)
            val restored = updater.rollback("cli", rootfs)
            assertNotNull("rollback must return a directory", restored)
            // The rolled-back live layer is v1's content.
            assertEquals(
                "1\n",
                File(rootfs, "elysium-layer-cli/v").readText()
            )
        } finally {
            cleanup(rootfs, v1)
            v2.delete()
        }
    }

    @Test
    fun `rollback with no snapshot returns null`() {
        val rootfs = newRootfs()
        try {
            val restored = SystemLayerUpdater().rollback("cli", rootfs)
            assertEquals(null, restored)
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `manifest load rejects unknown schema version`() {
        val tmp = Files.createTempFile("elysium-manifest", ".json").toFile()
        try {
            tmp.writeText("""{"version": 99, "channel": "stable", "layers": []}""")
            try {
                SystemLayerManifest.load(tmp)
                fail("expected IllegalArgumentException")
            } catch (expected: IllegalArgumentException) {
                // The schema-version guard fires.
            }
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `manifest rejects duplicate id-version pairs`() {
        val rootfs = newRootfs()
        val tarball = newTarGz("cli", listOf(TarEntry("v", "1\n")))
        try {
            val layer = makeLayer("cli", "1.0.0", tarball)
            try {
                SystemLayerManifest(
                    version = 1,
                    channel = UpdateChannel.STABLE,
                    generatedAtMs = 1_000L,
                    layers = listOf(layer, layer)
                )
                fail("expected IllegalArgumentException for duplicate layer entry")
            } catch (expected: IllegalArgumentException) {
                // The manifest's init block rejects duplicates.
            }
        } finally {
            cleanup(rootfs, tarball)
        }
    }

    // --- helpers ---

    private data class TarEntry(val path: String, val content: String)

    private fun newRootfs(): File =
        Files.createTempDirectory("elysium-updater").toFile()

    private fun newTarGz(prefix: String, entries: List<TarEntry>): File {
        val tarball = Files.createTempFile("elysium-$prefix-", ".tar.gz").toFile()
        org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream(
            tarball.outputStream().buffered()
        ).use { gz ->
            org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(gz).use { tar ->
                for (entry in entries) {
                    val tarEntry = org.apache.commons.compress.archivers.tar.TarArchiveEntry(entry.path)
                    tarEntry.size = entry.content.toByteArray(Charsets.UTF_8).size.toLong()
                    tar.putArchiveEntry(tarEntry)
                    tar.write(entry.content.toByteArray(Charsets.UTF_8))
                    tar.closeArchiveEntry()
                }
            }
        }
        return tarball
    }

    private fun makeLayer(id: String, version: String, tarball: File): SystemLayer {
        val sha = sha256Hex(tarball)
        return SystemLayer(
            id = id,
            displayName = id,
            version = version,
            tarball = tarball,
            sha256 = sha
        )
    }

    private fun manifestOf(layer: SystemLayer): SystemLayerManifest =
        SystemLayerManifest(
            version = 1,
            channel = UpdateChannel.STABLE,
            generatedAtMs = 1_000L,
            layers = listOf(layer)
        )

    private fun sha256Hex(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
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

    private fun cleanup(rootfs: File, vararg tarballs: File) {
        rootfs.deleteRecursively()
        for (t in tarballs) t.delete()
    }
}
