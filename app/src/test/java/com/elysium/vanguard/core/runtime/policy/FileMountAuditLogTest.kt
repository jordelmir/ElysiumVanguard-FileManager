package com.elysium.vanguard.core.runtime.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Phase 50 — tests for the [FileMountAuditLog].
 *
 * The audit log is the file-backed record of
 * every mount decision the [MountPolicyEnforcer]
 * has made. The tests pin:
 *
 *   - The file's parent directory is created on
 *     construction.
 *   - An empty log returns an empty readAll.
 *   - Append + readAll round-trip: every appended
 *     entry appears in readAll in append order.
 *   - Multiple appends survive across log
 *     instances (a fresh [FileMountAuditLog]
 *     reads what the previous one wrote).
 *   - Clear truncates the log.
 *   - Size is 0 for an empty log and > 0 after
 *     appends.
 *   - Decisions that are not in
 *     [MountAuditEntry.ALLOWED_DECISIONS] are
 *     rejected at construction.
 */
class FileMountAuditLogTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var logFile: File
    private lateinit var log: FileMountAuditLog

    @Before
    fun setUp() {
        logFile = File(tempFolder.newFolder("runtime"), "mount-audit.ndjson")
        log = FileMountAuditLog(logFile = logFile)
    }

    private fun makeEntry(
        atMs: Long,
        hostPath: String = "/sdcard/photos",
        decision: String = MountAuditEntry.DECISION_ALLOWED,
        reason: String = "test"
    ) = MountAuditEntry(
        atMs = atMs,
        workspaceId = "ws-test",
        sessionId = "s-test",
        hostPath = hostPath,
        guestPath = "/mnt/$hostPath",
        decision = decision,
        reason = reason
    )

    @Test
    fun `append creates the parent directory if missing`() {
        val newRoot = File(tempFolder.newFolder(), "fresh-root")
        assertFalse(newRoot.exists())
        val nestedFile = File(newRoot, "nested/mount-audit.ndjson")
        assertFalse(nestedFile.parentFile.exists())
        FileMountAuditLog(logFile = nestedFile)
        assertTrue(nestedFile.parentFile.exists())
    }

    @Test
    fun `empty log returns an empty readAll`() {
        assertTrue(log.readAll().isEmpty())
        assertTrue(log.isEmpty())
        assertEquals(0L, log.size())
    }

    @Test
    fun `append + readAll round-trip preserves every entry in append order`() {
        log.append(makeEntry(atMs = 1_000L, hostPath = "/sdcard/photos"))
        log.append(makeEntry(atMs = 2_000L, hostPath = "/sdcard/videos"))
        log.append(makeEntry(atMs = 3_000L, hostPath = "/sdcard/music"))
        val entries = log.readAll()
        assertEquals(3, entries.size)
        assertEquals(1_000L, entries[0].atMs)
        assertEquals("/sdcard/photos", entries[0].hostPath)
        assertEquals(2_000L, entries[1].atMs)
        assertEquals("/sdcard/videos", entries[1].hostPath)
        assertEquals(3_000L, entries[2].atMs)
        assertEquals("/sdcard/music", entries[2].hostPath)
    }

    @Test
    fun `survives a fresh log instance reading the same file`() {
        log.append(makeEntry(atMs = 1_000L, hostPath = "/sdcard/photos"))
        log.append(makeEntry(atMs = 2_000L, hostPath = "/sdcard/videos"))
        val freshLog = FileMountAuditLog(logFile = logFile)
        val entries = freshLog.readAll()
        assertEquals(2, entries.size)
        assertEquals(1_000L, entries[0].atMs)
        assertEquals(2_000L, entries[1].atMs)
    }

    @Test
    fun `clear truncates the log`() {
        log.append(makeEntry(atMs = 1_000L))
        log.append(makeEntry(atMs = 2_000L))
        assertEquals(2, log.readAll().size)
        log.clear()
        assertTrue(log.isEmpty())
        assertTrue(log.readAll().isEmpty())
    }

    @Test
    fun `size grows as entries are appended`() {
        assertEquals(0L, log.size())
        log.append(makeEntry(atMs = 1_000L))
        val sizeAfterOne = log.size()
        log.append(makeEntry(atMs = 2_000L))
        val sizeAfterTwo = log.size()
        assertTrue("size should grow after append: $sizeAfterOne -> $sizeAfterTwo", sizeAfterTwo > sizeAfterOne)
    }

    @Test
    fun `append escapes special characters in hostPath and reason`() {
        log.append(
            makeEntry(
                atMs = 1_000L,
                hostPath = "/path/with\"quote/and\\backslash",
                reason = "line1\nline2\ttab"
            )
        )
        val entries = log.readAll()
        assertEquals(1, entries.size)
        assertEquals("/path/with\"quote/and\\backslash", entries[0].hostPath)
        assertEquals("line1\nline2\ttab", entries[0].reason)
    }

    @Test
    fun `MountAuditEntry rejects an unknown decision`() {
        try {
            MountAuditEntry(
                atMs = 1_000L,
                workspaceId = "ws-1",
                sessionId = "s-1",
                hostPath = "/sdcard/photos",
                guestPath = "/mnt/photos",
                decision = "Maybe",
                reason = "test"
            )
            assert(false) { "expected IllegalArgumentException" }
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    @Test
    fun `MountAuditEntry accepts every documented decision`() {
        for (decision in MountAuditEntry.ALLOWED_DECISIONS) {
            val entry = MountAuditEntry(
                atMs = 1_000L,
                workspaceId = "ws-1",
                sessionId = "s-1",
                hostPath = "/sdcard/photos",
                guestPath = "/mnt/photos",
                decision = decision,
                reason = "test"
            )
            assertNotNull(entry)
            assertEquals(decision, entry.decision)
        }
    }
}
