package com.elysium.vanguard.core.runtime.distros.identity

import com.elysium.vanguard.core.runtime.distros.Distro
import com.elysium.vanguard.core.runtime.distros.DistroFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unit tests for Elysium Linux identity generation.
 */
class ElysiumLinuxIdentityTest {

    private val testDistro = Distro(
        id = "test-debian",
        displayName = "Test Debian",
        family = DistroFamily.DEBIAN,
        version = "12 (Bookworm)",
        approxSizeBytes = 100_000_000,
        minAndroidVersion = 26,
        rootfsUrl = "https://example.com/test.tar.gz",
        rootfsKind = com.elysium.vanguard.core.runtime.distros.RootfsKind.TarGz,
        bootstrapCommand = null,
        packageManager = "apt",
        homepage = "https://example.com",
        sha256 = "abc123"
    )

    @Test
    fun `os-release contains required fields`() {
        val osRelease = ElysiumLinuxIdentity.generateOsRelease(testDistro)
        assertTrue(osRelease.contains("NAME="))
        assertTrue(osRelease.contains("VERSION="))
        assertTrue(osRelease.contains("ID=debian"))
        assertTrue(osRelease.contains("PRETTY_NAME="))
        assertTrue("BUILD_ID=" in osRelease)
        assertTrue("elysium-" in osRelease)
        assertTrue("VARIANT=" in osRelease)
        assertTrue("Elysium Vanguard" in osRelease)
        assertTrue("PLATFORM_ID=" in osRelease)
    }

    @Test
    fun `os-release for Alpine uses musl ID`() {
        val alpine = testDistro.copy(
            id = "alpine-test",
            displayName = "Test Alpine",
            family = DistroFamily.MUSL
        )
        val osRelease = ElysiumLinuxIdentity.generateOsRelease(alpine)
        assertTrue(osRelease.contains("ID=musl"))
        assertTrue("ID_LIKE=" in osRelease)
        assertTrue("alpine" in osRelease)
    }

    @Test
    fun `os-release for Arch uses arch ID`() {
        val arch = testDistro.copy(
            id = "arch-test",
            displayName = "Test Arch",
            family = DistroFamily.ARCH
        )
        val osRelease = ElysiumLinuxIdentity.generateOsRelease(arch)
        assertTrue(osRelease.contains("ID=arch"))
        assertTrue("ID_LIKE=" in osRelease)
        assertTrue("arch" in osRelease)
    }

    @Test
    fun `rootfs manifest contains distro metadata`() {
        val manifest = ElysiumLinuxIdentity.generateRootfsManifest(
            distro = testDistro,
            rootfsDir = File("/tmp/test"),
            installedAtMs = 1234567890L
        )
        assertEquals("test-debian", manifest.distroId)
        assertEquals("Test Debian", manifest.distroDisplayName)
        assertEquals(DistroFamily.DEBIAN, manifest.distroFamily)
        assertEquals("12 (Bookworm)", manifest.distroVersion)
        assertEquals("apt", manifest.packageManager)
        assertEquals(1234567890L, manifest.installedAtMs)
    }

    @Test
    fun `rootfs manifest json contains distro info`() {
        val manifest = ElysiumLinuxIdentity.generateRootfsManifest(
            distro = testDistro,
            rootfsDir = File("/tmp/test")
        )
        val json = manifest.toJson()
        assertTrue(json.contains("\"distroId\":\"test-debian\""))
        assertTrue(json.contains("\"distroFamily\":\"DEBIAN\""))
        assertTrue(json.contains("\"sbom\":{"))
    }

    @Test
    fun `signed manifest contains signature`() {
        val manifest = ElysiumLinuxIdentity.generateRootfsManifest(
            distro = testDistro,
            rootfsDir = File("/tmp/test")
        )
        val signed = ElysiumLinuxIdentity.signManifest(manifest)
        assertTrue(signed.signature.isNotEmpty())
        assertEquals(64, signed.signature.length) // HMAC-SHA256 = 64 hex chars
        assertTrue(signed.signedAtMs > 0)
    }

    @Test
    fun `verifyRootfsIntegrity returns true when no expected hash`() {
        val result = ElysiumLinuxIdentity.verifyRootfsIntegrity(
            rootfsDir = File("/tmp/nonexistent"),
            expectedSha256 = null
        )
        assertTrue(result)
    }

    @Test
    fun `sbom contains package count`() {
        val sbom = SoftwareBillOfMaterials(
            packages = listOf(
                PackageEntry("bash", "5.2.15"),
                PackageEntry("coreutils", "9.4")
            ),
            totalPackages = 2,
            generatedAtMs = 1000L
        )
        val json = sbom.toJson()
        assertTrue(json.contains("\"totalPackages\":2"))
        assertTrue(json.contains("\"name\":\"bash\""))
        assertTrue(json.contains("\"version\":\"5.2.15\""))
    }
}
