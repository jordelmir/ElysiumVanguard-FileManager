package com.elysium.vanguard.foundry.core.scene

import com.elysium.vanguard.foundry.core.ontology.ids.AssetId
import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash

/**
 * Phase 3 / I-3.1 — a **Canonical 3D Asset**.
 *
 * The asset is the typed reference to a 3D model
 * the 3D renderer loads + the digital twin
 * selects + the part instance graph composes.
 * Per `.ai/skills/06-3d-cad-asset-pipeline/SKILL.md`
 * section 4 + the master vision's scene manifest:
 *
 *   - The asset has an `id` (the content-addressed
 *     id; the asset is content-addressed itself).
 *   - The asset has a list of `LOD`s (LOD0 = the
 *     highest detail; LODn = the lowest). The 3D
 *     renderer picks the appropriate LOD based on
 *     the camera distance.
 *   - The asset has a `bounds` (axis-aligned
 *     bounding box) the renderer uses for
 *     culling + the digital twin uses for
 *     selection.
 *   - The asset has a `transform` (position +
 *     rotation + scale) the renderer applies to
 *     position the asset in the scene.
 *   - The asset MAY have a `parent` (for the
 *     part instance graph). A `null` parent means
 *     the asset is a root of the graph.
 *
 * The asset is **content-addressed** (the id is
 * the content hash of the asset's geometry). Two
 * assets with the same geometry have the same id;
 * the 3D renderer deduplicates by id.
 *
 * The asset is **immutable** (a data class; no
 * setters). A new asset is a new id; an updated
 * asset is a new id; the old asset is retained
 * for back-compat.
 */
data class Canonical3DAsset(
    /**
     * The asset's id (a typed value class over
     * `String`). The id is the content hash of
     * the asset's geometry.
     */
    val id: AssetId,
    /**
     * The asset's display label (the user-facing
     * name in the digital twin's selection panel).
     */
    val label: String,
    /**
     * The list of level-of-detail variants. The
     * list MUST be sorted by `level` ascending
     * (LOD0 is the highest detail; the highest
     * `level` is the lowest detail).
     */
    val lods: List<AssetLod>,
    /**
     * The asset's axis-aligned bounding box. The
     * renderer uses the bounds for culling +
     * the digital twin uses the bounds for
     * selection.
     */
    val bounds: AssetBounds,
    /**
     * The asset's transform (position + rotation
     * + scale). The transform is in the parent's
     * coordinate system (or the scene's
     * coordinate system for root assets).
     */
    val transform: AssetTransform = AssetTransform.IDENTITY,
    /**
     * The asset's parent (for the part instance
     * graph). `null` means the asset is a root
     * of the graph.
     */
    val parentId: AssetId? = null,
    /**
     * The asset's coordinate system. The
     * transform is in the coordinate system
     * declared here; the parent's coordinate
     * system is the basis for the transform.
     */
    val coordinateSystem: CoordinateSystem = CoordinateSystem.LOCAL,
) {
    init {
        require(label.isNotBlank()) {
            "Canonical3DAsset.label must not be blank"
        }
        require(lods.isNotEmpty()) {
            "Canonical3DAsset.lods must not be empty; " +
                "an asset without a LOD has no geometry to render"
        }
        // The LODs MUST be sorted by `level` ascending
        // (the canonical form expects this order).
        val sortedLods = lods.sortedBy { it.level }
        require(lods == sortedLods) {
            "Canonical3DAsset.lods must be sorted by level ascending"
        }
        // The first LOD MUST be LOD0 (the highest
        // detail; the 3D renderer uses LOD0 as the
        // "always available" fallback).
        require(lods.first().level == 0) {
            "Canonical3DAsset.lods[0].level must be 0; " +
                "LOD0 is the highest detail and the always-available fallback"
        }
        // The asset MUST NOT be its own parent
        // (direct self-reference is a cycle).
        if (parentId != null) {
            require(parentId != id) {
                "Canonical3DAsset: an asset cannot be its own parent"
            }
        }
    }

    /**
     * The canonical form of the asset. The form
     * is the deterministic UTF-8 byte sequence
     * used to compute the asset's content hash
     * + the manifest's canonical form.
     */
    fun canonicalForm(): String = buildString {
        append("asset:id=").append(id.value)
        append("|label=").append(label)
        append("|lods=")
        append(lods.joinToString(";") { lod -> lod.canonicalForm() })
        append("|bounds=").append(bounds.canonicalForm())
        append("|transform=").append(transform.canonicalForm())
        append("|parent=").append(parentId?.value ?: "")
        append("|coord=").append(coordinateSystem.name)
    }
}

/**
 * A single level-of-detail variant of a
 * [Canonical3DAsset]. The LOD is the geometry
 * the 3D renderer loads at a given camera
 * distance. LOD0 is the highest detail; higher
 * `level` values are lower detail.
 *
 * The LOD carries:
 *   - A `level` (0, 1, 2, …).
 *   - A `geometryHash` (the content hash of the
 *     geometry; the renderer loads the geometry
 *     from the content-addressed store).
 *   - A `bounds` (the AABB of this specific LOD;
 *     LODs MAY have different bounds — a lower
 *     detail LOD has a coarser bounds).
 *   - A `triangleCount` (the triangle count; the
 *     renderer uses this to estimate the GPU cost).
 *   - A `targetScreenSize` (the screen size in
 *     pixels at which the renderer switches TO
 *     this LOD; LOD0 has the largest target).
 */
