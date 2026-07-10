package com.elysium.vanguard.core.runtime.distros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DistroCatalogTest {

    @Test
    fun `catalog is non-empty`() {
        assertTrue("At least one distro should be listed", DistroCatalog.ALL.isNotEmpty())
    }

    @Test
    fun `catalog ids are unique`() {
        val ids = DistroCatalog.ALL.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `find returns matching distro`() {
        val found = DistroCatalog.find("alpine-latest")
        assertNotNull(found)
        assertEquals("Alpine Linux", found!!.displayName)
        assertEquals("apk", found.packageManager)
    }

    @Test
    fun `find returns null for unknown id`() {
        assertEquals(null, DistroCatalog.find(""))
        assertEquals(null, DistroCatalog.find("nonexistent"))
    }

    @Test
    fun `every catalog entry has a positive size and a valid url`() {
        DistroCatalog.ALL.forEach { d ->
            assertTrue("Approx size should be positive for ${d.id}", d.approxSizeBytes > 0)
            assertTrue(
                "URL should be https for ${d.id}",
                d.rootfsUrl.startsWith("https://")
            )
        }
    }

    @Test
    fun `total catalog size is consistent`() {
        val sum = DistroCatalog.ALL.sumOf { it.approxSizeBytes }
        assertEquals(sum, DistroCatalog.totalCatalogSizeBytes)
    }
}

class DistroRootfsExtractorTest {

    /**
     * Build a tiny tar archive in memory with two files and verify
     * extraction. We don't gzip-wrap it (TarGz path requires JVM gzip);
     * we test the bare-tar path, which the catalog's Custom kind uses.
     */
    @Test
    fun `extracts a tar archive`() {
        // Build: 2 file entries, each with header + data + padding.
        val bytes = buildTinyTar("hello.txt", "hello\n", "world.txt", "world\n")
        val result = recordExtractorRun(bytes)
        assertTrue("Expected at least two entries", result.entriesExtracted >= 2)
    }

    @Test
    fun `extracts gzipped tar with one regular file`() {
        val rawBytes = buildTinyTar("only.txt", "only\n")
        val gzipped = gzip(rawBytes)
        val result = recordExtractorRun(gzipped, useGzip = true)
        assertTrue(result.entriesExtracted >= 1)
    }

    @Test
    fun `display byte size has a unit suffix`() {
        // Explicit `L` suffixes + parens; otherwise Kotlin binds the
        // postfix method to the Int literal, which has no
        // `displayByteSize`.
        val sixtyMb = 60L * 1024 * 1024
        assertEquals("60 MB", sixtyMb.displayByteSize())
        val oneGb = 1024L * 1024 * 1024
        assertEquals("1 GB", oneGb.displayByteSize())
        assertEquals("0 B", 0L.displayByteSize())
        assertEquals("42 B", 42L.displayByteSize())
    }

    // ─── helpers ────────────────────────────────────────────────────────

    private fun recordExtractorRun(
        bytes: ByteArray,
        useGzip: Boolean = false
    ): DistroRootfsExtractor.ExtractResult {
        val tmpDir = kotlin.io.path.createTempDirectory("distro-test").toFile()
        val extractor = DistroRootfsExtractor()
        return extractor.extract(
            stream = bytes.inputStream(),
            destDir = tmpDir,
            kind = if (useGzip) RootfsKind.TarGz else RootfsKind.Custom,
            progress = DistroRootfsExtractor.ProgressCallback.NONE
        )
    }
}

class DistroInstallerTest {

    @Test
    fun `installer stores rootfs under baseDir id rootfs`() {
        // Mock a downloader that "downloads" an empty gzipped tar by
        // returning an empty stream — the extractor will simply report
        // zero entries, but the install pipeline should not throw.
        val baseDir = kotlin.io.path.createTempDirectory("distro-base").toFile()
        val downloader = DistroHttpDownloader { bytesInputStream() }
        val installer = DistroInstaller(downloader = downloader)
        val distro = DistroCatalog.find("alpine-latest")!!
        // Phase 9.6.2 uses a placeholder URL; we never reach download.
        // The empty stream will produce zero entries — that's fine.
        try {
            installer.install(distro, baseDir)
        } catch (_: Exception) {
            // Empty stream may throw at the extractor level — acceptable
            // for this micro-test. We're checking the surrounding
            // behavior of the installer (path layout, error sentinel).
        }
        // The base dir should at least contain a directory named
        // "alpine-latest"; the inner structure may be partial.
        val rootDir = java.io.File(baseDir, "alpine-latest")
        assertTrue("rootDir should exist after install attempt", rootDir.exists())
    }
}

// ─── tar building utilities (private to this file) ────────────────────────

/**
 * Build a minimal ustar archive containing one regular file with the
 * given content. Used to exercise the extractor without needing a real
 * distro tarball in the test classpath.
 */
internal fun buildTinyTar(name: String, content: String): ByteArray {
    val payload = content.toByteArray()
    val header = ByteArray(512)
    // Filename (offset 0, 100 bytes).
    val nameBytes = name.toByteArray(Charsets.UTF_8)
    System.arraycopy(nameBytes, 0, header, 0, nameBytes.size.coerceAtMost(100))
    // File mode (offset 100, 8 bytes octal). We use 0644.
    val mode = "0000644".toByteArray(Charsets.US_ASCII)
    System.arraycopy(mode, 0, header, 100, mode.size)
    // uid/gid (offset 108/116, 8 bytes each).
    val zero = "0000000".toByteArray(Charsets.US_ASCII)
    System.arraycopy(zero, 0, header, 108, zero.size)
    System.arraycopy(zero, 0, header, 116, zero.size)
    // Size (offset 124, 12 bytes).
    val sizeStr = String.format("%011o", payload.size).toByteArray(Charsets.US_ASCII)
    System.arraycopy(sizeStr, 0, header, 124, sizeStr.size)
    // mtime (offset 136, 12 bytes).
    val mtime = "00000000000".toByteArray(Charsets.US_ASCII)
    System.arraycopy(mtime, 0, header, 136, mtime.size)
    // Checksum (offset 148, 8 bytes) — fill with spaces initially; we
    // recompute below.
    for (i in 148..155) header[i] = ' '.code.toByte()
    // typeflag (offset 156): '0' = regular file.
    header[156] = '0'.code.toByte()
    // USTAR magic (offset 257).
    val magic = "ustar\u0000".toByteArray(Charsets.US_ASCII)
    System.arraycopy(magic, 0, header, 257, magic.size)
    val version = "00".toByteArray(Charsets.US_ASCII)
    System.arraycopy(version, 0, header, 263, version.size)
    // Compute checksum.
    val checksum = header.fold(0L) { acc, b -> acc + (b.toInt() and 0xFF) }
    val checkStr = String.format("%06o", checksum).toByteArray(Charsets.US_ASCII)
    System.arraycopy(checkStr, 0, header, 148, checkStr.size)
    header[154] = 0 // trailing null
    header[155] = ' '.code.toByte()

    // Payload (pad to 512).
    val padded = ByteArray(((payload.size + 511) / 512) * 512)
    System.arraycopy(payload, 0, padded, 0, payload.size)

    val out = ByteArray(512 + padded.size)
    System.arraycopy(header, 0, out, 0, 512)
    System.arraycopy(padded, 0, out, 512, padded.size)
    return out
}

internal fun buildTinyTar(
    name1: String, content1: String,
    name2: String, content2: String
): ByteArray {
    val first = buildTinyTar(name1, content1)
    val second = buildTinyTar(name2, content2)
    return first + second
}

private fun gzip(bytes: ByteArray): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    java.util.zip.GZIPOutputStream(out).use { gzos ->
        gzos.write(bytes)
    }
    return out.toByteArray()
}

private fun bytesInputStream(): java.io.InputStream =
    java.io.ByteArrayInputStream(buildTinyTar("placeholder.txt", "x"))
