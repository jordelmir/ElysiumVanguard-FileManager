package com.elysium.vanguard.core.runtime.distros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * PHASE 9.6.3.3 — Tests for the custom manifest parser.
 *
 * Phase 9.6.3.3 — first build; intentionally minimal.
 */
class CustomManifestParserTest {

    @Test
    fun `parseText reads back a well-formed alpine manifest`() {
        val parser = CustomManifestParser()
        val text = """
            {
              "distroId": "custom-alpine-minirootfs-3-21-2-aarch64-tar-gz",
              "displayName": "custom-alpine-minirootfs-3-21-2-aarch64-tar-gz",
              "version": "3.21",
              "packageManager": "apk",
              "rootfsUrl": "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.2-aarch64.tar.gz",
              "rootfsKind": "TarGz",
              "installedAtMs": 1752108765432,
              "installVia": "custom",
              "bytesWritten": 62914560,
              "entriesExtracted": 1234,
              "id": "alpine"
            }
        """.trimIndent()
        val distro = parser.parseText(text)
        assertNotNull(distro)
        distro!!
        assertEquals("custom-alpine-minirootfs-3-21-2-aarch64-tar-gz", distro.id)
        assertEquals("apk", distro.packageManager)
        assertEquals(RootfsKind.TarGz, distro.rootfsKind)
        assertEquals(DistroFamily.MUSL, distro.family)
        assertEquals(62914560L, distro.approxSizeBytes)
        assertEquals(
            "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.2-aarch64.tar.gz",
            distro.rootfsUrl
        )
    }

    @Test
    fun `parseText returns null when required fields are missing`() {
        val parser = CustomManifestParser()
        val text = """{ "version": "0" }"""
        assertNull(parser.parseText(text))
    }

    @Test
    fun `parseText returns null when the URL is blank`() {
        val parser = CustomManifestParser()
        val text = """
            { "distroId": "x", "rootfsUrl": " " }
        """.trimIndent()
        assertNull(parser.parseText(text))
    }

    @Test
    fun `parse falls back to DEBIAN for unknown ids`() {
        val parser = CustomManifestParser()
        val text = """
            { "distroId": "mystery-1", "rootfsUrl": "https://x/y.tar.gz", "id": "weirdo" }
        """.trimIndent()
        val distro = parser.parseText(text)
        assertNotNull(distro)
        assertEquals(DistroFamily.DEBIAN, distro!!.family)
    }

    @Test
    fun `parse detects arch family`() {
        val parser = CustomManifestParser()
        val text = """
            { "distroId": "x", "rootfsUrl": "https://x/y.tar.gz", "id": "arch" }
        """.trimIndent()
        val distro = parser.parseText(text)
        assertEquals(DistroFamily.ARCH, distro!!.family)
    }

    @Test
    fun `parse handles TarXz kind`() {
        val parser = CustomManifestParser()
        val text = """
            { "distroId": "x", "rootfsUrl": "https://x/y.tar.xz", "rootfsKind": "TarXz" }
        """.trimIndent()
        val distro = parser.parseText(text)
        assertEquals(RootfsKind.TarXz, distro!!.rootfsKind)
    }

    @Test
    fun `parse handles plain tar kind`() {
        val parser = CustomManifestParser()
        val text = """
            { "distroId": "x", "rootfsUrl": "https://x/y.tar", "rootfsKind": "Tar" }
        """.trimIndent()
        val distro = parser.parseText(text)
        assertEquals(RootfsKind.Custom, distro!!.rootfsKind)
    }

    @Test
    fun `parse returns null for missing file`() {
        val parser = CustomManifestParser()
        val missing = File("/no/such/path/manifest.json")
        assertNull(parser.parse(missing))
    }

    @Test
    fun `parse reads from a real file`() {
        val dir = Files.createTempDirectory("elysium-manifest-parser").toFile()
        try {
            val manifest = File(dir, "manifest.json")
            manifest.writeText(
                """
                { "distroId": "from-file", "rootfsUrl": "https://x/y.tar.gz" }
                """.trimIndent()
            )
            val parser = CustomManifestParser()
            val distro = parser.parse(manifest)
            assertNotNull(distro)
            assertEquals("from-file", distro!!.id)
        } finally {
            dir.deleteRecursively()
        }
    }
}

/**
 * PHASE 9.6.3.3 — Tests for [ProgressInputStream].
 */
class ProgressInputStreamTest {

    @Test
    fun `progressBytes increments per byte read`() {
        val inner = "hello world".byteInputStream(Charsets.UTF_8)
        var totalReported = 0L
        val progress = ProgressInputStream(inner) { n -> totalReported += n }
        val buf = ByteArray(64)
        var total = 0
        while (true) {
            val n = progress.read(buf, 0, buf.size)
            if (n < 0) break
            total += n
        }
        assertEquals(11, total)
        assertEquals(11L, progress.progressBytes)
        assertEquals(11L, totalReported)
    }

    @Test
    fun `progress does not increment when read returns -1`() {
        val inner = "ab".byteInputStream(Charsets.UTF_8)
        val progress = ProgressInputStream(inner) { /* never */ }
        val buf = ByteArray(64)
        progress.read(buf, 0, 64) // 2 bytes
        assertEquals(2L, progress.progressBytes)
        // next read returns -1
        val n = progress.read(buf, 0, 64)
        assertEquals(-1, n)
        assertEquals(2L, progress.progressBytes)
    }

    @Test
    fun `single-byte read works`() {
        val inner = "abc".byteInputStream(Charsets.UTF_8)
        val progress = ProgressInputStream(inner) { /* */ }
        assertEquals('a'.code, progress.read())
        assertEquals('b'.code, progress.read())
        assertEquals('c'.code, progress.read())
        assertEquals(-1, progress.read())
        assertEquals(3L, progress.progressBytes)
    }

    @Test
    fun `Adapter wraps a stream and reports progress`() {
        val inner = "xyz".byteInputStream(Charsets.UTF_8)
        val seen = ArrayList<Long>()
        val adapter = ProgressInputStream.Adapter { seen += it }
        val progress = adapter.wrap(inner)
        val b = ByteArray(8)
        progress.read(b, 0, 8)
        assertEquals(listOf<Long>(3), seen)
    }
}
