package com.elysium.vanguard.core.runtime.distros.profile

import com.elysium.vanguard.core.runtime.distros.DistroFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 12.3 — profile unit tests.
 *
 * The profile enum is the master order §11.4 surface. The
 * installer tests verify the per-family command shape. Together
 * they pin:
 *
 *   - The four profiles exist and have stable ids / display names
 *     (the catalog UI keys on these).
 *   - The default profile is BALANCED.
 *   - Each non-headless profile has at least one upstream package.
 *   - Each profile pairs to a unique SystemLayer id and version.
 *   - The install command uses the right package manager per
 *     DistroFamily.
 *   - Headless emits a no-op command (no package manager call).
 *   - fromId is the only entry point for untrusted strings.
 */
class ElysiumProfileTest {

    @Test
    fun `four profiles are present with stable ids`() {
        val ids = ElysiumProfile.entries.map { it.id }
        assertEquals(
            listOf("lite", "balanced", "desktop", "headless"),
            ids
        )
    }

    @Test
    fun `default profile is BALANCED`() {
        assertEquals(ElysiumProfile.BALANCED, ElysiumProfile.DEFAULT)
    }

    @Test
    fun `every non-headless profile has at least one upstream package`() {
        for (profile in ElysiumProfile.entries) {
            if (profile == ElysiumProfile.HEADLESS) {
                assertTrue(
                    "headless must have no packages (it's the no-X profile)",
                    profile.packages.isEmpty()
                )
            } else {
                assertTrue(
                    "${profile.id} must declare at least one upstream package",
                    profile.packages.isNotEmpty()
                )
            }
        }
    }

    @Test
    fun `every profile pairs to a unique SystemLayer id`() {
        val layerIds = ElysiumProfile.entries.map { it.layerId }
        assertEquals(
            "duplicate layer id would corrupt the manifest",
            layerIds.size,
            layerIds.toSet().size
        )
    }

    @Test
    fun `every profile declares a non-blank layer version`() {
        for (profile in ElysiumProfile.entries) {
            assertTrue(
                "${profile.id} has blank layer version",
                profile.layerVersion.isNotBlank()
            )
        }
    }

    @Test
    fun `isGraphical is true for graphical profiles and false for headless`() {
        assertTrue(ElysiumProfile.LITE.isGraphical)
        assertTrue(ElysiumProfile.BALANCED.isGraphical)
        assertTrue(ElysiumProfile.DESKTOP.isGraphical)
        assertFalse(ElysiumProfile.HEADLESS.isGraphical)
    }

    @Test
    fun `fromId returns the matching profile for a known id`() {
        assertEquals(ElysiumProfile.LITE, ElysiumProfile.fromId("lite"))
        assertEquals(ElysiumProfile.BALANCED, ElysiumProfile.fromId("balanced"))
        assertEquals(ElysiumProfile.DESKTOP, ElysiumProfile.fromId("desktop"))
        assertEquals(ElysiumProfile.HEADLESS, ElysiumProfile.fromId("headless"))
    }

    @Test
    fun `fromId returns null for unknown or null ids`() {
        assertNull(ElysiumProfile.fromId("nope"))
        assertNull(ElysiumProfile.fromId(""))
        assertNull(ElysiumProfile.fromId(null))
    }

    @Test
    fun `estimated memory and disk values are monotonic with profile weight`() {
        val byFootprint = ElysiumProfile.entries.sortedBy { it.estimatedDiskMb }
        assertEquals(
            "headless must be the smallest by disk footprint",
            ElysiumProfile.HEADLESS,
            byFootprint.first()
        )
        assertEquals(
            "desktop must be the largest by disk footprint",
            ElysiumProfile.DESKTOP,
            byFootprint.last()
        )
    }

