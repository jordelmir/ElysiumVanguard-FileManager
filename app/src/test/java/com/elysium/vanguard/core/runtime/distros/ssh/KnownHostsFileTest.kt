package com.elysium.vanguard.core.runtime.distros.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * PHASE 9.6.11 — Tests for the OpenSSH known_hosts file parser.
 */
class KnownHostsFileTest {

    @Test
    fun `round trip preserves entries verbatim`() {
        val text = """
            my-host ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI0HKatL9g3oF6v6lK1lZjKL8rA1f+rV4D a-comment

            """.trimIndent()
        val parsed = KnownHostsFile.fromText(text)
        assertEquals(1, parsed.entries.size)
        assertEquals("my-host", parsed.entries[0].hostPattern)
        val regenerated = parsed.serialize()
        // Comments are dropped on serialize.
        assertTrue(!regenerated.contains("a-comment"))
        assertTrue(regenerated.contains("my-host"))
    }

    @Test
    fun `findMatching returns null for unknown hosts`() {
        val text = "hostA ssh-ed25519 AAAAb3NzaC1\n".toByteArray()
        val parsed = KnownHostsFile.fromText(String(text, Charsets.UTF_8))
        assertNull(parsed.findMatching("hostZ"))
    }

    @Test
    fun `findMatching returns the entry when host matches`() {
        val text = "hostA ssh-ed25519 AAAAb3NzaC1\n"
        val parsed = KnownHostsFile.fromText(text)
        assertNotNull(parsed.findMatching("hostA"))
    }

    @Test
    fun `findMatching honors comma-separated aliases`() {
        val text = "hostA,hostB,hostC ssh-ed25519 AAAAb3NzaC1\n"
        val parsed = KnownHostsFile.fromText(text)
        assertNotNull(parsed.findMatching("hostB"))
        assertNotNull(parsed.findMatching("hostC"))
        assertNull(parsed.findMatching("hostD"))
    }

    @Test
    fun `findMatching honors wildcard domains`() {
        val text = "*.example.com ssh-ed25519 AAAAb3NzaC1\n"
        val parsed = KnownHostsFile.fromText(text)
        assertNotNull(parsed.findMatching("server.example.com"))
        assertNotNull(parsed.findMatching("any.example.com"))
        // Wildcard doesn't extend to sub-domains.
        assertNull(parsed.findMatching("example.com"))
    }

    @Test
    fun `comments and blank lines are tolerated`() {
        val text = """
            # top comment
            hostA ssh-ed25519 AAAAb3NzaC1 a-comment

            # mid comment
            hostB ssh-ed25519 AAAAb3NzaC2 b-comment
        """.trimIndent()
        val parsed = KnownHostsFile.fromText(text)
        assertEquals(2, parsed.entries.size)
        assertEquals("a-comment", parsed.entries[0].comment)
        assertEquals("b-comment", parsed.entries[1].comment)
    }

    @Test
    fun `addOrReplace replaces by host pattern + key type`() {
        val original = KnownHostsFile.fromText("hostA ssh-ed25519 OLD\n")
        val updated = original.addOrReplace(
            KnownHostsFile.Entry("hostA", "ssh-ed25519", "NEW", null)
        )
        assertEquals(1, updated.entries.size)
        assertEquals("NEW", updated.entries[0].base64Key)
    }

    @Test
    fun `fromFile on a missing path returns empty list`() {
        val parsed = KnownHostsFile.fromFile(java.io.File("/nope/known_hosts"))
        assertEquals(0, parsed.entries.size)
    }

    @Test
    fun `fromFile on a real file reads the entries`() {
        val tmp = Files.createTempFile("elysium-known-hosts", "txt")
        try {
            tmp.toFile().writeText("hostA ssh-ed25519 AAA\nhostB ssh-ed25519 BBB\n")
            val parsed = KnownHostsFile.fromFile(tmp.toFile())
            assertEquals(2, parsed.entries.size)
        } finally {
            tmp.toFile().delete()
        }
    }
}
