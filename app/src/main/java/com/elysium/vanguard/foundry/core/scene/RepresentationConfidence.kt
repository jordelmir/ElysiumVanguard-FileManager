package com.elysium.vanguard.foundry.core.scene

import com.elysium.vanguard.foundry.core.ontology.primitives.RepresentationLevel

/**
 * Phase 3 / I-3.5 — the **Representation Confidence**.
 *
 * The confidence is the user-facing bundle of the
 * `VehicleRepresentationLevel` + the UI hint the
 * digital twin shows. Per the implementation
 * roadmap I-3.5 + `.ai/STANDARDS.md` 2.1:
 *
 *   - "The level is displayed prominently in the
 *     UI."
 *   - "The level is the gate for the marketplace
 *     (per ADR-0011)."
 *   - "The level is the input to the safety gate
 *     (per skill 13)."
 *
 * The confidence has:
 *   - `level: RepresentationLevel` — the level
 *     itself.
 *   - `displayLabel: String` — the user-facing
 *     label (e.g. "OEM-Verified", "Conceptual").
 *   - `description: String` — a longer
 *     user-facing description (e.g. "Geometry
 *     validated against an OEM-shipped vehicle;
 *     units are correct; coordinate system
 *     matches the OEM standard").
 *   - `uiColor: UiColor` — the UI color (RED,
 *     ORANGE, YELLOW, BLUE, GREEN).
 *   - `marketplaceEligible: Boolean` — the
 *     marketplace gate (per ADR-0011).
 *   - `safetyGatePasses: Boolean` — the safety
 *     gate (per skill 13).
 *
 * The confidence is **immutable** (a data class; no
 * setters). A new level produces a new confidence
 * (a new `confidenceFor(level)` lookup).
 */
data class RepresentationConfidence(
    val level: RepresentationLevel,
    val displayLabel: String,
    val description: String,
    val uiColor: UiColor,
    val marketplaceEligible: Boolean,
    val safetyGatePasses: Boolean,
) {
    init {
        require(level != RepresentationLevel.UNKNOWN) {
            "RepresentationConfidence.level must not be UNKNOWN; " +
                "an UNKNOWN level is a deployment error"
        }
        require(displayLabel.isNotBlank()) {
            "RepresentationConfidence.displayLabel must not be blank"
        }
        require(description.isNotBlank()) {
            "RepresentationConfidence.description must not be blank"
        }
    }

    /**
     * The UI color for the confidence. The
     * digital twin's UI uses the color to
     * display the confidence prominently
     * (per `.ai/STANDARDS.md` 2.1).
     */
    enum class UiColor {
        /** The lowest confidence (VISUAL_ONLY). Red. */
        RED,

        /** Low confidence (CONCEPTUAL). Orange. */
        ORANGE,

        /** Medium confidence (PARAMETRIC_FUNCTIONAL). Yellow. */
        YELLOW,

        /** High confidence (OEM_PARTIAL). Blue. */
        BLUE,

        /** The highest confidence (OEM_EXACT). Green. */
        GREEN,
    }

    companion object {
        /**
         * Look up the confidence for a [RepresentationLevel].
         * The function is **total**: every level has a
         * confidence (UNKNOWN is rejected by the
         * constructor; the caller is expected to
         * check before calling).
         *
         * The lookup is the platform's source of truth
         * for the per-level UI hint + the marketplace +
         * safety gates. A consumer that wants a
         * different per-level hint (e.g. an OEM-specific
         * label) can build its own lookup.
         */
        fun forLevel(level: RepresentationLevel): RepresentationConfidence =
            when (level) {
                RepresentationLevel.OEM_EXACT -> RepresentationConfidence(
                    level = level,
                    displayLabel = "OEM-Verified",
                    description = "Geometry validated against an OEM-shipped vehicle; " +
                        "units are correct; coordinate system matches the OEM standard; " +
                        "part numbers are traceable",
                    uiColor = UiColor.GREEN,
                    marketplaceEligible = true,
                    safetyGatePasses = true,
                )
                RepresentationLevel.OEM_PARTIAL -> RepresentationConfidence(
                    level = level,
                    displayLabel = "OEM-Partial",
                    description = "Most of the geometry is OEM-validated; " +
                        "some surfaces are parametric-functional " +
                        "(e.g. interior trim that varies by trim level)",
                    uiColor = UiColor.BLUE,
                    marketplaceEligible = true,
                    safetyGatePasses = true,
                )
                RepresentationLevel.PARAMETRIC_FUNCTIONAL -> RepresentationConfidence(
                    level = level,
                    displayLabel = "Parametric",
                    description = "Geometry is defined parametrically (mathematically) " +
                        "and is functionally accurate; the visual rendering " +
                        "is approximate",
                    uiColor = UiColor.YELLOW,
                    marketplaceEligible = true,
                    safetyGatePasses = true,
                )
                RepresentationLevel.CONCEPTUAL -> RepresentationConfidence(
                    level = level,
                    displayLabel = "Conceptual",
                    description = "Geometry is a placeholder for an engineering concept; " +
                        "dimensions are nominal, not validated; " +
                        "not eligible for a Settlement (per ADR-0011)",
                    uiColor = UiColor.ORANGE,
                    marketplaceEligible = false,
                    safetyGatePasses = false,
                )
                RepresentationLevel.VISUAL_ONLY -> RepresentationConfidence(
                    level = level,
                    displayLabel = "Visual Only",
                    description = "Geometry is a visual-only representation with " +
                        "no engineering claim; not eligible for a Settlement " +
                        "(per ADR-0011)",
                    uiColor = UiColor.RED,
                    marketplaceEligible = false,
                    safetyGatePasses = false,
                )
                RepresentationLevel.UNKNOWN -> throw IllegalArgumentException(
                    "RepresentationConfidence.forLevel(UNKNOWN) is invalid; " +
                        "UNKNOWN is the 'level not set' sentinel and is " +
                        "rejected by the constructor"
                )
            }

        /**
         * The marketplace gate (per ADR-0011):
         * `OEM_EXACT`, `OEM_PARTIAL`, and
         * `PARAMETRIC_FUNCTIONAL` are eligible;
         * `CONCEPTUAL` and `VISUAL_ONLY` are not.
         *
         * The function is a convenience over
         * `forLevel(level).marketplaceEligible`.
         */
        fun isMarketplaceEligible(level: RepresentationLevel): Boolean =
            forLevel(level).marketplaceEligible

        /**
         * The safety gate (per skill 13):
         * `OEM_EXACT`, `OEM_PARTIAL`, and
         * `PARAMETRIC_FUNCTIONAL` pass; `CONCEPTUAL`
         * and `VISUAL_ONLY` fail.
         *
         * The function is a convenience over
         * `forLevel(level).safetyGatePasses`.
         */
        fun passesSafetyGate(level: RepresentationLevel): Boolean =
            forLevel(level).safetyGatePasses
    }
}
