package com.elysium.vanguard.foundry.core.ontology.ids

import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import java.util.UUID

/**
 * Strongly-typed identifiers for every aggregate in the Elysium Automotive
 * Foundry platform. Every ID is a `@JvmInline value class` wrapping a `UUID`,
 * per `docs/foundry/domain-ownership.md` section 14 + `.ai/skills/03-vehicle-
 * domain-ontology/SKILL.md` section 14.
 *
 * The contract (per `.ai/AGENTS.md` section 24.1):
 *   - A raw `String` is never a domain ID.
 *   - Boundary validation rejects malformed UUIDs with a typed `FoundryError`.
 *   - Equality is value-based (not identity-based).
 *   - Hash code is derived from the underlying UUID.
 *
 * Why 16 IDs in one file: the platform has 16 aggregates, each with exactly
 * one ID type. They are intrinsically related (an `Order` references a
 * `ListingId`; a `Settlement` references an `OrderId`) and the platform's
 * "one owner, many readers" rule means a single domain owner maintains the
 * full set.
 */
@JvmInline
value class UserId(val value: UUID) {
    companion object {
        fun random(): UserId = UserId(UUID.randomUUID())
        fun from(raw: String): Result<UserId> = try {
            Result.success(UserId(UUID.fromString(raw)))
        } catch (e: IllegalArgumentException) {
            Result.failure(FoundryError.InvalidUuidFormat("UserId", raw, e))
        }
    }
}

@JvmInline
value class ProjectId(val value: UUID) {
    companion object {
        fun random(): ProjectId = ProjectId(UUID.randomUUID())
        fun from(raw: String): Result<ProjectId> = try {
            Result.success(ProjectId(UUID.fromString(raw)))
        } catch (e: IllegalArgumentException) {
            Result.failure(FoundryError.InvalidUuidFormat("ProjectId", raw, e))
        }
    }
}

@JvmInline
value class VehicleProgramId(val value: UUID) {
    companion object {
        fun random(): VehicleProgramId = VehicleProgramId(UUID.randomUUID())
        fun from(raw: String): Result<VehicleProgramId> = try {
            Result.success(VehicleProgramId(UUID.fromString(raw)))
        } catch (e: IllegalArgumentException) {
            Result.failure(FoundryError.InvalidUuidFormat("VehicleProgramId", raw, e))
        }
    }
}

@JvmInline
value class VehicleRevisionId(val value: UUID) {
    companion object {
        fun random(): VehicleRevisionId = VehicleRevisionId(UUID.randomUUID())
        fun from(raw: String): Result<VehicleRevisionId> = try {
            Result.success(VehicleRevisionId(UUID.fromString(raw)))
        } catch (e: IllegalArgumentException) {
            Result.failure(FoundryError.InvalidUuidFormat("VehicleRevisionId", raw, e))
        }
    }
}

@JvmInline
value class ContributorId(val value: UUID) {
    companion object {
        fun random(): ContributorId = ContributorId(UUID.randomUUID())
        fun from(raw: String): Result<ContributorId> = try {
            Result.success(ContributorId(UUID.fromString(raw)))
        } catch (e: IllegalArgumentException) {
            Result.failure(FoundryError.InvalidUuidFormat("ContributorId", raw, e))
        }
    }
}

@JvmInline
value class EngineeringArtifactId(val value: UUID) {
    companion object {
        fun random(): EngineeringArtifactId = EngineeringArtifactId(UUID.randomUUID())
        fun from(raw: String): Result<EngineeringArtifactId> = try {
            Result.success(EngineeringArtifactId(UUID.fromString(raw)))
        } catch (e: IllegalArgumentException) {
            Result.failure(FoundryError.InvalidUuidFormat("EngineeringArtifactId", raw, e))
        }
    }
}

@JvmInline
value class ProvenanceRecordId(val value: UUID) {
    companion object {
        fun random(): ProvenanceRecordId = ProvenanceRecordId(UUID.randomUUID())
        fun from(raw: String): Result<ProvenanceRecordId> = try {
            Result.success(ProvenanceRecordId(UUID.fromString(raw)))
        } catch (e: IllegalArgumentException) {
            Result.failure(FoundryError.InvalidUuidFormat("ProvenanceRecordId", raw, e))
        }
    }
}

@JvmInline
value class PartId(val value: UUID) {
    companion object {
        fun random(): PartId = PartId(UUID.randomUUID())
        fun from(raw: String): Result<PartId> = try {
            Result.success(PartId(UUID.fromString(raw)))
        } catch (e: IllegalArgumentException) {
            Result.failure(FoundryError.InvalidUuidFormat("PartId", raw, e))
        }
    }
}

