package com.elysium.vanguard.core.runtime.distros.custom

import com.elysium.vanguard.core.runtime.distros.Distro
import com.elysium.vanguard.core.runtime.distros.DistroFamily
import com.elysium.vanguard.core.runtime.distros.RootfsKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

/**
 * PHASE 9.6.3.2 — Tests for the custom rootfs pipeline.
 *
 * These tests construct a tar.gz in memory, point a fake downloader at
 * it, and run the installer against a temp directory. They verify the
 * happy path AND the failure paths so we don't ship a silent error.
 *
 * Phase 9.6.3.2 — first build; intentionally minimal.
 */
class CustomRootfsInstallerTest {

    /**
     * In-memory helper: returns a [Distro] the installer will accept.
     */
    private fun fakeDistro(): Distro = Distro(
        id = "custom-fake",
        displayName = "Fake Custom Distro",
        family = DistroFamily.DEBIAN,
        version = "9.9.9-test",
        approxSizeBytes = 1024L,
        minAndroidVersion = 26,
        rootfsUrl = "irrelevant://",
        rootfsKind = RootfsKind.Custom,
        bootstrapCommand = null,
        packageManager = "apt",
        homepage = "https://example.com/"
    )

    /**
     * Build a minimal tar archive (POSIX.1-1988 ustar) with one
     * regular file and one directory. The data is then optionally
     * gzipped depending on [gzip].
     */
    private fun buildTarBytes(gzip: Boolean): ByteArray {
        // The tar file we want:
        //   entry 1: directory "etc/"
        //   entry 2: regular file "etc/os-release" (content "NAME=elysium-test\n....\n")
        // Two 512-byte headers + payload + two 512-byte end-of-archive
        // blocks. We hand-roll because the existing extractor (9.6.2)
        // already proved this code path works.
        // Payload is exactly 22 bytes so the header size matches.
        // "NAME=elysium-test\n1234" is 22 chars (17 + \n + 4 digits).
        val payload = "NAME=elysium-test\n1234".toByteArray(Charsets.UTF_8)
        check(payload.size == 22) { "payload size drifted (got ${payload.size}); update the assertion" }
        val tarBytes = ByteArray(512 * 4)
        writeTarHeader(
            tarBytes, 0,
            name = "etc",
            size = 0L,
            typeFlag = '5'
        )
        writeTarHeader(
            tarBytes, 512,
            name = "etc/os-release",
            size = payload.size.toLong(),
            typeFlag = '0'
        )
        System.arraycopy(payload, 0, tarBytes, 1024, payload.size)
        return if (gzip) gzipEncode(tarBytes) else tarBytes
    }

    private fun writeTarHeader(buf: ByteArray, offset: Int, name: String, size: Long, typeFlag: Char) {
        // name (offset 0, length 100)
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        System.arraycopy(nameBytes, 0, buf, offset, nameBytes.size.coerceAtMost(100))
        // size (offset 124, length 12) octal
        val sizeStr = java.lang.Long.toOctalString(size)
        val sizeField = ("%-11s ".format(sizeStr)).toByteArray(Charsets.US_ASCII)
        System.arraycopy(sizeField, 0, buf, offset + 124, sizeField.size)
        // typeflag at offset 156
        buf[offset + 156] = typeFlag.code.toByte()
        // version "ustar\0" at offset 257
        val magic = "ustar".toByteArray(Charsets.US_ASCII)
        System.arraycopy(magic, 0, buf, offset + 257, 5)
    }

    private fun gzipEncode(raw: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(raw.size + 64)
        GZIPOutputStream(out).use { gz ->
            gz.write(raw)
            gz.finish()
        }
        return out.toByteArray()
    }

    private class FakeDownloader(private val bytes: ByteArray) :
        com.elysium.vanguard.core.runtime.distros.DistroHttpDownloader {
        var openedUrls: MutableList<String> = mutableListOf()
        override fun open(url: String): InputStream {
            openedUrls += url
            return ByteArrayInputStream(bytes)
        }
    }

    private class ThrowingDownloader(private val message: String) :
        com.elysium.vanguard.core.runtime.distros.DistroHttpDownloader {
        override fun open(url: String): InputStream =
            throw IOException(message)
    }

