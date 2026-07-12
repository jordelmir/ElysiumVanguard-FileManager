package com.elysium.vanguard.core.runtime.distros.gui

import com.elysium.vanguard.core.runtime.distros.launcher.LauncherKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GraphicalDesktopCapabilityTest {
    @Test
    fun `missing rootfs never claims a graphical surface`() {
        val capability = GraphicalDesktopCapabilityDetector.inspect(null, null)
        assertEquals(GraphicalDesktopCapability.State.ROOTFS_UNAVAILABLE, capability.state)
        assertFalse(capability.canRenderDesktop)
    }

    @Test
    fun `a normal rootfs exposes terminal-only status until a server exists`() {
        val rootfs = kotlin.io.path.createTempDirectory("desktop-capability").toFile()
        val capability = GraphicalDesktopCapabilityDetector.inspect(rootfs, LauncherKind.NATIVE_PROOT)
        assertEquals(GraphicalDesktopCapability.State.TERMINAL_READY, capability.state)
        assertFalse(capability.canRenderDesktop)
    }

    @Test
    fun `a detected server is not misrepresented as a rendered desktop`() {
        val rootfs = kotlin.io.path.createTempDirectory("desktop-server").toFile()
        val server = File(rootfs, "usr/bin/Xvnc").apply {
            parentFile?.mkdirs()
            writeText("binary placeholder")
            setExecutable(true)
        }
        val capability = GraphicalDesktopCapabilityDetector.inspect(rootfs, LauncherKind.NATIVE_PROOT)
        assertTrue(server.canExecute())
        assertEquals(GraphicalDesktopCapability.State.SERVER_DETECTED_RENDERER_UNAVAILABLE, capability.state)
        assertEquals("usr/bin/Xvnc", capability.detectedServer)
        assertFalse(capability.canRenderDesktop)
    }
}
