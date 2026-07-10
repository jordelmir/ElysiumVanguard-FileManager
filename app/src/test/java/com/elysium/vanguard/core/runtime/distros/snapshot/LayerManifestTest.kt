package com.elysium.vanguard.core.runtime.distros.snapshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PHASE 9.6.7 — Tests for the layered-distro metadata.
 *
 * Today this is purely about the [LayerManifest] round-trip; the
 * hardlink registry ships in 9.6.7.1 once we have an FS that
 * supports cross-mount `linkat(2)`.
 *
 * Phase 9.6.7 — first build; intentionally minimal.
 */
class LayerManifestTest {

    @Test
    fun `round trip preserves base kind and packages`() {
        val manifest = LayerManifest(
            layerId = "base-busybox",
            distroId = "alpine-latest",
            kind = DistroLayer.Kind.BASE,
            installedAtMs = 1752108765432L,
            packages = listOf("busybox", "musl"),
            markers = listOf("/bin/busybox", "/etc/os-release")
        )
        val text = manifest.toJson()
        val parsed = LayerManifest.parse(text)
        assertNotNull(parsed)
        assertEquals(manifest.layerId, parsed!!.layerId)
        assertEquals(manifest.distroId, parsed.distroId)
        assertEquals(manifest.kind, parsed.kind)
        assertEquals(manifest.installedAtMs, parsed.installedAtMs)
    }

    @Test
    fun `round trip preserves head kind`() {
        val manifest = LayerManifest(
            layerId = "head-alpine-apk",
            distroId = "alpine-latest",
            kind = DistroLayer.Kind.HEAD,
            installedAtMs = 1752108765432L,
            packages = listOf("apk-tools"),
            markers = listOf("/sbin/apk")
        )
        val text = manifest.toJson()
        val parsed = LayerManifest.parse(text)
        assertNotNull(parsed)
        assertEquals(DistroLayer.Kind.HEAD, parsed!!.kind)
    }

    @Test
    fun `parse returns null when layerId is missing`() {
        val text = """
            {"distroId":"alpine-latest","kind":"BASE","installedAtMs":1}
        """.trimIndent()
        assertNull(LayerManifest.parse(text))
    }

    @Test
    fun `parse returns null when kind is unknown`() {
        val text = """
            {"layerId":"x","distroId":"y","kind":"FRAGMENT","installedAtMs":1}
        """.trimIndent()
        assertNull(LayerManifest.parse(text))
    }

    @Test
    fun `DistroLayerKind enum has exactly two cases`() {
        // If we add more, tests catch the change.
        assertEquals(2, DistroLayer.Kind.values().size)
        assertNotNull(DistroLayer.Kind.valueOf("BASE"))
        assertNotNull(DistroLayer.Kind.valueOf("HEAD"))
    }
}
