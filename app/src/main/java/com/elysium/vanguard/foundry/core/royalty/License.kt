package com.elysium.vanguard.foundry.core.royalty

import com.elysium.vanguard.foundry.core.ontology.ids.ContributorId
import com.elysium.vanguard.foundry.core.ontology.ids.VehicleProgramId
import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import java.util.UUID

/**
 * Phase F5 second half (G6+G7, I-5.2) — the
 * **License**, the typed usage license for a
 * [RoyaltyContract].
 *
 * The license is the **legal envelope** that
 * defines what the contributor can do with
 * the contribution (use, modify, distribute,
 * sublicense). The license is bound to a
 * specific [RoyaltyContract] + a specific
 * [VehicleProgramId].
 *
 * The license is a **sealed class** (not a flag
 * or a string id). The 4 cases reflect the
 * **4 distinct license kinds** the platform
 * supports:
 *
 *   - **`PermissiveLicense`** — a permissive
 *     license (e.g. MIT / Apache-2.0): the
 *     contributor allows use, modification,
 *     distribution, and sublicensing with
 *     attribution.
 *   - **`CopyleftLicense`** — a copyleft
 *     license (e.g. GPL-3.0): the contributor
 *     allows use, modification, and
 *     distribution, but derivative works MUST
 *     use the same license.
 *   - **`ProprietaryLicense`** — a proprietary
 *     license (e.g. Elysium-Proprietary): the
 *     contributor allows a specific set of
 *     uses; derivative works are NOT allowed.
 *   - **`CustomLicense`** — a custom license:
 *     the contributor defines the terms in
 *     a separate document.
 *
 * The license is **immutable** (a sealed class
 * with `val` fields; no setters). A new license
 * is a new value. The license's lifecycle
 * (a renewal, a revocation) is a new `License`
 * value, not a mutation of the existing one.
 *
 * The license is **signed** (the platform
 * binds the license to the publisher via
 * the license's signature).
 */
sealed class License {

    /**
     * The license's unique id. The id is a
     * UUID (per the Foundry id convention);
     * the id is the join key the consumer
     * uses to reference the license.
     */
    abstract val licenseId: LicenseId

    /**
     * The contract the license is bound to.
     * Every license is bound to exactly one
     * contract; a license that wants to
     * cover multiple contracts is multiple
     * licenses.
     */
    abstract val contractId: RoyaltyContractId

    /**
     * The program the license is for. Every
     * license is for exactly one program.
     */
    abstract val programId: VehicleProgramId

    /**
     * The contributor the license is
     * granted by. Every license is granted
     * by exactly one contributor.
     */
    abstract val contributorId: ContributorId

    /**
     * The license's human-readable display
     * name. The name is what the UI shows
     * in the license picker.
     */
    abstract val displayName: String

    /**
     * The license's effective period.
     */
    abstract val effectiveFromMs: Long
    abstract val effectiveUntilMs: Long?

    /**
     * The license's signature. The signature
     * binds the license to the publisher.
     */
    abstract val signature: Signature

    /**
     * The license's content hash. The hash
     * is the SHA-256 of the canonical form
     * of the license (used for audit + the
     * content-addressed storage).
     */
    abstract val contentHash: ContentHash

    /**
     * A permissive license. The contributor
     * allows use, modification, distribution,
     * and sublicensing with attribution.
     *
     * Examples: MIT, Apache-2.0, BSD-2-Clause.
     */
    data class PermissiveLicense(
        override val licenseId: LicenseId,
        override val contractId: RoyaltyContractId,
        override val programId: VehicleProgramId,
        override val contributorId: ContributorId,
        override val displayName: String,
        val spdxIdentifier: String,
        val attributionRequired: Boolean = true,
        override val effectiveFromMs: Long,
        override val effectiveUntilMs: Long? = null,
        override val signature: Signature,
        override val contentHash: ContentHash,
    ) : License() {
        init {
            require(displayName.isNotBlank()) {
                "License.PermissiveLicense.displayName must not be blank"
            }
            require(spdxIdentifier.isNotBlank()) {
                "License.PermissiveLicense.spdxIdentifier must not be blank"
            }
            require(effectiveFromMs > 0) {
                "License.PermissiveLicense.effectiveFromMs must be > 0"
            }
        }
    }

