package com.elysium.vanguard.foundry.core.scene

import com.elysium.vanguard.foundry.core.ontology.ids.AssetId
import com.elysium.vanguard.foundry.core.ontology.ids.EngineeringArtifactId
import com.elysium.vanguard.foundry.core.ontology.ids.RepairActionId

/**
 * Phase 3 / I-3.2 — a **Part Instance** in the digital
 * twin's runtime graph.
 *
 * The [PartInstance] is the runtime representation of a
 * 3D asset in the digital twin. Per the implementation
 * roadmap I-3.2:
 *
 *   - "The user can select a part, isolate it, view its
 *     diagnostics, see its `EngineeringArtifact`
 *     references, and trigger a `RepairAction`."
 *
 * The instance has:
 *   - `id: PartInstanceId` — a runtime-generated id
 *     (separate from the asset's content hash; the same
 *     asset can be instantiated multiple times in the
 *     graph).
 *   - `assetId: AssetId` — the typed reference to the
 *     `Canonical3DAsset` the instance is built from.
 *   - `parentInstanceId: PartInstanceId?` — the parent
 *     instance (for the part instance graph); `null`
 *     means the instance is a root.
 *   - `displayLabel: String` — the user-facing label
 *     (defaults to the asset's label; can be overridden
 *     for a specific instance — e.g. "front-left wheel"
 *     vs the asset's "wheel").
 *   - `engineeringArtifactRefs: List<EngineeringArtifactId>`
 *     — the typed references to the engineering artifacts
 *     that document this part (per skill 03 + skill 07).
 *   - `repairActions: List<RepairActionId>` — the typed
 *     references to the repair actions the user can
 *     trigger for this part.
 *
 * The instance is **immutable** (a data class; no
 * setters). A new instance is a new id; an updated
 * instance is a new id; the old instance is retained
 * for back-compat.
 *
 * The instance is **content-addressed by composition**:
 * the same `assetId` + the same `parentInstanceId` +
 * the same `displayLabel` produces the same instance
 * (the `id` is derived from the composition; the
 * instance IS its own content address).
 */
data class PartInstance(
    val id: PartInstanceId,
    val assetId: AssetId,
    val parentInstanceId: PartInstanceId? = null,
    val displayLabel: String,
    val engineeringArtifactRefs: List<EngineeringArtifactId> = emptyList(),
    val repairActions: List<RepairActionId> = emptyList(),
) {
    init {
        require(displayLabel.isNotBlank()) {
            "PartInstance.displayLabel must not be blank"
        }
        // An instance cannot be its own parent
        // (direct self-reference is a cycle).
        if (parentInstanceId != null) {
            require(parentInstanceId != id) {
                "PartInstance: an instance cannot be its own parent"
            }
        }
    }
}

/**
 * A typed id for a [PartInstance]. The id is
 * runtime-generated (a `UUID`); the same instance
 * across calls has the same id.
 *
 * The id is a `@JvmInline value class` wrapping
 * `UUID` (per the platform's id convention). The
 * id is distinct from the asset's content hash —
 * the same asset can be instantiated multiple
 * times, each with a different `PartInstanceId`.
 */
@JvmInline
value class PartInstanceId(val value: java.util.UUID) {
    companion object {
        fun random(): PartInstanceId = PartInstanceId(java.util.UUID.randomUUID())
        fun from(raw: String): Result<PartInstanceId> = try {
            Result.success(PartInstanceId(java.util.UUID.fromString(raw)))
        } catch (e: IllegalArgumentException) {
            Result.failure(
                com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError.InvalidUuidFormat(
                    idTypeName = "PartInstanceId",
                    rawInput = raw,
                    parseFailure = e,
                ),
            )
        }
    }
}
