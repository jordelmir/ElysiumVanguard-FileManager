package com.elysium.vanguard.foundry.core.scene

import com.elysium.vanguard.foundry.core.ontology.ids.AssetId
import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 3 / I-3.3 — the JVM tests for [LodSelector].
 *
 * The selector picks the appropriate LOD for a
 * given asset + screen size. The tests cover:
 *   - The selection rule (smallest LOD whose
 *     targetScreenSize <= screen size).
 *   - The fallback to the most-coarse LOD when
 *     the screen size is smaller than every
 *     LOD's target.
 *   - The fallback to LOD0 when the screen size
 *     is larger than every LOD's target.
 *   - The selector rejects a non-positive screen
 *     size.
 *   - The selector is deterministic.
 *   - The selectAll helper returns one (asset,
 *     lod) pair per asset in input order.
 */
class LodSelectorTest {

    // ============================================================
    // Selection rule
    // ============================================================

    @Test
    fun `select picks the LOD with the largest target that fits the screen size`() {
        val asset = buildAsset(
            lods = listOf(
                lod(0, target = 4096),  // highest detail
                lod(1, target = 2048),
                lod(2, target = 1024),
                lod(3, target = 512),   // most coarse
            ),
        )
        // Screen size 1500 fits LOD2 (target 1024)
        // but not LOD1 (target 2048). LOD2 is the
        // highest detail that fits.
        val selected = LodSelector.select(asset, screenSize = 1500)
        assertEquals(2, selected.level)
    }

    @Test
    fun `select returns the highest detail LOD0 when screen size is huge`() {
        val asset = buildAsset(
            lods = listOf(
                lod(0, target = 4096),
                lod(1, target = 2048),
                lod(2, target = 1024),
            ),
        )
        val selected = LodSelector.select(asset, screenSize = 10000)
        assertEquals(0, selected.level)
    }

    @Test
    fun `select returns the most coarse LOD when screen size is tiny`() {
        val asset = buildAsset(
            lods = listOf(
                lod(0, target = 4096),
                lod(1, target = 2048),
                lod(2, target = 1024),
                lod(3, target = 512),
            ),
        )
        val selected = LodSelector.select(asset, screenSize = 100)
        assertEquals(3, selected.level)
    }

    @Test
    fun `select returns the LOD whose target exactly matches the screen size`() {
        val asset = buildAsset(
            lods = listOf(
                lod(0, target = 4096),
                lod(1, target = 2048),
            ),
        )
        val selected = LodSelector.select(asset, screenSize = 2048)
        assertEquals(1, selected.level)
    }

    @Test
    fun `select with a single LOD always returns that LOD`() {
        val asset = buildAsset(
            lods = listOf(lod(0, target = 1024)),
        )
        assertEquals(0, LodSelector.select(asset, screenSize = 100).level)
        assertEquals(0, LodSelector.select(asset, screenSize = 10000).level)
    }

    @Test
    fun `select rejects a non-positive screen size`() {
        val asset = buildAsset(
            lods = listOf(lod(0, target = 1024)),
        )
        try {
            LodSelector.select(asset, screenSize = 0)
            fail("expected IllegalArgumentException for screen size 0")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // ============================================================
    // Determinism
    // ============================================================

    @Test
    fun `select is deterministic for the same input`() {
        val asset = buildAsset(
            lods = listOf(
                lod(0, target = 4096),
                lod(1, target = 2048),
                lod(2, target = 1024),
            ),
        )
        val a = LodSelector.select(asset, screenSize = 1500)
        val b = LodSelector.select(asset, screenSize = 1500)
        assertEquals(a, b)
    }

    @Test
    fun `select differs for different screen sizes`() {
        val asset = buildAsset(
            lods = listOf(
                lod(0, target = 4096),
                lod(1, target = 2048),
                lod(2, target = 1024),
            ),
        )
        val a = LodSelector.select(asset, screenSize = 5000)
        val b = LodSelector.select(asset, screenSize = 1500)
        assertNotEquals(a.level, b.level)
    }

    // ============================================================
    // selectAll
    // ============================================================

    @Test
    fun `selectAll returns one (asset, lod) pair per asset in input order`() {
        val assets = listOf(
            buildAsset(
                lods = listOf(lod(0, target = 4096), lod(1, target = 1024)),
            ),
            buildAsset(
                lods = listOf(lod(0, target = 4096), lod(1, target = 1024)),
            ),
        )
        val pairs = LodSelector.selectAll(assets, screenSize = 1500)
        assertEquals(2, pairs.size)
        // Each pair is (asset, lod). The screen size
        // 1500 fits LOD1 (target 1024) for both assets.
        for (pair in pairs) {
            assertEquals(1, pair.second.level)
        }
    }

    @Test
    fun `selectAll with empty asset list returns empty result`() {
        val pairs = LodSelector.selectAll(emptyList(), screenSize = 1500)
        assertEquals(0, pairs.size)
    }

    // ============================================================
    // Fixtures
    // ============================================================

    private fun lod(level: Int, target: Int): AssetLod = AssetLod(
        level = level,
        geometryHash = ContentHash(level.toString().repeat(64).take(64)),
        bounds = AssetBounds(
            min = Vector3(-1.0, -1.0, -1.0),
            max = Vector3(1.0, 1.0, 1.0),
        ),
        triangleCount = 1000,
        targetScreenSize = target,
    )

    private fun buildAsset(lods: List<AssetLod>): Canonical3DAsset =
        Canonical3DAsset(
            id = AssetId("a".repeat(64)),
            label = "test asset",
            lods = lods,
            bounds = AssetBounds(
                min = Vector3(-1.0, -1.0, -1.0),
                max = Vector3(1.0, 1.0, 1.0),
            ),
        )
}
