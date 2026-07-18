package com.elysium.vanguard.foundry.core.ontology.primitives

/**
 * The platform's `VehicleRepresentationLevel` — the typed declaration of
 * how accurately the current `VehicleDefinition` represents a real
 * engineering artifact.
 *
 * Per `.ai/STANDARDS.md` section 2.1 + ADR-0002:
 *   - The level is on the `Vehicle` aggregate, never on the asset alone.
 *   - The level is displayed prominently in the UI (per the master prompt).
 *   - Transitions are append-only + signed; a silent downgrade is `R-DI-6`.
 *   - `VISUAL_ONLY` and `CONCEPTUAL` are not eligible for a `Settlement`
 *     (per ADR-0011).
 *   - The current visual model of the Elysium Vanguard superdeportivo is
 *     never presented as `OEM_EXACT` geometry of a Hyundai Accent 2005
 *     (per the master prompt's 3D representation rule).
 */
enum class RepresentationLevel {
    /**
     * Sentinel: the level is not set. Used as a default value
     * + a "missing required field" indicator (per
     * `.ai/STANDARDS.md` 4 — a `vehicle` without a
     * `representationLevel` is rejected). The schema rejects
     * this value at construction time.
     */
    UNKNOWN,

    /**
     * The geometry has been validated against an OEM-shipped vehicle; the
     * units are correct; the coordinate system matches the OEM standard;
     * the part numbers are traceable. Requires an `OEM_VERIFIED`
     * `EngineeringFact<Geometry>` + a `ProvenanceRecord` + an
     * `OEM_VERIFIED` `VerificationStatus`.
     */
    OEM_EXACT,

    /**
     * Most of the geometry is OEM-validated; some surfaces are
     * parametric-functional (e.g. interior trim that varies by trim level).
     * Requires an `OEM_VERIFIED` base + a documented partial-coverage note.
     */
    OEM_PARTIAL,

    /**
     * The geometry is defined parametrically (mathematically) and is
     * functionally accurate; the visual rendering is approximate. This is
     * the **default** level for Phase 1 revisions (no validated OEM
     * assets exist yet).
     */
    PARAMETRIC_FUNCTIONAL,

    /**
     * The geometry is a placeholder for an engineering concept; the
     * dimensions are nominal, not validated. Not eligible for a
     * `Settlement` (per ADR-0011).
     */
    CONCEPTUAL,

    /**
     * The geometry is a visual-only representation with no engineering
     * claim (a render, a sketch, a marketing visual). Not eligible for a
     * `Settlement` (per ADR-0011).
     */
    VISUAL_ONLY,
}
