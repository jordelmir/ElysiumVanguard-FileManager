package com.elysium.vanguard.core.server

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * PHASE 2.3 — TransferService filesystem path safety tests.
 *
 * The most security-sensitive code in the local server. These tests pin down that
 * `..` traversal cannot escape the configured root, and that the resolver rejects
 * absolute paths that point outside root.
 *
 * We only exercise the filesystem resolver here — the SAF resolver needs a real
 * ContentResolver and is covered by instrumented tests.
 */
class TransferServiceTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun service(root: File): TransferService {
        // We don't actually need a working Context for the resolver tests — the
        // supplier lambdas capture [root] and the suspend functions that touch the
        // ContentResolver aren't exercised here.
        return TransferService(
            context = null as android.content.Context?,
            safTreeUri = { null },
            fsRoot = { root }
        )
    }

    @Test fun `resolves legitimate nested file`() {
        val root = tmp.root
        val sub = File(root, "a/b").also { it.mkdirs() }
        val file = File(sub, "c.txt").also { it.writeText("ok") }
        val svc = service(root)
        val resolved = svc.resolveFsPath(root, "a/b/c.txt")
        assertEquals(file.canonicalPath, resolved?.canonicalPath)
    }

    @Test fun `rejects dot-dot traversal outside root`() {
        val root = tmp.root
        val svc = service(root)
        assertNull(svc.resolveFsPath(root, "../etc/passwd"))
        assertNull(svc.resolveFsPath(root, "a/../../escape"))
    }

    @Test fun `rejects absolute path outside root`() {
        val root = tmp.root
        val svc = service(root)
        assertNull(svc.resolveFsPath(root, "/etc/passwd"))
    }

    @Test fun `allows absolute path inside root`() {
        val root = tmp.root
        val inside = File(root, "inside.txt").also { it.writeText("hi") }
        val svc = service(root)
        val resolved = svc.resolveFsPath(root, inside.absolutePath)
        assertEquals(inside.canonicalPath, resolved?.canonicalPath)
    }

    @Test fun `dot segments that stay inside root resolve correctly`() {
        val root = tmp.root
        val sub = File(root, "a/b").also { it.mkdirs() }
        val file = File(sub, "c.txt").also { it.writeText("ok") }
        val svc = service(root)
        // ./a/./b/../b/c.txt should still resolve to the same file.
        val resolved = svc.resolveFsPath(root, "a/./b/../b/c.txt")
        assertEquals(file.canonicalPath, resolved?.canonicalPath)
    }

    @Test fun `list returns entries for an existing directory`() {
        val root = tmp.root
        File(root, "hello.txt").writeText("a")
        File(root, "dir").mkdirs()
        val svc = service(root)
        val entries = runBlocking { svc.list(null) }
        assertNotNull(entries)
        val names = entries!!.map { it.name }.toSet()
        assertTrue("hello.txt" in names)
        assertTrue("dir" in names)
    }

    @Test fun `list returns null for nonexistent directory`() {
        val svc = service(tmp.root)
        val entries = runBlocking { svc.list("does/not/exist") }
        assertNull(entries)
    }
}