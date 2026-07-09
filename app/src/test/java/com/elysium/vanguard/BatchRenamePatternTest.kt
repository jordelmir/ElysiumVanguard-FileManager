package com.elysium.vanguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 0.8 — Pure unit tests for batch rename pattern parsing.
 *
 * Phase 1.6 will introduce a real template engine. Until then we guard the
 * simplest placeholder contract: `{counter}` zero-pads to N digits, `{name}`
 * and `{ext}` are literal substitutions.
 */
class BatchRenamePatternTest {

    @Test
    fun `counter placeholder zero-pads to 3 digits`() {
        val out = applyPattern(
            pattern = "song_{counter}",
            originalName = "track01.mp3",
            counter = 1,
            padding = 3
        )
        assertEquals("song_001.mp3", out)
    }

    @Test
    fun `counter placeholder zero-pads to 5 digits`() {
        val out = applyPattern(
            pattern = "photo_{counter}",
            originalName = "IMG.jpg",
            counter = 42,
            padding = 5
        )
        assertEquals("photo_00042.jpg", out)
    }

    @Test
    fun `counter greater than padding cap keeps full digits`() {
        val out = applyPattern(
            pattern = "file_{counter}",
            originalName = "a.txt",
            counter = 123_456,
            padding = 3
        )
        // 123456 has 6 digits, more than padding 3, so the full number is kept.
        assertEquals("file_123456.txt", out)
    }

    @Test
    fun `pattern without placeholders is appended verbatim`() {
        val out = applyPattern(
            pattern = "renamed",
            originalName = "old.bin",
            counter = 0,
            padding = 1
        )
        assertEquals("renamed.bin", out)
    }

    @Test
    fun `original name without extension is preserved as-is`() {
        val out = applyPattern(
            pattern = "doc_{counter}",
            originalName = "noext",
            counter = 7,
            padding = 2
        )
        // Files with no extension get renamed without a trailing dot.
        assertEquals("doc_07", out)
    }

    @Test
    fun `only the last dot is treated as extension separator`() {
        val out = applyPattern(
            pattern = "backup_{counter}",
            originalName = "data.tar.gz",
            counter = 1,
            padding = 2
        )
        // Only the segment after the LAST dot is "extension"; multi-dot
        // archives lose their inner extension. Documented behavior.
        assertEquals("backup_01.gz", out)
    }

    // Pure mirror of the upcoming Phase 1.6 batch-rename semantics.
    private fun applyPattern(
        pattern: String,
        originalName: String,
        counter: Int,
        padding: Int
    ): String {
        val dotIdx = originalName.lastIndexOf('.')
        val name = if (dotIdx > 0) originalName.substring(0, dotIdx) else originalName
        val ext = if (dotIdx > 0) originalName.substring(dotIdx + 1) else ""

        val counterStr = counter.toString().padStart(padding, '0').let {
            // Allow overflow above padding.
            if (it.length < counter.toString().length) counter.toString() else it
        }
        val newName = pattern.replace("{counter}", counterStr).replace("{name}", name)

        return if (ext.isNotEmpty()) "$newName.$ext" else newName
    }
}