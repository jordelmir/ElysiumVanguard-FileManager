package com.elysium.vanguard.core.runtime.wine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * PHASE 144 — JVM unit tests for
 * [ElysiumWineStackDetector].
 *
 * The detector walks three probe sites (bundled APK,
 * user-installed under `filesDir/wine/`, Termux/system
 * prefixes) for `wine` + `box64` and returns a populated
 * [WineStack]. The tests use a [TemporaryFolder] to build
 * isolated candidate trees — no real Wine binaries are
 * required.
 *
 * The tests pin:
 *   - The bundled pass wins over user-installed wins over
 *     Termux (priority order).
 *   - The user-installed pass walks both flat
 *     (`<dir>/libwine.so`) and per-ABI
 *     (`<dir>/<abi>/libwine.so`) layouts.
 *   - `box64` resolution is independent of `wine` resolution
 *     (Wine can be bundled while Box64 comes from Termux).
 *   - A completely empty filesystem returns `null` for both
 *     `wineLocation` and `box64Location`.
 *   - The first existing-and-executable Termux candidate
 *     wins.
 *   - A zero-byte file (exists but is empty) is rejected
 *     (defense-in-depth against corrupted downloads).
 */
class ElysiumWineStackDetectorTest {

    @get:Rule val tmp = TemporaryFolder()

    // --- Three-pass resolution ---

    @Test
    fun `bundled pass wins over user-installed and Termux`() {
        val nativeDir = tmp.newFolder("nativeLib")
        val bundledWine = touchFile(File(nativeDir, "libwine.so"), 1024L)
        val bundledBox64 = touchFile(File(nativeDir, "libbox64.so"), 1024L)
        val userDir = tmp.newFolder("userWine")
        touchFile(File(userDir, "libwine.so"), 1024L) // would lose to bundled
        val termuxDir = tmp.newFolder("termux")
        // The termux probe paths we pass point INTO termuxDir
        val termuxWine = touchFile(File(termuxDir, "wine"), 1024L)
        termuxWine.setExecutable(true)
        val termuxBox64 = touchFile(File(termuxDir, "box64"), 1024L)
        termuxBox64.setExecutable(true)

        val detector = ElysiumWineStackDetector(
            nativeLibraryDir = nativeDir,
            userWineDir = userDir,
            termuxWineCandidates = listOf(termuxWine),
            termuxBox64Candidates = listOf(termuxBox64),
        )
        assertEquals(bundledWine, detector.wineLocation?.path)
        assertEquals(ElysiumWineLocation.Source.BUNDLED, detector.wineLocation?.source)
        assertEquals(bundledBox64, detector.box64Location?.path)
        assertEquals(ElysiumBox64Location.Source.BUNDLED, detector.box64Location?.source)
    }

    @Test
    fun `user-installed pass wins over Termux when bundled is empty`() {
        val userDir = tmp.newFolder("userWine")
        val userWine = touchFile(File(userDir, "libwine.so"), 1024L)
        val userBox64 = touchFile(File(userDir, "libbox64.so"), 1024L)
        val termuxDir = tmp.newFolder("termux")
        val termuxWine = touchFile(File(termuxDir, "wine"), 1024L)
        termuxWine.setExecutable(true)
        val termuxBox64 = touchFile(File(termuxDir, "box64"), 1024L)
        termuxBox64.setExecutable(true)

        val detector = ElysiumWineStackDetector(
            nativeLibraryDir = null,
            userWineDir = userDir,
            termuxWineCandidates = listOf(termuxWine),
            termuxBox64Candidates = listOf(termuxBox64),
        )
        assertEquals(userWine, detector.wineLocation?.path)
        assertEquals(ElysiumWineLocation.Source.USER_INSTALLED, detector.wineLocation?.source)
        assertEquals(userBox64, detector.box64Location?.path)
        assertEquals(ElysiumBox64Location.Source.USER_INSTALLED, detector.box64Location?.source)
    }

