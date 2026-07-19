package com.elysium.vanguard.foundry.core.scene

/**
 * Phase 3 / I-3.3 — the **LOD Selector**.
 *
 * The selector picks the appropriate level-of-detail
 * for an asset based on the camera's screen size.
 * Per `.ai/skills/06-3d-cad-asset-pipeline/SKILL.md`
 * section 6:
 *
 *   - The 3D renderer has a `screenSize` (the size in
 *     pixels the asset covers on screen).
 *   - The selector picks the smallest LOD whose
 *     `targetScreenSize` is <= the screen size.
 *   - LOD0 (the highest detail) is the fallback
 *     when no smaller LOD is available.
 *
 * The selector is a **pure function**: same asset +
 * same screen size → same LOD. The selector is
 * **deterministic** + **total** (every input
 * produces a result).
 *
 * The selector is **separate from the asset
 * itself**: the asset declares the LODs; the
 * selector picks which LOD to render. The 3D
 * renderer combines the asset + the selector's
 * result + the actual camera distance to produce
 * the render call.
 */
object LodSelector {

    /**
     * The default screen size when the renderer
     * doesn't know the camera's actual screen
     * size. The default is the "high detail"
     * fallback (LOD0); the renderer is expected
     * to provide a more accurate screen size
     * in production.
     */
    const val DEFAULT_SCREEN_SIZE: Int = 4096

    /**
     * Select the appropriate [AssetLod] for a
     * given [asset] + [screenSize] (in pixels).
     *
     * The selection rule (per skill 06):
     *   - The smallest LOD whose `targetScreenSize`
     *     is <= the screen size.
     *   - The highest level (the most coarse) when
     *     no smaller LOD is available (the asset
     *     is rendered at its most-coarse detail).
     *   - LOD0 when the screen size is > all
     *     `targetScreenSize` values (the asset is
     *     viewed very close; LOD0 is the highest
     *     detail).
     *
     * The function is total: every input produces
     * a result. The function is deterministic:
     * same asset + same screen size → same LOD.
     */
    fun select(asset: Canonical3DAsset, screenSize: Int): AssetLod {
        require(screenSize > 0) {
            "LodSelector.select: screenSize must be > 0, got $screenSize"
        }
        // The LODs are sorted by level ascending
        // (LOD0 is the highest detail; the highest
        // level is the lowest detail). The
        // Canonical3DAsset.init validates the sort.
        val lods = asset.lods
        // Find the smallest LOD whose targetScreenSize
        // is <= the screen size. The first LOD with a
        // target <= screen size is the "largest LOD
        // that fits"; a higher target is more detail
        // (and more expensive).
        val fitting = lods.firstOrNull { it.targetScreenSize <= screenSize }
        if (fitting != null) {
            return fitting
        }
        // No LOD fits (the screen size is smaller than
        // every LOD's target — the asset is being
        // viewed from very far away). Return the
        // most-coarse LOD (the highest level).
        return lods.last()
    }

    /**
     * Select the LODs for every asset in a list.
     * The helper is the typical call site for the
     * 3D renderer (one LOD per asset per frame).
     *
     * The result is a list of (asset, lod) pairs
     * in the same order as the input assets.
     * The function is total + deterministic.
     */
    fun selectAll(
        assets: List<Canonical3DAsset>,
        screenSize: Int,
    ): List<Pair<Canonical3DAsset, AssetLod>> =
        assets.map { it to select(it, screenSize) }
}
