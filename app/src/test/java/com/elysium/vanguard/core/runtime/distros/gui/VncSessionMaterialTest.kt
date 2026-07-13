package com.elysium.vanguard.core.runtime.distros.gui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class VncSessionMaterialTest {

    @Test
    fun `material lives under the rootfs supplies an ephemeral password and is deleted`() {
        val rootfs = Files.createTempDirectory("elysium-rootfs-").toFile()
        try {
            File(rootfs, "var").mkdirs()
            Files.createSymbolicLink(File(rootfs, "var/run").toPath(), Path.of("/run"))
            val material = VncSessionMaterial.create(rootfs)
            val password = material.passwordProvider.acquirePassword() ?: error("missing password")

            assertTrue(material.hostFile.isFile)
            assertTrue(material.guestPath.startsWith("/run/elysium-vnc/"))
            assertEquals(8, password.size)
            assertTrue(password.all { it.code in 0x20..0x7E })
            assertNotEquals(password.concatToString(), material.hostFile.readText())

            material.close()
            assertFalse(material.hostFile.exists())
            assertEquals(null, material.passwordProvider.acquirePassword())
        } finally {
            rootfs.deleteRecursively()
        }
    }
}