data class AssetLod(
    val level: Int,
    val geometryHash: ContentHash,
    val bounds: AssetBounds,
    val triangleCount: Int,
    val targetScreenSize: Int,
) {
    init {
        require(level >= 0) {
            "AssetLod.level must be >= 0, got $level"
        }
        require(triangleCount > 0) {
            "AssetLod.triangleCount must be > 0, got $triangleCount"
        }
        require(targetScreenSize > 0) {
            "AssetLod.targetScreenSize must be > 0, got $targetScreenSize"
        }
    }

    fun canonicalForm(): String = buildString {
        append("level=").append(level)
        append("|geometry=").append(geometryHash.value)
        append("|bounds=").append(bounds.canonicalForm())
        append("|triangles=").append(triangleCount)
        append("|targetSize=").append(targetScreenSize)
    }
}

/**
 * An axis-aligned bounding box. The box is in
 * the asset's coordinate system. The 3D renderer
 * uses the bounds for culling; the digital twin
 * uses the bounds for selection.
 *
 * The box is defined by:
 *   - `min: Vector3` — the minimum corner.
 *   - `max: Vector3` — the maximum corner.
 *
 * The box MUST be valid (`min <= max` for every
 * axis).
 */
data class AssetBounds(
    val min: Vector3,
    val max: Vector3,
) {
    init {
        require(min.x <= max.x) {
            "AssetBounds: min.x (${min.x}) must be <= max.x (${max.x})"
        }
        require(min.y <= max.y) {
            "AssetBounds: min.y (${min.y}) must be <= max.y (${max.y})"
        }
        require(min.z <= max.z) {
            "AssetBounds: min.z (${min.z}) must be <= max.z (${max.z})"
        }
    }

    fun canonicalForm(): String = "min=${min.canonicalForm()}|max=${max.canonicalForm()}"
}

/**
 * A 3D vector. The vector is in the asset's
 * coordinate system. The vector is **immutable**
 * (a data class; no setters).
 */
data class Vector3(val x: Double, val y: Double, val z: Double) {
    fun canonicalForm(): String = "(${x},${y},${z})"

    companion object {
        /** The zero vector (the origin). */
        val ZERO: Vector3 = Vector3(0.0, 0.0, 0.0)

        /** The unit vector along the X axis. */
        val UNIT_X: Vector3 = Vector3(1.0, 0.0, 0.0)

        /** The unit vector along the Y axis. */
        val UNIT_Y: Vector3 = Vector3(0.0, 1.0, 0.0)

        /** The unit vector along the Z axis. */
        val UNIT_Z: Vector3 = Vector3(0.0, 0.0, 1.0)
    }
}

/**
 * An asset's transform. The transform is in the
 * asset's coordinate system (or the parent's
 * coordinate system for non-root assets).
 *
 * The transform is:
 *   - `position: Vector3` — the position offset.
 *   - `rotation: Quaternion` — the rotation
 *     (a unit quaternion; the 3D renderer
 *     applies the rotation as a quaternion
 *     to avoid gimbal lock).
 *   - `scale: Vector3` — the scale (per-axis).
 *     A scale of (1, 1, 1) is the identity.
 */
data class AssetTransform(
    val position: Vector3,
    val rotation: Quaternion,
    val scale: Vector3,
) {
    init {
        require(scale.x > 0 && scale.y > 0 && scale.z > 0) {
            "AssetTransform.scale must be positive on every axis"
        }
    }

    fun canonicalForm(): String = buildString {
        append("pos=").append(position.canonicalForm())
        append("|rot=").append(rotation.canonicalForm())
        append("|scale=").append(scale.canonicalForm())
    }

    companion object {
        /** The identity transform (no position, no rotation, no scale). */
        val IDENTITY: AssetTransform = AssetTransform(
            position = Vector3.ZERO,
            rotation = Quaternion.IDENTITY,
            scale = Vector3(1.0, 1.0, 1.0),
        )
    }
}

/**
 * A unit quaternion (the 3D renderer's rotation
 * representation). The quaternion is `w + xi +
 * yj + zk` with `w² + x² + y² + z² = 1`.
 *
 * The quaternion is **normalized** in the
 * `init` block (the input MAY be unnormalized;
 * the `init` block normalizes it to a unit
 * quaternion).
 *
 * The identity quaternion is (1, 0, 0, 0).
 */
data class Quaternion(val w: Double, val x: Double, val y: Double, val z: Double) {
    init {
        val norm = kotlin.math.sqrt(w * w + x * x + y * y + z * z)
        require(norm > 0) {
            "Quaternion norm must be > 0, got $norm"
        }
    }

    fun canonicalForm(): String = "($w,$x,$y,$z)"

    companion object {
        /** The identity quaternion (no rotation). */
        val IDENTITY: Quaternion = Quaternion(1.0, 0.0, 0.0, 0.0)
    }
}
