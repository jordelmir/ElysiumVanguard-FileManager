package com.elysium.vanguard.core.runtime.distros.launcher

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * PHASE 9.6.4 — Tests for the proot native-library detector.
 *
 * We mock location paths because we don't have libproot.so in the unit
 * test classpath; the test only verifies the lookup loop and the
 * descriptor string surface.
 *
 * Phase 9.6.4 — first build; intentionally minimal.
 */
class ProotNativeLibraryTest {

    @Test
    fun `find bundled proot in Android native library directory`() {
        val nativeDir = Files.createTempDirectory("elysium-native-libs").toFile()
        try {
            File(nativeDir, "libproot.so").writeText("proot")
            val loader = File(nativeDir, "libproot_loader.so").apply { writeText("loader") }
            val probe = ProotNativeLibrary.default(
                abis = setOf("arm64-v8a"),
                nativeLibraryDir = nativeDir,
                userProotDir = null,
                termuxProotCandidates = emptyList()
            )

            val location = probe.location
            assertNotNull(location)
            assertEquals(ProotLocation.Source.BUNDLED, location!!.source)
            assertEquals(loader, location.loaderPath)
        } finally {
            nativeDir.deleteRecursively()
        }
    }

    @Test
    fun `null when nothing is available`() {
        val lib = ProotNativeLibrary.default(
            abis = setOf("arm64-v8a"),
            userProotDir = null,
            termuxProotCandidates = emptyList()
        )
        // Placeholder path never resolves on JVM; the library should
        // walk past it and return null.
        assertNull(lib.location)
        assertTrue(lib.describeForUi().contains("jailed", ignoreCase = true))
    }

    @Test
    fun `find libproot so in user-installed dir`() {
        val base = Files.createTempDirectory("elysium-proot-test").toFile()
        try {
            val abiDir = File(base, "arm64-v8a").apply { mkdirs() }
            val lib = File(abiDir, "libproot.so")
            lib.createNewFile()
            val probe = ProotNativeLibrary.default(
                abis = setOf("arm64-v8a"),
                userProotDir = base,
                termuxProotCandidates = emptyList()
            )
            val location = probe.location
            assertNotNull(location)
            assertEquals(ProotLocation.Source.USER_INSTALLED, location!!.source)
            assertEquals("arm64-v8a", location.abi)
            assertTrue(location.displayPath.contains("libproot.so"))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `find libproot so in flat user-installed dir`() {
        // Some users put a single libproot.so at the top of
        // filesDir/proot/ without per-ABI subdirs; support it.
        val base = Files.createTempDirectory("elysium-proot-flat").toFile()
        try {
            base.mkdirs()
            val lib = File(base, "libproot.so")
            lib.createNewFile()
            val probe = ProotNativeLibrary.default(
                abis = setOf("arm64-v8a"),
                userProotDir = base,
                termuxProotCandidates = emptyList()
            )
            val location = probe.location
            assertNotNull(location)
            assertEquals(ProotLocation.Source.USER_INSTALLED, location!!.source)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `user-installed wins over Termux when both exist`() {
        val user = Files.createTempDirectory("elysium-proot-user").toFile()
        val termux = Files.createTempDirectory("elysium-proot-termux").toFile()
        try {
            user.mkdirs()
            val userLib = File(user, "libproot.so").apply { createNewFile() }
            val termuxLib = File(termux, "libproot.so").apply { createNewFile() }
            val probe = ProotNativeLibrary.default(
                abis = setOf("arm64-v8a"),
                userProotDir = user,
                termuxProotCandidates = listOf(termuxLib)
            )
            val location = probe.location
            assertNotNull(location)
            assertEquals(ProotLocation.Source.USER_INSTALLED, location!!.source)
        } finally {
            user.deleteRecursively()
            termux.deleteRecursively()
        }
    }

    @Test
    fun `Termux is the last resort`() {
        val termux = Files.createTempDirectory("elysium-proot-termux").toFile()
        try {
            val termuxLib = File(termux, "libproot.so").apply { createNewFile() }
            val probe = ProotNativeLibrary.default(
                abis = setOf("arm64-v8a"),
                userProotDir = null,
                termuxProotCandidates = listOf(termuxLib)
            )
            val location = probe.location
            assertNotNull(location)
            assertEquals(ProotLocation.Source.TERMUX, location!!.source)
        } finally {
            termux.deleteRecursively()
        }
    }

    @Test
    fun `describeForUi returns the location source and path when found`() {
        val base = Files.createTempDirectory("elysium-proot-desc").toFile()
        try {
            base.mkdirs()
            File(base, "libproot.so").createNewFile()
            val probe = ProotNativeLibrary.default(
                abis = setOf("arm64-v8a"),
                userProotDir = base,
                termuxProotCandidates = emptyList()
            )
            val description = probe.describeForUi()
            assertTrue(description.contains("user_installed"))
            assertTrue(description.contains(".so"))
        } finally {
            base.deleteRecursively()
        }
    }
}

/**
 * PHASE 9.6.4 — Tests for the JNI bridge stub.
 *
 * We do NOT actually invoke the JNI bridge in JVM tests — there is no
 * Android `System.loadLibrary` and no proot_main symbol here. We
 * verify only the symbolic shape: the bridge exposes the right
 * methods, accepts the right argument shape, and returns null when
 * not loaded.
 */
class ProotNativeBridgeTest {

    @Test
    fun `bridge reports unloaded when proot_jni is not on the classpath`() {
        val bridge = ProotNativeBridgeStub()
        assertEquals(ProotNativeBridgeStub.LoadingState.UNLOADED, bridge.loadingState)
        // Running a probe should NOT throw — it just returns false.
        assertEquals(false, bridge.isLoaded())
    }
}
