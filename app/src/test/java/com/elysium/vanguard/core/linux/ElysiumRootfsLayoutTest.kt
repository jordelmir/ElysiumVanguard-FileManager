package com.elysium.vanguard.core.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 73 third half (I-73.3.2) — the JVM tests
 * for [ElysiumRootfsPath] + [ElysiumRootfsLayout].
 *
 * These tests cover:
 *   - Path validation (blank, relative, traversal,
 *     double-slash rejection).
 *   - Path operations (join, parent, segments,
 *     relativeTo).
 *   - Layout: default FHS paths are correct.
 *   - Layout factory methods: runtimeLayerPath,
 *     packageInstallPath, workspacePath.
 *   - Layout construction invariants (every path
 *     is under the root).
 */
class ElysiumRootfsLayoutTest {

    // ============================================================
    // ElysiumRootfsPath validation
    // ============================================================

    @Test
    fun `path rejects blank value`() {
        try {
            ElysiumRootfsPath("")
            fail("expected IllegalArgumentException for blank value")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("value"))
        }
    }

    @Test
    fun `path rejects relative value`() {
        try {
            ElysiumRootfsPath("usr/bin")
            fail("expected IllegalArgumentException for relative value")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("absolute"))
        }
    }

    @Test
    fun `path rejects path-traversal segment`() {
        try {
            ElysiumRootfsPath("/usr/../etc")
            fail("expected IllegalArgumentException for path-traversal")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains(".."))
        }
    }

    @Test
    fun `path rejects double-slash segment`() {
        try {
            ElysiumRootfsPath("/usr//bin")
            fail("expected IllegalArgumentException for double-slash")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("//"))
        }
    }

    @Test
    fun `path accepts the root path`() {
        val p = ElysiumRootfsPath("/")
        assertEquals("/", p.value)
    }

    @Test
    fun `path accepts a normal FHS path`() {
        val p = ElysiumRootfsPath("/usr/bin/elysium-pm")
        assertEquals("/usr/bin/elysium-pm", p.value)
    }

    // ============================================================
    // ElysiumRootfsPath operations
    // ============================================================

    @Test
    fun `path join concatenates relative to absolute`() {
        val base = ElysiumRootfsPath("/usr/lib/elysium")
        val joined = base.join("runtime/box64")
        assertEquals("/usr/lib/elysium/runtime/box64", joined.value)
    }

    @Test
    fun `path join rejects absolute relative`() {
        val base = ElysiumRootfsPath("/usr/lib/elysium")
        try {
            base.join("/etc")
            fail("expected IllegalArgumentException for absolute relative")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("absolute"))
        }
    }

    @Test
    fun `path join rejects path-traversal relative`() {
        val base = ElysiumRootfsPath("/usr/lib/elysium")
        try {
            base.join("../etc")
            fail("expected IllegalArgumentException for traversal relative")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains(".."))
        }
    }

    @Test
    fun `path join rejects blank relative`() {
        val base = ElysiumRootfsPath("/usr/lib/elysium")
        try {
            base.join("")
            fail("expected IllegalArgumentException for blank relative")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("blank"))
        }
    }

    @Test
    fun `path parent of the root is the root`() {
        val root = ElysiumRootfsPath("/")
        assertEquals("/", root.parent.value)
    }

    @Test
    fun `path parent of a top-level directory is the root`() {
        val usr = ElysiumRootfsPath("/usr")
        assertEquals("/", usr.parent.value)
    }

    @Test
    fun `path parent of a nested directory is the immediate parent`() {
        val bin = ElysiumRootfsPath("/usr/bin")
        assertEquals("/usr", bin.parent.value)
    }

    @Test
    fun `path segments of the root is empty`() {
        val root = ElysiumRootfsPath("/")
        assertEquals(emptyList<String>(), root.segments)
    }

    @Test
    fun `path segments splits on slashes`() {
        val p = ElysiumRootfsPath("/usr/lib/elysium/runtime")
        assertEquals(
            listOf("usr", "lib", "elysium", "runtime"),
            p.segments,
        )
    }

    @Test
    fun `path relativeTo returns the relative part`() {
        val base = ElysiumRootfsPath("/usr/lib/elysium")
        val child = ElysiumRootfsPath("/usr/lib/elysium/runtime/box64/0.3.2")
        assertEquals("runtime/box64/0.3.2", child.relativeTo(base))
    }

    @Test
    fun `path relativeTo returns dot for the same path`() {
        val p = ElysiumRootfsPath("/usr/lib/elysium")
        assertEquals(".", p.relativeTo(p))
    }

    @Test
    fun `path relativeTo rejects a non-descendant`() {
        val base = ElysiumRootfsPath("/usr/lib/elysium")
        val sibling = ElysiumRootfsPath("/opt/elysium/packages")
        try {
            sibling.relativeTo(base)
            fail("expected IllegalArgumentException for non-descendant")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("not under"))
        }
    }

    // ============================================================
    // ElysiumRootfsLayout — default FHS paths
    // ============================================================

    @Test
    fun `default layout root is the FHS root`() {
        assertEquals("/", ElysiumRootfsLayout.DEFAULT.rootPath.value)
    }

    @Test
    fun `default layout bin is the FHS bin`() {
        assertEquals("/bin", ElysiumRootfsLayout.DEFAULT.binPath.value)
    }

    @Test
    fun `default layout etc is the FHS etc`() {
        assertEquals("/etc", ElysiumRootfsLayout.DEFAULT.etcPath.value)
    }

    @Test
    fun `default layout etcElysium is under etc`() {
        val layout = ElysiumRootfsLayout.DEFAULT
        assertTrue(
            "expected /etc/elysium under /etc, got: ${layout.etcElysiumPath.value}",
            layout.etcElysiumPath.value.startsWith("${layout.etcPath.value}/"),
        )
    }

    @Test
    fun `default layout usrLibElysium is under usrLib`() {
        val layout = ElysiumRootfsLayout.DEFAULT
        assertTrue(
            "expected /usr/lib/elysium under /usr/lib",
            layout.usrLibElysiumPath.value.startsWith("${layout.usrLibPath.value}/"),
        )
    }

    @Test
    fun `default layout usrLibElysiumRuntime is under usrLibElysium`() {
        val layout = ElysiumRootfsLayout.DEFAULT
        assertTrue(
            "expected /usr/lib/elysium/runtime under /usr/lib/elysium",
            layout.usrLibElysiumRuntimePath.value.startsWith(
                "${layout.usrLibElysiumPath.value}/",
            ),
        )
    }

    @Test
    fun `default layout varLibElysium is under varLib`() {
        val layout = ElysiumRootfsLayout.DEFAULT
        assertTrue(
            "expected /var/lib/elysium under /var/lib",
            layout.varLibElysiumPath.value.startsWith("${layout.varLibPath.value}/"),
        )
    }

    @Test
    fun `default layout varLogElysium is under varLog`() {
        val layout = ElysiumRootfsLayout.DEFAULT
        assertTrue(
            "expected /var/log/elysium under /var/log",
            layout.varLogElysiumPath.value.startsWith("${layout.varLogPath.value}/"),
        )
    }

    @Test
    fun `default layout workspaces is at the rootfs root`() {
        assertEquals(
            "/workspaces",
            ElysiumRootfsLayout.DEFAULT.workspacesPath.value,
        )
    }

    // ============================================================
    // ElysiumRootfsLayout — factory methods
    // ============================================================

    @Test
    fun `runtimeLayerPath returns the canonical path for a layer version`() {
        val layout = ElysiumRootfsLayout.DEFAULT
        val path = layout.runtimeLayerPath(
            ElysiumRuntimeLayerId.BOX64,
            ElysiumPackageVersion.parse("0.3.2").getOrThrow(),
        )
        assertEquals(
            "/usr/lib/elysium/runtime/box64/0.3.2",
            path.value,
        )
    }

    @Test
    fun `runtimeLayerPath for mesa-turnip returns the canonical path`() {
        val layout = ElysiumRootfsLayout.DEFAULT
        val path = layout.runtimeLayerPath(
            ElysiumRuntimeLayerId.MESA_TURNIP,
            ElysiumPackageVersion.parse("24.1.0").getOrThrow(),
        )
        assertEquals(
            "/usr/lib/elysium/runtime/mesa-turnip/24.1.0",
            path.value,
        )
    }

    @Test
    fun `packageInstallPath returns the canonical path for a package`() {
        val layout = ElysiumRootfsLayout.DEFAULT
        val path = layout.packageInstallPath("com.elysium.runtime.python")
        assertEquals(
            "/opt/elysium/packages/com.elysium.runtime.python",
            path.value,
        )
    }

    @Test
    fun `workspacePath returns the canonical path for a workspace`() {
        val layout = ElysiumRootfsLayout.DEFAULT
        val path = layout.workspacePath("blender-linux")
        assertEquals(
            "/workspaces/blender-linux",
            path.value,
        )
    }

    // ============================================================
    // ElysiumRootfsLayout — custom root
    // ============================================================

    @Test
    fun `custom root layout has all paths under the custom root`() {
        val layout = ElysiumRootfsLayout(
            rootPath = ElysiumRootfsPath("/opt/elysium-test"),
            binPath = ElysiumRootfsPath("/opt/elysium-test/bin"),
            etcPath = ElysiumRootfsPath("/opt/elysium-test/etc"),
            etcElysiumPath = ElysiumRootfsPath("/opt/elysium-test/etc/elysium"),
            etcElysiumRuntimePath = ElysiumRootfsPath("/opt/elysium-test/etc/elysium/runtime"),
            optPath = ElysiumRootfsPath("/opt/elysium-test/opt"),
            optElysiumPackagesPath = ElysiumRootfsPath("/opt/elysium-test/opt/elysium/packages"),
            usrPath = ElysiumRootfsPath("/opt/elysium-test/usr"),
            usrBinPath = ElysiumRootfsPath("/opt/elysium-test/usr/bin"),
            usrLibPath = ElysiumRootfsPath("/opt/elysium-test/usr/lib"),
            usrLibElysiumPath = ElysiumRootfsPath("/opt/elysium-test/usr/lib/elysium"),
            usrLibElysiumRuntimePath = ElysiumRootfsPath("/opt/elysium-test/usr/lib/elysium/runtime"),
            varPath = ElysiumRootfsPath("/opt/elysium-test/var"),
            varCachePath = ElysiumRootfsPath("/opt/elysium-test/var/cache"),
            varLibPath = ElysiumRootfsPath("/opt/elysium-test/var/lib"),
            varLibElysiumPath = ElysiumRootfsPath("/opt/elysium-test/var/lib/elysium"),
            varLibElysiumCatalogPath = ElysiumRootfsPath("/opt/elysium-test/var/lib/elysium/catalog"),
            varLibElysiumPackagesPath = ElysiumRootfsPath("/opt/elysium-test/var/lib/elysium/packages"),
            varLibElysiumStatePath = ElysiumRootfsPath("/opt/elysium-test/var/lib/elysium/state"),
            varLogPath = ElysiumRootfsPath("/opt/elysium-test/var/log"),
            varLogElysiumPath = ElysiumRootfsPath("/opt/elysium-test/var/log/elysium"),
            varLogElysiumPackageManagerPath = ElysiumRootfsPath("/opt/elysium-test/var/log/elysium/pm"),
            varLogElysiumOrchestratorPath = ElysiumRootfsPath("/opt/elysium-test/var/log/elysium/orchestrator"),
            varLogElysiumAuditPath = ElysiumRootfsPath("/opt/elysium-test/var/log/elysium/audit"),
            workspacesPath = ElysiumRootfsPath("/opt/elysium-test/workspaces"),
        )
        val layerPath = layout.runtimeLayerPath(
            ElysiumRuntimeLayerId.WINE,
            ElysiumPackageVersion.parse("9.0.0").getOrThrow(),
        )
        assertEquals(
            "/opt/elysium-test/usr/lib/elysium/runtime/wine/9.0.0",
            layerPath.value,
        )
    }

    @Test
    fun `custom root layout rejects paths outside the root`() {
        try {
            ElysiumRootfsLayout(
                rootPath = ElysiumRootfsPath("/opt/elysium-test"),
                binPath = ElysiumRootfsPath("/etc"),  // outside the root
                etcPath = ElysiumRootfsPath("/opt/elysium-test/etc"),
                etcElysiumPath = ElysiumRootfsPath("/opt/elysium-test/etc/elysium"),
                etcElysiumRuntimePath = ElysiumRootfsPath("/opt/elysium-test/etc/elysium/runtime"),
                optPath = ElysiumRootfsPath("/opt/elysium-test/opt"),
                optElysiumPackagesPath = ElysiumRootfsPath("/opt/elysium-test/opt/elysium/packages"),
                usrPath = ElysiumRootfsPath("/opt/elysium-test/usr"),
                usrBinPath = ElysiumRootfsPath("/opt/elysium-test/usr/bin"),
                usrLibPath = ElysiumRootfsPath("/opt/elysium-test/usr/lib"),
                usrLibElysiumPath = ElysiumRootfsPath("/opt/elysium-test/usr/lib/elysium"),
                usrLibElysiumRuntimePath = ElysiumRootfsPath("/opt/elysium-test/usr/lib/elysium/runtime"),
                varPath = ElysiumRootfsPath("/opt/elysium-test/var"),
                varCachePath = ElysiumRootfsPath("/opt/elysium-test/var/cache"),
                varLibPath = ElysiumRootfsPath("/opt/elysium-test/var/lib"),
                varLibElysiumPath = ElysiumRootfsPath("/opt/elysium-test/var/lib/elysium"),
                varLibElysiumCatalogPath = ElysiumRootfsPath("/opt/elysium-test/var/lib/elysium/catalog"),
                varLibElysiumPackagesPath = ElysiumRootfsPath("/opt/elysium-test/var/lib/elysium/packages"),
                varLibElysiumStatePath = ElysiumRootfsPath("/opt/elysium-test/var/lib/elysium/state"),
                varLogPath = ElysiumRootfsPath("/opt/elysium-test/var/log"),
                varLogElysiumPath = ElysiumRootfsPath("/opt/elysium-test/var/log/elysium"),
                varLogElysiumPackageManagerPath = ElysiumRootfsPath("/opt/elysium-test/var/log/elysium/pm"),
                varLogElysiumOrchestratorPath = ElysiumRootfsPath("/opt/elysium-test/var/log/elysium/orchestrator"),
                varLogElysiumAuditPath = ElysiumRootfsPath("/opt/elysium-test/var/log/elysium/audit"),
                workspacesPath = ElysiumRootfsPath("/opt/elysium-test/workspaces"),
            )
            fail("expected IllegalArgumentException for path outside root")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "expected error to mention 'under the root', got: ${e.message}",
                e.message!!.contains("under the root"),
            )
        }
    }

    // ============================================================
    // Sanity: data class equality
    // ============================================================

    @Test
    fun `two layouts with the same fields are equal`() {
        val a = ElysiumRootfsLayout.DEFAULT
        val b = ElysiumRootfsLayout.DEFAULT
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `two paths with the same value are equal`() {
        val a = ElysiumRootfsPath("/usr/bin")
        val b = ElysiumRootfsPath("/usr/bin")
        assertEquals(a, b)
        assertNotEquals(a, ElysiumRootfsPath("/usr/sbin"))
    }
}
