package com.elysium.vanguard.core.rename

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BatchRenameEngineTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private fun makeFile(name: String, content: String = "x", modified: Long = 0L): File {
        val f = File(tempFolder.root, name)
        f.parentFile?.mkdirs()
        f.writeText(content)
        if (modified > 0) f.setLastModified(modified)
        return f
    }

    private val engine = BatchRenameEngine()

    @Test
    fun `counter placeholder zero-pads`() {
        val f = makeFile("a.jpg")
        val plan = engine.plan(listOf(f), BatchRenameEngine.Pattern("img_{counter}", counterPadding = 3))
        assertEquals(1, plan.renames.size)
        assertEquals("img_001.jpg", plan.renames[0].renamed.name)
    }

    @Test
    fun `date placeholder uses file lastModified`() {
        val date = 1_710_000_000_000L // 2024-03-09 ish
        val f = makeFile("a.jpg", modified = date)
        val plan = engine.plan(listOf(f), BatchRenameEngine.Pattern("photo_{date}"))
        assertEquals(1, plan.renames.size)
        // The exact format depends on timezone; assert the year at least.
        assertTrue(plan.renames[0].renamed.name.contains("2024"))
    }

    @Test
    fun `name placeholder keeps the original stem`() {
        val f = makeFile("hello.jpg")
        val plan = engine.plan(listOf(f), BatchRenameEngine.Pattern("{name}_v2"))
        assertEquals("hello_v2.jpg", plan.renames[0].renamed.name)
    }

    @Test
    fun `uppercaseExt flag uppercases the extension`() {
        val f = makeFile("a.jpg")
        val plan = engine.plan(listOf(f), BatchRenameEngine.Pattern("img_{counter}", uppercaseExt = true))
        assertEquals("img_001.JPG", plan.renames[0].renamed.name)
    }

    @Test
    fun `startAt offset`() {
        val f = makeFile("a.jpg")
        val plan = engine.plan(listOf(f), BatchRenameEngine.Pattern("img_{counter}", startAt = 100, counterPadding = 4))
        assertEquals("img_0100.jpg", plan.renames[0].renamed.name)
    }

    @Test
    fun `skip conflict resolution leaves target untouched`() {
        // Pre-existing target file should cause a skip.
        val source = makeFile("a.jpg")
        val existing = makeFile("img_001.jpg")
        val plan = engine.plan(
            listOf(source),
            BatchRenameEngine.Pattern("img_{counter}", onConflict = BatchRenameEngine.ConflictResolution.SKIP)
        )
        assertEquals(0, plan.renames.size)
        assertEquals(1, plan.skipped.size)
    }

    @Test
    fun `append suffix resolves conflicts`() {
        val source = makeFile("a.jpg")
        makeFile("img_001.jpg")
        makeFile("img_001 (1).jpg")
        val plan = engine.plan(
            listOf(source),
            BatchRenameEngine.Pattern("img_{counter}", onConflict = BatchRenameEngine.ConflictResolution.APPEND_SUFFIX)
        )
        assertEquals(1, plan.renames.size)
        assertEquals("img_001 (2).jpg", plan.renames[0].renamed.name)
    }

    @Test
    fun `abort stops on first conflict`() {
        val a = makeFile("a.jpg")
        val b = makeFile("b.jpg")
        makeFile("img_001.jpg")
        val plan = engine.plan(
            listOf(a, b),
            BatchRenameEngine.Pattern("img_{counter}", onConflict = BatchRenameEngine.ConflictResolution.ABORT)
        )
        assertTrue(plan.aborted)
        assertEquals(0, plan.renames.size)
    }

    @Test
    fun `execute renames files on disk`() {
        val a = makeFile("a.jpg", content = "alpha")
        val b = makeFile("b.jpg", content = "beta")
        val plan = engine.plan(listOf(a, b), BatchRenameEngine.Pattern("img_{counter}"))
        val ok = engine.execute(plan)
        assertEquals(2, ok)
        assertFalse(a.exists())
        assertFalse(b.exists())
        assertTrue(File(tempFolder.root, "img_001.jpg").exists())
        assertTrue(File(tempFolder.root, "img_002.jpg").exists())
        assertEquals("alpha", File(tempFolder.root, "img_001.jpg").readText())
    }

    @Test
    fun `blank template skips everything`() {
        val a = makeFile("a.jpg")
        val plan = engine.plan(listOf(a), BatchRenameEngine.Pattern(""))
        assertEquals(0, plan.renames.size)
        assertEquals(1, plan.skipped.size)
    }
}