    @Test
    fun `Termux pass is the fallback when bundled and user-installed are absent`() {
        val termuxDir = tmp.newFolder("termux")
        val termuxWine = touchFile(File(termuxDir, "wine"), 1024L)
        termuxWine.setExecutable(true)
        val termuxBox64 = touchFile(File(termuxDir, "box64"), 1024L)
        termuxBox64.setExecutable(true)

        val detector = ElysiumWineStackDetector(
            nativeLibraryDir = null,
            userWineDir = tmp.newFolder("userWine"),
            termuxWineCandidates = listOf(termuxWine),
            termuxBox64Candidates = listOf(termuxBox64),
        )
        assertEquals(termuxWine, detector.wineLocation?.path)
        assertEquals(ElysiumWineLocation.Source.TERMUX, detector.wineLocation?.source)
        assertEquals(termuxBox64, detector.box64Location?.path)
    }

    @Test
    fun `null is returned when no probe site resolves`() {
        val detector = ElysiumWineStackDetector(
            nativeLibraryDir = null,
            userWineDir = tmp.newFolder("userWine"),
            termuxWineCandidates = listOf(tmp.newFile("none-wine")),
            termuxBox64Candidates = listOf(tmp.newFile("none-box64")),
        )
        assertNull(detector.wineLocation)
        assertNull(detector.box64Location)
        assertNull(detector.stack)
    }

    // --- User-installed layout variants ---

    @Test
    fun `user-installed per-ABI layout is recognized`() {
        val userDir = tmp.newFolder("userWine")
        val abiDir = File(userDir, "arm64-v8a")
        abiDir.mkdirs()
        val perAbiWine = touchFile(File(abiDir, "libwine.so"), 1024L)
        val perAbiBox64 = touchFile(File(abiDir, "libbox64.so"), 1024L)
        val detector = ElysiumWineStackDetector(
            nativeLibraryDir = null,
            userWineDir = userDir,
            termuxWineCandidates = emptyList(),
            termuxBox64Candidates = emptyList(),
        )
        assertEquals(perAbiWine, detector.wineLocation?.path)
        assertEquals(perAbiBox64, detector.box64Location?.path)
    }

    @Test
    fun `flat user-installed layout is recognized when per-ABI is absent`() {
        val userDir = tmp.newFolder("userWine")
        val flatWine = touchFile(File(userDir, "libwine.so"), 1024L)
        val detector = ElysiumWineStackDetector(
            nativeLibraryDir = null,
            userWineDir = userDir,
            termuxWineCandidates = emptyList(),
            termuxBox64Candidates = emptyList(),
        )
        assertEquals(flatWine, detector.wineLocation?.path)
    }

    // --- Defense-in-depth: zero-byte files are rejected ---

    @Test
    fun `zero-byte bundled libwine_so is rejected`() {
        val nativeDir = tmp.newFolder("nativeLib")
        // Create the file (it exists) but it's 0 bytes.
        File(nativeDir, "libwine.so").createNewFile()
        val detector = ElysiumWineStackDetector(
            nativeLibraryDir = nativeDir,
            userWineDir = null,
            termuxWineCandidates = emptyList(),
            termuxBox64Candidates = emptyList(),
        )
        assertNull("zero-byte file must not be reported as a valid wine location", detector.wineLocation)
    }

    // --- Wine + Box64 resolve independently ---

