package com.elysium.vanguard.core.runtime.distros.custom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 9.6.3.1 — Tests for the URL validator. Network tests point at
 * a fake [CustomRootfsHttpProbe] to avoid hitting the real Internet.
 *
 * Phase 9.6.3.1 — first build; intentionally minimal.
 */
class CustomRootfsValidatorTest {

    private class FakeProbe(
        private val response: CustomRootfsHttpProbeResult
    ) : CustomRootfsHttpProbe {
        var lastUrl: String? = null
        override fun head(url: String): CustomRootfsHttpProbeResult {
            lastUrl = url
            return response
        }
    }

    @Test
    fun `probe flags a tarball as acceptable when HEAD returns 200`() {
        val probe = FakeProbe(
            CustomRootfsHttpProbeResult(
                reachable = true,
                statusCode = 200,
                contentLengthBytes = 60L * 1024L * 1024L,
                contentType = "application/gzip",
                etag = "\"abc\"",
                lastModified = "Thu, 09 Jul 2026 12:00:00 GMT"
            )
        )
        val validator = CustomRootfsValidator(probe)
        val result = validator.probe(
            "https://example.com/distros/alpine-mini.tar.gz"
        )
        assertTrue(result.reachable)
        assertEquals(200, result.statusCode)
        assertTrue(result.looksLikeTarball)
        assertEquals(CustomRootfsKind.TarGz, result.suggestedKind)
        assertTrue(result.isAcceptable)
    }

    @Test
    fun `probe flags a tar-xz as TarXz`() {
        val probe = FakeProbe(
            CustomRootfsHttpProbeResult(
                reachable = true,
                statusCode = 200,
                contentLengthBytes = 100L * 1024L * 1024L,
                contentType = "application/x-xz",
                etag = null,
                lastModified = null
            )
        )
        val validator = CustomRootfsValidator(probe)
        val result = validator.probe(
            "https://example.com/distros/ubuntu-base.tar.xz"
        )
        assertEquals(CustomRootfsKind.TarXz, result.suggestedKind)
        assertTrue(result.isAcceptable)
    }

    @Test
    fun `probe flags a tgz url as Tgz kind`() {
        val probe = FakeProbe(
            CustomRootfsHttpProbeResult(
                reachable = true,
                statusCode = 200,
                contentLengthBytes = 5L * 1024L * 1024L,
                contentType = "application/gzip",
                etag = null,
                lastModified = null
            )
        )
        val validator = CustomRootfsValidator(probe)
        val result = validator.probe("https://example.com/distros/foo.tgz")
        assertEquals(CustomRootfsKind.Tgz, result.suggestedKind)
        assertTrue(result.isAcceptable)
    }

    @Test
    fun `probe marks a non-tarball URL as not acceptable`() {
        val probe = FakeProbe(
            CustomRootfsHttpProbeResult(
                reachable = true,
                statusCode = 200,
                contentLengthBytes = 5_000_000L,
                contentType = "application/json",
                etag = null,
                lastModified = null
            )
        )
        val validator = CustomRootfsValidator(probe)
        val result = validator.probe("https://example.com/data.json")
        assertFalse(result.looksLikeTarball)
        assertFalse(result.isAcceptable)
    }

    @Test
    fun `probe rejects tarballs over 2GB`() {
        val probe = FakeProbe(
            CustomRootfsHttpProbeResult(
                reachable = true,
                statusCode = 200,
                contentLengthBytes = 3L * 1024L * 1024L * 1024L, // 3 GB
                contentType = "application/gzip",
                etag = null,
                lastModified = null
            )
        )
        val validator = CustomRootfsValidator(probe)
        val result = validator.probe("https://example.com/huge.tar.gz")
        assertTrue(result.looksLikeTarball)
        assertFalse(result.isAcceptable)
    }

    @Test
    fun `probe handles unreachable hosts gracefully`() {
        val probe = FakeProbe(
            CustomRootfsHttpProbeResult(
                reachable = false,
                statusCode = -1,
                contentLengthBytes = null,
                contentType = null,
                etag = null,
                lastModified = null
            )
        )
        val validator = CustomRootfsValidator(probe)
        val result = validator.probe("https://no-such-host.example/foo.tar.gz")
        assertFalse(result.reachable)
        assertFalse(result.isAcceptable)
        assertEquals(CustomRootfsKind.TarGz, result.suggestedKind)
    }

    @Test
    fun `blank URL is rejected before probe runs`() {
        val probe = FakeProbe(
            CustomRootfsHttpProbeResult(
                reachable = true,
                statusCode = 200,
                contentLengthBytes = 1L,
                contentType = null,
                etag = null,
                lastModified = null
            )
        )
        val validator = CustomRootfsValidator(probe)
        try {
            validator.probe("")
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // Probe should not have been called.
            assertNull(probe.lastUrl)
        }
    }
}
