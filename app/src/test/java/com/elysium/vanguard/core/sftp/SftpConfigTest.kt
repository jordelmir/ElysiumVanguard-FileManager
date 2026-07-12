package com.elysium.vanguard.core.sftp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * PHASE 2.4 — SFTP config tests.
 *
 * The config object is pure data + a password generator. We test the
 * generator and the default values here. The actual MINA SSHD integration
 * is exercised in instrumented tests (requires a network listener on
 * a real device).
 */
class SftpConfigTest {

    @Test fun `password is non-empty and reasonably long`() {
        val pw = SftpConfig.generatePassword()
        assertTrue("password should be >= 20 chars", pw.length >= 20)
    }

    @Test fun `passwords are unique across calls`() {
        val a = SftpConfig.generatePassword()
        val b = SftpConfig.generatePassword()
        assertFalse("two generated passwords should differ", a == b)
    }

    @Test fun `default port is 2222 - non-privileged and well-known for SFTP`() {
        assertEquals(2222, SftpConfig.DEFAULT_PORT)
    }

    @Test fun `default user is elysium`() {
        assertEquals("elysium", SftpConfig.DEFAULT_USER)
    }

    @Test fun `network binding is loopback unless LAN exposure is explicit`() {
        assertEquals("127.0.0.1", SftpConfig.DEFAULT_BIND_ADDRESS)
        assertEquals("0.0.0.0", SftpConfig.LAN_BIND_ADDRESS)
    }

    @Test fun `filesystem root spec carries the directory`() {
        val dir = File("/tmp/test")
        val cfg = SftpConfig(root = SftpConfig.RootSpec.Filesystem(dir))
        assertEquals(dir, (cfg.root as SftpConfig.RootSpec.Filesystem).dir)
    }
}
