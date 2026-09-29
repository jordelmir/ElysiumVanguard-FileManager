package com.elysium.vanguard.core.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Random

/**
 * On-device proof that the compression engine works on real Android
 * hardware and the app's scoped-storage filesystem — not just the JVM.
 *
 * Runs the exact production code paths (zip4j AES-256 create/extract,
 * split multi-part chains, tar.gz + mtime restoration) against
 * `context.cacheDir`.
 */
@RunWith(AndroidJUnit4::class)
class CompressionEngineDeviceTest {

    private lateinit var workDir: File

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        workDir = File(ctx.cacheDir, "ev_compress_device").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        workDir.deleteRecursively()
    }

    private fun sampleFiles(prefix: String): List<File> {
        val a = File(workDir, "${prefix}_a.txt")
        a.writeText("Hello from device!\n".repeat(40))
        val b = File(workDir, "${prefix}_b.bin")
        b.writeBytes(ByteArray(8192) { (it % 251).toByte() })
        return listOf(a, b)
    }

    private fun assertContentsBack(source: List<File>, extractDir: File, tag: String) {
        for (src in source) {
            val recovered = File(extractDir, src.name)
            assertTrue("$tag: ${src.name} missing", recovered.exists())
            assertArrayEquals(
                "$tag: ${src.name} bytes differ",
                src.readBytes(), recovered.readBytes()
            )
        }
    }

    private fun bytesContain(haystack: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    @Test
    fun zipPasswordAes_createAndExtractOnDevice() {
        val files = sampleFiles("aes")
        val out = File(workDir, "aes.zip")

        val r = CompressionEngine.compress(files, out, ArchiveFormat.ZIP, "device-secret")
        assertTrue("device compress failed: ${r.exceptionOrNull()}", r.isSuccess)
        assertTrue(out.length() > 0)

        // Plaintext must not survive into the protected archive.
        val haystack = out.readBytes()
        assertFalse(
            "plaintext leaked into AES archive on device",
            bytesContain(haystack, "Hello from device!".toByteArray())
        )

        val extractDir = File(workDir, "x_aes").apply { mkdirs() }
        val d = CompressionEngine.decompress(out, extractDir, "device-secret")
        assertTrue("device extract failed: ${d.exceptionOrNull()}", d.isSuccess)
        assertContentsBack(files, extractDir, "aes")

        // Wrong password must fail loudly.
        val bad = CompressionEngine.decompress(
            out, File(workDir, "x_aes_bad").apply { mkdirs() }, "wrong"
        )
        assertTrue("wrong password must fail on device", bad.isFailure)
    }

    @Test
    fun zipSplit_createAndExtractOnDevice() {
        val big = File(workDir, "split_src.bin")
            .apply { writeBytes(ByteArray(1_500_000).also { Random(21).nextBytes(it) }) }
        val out = File(workDir, "device_split.zip")
        val opts = CompressionEngine.CompressionOptions(
            splitSize = CompressionEngine.SplitSize.MB_1
        )

        val r = CompressionEngine.compress(listOf(big), out, ArchiveFormat.ZIP, null, null, opts)
        assertTrue("device split compress failed: ${r.exceptionOrNull()}", r.isSuccess)
        val z01 = File(workDir, "device_split.z01")
        assertTrue(
            "expected .z01 on device; siblings: ${workDir.list()?.joinToString()}",
            z01.exists()
        )

        val extractDir = File(workDir, "x_split").apply { mkdirs() }
        val d = CompressionEngine.decompress(z01, extractDir)
        assertTrue("device split extract failed: ${d.exceptionOrNull()}", d.isSuccess)
        assertArrayEquals(big.readBytes(), File(extractDir, big.name).readBytes())
    }

    @Test
    fun tarGz_preservesTimestampsOnDevice() {
        val src = File(workDir, "mtime.txt").apply { writeText("timestamped on device") }
        val old = System.currentTimeMillis() - 3L * 24 * 3600 * 1000
        assertTrue("setLastModified failed", src.setLastModified(old))

        val out = File(workDir, "device.tar.gz")
        val r = CompressionEngine.compress(listOf(src), out, ArchiveFormat.TAR_GZ)
        assertTrue("device tar.gz compress failed: ${r.exceptionOrNull()}", r.isSuccess)

        val extractDir = File(workDir, "x_tgz").apply { mkdirs() }
        val d = CompressionEngine.decompress(out, extractDir)
        assertTrue("device tar.gz extract failed: ${d.exceptionOrNull()}", d.isSuccess)

        val recovered = File(extractDir, "mtime.txt")
        assertTrue(recovered.exists())
        assertEquals("timestamped on device", recovered.readText())
        val delta = Math.abs(recovered.lastModified() - src.lastModified())
        assertTrue("device mtime drifted by ${delta}ms", delta <= 10_000)
    }
}
