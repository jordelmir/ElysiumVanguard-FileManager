package com.elysium.vanguard.core.runtime.distros.gui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinuxDesktopLaunchPlanTest {

    @Test
    fun `builds a loopback authenticated Openbox desktop command`() {
        val plan = LinuxDesktopLaunchPlan(
            guestPasswordPath = "/run/elysium-vnc/session-abc.passwd",
            geometry = LinuxDesktopGeometry.fromDisplayPixels(2156, 2344)
        )

        assertEquals(2156, plan.geometry.width)
        assertEquals(2344, plan.geometry.height)
        assertTrue(plan.script.contains("/usr/bin/Xvnc :1 -localhost -SecurityTypes VncAuth -rfbport 5901"))
        assertTrue(plan.script.contains("-rfbauth /run/elysium-vnc/session-abc.passwd"))
        assertTrue(plan.script.contains("stale_vnc_pid=${'$'}(tr -d '[:space:]'"))
        assertTrue(plan.script.contains("vnc_pid=${'$'}(tr -d '[:space:]'"))
        assertTrue(plan.script.contains("while kill -0 \"${'$'}vnc_pid\""))
        assertTrue(!plan.script.contains("AlwaysShared -fg"))
        assertTrue(plan.script.contains("/usr/bin/openbox"))
        assertTrue(plan.script.contains("/usr/bin/xterm -geometry 120x40 -fa 'DejaVu Sans Mono' -fs 14"))
        assertTrue(plan.script.contains("/usr/bin/xterm"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects an unsafe password path`() {
        LinuxDesktopLaunchPlan(
            guestPasswordPath = "/run/elysium-vnc/../other",
            geometry = LinuxDesktopGeometry.fromDisplayPixels(1080, 2400)
        )
    }

    @Test
    fun `bounds extreme framebuffer sizes without changing orientation`() {
        val geometry = LinuxDesktopGeometry.fromDisplayPixels(8_000, 200)

        assertEquals(2_560, geometry.width)
        assertEquals(600, geometry.height)
    }
}