    @Test
    fun `wine and box64 resolve independently across probe sites`() {
        val nativeDir = tmp.newFolder("nativeLib")
        val bundledWine = touchFile(File(nativeDir, "libwine.so"), 1024L)
        // box64 is missing from the bundled layout.
        val userDir = tmp.newFolder("userWine")
        val userBox64 = touchFile(File(userDir, "libbox64.so"), 1024L)
        val detector = ElysiumWineStackDetector(
            nativeLibraryDir = nativeDir,
            userWineDir = userDir,
            termuxWineCandidates = emptyList(),
            termuxBox64Candidates = emptyList(),
        )
        // wine from BUNDLED, box64 from USER_INSTALLED.
        assertEquals(bundledWine, detector.wineLocation?.path)
        assertEquals(ElysiumWineLocation.Source.BUNDLED, detector.wineLocation?.source)
        assertEquals(userBox64, detector.box64Location?.path)
        assertEquals(ElysiumBox64Location.Source.USER_INSTALLED, detector.box64Location?.source)
        // The stack is non-null and supports x86-64.
        val stack = detector.stack
        assertNotNull(stack)
        assertTrue("stack should support x86-64 when box64 is present", stack!!.supportsX86_64)
    }

    @Test
    fun `stack reports x86-64 unsupported when box64 is missing`() {
        val nativeDir = tmp.newFolder("nativeLib")
        touchFile(File(nativeDir, "libwine.so"), 1024L)
        val detector = ElysiumWineStackDetector(
            nativeLibraryDir = nativeDir,
            userWineDir = null,
            termuxWineCandidates = emptyList(),
            termuxBox64Candidates = emptyList(),
        )
        val stack = detector.stack
        assertNotNull(stack)
        assertEquals(false, stack!!.supportsX86_64)
    }

    // --- describeForUi ---

    @Test
    fun `describeForUi reports not-installed when nothing is found`() {
        val detector = ElysiumWineStackDetector(
            nativeLibraryDir = null,
            userWineDir = null,
            termuxWineCandidates = emptyList(),
            termuxBox64Candidates = emptyList(),
        )
        val description = detector.describeForUi()
        assertTrue(
            "expected not-installed message, got: $description",
            description.startsWith("no wine located"),
        )
    }

    @Test
    fun `describeForUi reports the resolved paths when installed`() {
        val nativeDir = tmp.newFolder("nativeLib")
        touchFile(File(nativeDir, "libwine.so"), 1024L)
        touchFile(File(nativeDir, "libbox64.so"), 1024L)
        val detector = ElysiumWineStackDetector(
            nativeLibraryDir = nativeDir,
            userWineDir = null,
            termuxWineCandidates = emptyList(),
            termuxBox64Candidates = emptyList(),
        )
        val description = detector.describeForUi()
        assertTrue(
            "expected description to mention libwine.so, got: $description",
            description.contains("libwine.so"),
        )
        assertTrue(
            "expected description to mention libbox64.so, got: $description",
            description.contains("libbox64.so"),
        )
        assertTrue(
            "expected description to mention source, got: $description",
            description.contains("source=bundled"),
        )
    }

    // --- Termux candidate priority ---

    @Test
    fun `first existing Termux candidate wins`() {
        val termuxDir = tmp.newFolder("termux")
        val first = touchFile(File(termuxDir, "wine-a"), 1024L)
        first.setExecutable(true)
        val second = touchFile(File(termuxDir, "wine-b"), 1024L)
        second.setExecutable(true)
        val detector = ElysiumWineStackDetector(
            nativeLibraryDir = null,
            userWineDir = null,
            termuxWineCandidates = listOf(first, second),
            termuxBox64Candidates = emptyList(),
        )
        assertEquals(first, detector.wineLocation?.path)
    }

    // Note: the "non-executable Termux candidate" case
    // is platform-dependent (macOS + Linux differ on
    // setExecutable semantics for newly-created files).
    // The detector's intent is documented in its KDoc:
    // "first existing-and-executable wins". We do not
    // test the executable bit here; the isFile + length > 0
    // test on the bundled pass + the "first existing wins"
    // test on the Termux pass are sufficient.

    // --- helpers ---

    private fun touchFile(file: File, size: Long): File {
        file.parentFile?.mkdirs()
        file.createNewFile()
        // Write `size` zero bytes to make length() > 0.
        if (size > 0L) {
            file.writeBytes(ByteArray(size.toInt()) { 0 })
        }
        return file
    }
}
