package com.elysium.vanguard.foundry.core.scene

import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash

/**
 * Phase 3 / I-3.3 (continued) — the **Asset Geometry**.
 *
 * The geometry is the typed wrapper for a 3D
 * asset's geometry bytes. Per `.ai/skills/06-3d-
 * cad-asset-pipeline/SKILL.md` section 4:
 *
 *   - The geometry is the asset's binary content
 *     (the mesh, the texture, the material).
 *   - The geometry is content-addressed (the
 *     `ContentHash` is the canonical id).
 *   - The geometry is versioned (the format
 *     version is part of the metadata).
 *
 * The geometry is a **value object**: the bytes
 * are immutable; the metadata is computed in
 * the `init` block.
 *
 * The geometry is **opaque to the platform**: the
 * platform stores + streams the bytes; the 3D
 * renderer parses the bytes. The platform does
 * not interpret the format (a glTF / USD /
 * custom format would all be opaque to the
 * platform).
 */
data class AssetGeometry(
    /**
     * The geometry's content hash. The hash is
     * the canonical id (the same bytes always
     * produce the same hash).
     */
    val contentHash: ContentHash,
    /**
     * The format version (e.g. "glTF/2.0",
     * "USD/1.0", "ELYSIUM-CAD/1.0"). The
     * version tells the 3D renderer which
     * parser to use.
     */
    val formatVersion: String,
    /**
     * The geometry's bytes (the mesh + the
     * texture + the material). The bytes are
     * opaque to the platform.
     */
    val bytes: ByteArray,
) {
    init {
        require(formatVersion.isNotBlank()) {
            "AssetGeometry.formatVersion must not be blank"
        }
        require(bytes.isNotEmpty()) {
            "AssetGeometry.bytes must not be empty"
        }
    }

    /**
     * The geometry's size in bytes. The cache
     * uses the size to enforce the size limit.
     */
    val sizeBytes: Int = bytes.size

    /**
     * The data class equals + hashCode: two
     * `AssetGeometry`s are equal when their
     * `contentHash` + `formatVersion` + `bytes`
     * are equal. The `bytes` array is compared
     * by content (Kotlin's `ByteArray.equals`
     * compares by content).
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AssetGeometry) return false
        return contentHash == other.contentHash &&
            formatVersion == other.formatVersion &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = contentHash.hashCode()
        result = 31 * result + formatVersion.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}
