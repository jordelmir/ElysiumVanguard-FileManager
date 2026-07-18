package com.elysium.vanguard.foundry.core.artifact

import com.elysium.vanguard.foundry.core.ontology.ids.EngineeringArtifactId
import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp

/**
 * A typed reference to a content-addressed engineering artifact.
 *
 * Per `docs/foundry/domain-ownership.md` section 2.11:
 *   - The artifact's bytes are in the content-addressed store
 *     (skill 08); the reference is the typed pointer.
 *   - The `contentHash` is the SHA-256 of the bytes; the reference
 *     is verified at load time.
 *   - The artifact can be a glTF / STEP / USD / PDF datasheet /
 *     OCR'd image / CAD drawing / simulated stress report.
 *
 * Phase 1 ships the reference shape. The storage layer
 * (skill 08's content-addressed store) is wired in Phase 2.
 */
data class EngineeringArtifact(
    val id: EngineeringArtifactId,
    val contentHash: ContentHash,
    val format: EngineeringArtifactFormat,
    val sizeBytes: Long,
    val subjectId: String,
    val createdAt: Timestamp,
    val version: Long = 0L,
) {
    init {
        require(sizeBytes >= 0) {
            "EngineeringArtifact sizeBytes must be non-negative, got $sizeBytes"
        }
        require(subjectId.isNotBlank()) { "EngineeringArtifact subjectId must not be blank" }
    }
}

/**
 * The format of an engineering artifact. The set covers the most
 * common formats in the 3D pipeline + the regulatory pipeline.
 * New formats are added as ADRs.
 */
enum class EngineeringArtifactFormat {
    /** glTF binary container (`.glb`). */
    GLB,

    /** glTF JSON + binary (`.gltf` + `.bin`). */
    GLTF,

    /** Universal Scene Description (`.usd` / `.usda`). */
    USD,

    /** Universal Scene Description zipped (`.usdz`). */
    USDZ,

    /** ISO 10303 STEP file (`.step` / `.stp`). */
    STEP,

    /** Initial Graphics Exchange Specification (`.igs` / `.iges`). */
    IGES,

    /** Autodesk FBX (`.fbx`). */
    FBX,

    /** PDF document (`.pdf`). */
    PDF,

    /** Raster image (`.png`, `.jpg`, `.webp`). */
    IMAGE,

    /** Other format not covered above. New formats are ADRs. */
    OTHER,
}