    /**
     * A copyleft license. The contributor
     * allows use, modification, and
     * distribution, but derivative works MUST
     * use the same license.
     *
     * Examples: GPL-2.0, GPL-3.0, AGPL-3.0.
     */
    data class CopyleftLicense(
        override val licenseId: LicenseId,
        override val contractId: RoyaltyContractId,
        override val programId: VehicleProgramId,
        override val contributorId: ContributorId,
        override val displayName: String,
        val spdxIdentifier: String,
        val shareAlikeRequired: Boolean = true,
        override val effectiveFromMs: Long,
        override val effectiveUntilMs: Long? = null,
        override val signature: Signature,
        override val contentHash: ContentHash,
    ) : License() {
        init {
            require(displayName.isNotBlank()) {
                "License.CopyleftLicense.displayName must not be blank"
            }
            require(spdxIdentifier.isNotBlank()) {
                "License.CopyleftLicense.spdxIdentifier must not be blank"
            }
            require(effectiveFromMs > 0) {
                "License.CopyleftLicense.effectiveFromMs must be > 0"
            }
        }
    }

    /**
     * A proprietary license. The contributor
     * allows a specific set of uses; derivative
     * works are NOT allowed.
     */
    data class ProprietaryLicense(
        override val licenseId: LicenseId,
        override val contractId: RoyaltyContractId,
        override val programId: VehicleProgramId,
        override val contributorId: ContributorId,
        override val displayName: String,
        val spdxIdentifier: String,
        val allowedUses: List<String>,
        val redistributionProhibited: Boolean = true,
        override val effectiveFromMs: Long,
        override val effectiveUntilMs: Long? = null,
        override val signature: Signature,
        override val contentHash: ContentHash,
    ) : License() {
        init {
            require(displayName.isNotBlank()) {
                "License.ProprietaryLicense.displayName must not be blank"
            }
            require(spdxIdentifier.isNotBlank()) {
                "License.ProprietaryLicense.spdxIdentifier must not be blank"
            }
            require(allowedUses.isNotEmpty()) {
                "License.ProprietaryLicense.allowedUses must not be empty " +
                    "(a proprietary license with no allowed uses " +
                    "is a deployment error)"
            }
            require(allowedUses.all { it.isNotBlank() }) {
                "License.ProprietaryLicense.allowedUses must not contain " +
                    "blank entries"
            }
            require(effectiveFromMs > 0) {
                "License.ProprietaryLicense.effectiveFromMs must be > 0"
            }
        }
    }

    /**
     * A custom license. The contributor
     * defines the terms in a separate
     * document (referenced by URL or stored
     * in the platform's document store).
     */
    data class CustomLicense(
        override val licenseId: LicenseId,
        override val contractId: RoyaltyContractId,
        override val programId: VehicleProgramId,
        override val contributorId: ContributorId,
        override val displayName: String,
        val termsDocumentUrl: String,
        val termsDocumentHash: ContentHash,
        override val effectiveFromMs: Long,
        override val effectiveUntilMs: Long? = null,
        override val signature: Signature,
        override val contentHash: ContentHash,
    ) : License() {
        init {
            require(displayName.isNotBlank()) {
                "License.CustomLicense.displayName must not be blank"
            }
            require(termsDocumentUrl.isNotBlank()) {
                "License.CustomLicense.termsDocumentUrl must not be blank"
            }
            require(termsDocumentHash.value.isNotBlank()) {
                "License.CustomLicense.termsDocumentHash must not be blank"
            }
            require(effectiveFromMs > 0) {
                "License.CustomLicense.effectiveFromMs must be > 0"
            }
        }
    }
}

/**
 * The typed id of a license. The id is a
 * UUID (per the Foundry id convention).
 */
@JvmInline
value class LicenseId(val value: UUID) {
    companion object {
        fun random(): LicenseId = LicenseId(UUID.randomUUID())
        fun from(raw: String): Result<LicenseId> = try {
            Result.success(LicenseId(UUID.fromString(raw)))
        } catch (e: IllegalArgumentException) {
            Result.failure(FoundryError.InvalidUuidFormat("LicenseId", raw, e))
        }
    }
}