@JvmInline
value class VariantId(val value: UUID) {
    companion object {
        fun random(): VariantId = VariantId(UUID.randomUUID())
        fun from(raw: String): Result<VariantId> = try {
            Result.success(VariantId(UUID.fromString(raw)))
        } catch (e: IllegalArgumentException) {
            Result.failure(FoundryError.InvalidUuidFormat("VariantId", raw, e))
        }
    }
}

@JvmInline
value class CompatibilityId(val value: UUID) {
    companion object {
        fun random(): CompatibilityId = CompatibilityId(UUID.randomUUID())
        fun from(raw: String): Result<CompatibilityId> = try {
            Result.success(CompatibilityId(UUID.fromString(raw)))
        } catch (e: IllegalArgumentException) {
            Result.failure(FoundryError.InvalidUuidFormat("CompatibilityId", raw, e))
        }
    }
}

@JvmInline
value class SubsystemId(val value: UUID) {
    companion object {
        fun random(): SubsystemId = SubsystemId(UUID.randomUUID())
        fun from(raw: String): Result<SubsystemId> = try {
            Result.success(SubsystemId(UUID.fromString(raw)))
        } catch (e: IllegalArgumentException) {
            Result.failure(FoundryError.InvalidUuidFormat("SubsystemId", raw, e))
        }
    }
}

@JvmInline
value class AssemblyId(val value: UUID) {
    companion object {
        fun random(): AssemblyId = AssemblyId(UUID.randomUUID())
        fun from(raw: String): Result<AssemblyId> = try {
            Result.success(AssemblyId(UUID.fromString(raw)))
        } catch (e: IllegalArgumentException) {
            Result.failure(FoundryError.InvalidUuidFormat("AssemblyId", raw, e))
        }
    }
}

@JvmInline
value class BrandId(val value: UUID) {
    companion object {
        fun random(): BrandId = BrandId(UUID.randomUUID())
        fun from(raw: String): Result<BrandId> = try {
            Result.success(BrandId(UUID.fromString(raw)))
        } catch (e: IllegalArgumentException) {
            Result.failure(FoundryError.InvalidUuidFormat("BrandId", raw, e))
        }
    }
}

@JvmInline
value class DiagnosticId(val value: UUID) {
    companion object {
        fun random(): DiagnosticId = DiagnosticId(UUID.randomUUID())
        fun from(raw: String): Result<DiagnosticId> = try {
            Result.success(DiagnosticId(UUID.fromString(raw)))
        } catch (e: IllegalArgumentException) {
            Result.failure(FoundryError.InvalidUuidFormat("DiagnosticId", raw, e))
        }
    }
}

@JvmInline
value class FaultId(val value: UUID) {
    companion object {
        fun random(): FaultId = FaultId(UUID.randomUUID())
        fun from(raw: String): Result<FaultId> = try {
            Result.success(FaultId(UUID.fromString(raw)))
        } catch (e: IllegalArgumentException) {
            Result.failure(FoundryError.InvalidUuidFormat("FaultId", raw, e))
        }
    }
}

@JvmInline
value class RepairActionId(val value: UUID) {
    companion object {
        fun random(): RepairActionId = RepairActionId(UUID.randomUUID())
        fun from(raw: String): Result<RepairActionId> = try {
            Result.success(RepairActionId(UUID.fromString(raw)))
        } catch (e: IllegalArgumentException) {
            Result.failure(FoundryError.InvalidUuidFormat("RepairActionId", raw, e))
        }
    }
}

/**
 * A typed 3D asset id. The asset id is the content
 * hash of the asset's geometry (per
 * `.ai/skills/06-3d-cad-asset-pipeline/SKILL.md`
 * section 4 — content-addressed assets). The value
 * class wraps a `String` (not a `UUID`); the value
 * IS the content hash.
 *
 * Phase 3 / I-3.1 — new value class.
 */
@JvmInline
value class AssetId(val value: String) {
    init {
        require(value.isNotBlank()) {
            "AssetId.value must not be blank"
        }
    }

    companion object {
        /** The empty / unknown sentinel (used for tests + error paths). */
        val UNKNOWN: AssetId = AssetId("__unknown__")

        /**
         * Build an `AssetId` from a content hash. The
         * hash is a SHA-256 hex string (64 chars).
         * The asset id is the hash; no transformation.
         */
        fun fromHash(hash: String): Result<AssetId> {
            if (hash.length != 64 || !hash.all { it.isDigit() || it in 'a'..'f' }) {
                return Result.failure(
                    FoundryError.ArtifactIntegrityFailure(
                        artifactId = hash,
                        reason = "expected 64-char SHA-256 hex, got: $hash",
                    ),
                )
            }
            return Result.success(AssetId(hash))
        }
    }
}
