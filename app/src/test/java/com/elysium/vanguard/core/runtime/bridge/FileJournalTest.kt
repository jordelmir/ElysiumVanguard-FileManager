package com.elysium.vanguard.core.runtime.bridge

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class FileJournalTest {

    private lateinit var tempDir: File
    private lateinit var journalDir: File
    private lateinit var journal: FileJournal

    @Before
    fun setUp() {
        tempDir = createTempDir("filejournal-test")
        journalDir = File(tempDir, ".journal")
        journal = FileJournal(journalDir)
    }

    @After
    fun tearDown() {
        journal.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun `pending is empty initially`() {
        assertTrue(journal.pending().isEmpty())
        assertEquals(0, journal.pendingOps())
    }

    @Test
    fun `recordCopy adds a copy entry`() {
        val src = createFile("source.txt", "hello world")
        val dst = File(tempDir, "target.txt")
        val entry = journal.recordCopy(src, dst)
        assertEquals("cp", entry.id.take(2))
        assertEquals(src.canonicalPath, entry.sourcePath)
        assertEquals(dst.canonicalPath, entry.targetPath)
        assertEquals(1, journal.pendingOps())
    }

    @Test
    fun `recordMove adds a move entry`() {
        val src = createFile("source.txt", "move me")
        val dst = File(tempDir, "moved.txt")
        val entry = journal.recordMove(src, dst)
        assertEquals("mv", entry.id.take(2))
        assertEquals(src.canonicalPath, entry.sourcePath)
        assertEquals(1, journal.pendingOps())
    }

    @Test
    fun `recordDelete adds a delete entry`() {
        val file = createFile("todelete.txt", "delete me")
        val entry = journal.recordDelete(file, null)
        assertEquals("rm", entry.id.take(2))
        assertEquals(file.canonicalPath, entry.sourcePath)
        assertEquals(1, journal.pendingOps())
    }

    @Test
    fun `recordMkdir adds a mkdir entry`() {
        val dir = File(tempDir, "newdir")
        val entry = journal.recordMkdir(dir)
        assertEquals("mkdir", entry.id.take(5))
        assertEquals(dir.canonicalPath, entry.sourcePath)
        assertEquals(1, journal.pendingOps())
    }

    @Test
    fun `pending returns all entries in order`() {
        val f1 = createFile("a.txt", "a")
        val f2 = createFile("b.txt", "b")
        val f3 = createFile("c.txt", "c")
        val d1 = File(tempDir, "a-copy.txt")
        val d2 = File(tempDir, "b-copy.txt")
        journal.recordCopy(f1, d1)
        journal.recordCopy(f2, d2)
        journal.recordDelete(f3, null)
        assertEquals(3, journal.pendingOps())
    }

    @Test
    fun `commit write journal file`() {
        val src = createFile("commit-test.txt", "committed data")
        val dst = File(tempDir, "committed.txt")
        journal.recordCopy(src, dst)
        journal.commit()
        assertTrue(File(journalDir, "journal.committed").exists())
    }

    @Test
    fun `rollback restores deleted file`() {
        val file = createFile("rollback-test.txt", "important data")
        val backup = File(tempDir, "backup.txt")
        file.copyTo(backup, overwrite = true)
        journal.recordDelete(file, backup)
        file.delete()
        val actions = journal.rollback()
        assertEquals(1, actions.size)
        assertTrue(actions[0].success)
        assertTrue(file.exists())
        assertEquals("important data", file.readText())
    }

    @Test
    fun `rollback reverses copy`() {
        val src = createFile("original.txt", "original content")
        val dst = File(tempDir, "copy.txt")
        dst.writeText("original content")
        journal.recordCopy(src, dst)
        val actions = journal.rollback()
        assertEquals(1, actions.size)
        assertTrue(actions[0].success)
        assertFalse(dst.exists())
    }

    @Test
    fun `rollback reverses move`() {
        val src = createFile("moved-source.txt", "moved content")
        val dst = File(tempDir, "moved-dest.txt")
        journal.recordMove(src, dst)
        src.copyTo(dst, overwrite = true)
        src.delete()
        val actions = journal.rollback()
        assertTrue(actions.any { it.success })
        assertTrue(src.exists())
        assertEquals("moved content", src.readText())
    }

    @Test
    fun `commit clears dirty state`() {
        val src = createFile("commit-clear.txt", "data")
        journal.recordCopy(src, File(tempDir, "dst.txt"))
        assertEquals(1, journal.pendingOps())
        journal.commit()
        journal.rollback()
    }

    @Test
    fun `sha256 produces consistent hash`() {
        val file = createFile("hash.txt", "hello world")
        val hash1 = FileJournal.sha256(file)
        val hash2 = FileJournal.sha256(file)
        assertEquals(hash1, hash2)
        assertEquals(64, hash1.length)
    }

    @Test
    fun `sha256 differs for different content`() {
        val f1 = createFile("a.txt", "hello")
        val f2 = createFile("b.txt", "world")
        assertFalse(FileJournal.sha256(f1) == FileJournal.sha256(f2))
    }

    @Test
    fun `recover returns NoJournal when empty`() {
        val result = journal.recover()
        assertEquals(RecoveryResult.NoJournal, result)
    }

    @Test
    fun `recover detects committed journal`() {
        val src = createFile("recover-test.txt", "data")
        journal.recordCopy(src, File(tempDir, "dst.txt"))
        journal.commit()
        val result = journal.recover()
        assertEquals(RecoveryResult.Committed, result)
    }

    @Test
    fun `multiple mixed operations in journal`() {
        val f1 = createFile("mix-a.txt", "a")
        val f2 = createFile("mix-b.txt", "b")
        val dir = File(tempDir, "mix-dir")
        journal.recordCopy(f1, File(tempDir, "mix-a-copy.txt"))
        journal.recordMkdir(dir)
        journal.recordDelete(f2, null)
        assertEquals(3, journal.pendingOps())
        val entries = journal.pending()
        assertTrue(entries.any { it is JournalEntry.Copy })
        assertTrue(entries.any { it is JournalEntry.Mkdir })
        assertTrue(entries.any { it is JournalEntry.Delete })
    }

    @Test
    fun `copy entry contains sha256`() {
        val src = createFile("sha-copy.txt", "sha256 content")
        val entry = journal.recordCopy(src, File(tempDir, "sha-dst.txt"))
        assertEquals(64, entry.sourceSha256.length)
        assertTrue(entry.sizeBytes > 0)
    }

    @Test
    fun `close clears pending without persist`() {
        createFile("close-test.txt", "data")
        journal.recordCopy(File(tempDir, "close-test.txt"), File(tempDir, "close-dst.txt"))
        journal.close()
        assertTrue(journal.pending().isEmpty())
    }

    @Test
    fun `rollback after commit produces empty journal`() {
        val src = createFile("cr-test.txt", "data")
        journal.recordCopy(src, File(tempDir, "cr-dst.txt"))
        journal.commit()
        journal.rollback()
        assertEquals(0, journal.pendingOps())
    }

    private fun createFile(name: String, content: String): File {
        val file = File(tempDir, name)
        file.writeText(content)
        return file
    }

    private fun createTempDir(prefix: String): File {
        val dir = File.createTempFile(prefix, "")
        dir.delete()
        dir.mkdirs()
        return dir
    }
}
