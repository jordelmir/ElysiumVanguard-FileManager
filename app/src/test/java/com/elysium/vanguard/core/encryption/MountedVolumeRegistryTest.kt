package com.elysium.vanguard.core.encryption

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Registry behavior: canonical-path keys, mount/get/unmount, and the
 * mounted-path snapshot the resolver reads from the action context.
 */
class MountedVolumeRegistryTest {

    @get:Rule val tempFolder = TemporaryFolder()

    @Test
    fun `mount get isMounted unmount roundtrip`() {
        val dir = tempFolder.newFolder("v.encfs")
        val volume = EncFsVolume.create(dir, "pw".toCharArray())
        val registry = MountedVolumeRegistry()

        assertFalse(registry.isMounted(dir.absolutePath))
        registry.mount(dir.absolutePath, volume)
        assertTrue(registry.isMounted(dir.absolutePath))
        assertEquals(volume, registry.get(dir.absolutePath))
        // Keys are canonicalized (e.g. macOS /var -> /private/var).
        assertEquals(setOf(dir.canonicalPath), registry.mountedPaths())

        val removed = registry.unmount(dir.absolutePath)
        assertEquals(volume, removed)
        assertFalse(registry.isMounted(dir.absolutePath))
        assertNull(registry.get(dir.absolutePath))
        assertTrue(registry.mountedPaths().isEmpty())
    }

    @Test
    fun `keys are canonicalized - relative and absolute paths match`() {
        val dir = tempFolder.newFolder("v2.encfs")
        val volume = EncFsVolume.create(dir, "pw".toCharArray())
        val registry = MountedVolumeRegistry()

        val dotted = "${dir.absolutePath}/./../${dir.name}"
        registry.mount(dotted, volume)
        assertTrue(registry.isMounted(dir.absolutePath))
        assertEquals(volume, registry.unmount(dir.absolutePath))
    }

    @Test
    fun `unmount of an unknown path returns null`() {
        val registry = MountedVolumeRegistry()
        assertNull(registry.unmount("/nope/at/all"))
    }
}
