package com.elysium.vanguard.core.runtime.wine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Phase 54 — tests for [WineStack] +
 * [WinePrefix] + [Box64Config].
 *
 * The value types are the configuration
 * surface for the Wine + Box64 backend.
 * The tests pin:
 *
 *   - [WineStack] init-block invariants
 *     (non-blank winePath).
 *   - [WineStack.supportsX86_64] reflects
 *     the presence of box64.
 *   - [WineStack.supportsX86] reflects the
 *     presence of box86.
 *   - [WinePrefix] init-block invariants
 *     (architecture is "win64" or "win32").
 *   - [WinePrefix.initialise] creates the
 *     directory tree (drive_c, windows,
 *     system32).
 *   - [Box64Config.toEnvironment] emits
 *     the right `BOX64_*` env vars for the
 *     chosen translation mode.
 */
class WineStackTest {

    // --- WineStack ---

    @Test
    fun `WineStack rejects a blank winePath`() {
        try {
            WineStack(
                winePath = File(""),
                box64Path = null
            )
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    @Test
    fun `WineStack supports x86-64 when box64 is present`() {
        val stack = WineStack(
            winePath = File("/usr/bin/wine"),
            box64Path = File("/usr/bin/box64"),
            box86Path = null
        )
        assertTrue(stack.supportsX86_64)
        assertFalse(stack.supportsX86)
    }

    @Test
    fun `WineStack supports x86 when box86 is present`() {
        val stack = WineStack(
            winePath = File("/usr/bin/wine"),
            box64Path = null,
            box86Path = File("/usr/bin/box86")
        )
        assertFalse(stack.supportsX86_64)
        assertTrue(stack.supportsX86)
    }

    @Test
    fun `WineStack supports neither when no translator is present`() {
        val stack = WineStack(
            winePath = File("/usr/bin/wine"),
            box64Path = null,
            box86Path = null
        )
        assertFalse(stack.supportsX86_64)
        assertFalse(stack.supportsX86)
    }

    // --- WinePrefix ---

    @Test
    fun `WinePrefix rejects an unknown architecture`() {
        try {
            WinePrefix(
                path = File("/tmp/wine"),
                architecture = "winARM"
            )
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    @Test
    fun `WinePrefix accepts win64 and win32`() {
        val a = WinePrefix(path = File("/tmp/wine"), architecture = "win64")
        val b = WinePrefix(path = File("/tmp/wine"), architecture = "win32")
        assertEquals("win64", a.architecture)
        assertEquals("win32", b.architecture)
    }

    @Test
    fun `WinePrefix driveC points to drive_c under the prefix`() {
        val prefix = WinePrefix(path = File("/tmp/wine-prefix"))
        assertEquals(File("/tmp/wine-prefix/drive_c"), prefix.driveC)
    }

    @Test
    fun `WinePrefix system32 points to drive_c windows system32`() {
        val prefix = WinePrefix(path = File("/tmp/wine-prefix"))
        assertEquals(
            File("/tmp/wine-prefix/drive_c/windows/system32"),
            prefix.system32
        )
    }

    @Test
    fun `WinePrefix initialise creates the directory tree`() {
        val tempPrefix = createTempDir("wine-prefix")
        try {
            val prefix = WinePrefix(path = tempPrefix)
            prefix.initialise()
            assertTrue("prefix dir should exist", prefix.path.isDirectory)
            assertTrue("drive_c should exist", prefix.driveC.isDirectory)
            assertTrue(
                "system32 should exist",
                prefix.system32.isDirectory
            )
        } finally {
            tempPrefix.deleteRecursively()
        }
    }

    @Test
    fun `WinePrefix initialise is idempotent`() {
        val tempPrefix = createTempDir("wine-prefix")
        try {
            val prefix = WinePrefix(path = tempPrefix)
            prefix.initialise()
            prefix.initialise() // second call should not throw
            assertTrue(prefix.path.isDirectory)
        } finally {
            tempPrefix.deleteRecursively()
        }
    }

    // --- Box64Config ---

    @Test
    fun `Box64Config DEFAULT mode emits no BOX64 env vars`() {
        val env = Box64Config(translationMode = Box64Config.TranslationMode.DEFAULT)
            .toEnvironment()
        assertFalse(
            "DEFAULT mode should not emit BOX64_DYNAREC: $env",
            env.containsKey("BOX64_DYNAREC")
        )
    }

    @Test
    fun `Box64Config DYNAREC mode emits BOX64_DYNAREC=1`() {
        val env = Box64Config(translationMode = Box64Config.TranslationMode.DYNAREC)
            .toEnvironment()
        assertEquals("1", env["BOX64_DYNAREC"])
    }

    @Test
    fun `Box64Config library overrides emit BOX64_LD_LIBRARY_PATH`() {
        val env = Box64Config(
            libraryOverrides = listOf("/data/data/com.foo/lib", "/data/data/com.bar/lib")
        ).toEnvironment()
        assertEquals(
            "/data/data/com.foo/lib:/data/data/com.bar/lib",
            env["BOX64_LD_LIBRARY_PATH"]
        )
    }

    @Test
    fun `Box64Config custom environment vars pass through`() {
        val env = Box64Config(
            environmentVariables = mapOf("LANG" to "C.UTF-8", "DEBUG" to "1")
        ).toEnvironment()
        assertEquals("C.UTF-8", env["LANG"])
        assertEquals("1", env["DEBUG"])
    }

    // --- helpers ---

    private fun createTempDir(prefix: String): File {
        val tempDir = File.createTempFile(prefix, "")
        if (!tempDir.delete()) {
            fail("could not delete temp file ${tempDir.absolutePath}")
        }
        if (!tempDir.mkdirs()) {
            fail("could not create temp dir ${tempDir.absolutePath}")
        }
        return tempDir
    }
}
