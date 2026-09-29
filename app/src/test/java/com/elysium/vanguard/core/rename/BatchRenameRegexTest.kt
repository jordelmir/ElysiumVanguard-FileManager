package com.elysium.vanguard.core.rename

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Regex mode of the batch-rename engine: search/replace rules instead of
 * `{placeholder}` templates.
 */
class BatchRenameRegexTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val engine = BatchRenameEngine()

    private fun file(name: String, content: String = "x"): File =
        File(tempFolder.root, name).apply { writeText(content) }

    private fun regexPattern(
        pattern: String,
        replacement: String,
        ignoreCase: Boolean = false,
        wholeName: Boolean = false,
        conflict: BatchRenameEngine.ConflictResolution = BatchRenameEngine.ConflictResolution.SKIP,
    ) = BatchRenameEngine.Pattern(
        template = "", // unused in regex mode
        onConflict = conflict,
        regex = BatchRenameEngine.RegexRule(
            pattern = pattern,
            replacement = replacement,
            ignoreCase = ignoreCase,
            applyToWholeName = wholeName,
        ),
    )

    @Test
    fun `regex applies to the stem and keeps the extension`() {
        val a = file("IMG_001.jpg")
        val b = file("IMG_002.jpg")
        val plan = engine.plan(
            listOf(a, b),
            regexPattern(pattern = """^IMG_(\d+)$""", replacement = "photo_$1"),
        )
        assertEquals(2, plan.renames.size)
        assertEquals("photo_001.jpg", plan.renames[0].renamed.name)
        assertEquals("photo_002.jpg", plan.renames[1].renamed.name)
    }

    @Test
    fun `execute performs the regex rename on disk`() {
        val a = file("draft_report.txt")
        val plan = engine.plan(listOf(a), regexPattern(pattern = "draft", replacement = "final"))
        assertEquals(1, engine.execute(plan))
        assertTrue(File(tempFolder.root, "final_report.txt").exists())
        assertTrue(!File(tempFolder.root, "draft_report.txt").exists())
    }

    @Test
    fun `ignoreCase matches regardless of case`() {
        val a = file("DRAFT_notes.txt")
        val plan = engine.plan(
            listOf(a),
            regexPattern(pattern = "draft", replacement = "final", ignoreCase = true),
        )
        assertEquals("final_notes.txt", plan.renames[0].renamed.name)
    }

    @Test
    fun `applyToWholeName rewrites the extension`() {
        val a = file("holiday.jpeg")
        val plan = engine.plan(
            listOf(a),
            regexPattern(pattern = """\.jpe?g$""", replacement = ".jpg", wholeName = true),
        )
        assertEquals("holiday.jpg", plan.renames[0].renamed.name)
    }

    @Test
    fun `no match is an identity rename - execute succeeds without touching the file`() {
        val a = file("untouched.bin")
        val plan = engine.plan(listOf(a), regexPattern(pattern = "zzz", replacement = "yyy"))
        assertEquals(1, plan.renames.size)
        assertEquals(1, engine.execute(plan))
        assertTrue(File(tempFolder.root, "untouched.bin").exists())
    }

    @Test
    fun `two files collapsing to the same target honor conflict resolution`() {
        val a = file("a.txt")
        val b = file("A.txt")
        val plan = engine.plan(
            listOf(a, b),
            regexPattern(
                pattern = "a",
                replacement = "x",
                ignoreCase = true,
                conflict = BatchRenameEngine.ConflictResolution.SKIP,
            ),
        )
        assertEquals(1, plan.renames.size)
        assertEquals(1, plan.skipped.size)
    }

    @Test
    fun `empty replacement that blanks a name skips the file`() {
        val a = file("abc.txt")
        val plan = engine.plan(listOf(a), regexPattern(pattern = "abc", replacement = ""))
        assertTrue(plan.renames.isEmpty())
        assertEquals(1, plan.skipped.size)
    }

    @Test(expected = BatchRenameException::class)
    fun `invalid regex throws BatchRenameException`() {
        engine.plan(listOf(file("x.txt")), regexPattern(pattern = "([unclosed", replacement = "y"))
    }

    @Test
    fun `blank regex skips every file instead of throwing`() {
        val a = file("x.txt")
        val plan = engine.plan(listOf(a), regexPattern(pattern = "", replacement = "y"))
        assertTrue(plan.renames.isEmpty())
        assertEquals(1, plan.skipped.size)
    }

    @Test
    fun `template mode still works when regex is null`() {
        val a = file("x.txt")
        val plan = engine.plan(
            listOf(a),
            BatchRenameEngine.Pattern(template = "copy_{counter}"),
        )
        assertEquals("copy_001.txt", plan.renames[0].renamed.name)
    }
}