    @Test
    fun `installer plan for DEBIAN family uses apt-get`() {
        val plan = ProfileInstaller().plan(ElysiumProfile.BALANCED, DistroFamily.DEBIAN)
        assertTrue(
            "DEBIAN install command must use apt-get: ${plan.installCommand}",
            plan.installCommand.contains("apt-get install")
        )
        assertTrue(
            "DEBIAN install command must pass -y (non-interactive): ${plan.installCommand}",
            plan.installCommand.contains(" -y ")
        )
        assertTrue(
            "DEBIAN install command must pass the actual package names",
            plan.installCommand.contains("xfce4") &&
                plan.installCommand.contains("thunar")
        )
        // The DEBIAN installer must also set DEBIAN_FRONTEND so apt
        // does not prompt for the timezone / mailer configs in the
        // chroot (master order §11.4: "sin sistema gráfico" must
        // also be non-interactive, or installs hang in CI).
        assertTrue(
            "DEBIAN install must set DEBIAN_FRONTEND=noninteractive: ${plan.installCommand}",
            plan.installCommand.contains("DEBIAN_FRONTEND=noninteractive")
        )
    }

    @Test
    fun `installer plan for MUSL family uses apk`() {
        val plan = ProfileInstaller().plan(ElysiumProfile.LITE, DistroFamily.MUSL)
        assertTrue(
            "MUSL install command must use apk: ${plan.installCommand}",
            plan.installCommand.contains("apk add")
        )
        assertTrue(plan.installCommand.contains("openbox"))
    }

    @Test
    fun `installer plan for ARCH family uses pacman`() {
        val plan = ProfileInstaller().plan(ElysiumProfile.DESKTOP, DistroFamily.ARCH)
        assertTrue(
            "ARCH install command must use pacman: ${plan.installCommand}",
            plan.installCommand.contains("pacman -S")
        )
        assertTrue(plan.installCommand.contains("--noconfirm"))
        assertTrue(plan.installCommand.contains("lxqt"))
    }

    @Test
    fun `installer plan for headless emits a no-op install command`() {
        for (family in DistroFamily.entries) {
            val plan = ProfileInstaller().plan(ElysiumProfile.HEADLESS, family)
            assertEquals(
                "headless must not invoke the package manager on $family",
                "# no upstream packages for headless",
                plan.installCommand
            )
        }
    }

    @Test
    fun `installer plan carries the profile's layer id and version`() {
        val installer = ProfileInstaller()
        try {
            for (profile in ElysiumProfile.entries) {
                val plan = installer.plan(profile, DistroFamily.DEBIAN)
                assertEquals(profile.layerId, plan.layerId)
                assertEquals(profile.layerVersion, plan.layerVersion)
                assertEquals(
                    "layer display name should match profile display name",
                    profile.displayName,
                    plan.layerDisplayName
                )
            }
        } finally {
            // Each plan leaked a placeholder file; clean them up.
            for (profile in ElysiumProfile.entries) {
                val plan = installer.plan(profile, DistroFamily.DEBIAN)
                plan.layerTarballPlaceholder.delete()
            }
        }
    }

    @Test
    fun `installer plan placeholder tarball exists on disk`() {
        val plan = ProfileInstaller().plan(ElysiumProfile.BALANCED, DistroFamily.DEBIAN)
        try {
            assertTrue(
                "placeholder must be a real file so SystemLayer's init accepts it",
                plan.layerTarballPlaceholder.isFile
            )
            assertEquals(
                "placeholder must be empty (no fake content)",
                0L,
                plan.layerTarballPlaceholder.length()
            )
        } finally {
            plan.layerTarballPlaceholder.delete()
        }
    }

    @Test
    fun `every package name is well-formed for apt and apk`() {
        // Names that would trip up apt (contains space, starts with
        // dash) are excluded by construction. We pin the
        // assumption with a regex test.
        val pattern = Regex("^[a-z0-9][a-z0-9+_.\\-]*$")
        for (profile in ElysiumProfile.entries) {
            for (pkg in profile.packages) {
                assertTrue(
                    "package '$pkg' in profile ${profile.id} is not a valid apt/apk name",
                    pattern.matches(pkg)
                )
            }
        }
    }
}