    @Test
    fun `install writes a manifest and creates rootfs dir`() {
        val base = Files.createTempDirectory("elysium-custom-base").toFile()
        try {
            val tar = buildTarBytes(gzip = true)
            val installer = CustomRootfsInstaller(downloader = FakeDownloader(tar))
            val result = installer.install(
                distro = fakeDistro(),
                baseDir = base,
                url = "https://example.com/foo.tar.gz",
                kind = CustomRootfsKind.TarGz
            )
            assertTrue(result.isSuccess)
            val target = File(File(base, "custom-fake"), "rootfs")
            assertTrue(target.isDirectory)
            val manifest = File(File(base, "custom-fake"), "manifest.json")
            assertTrue(manifest.isFile)
            val text = manifest.readText()
            assertTrue(text.contains("\"rootfsUrl\":\"https://example.com/foo.tar.gz\""))
            assertTrue(text.contains("\"rootfsKind\":\"TarGz\""))
            assertTrue(text.contains("\"installVia\":\"custom\""))
            assertTrue(File(File(base, "custom-fake"), "installed-via=custom").exists())
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `install extracts files into rootfs`() {
        val base = Files.createTempDirectory("elysium-custom-base").toFile()
        try {
            val tar = buildTarBytes(gzip = true)
            val installer = CustomRootfsInstaller(downloader = FakeDownloader(tar))
            val result = installer.install(
                distro = fakeDistro(),
                baseDir = base,
                url = "https://example.com/foo.tar.gz",
                kind = CustomRootfsKind.TarGz
            )
            assertTrue(result.isSuccess)
            val rootfs = File(File(base, "custom-fake"), "rootfs")
            assertTrue(File(rootfs, "etc").isDirectory)
            assertTrue(File(rootfs, "etc/os-release").isFile)
            assertEquals("NAME=elysium-test\n1234", File(rootfs, "etc/os-release").readText())
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `install fails cleanly when downloader throws`() {
        val base = Files.createTempDirectory("elysium-custom-base").toFile()
        try {
            val installer = CustomRootfsInstaller(
                downloader = ThrowingDownloader("simulated network error")
            )
            val result = installer.install(
                distro = fakeDistro(),
                baseDir = base,
                url = "https://example.com/foo.tar.gz",
                kind = CustomRootfsKind.TarGz
            )
            assertTrue(result.isFailure)
            // No phantom directory.
            assertEquals(0, base.listFiles()?.size ?: 0)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `install fails cleanly when target dir already exists`() {
        val base = Files.createTempDirectory("elysium-custom-base").toFile()
        try {
            File(base, "custom-fake").mkdirs()
            val tar = buildTarBytes(gzip = true)
            val installer = CustomRootfsInstaller(downloader = FakeDownloader(tar))
            val result = installer.install(
                distro = fakeDistro(),
                baseDir = base,
                url = "https://example.com/foo.tar.gz",
                kind = CustomRootfsKind.TarGz
            )
            assertTrue(result.isFailure)
            val existing = File(base, "custom-fake")
            assertTrue(existing.exists())
            // We didn't blow it away.
            assertEquals(0, existing.listFiles()?.size ?: 0)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `install supports raw tar without gzip wrapping`() {
        val base = Files.createTempDirectory("elysium-custom-base").toFile()
        try {
            val tar = buildTarBytes(gzip = false)
            val installer = CustomRootfsInstaller(downloader = FakeDownloader(tar))
            val result = installer.install(
                distro = fakeDistro(),
                baseDir = base,
                url = "https://example.com/foo.tar",
                kind = CustomRootfsKind.Tar
            )
            assertTrue(result.isSuccess)
            val rootfs = File(File(base, "custom-fake"), "rootfs")
            assertTrue(File(rootfs, "etc/os-release").isFile)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `install fires per-entry progress callbacks`() {
        val base = Files.createTempDirectory("elysium-custom-base").toFile()
        try {
            val tar = buildTarBytes(gzip = true)
            val installer = CustomRootfsInstaller(downloader = FakeDownloader(tar))
            val entriesSeen = ArrayList<String>()
            val result = installer.install(
                distro = fakeDistro(),
                baseDir = base,
                url = "https://example.com/foo.tar.gz",
                kind = CustomRootfsKind.TarGz,
                onProgress = { _, name -> entriesSeen += name }
            )
            assertTrue(result.isSuccess)
            assertTrue(entriesSeen.isNotEmpty())
            // At minimum we should see "etc" and "etc/os-release".
            assertTrue("etc" in entriesSeen)
            assertTrue("etc/os-release" in entriesSeen)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `install rejects unknown kind with a clear error`() {
        val base = Files.createTempDirectory("elysium-custom-base").toFile()
        try {
            val tar = buildTarBytes(gzip = true)
            val installer = CustomRootfsInstaller(downloader = FakeDownloader(tar))
            val result = installer.install(
                distro = fakeDistro(),
                baseDir = base,
                url = "https://example.com/foo.tar.gz",
                kind = CustomRootfsKind.Unknown
            )
            assertTrue(result.isFailure)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `pipeline refuses unacceptable URL before download`() {
        val base = Files.createTempDirectory("elysium-custom-base").toFile()
        try {
            val validator = CustomRootfsValidator()
            val installer = CustomRootfsInstaller(
                downloader = ThrowingDownloader("should not be called")
            )
            val pipeline = CustomRootfsPipeline(validator, installer)
            val result = pipeline.install(
                distro = fakeDistro(),
                baseDir = base,
                url = "https://example.com/data.json" // not a tarball
            )
            assertTrue(result.isFailure)
            val msg = result.exceptionOrNull()?.message ?: ""
            assertTrue("expected error to mention 'not acceptable', got: $msg",
                msg.contains("not acceptable"))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `pipeline isCustom recognizes sentinel files`() {
        val base = Files.createTempDirectory("elysium-custom-base").toFile()
        try {
            val realDir = File(base, "a-real-distro").apply { mkdirs() }
            File(realDir, "rootfs").mkdirs()
            val customDir = File(base, "a-custom-distro").apply { mkdirs() }
            File(customDir, "installed-via=custom").createNewFile()
            val pipeline = CustomRootfsPipeline(
                validator = CustomRootfsValidator(),
                installer = CustomRootfsInstaller(
                    downloader = ThrowingDownloader("unused")
                )
            )
            assertTrue(pipeline.isCustom(base, "a-custom-distro"))
            assertEquals(false, pipeline.isCustom(base, "a-real-distro"))
            val ids = pipeline.listCustomIds(base)
            assertEquals(listOf("a-custom-distro"), ids)
        } finally {
            base.deleteRecursively()
        }
    }
}
