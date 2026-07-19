package com.elysium.vanguard.foundry.core.dsl.validator.rules

import com.elysium.vanguard.foundry.core.dsl.parser.CompilationDiagnostic
import com.elysium.vanguard.foundry.core.dsl.schema.CompiledVehicleSpec
import com.elysium.vanguard.foundry.core.dsl.validator.SpecRule
import com.elysium.vanguard.foundry.core.dsl.validator.SpecRuleSet

/**
 * Phase 2 / I-2.4 — the default rule set.
 *
 * The default rule set is the 9 cross-aggregate
 * invariants the platform ships. The rules are
 * deterministic + total; the rule order is stable
 * (the validator iterates the list index-first).
 *
 * The rule set is versioned (`v1.0.0`) per `.ai/skills/
 * 04-vehicle-dsl-compiler/SKILL.md` section 6 step 22
 * — Rule-set versioning. A breaking change to a rule
 * (e.g. "the COUPE + 4 doors rule is no longer HARD")
 * is a new rule set version + a migration path.
 *
 * The rule set's name is `default` — the default
 * validator uses this rule set. Custom validators
 * (an OEM's brand-specific validator) can use a
 * different rule set.
 */
val DefaultRuleSet: SpecRuleSet = SpecRuleSet(
    name = "default",
    version = "v1.0.0",
    rules = listOf(
        // Drive-train rules
        ElectricRequiresSingleOrTwoSpeedTransmissionRule,
        V10OrV12EngineRequiresRwdOrAwdRule,
        // Body shape rules
        RoadsterMustHave2DoorsRule,
        CoupeMustHave2DoorsRule,
        CoupeAndRoadsterMax2SeatsRule,
        VanMustHave3PlusDoorsRule,
        PickupWith3DoorsRule,
        WagonWith9SeatsMustHave4PlusDoorsRule,
        // Combination rules
        GasolineWithSingleSpeedTransmissionRule,
    ),
)

// ============================================================
// Drive-train rules
// ============================================================

/**
 * `ELECTRIC` propulsion requires `SINGLE_SPEED` or
 * `TWO_SPEED` transmission. A multi-speed manual or
 * automatic transmission on an EV is mechanically
 * nonsensical (an electric motor has full torque at
 * zero RPM — a clutch + gears add cost + weight
 * with no benefit).
 *
 * HARD severity.
 */
object ElectricRequiresSingleOrTwoSpeedTransmissionRule : SpecRule {
    override val code: String = "VCOMP-RULE-EV-TRANSMISSION"
    override val name: String = "EV transmission must be single- or two-speed"
    override fun check(spec: CompiledVehicleSpec): List<CompilationDiagnostic> {
        if (spec.propulsion.energySource != com.elysium.vanguard.foundry.core.dsl.schema.EnergySource.ELECTRIC) {
            return emptyList()
        }
        val tx = spec.driveline.transmission
        val allowed = setOf(
            com.elysium.vanguard.foundry.core.dsl.schema.Transmission.SINGLE_SPEED,
            com.elysium.vanguard.foundry.core.dsl.schema.Transmission.TWO_SPEED,
        )
        if (tx !in allowed) {
            return listOf(
                CompilationDiagnostic.CrossAggregateInvariantViolation(
                    ruleCode = code,
                    reason = "ELECTRIC propulsion requires SINGLE_SPEED or TWO_SPEED " +
                        "transmission; got ${tx.name}",
                    jsonPaths = listOf(
                        "$.propulsion.energySource",
                        "$.driveline.transmission",
                    ),
                ),
            )
        }
        return emptyList()
    }
}

/**
 * A V10 or V12 engine with FWD traction is a
 * physical packaging impossibility. The engine
 * is too long to fit transversely; a longitudinal
 * V10/V12 + FWD would mean the differential +
 * half-shafts have to clear the engine's length.
 *
 * Safety-critical severity (the spec claims a
 * build that is not physically realizable).
 */
object V10OrV12EngineRequiresRwdOrAwdRule : SpecRule {
    override val code: String = "VCOMP-RULE-V12-FWD"
    override val name: String = "V10/V12 engine requires RWD or AWD traction"
    override fun check(spec: CompiledVehicleSpec): List<CompilationDiagnostic> {
        val bigEngine = spec.propulsion.engine.configuration in setOf(
            com.elysium.vanguard.foundry.core.dsl.schema.EngineConfiguration.V10,
            com.elysium.vanguard.foundry.core.dsl.schema.EngineConfiguration.V12,
        )
        if (!bigEngine) return emptyList()
        if (spec.driveline.traction != com.elysium.vanguard.foundry.core.dsl.schema.Traction.FWD) {
            return emptyList()
        }
        return listOf(
            CompilationDiagnostic.CrossAggregateInvariantViolation(
                ruleCode = code,
                reason = "${spec.propulsion.engine.configuration.name} engine " +
                    "with FWD traction is a packaging impossibility; " +
                    "the engine is too long for transverse mounting and " +
                    "longitudinal FWD would conflict with the differential",
                jsonPaths = listOf(
                    "$.propulsion.engine.configuration",
                    "$.driveline.traction",
                ),
                diagnosticSeverity = CompilationDiagnostic.Severity.SAFETY_CRITICAL,
            ),
        )
    }
}

// ============================================================
// Body shape rules
// ============================================================

/**
 * A roadster must have 2 doors. Roadsters by
 * definition are 2-seat, 2-door, open-top
 * sports cars. A roadster with 4 doors is a
 * contradiction in terms.
 *
 * HARD severity.
 */
object RoadsterMustHave2DoorsRule : SpecRule {
    override val code: String = "VCOMP-RULE-ROADSTER-DOORS"
    override val name: String = "Roadster must have 2 doors"
    override fun check(spec: CompiledVehicleSpec): List<CompilationDiagnostic> {
        if (spec.body.architecture != com.elysium.vanguard.foundry.core.dsl.schema.BodyArchitecture.ROADSTER) {
            return emptyList()
        }
        if (spec.body.doors == 2) return emptyList()
        return listOf(
            CompilationDiagnostic.CrossAggregateInvariantViolation(
                ruleCode = code,
                reason = "ROADSTER body architecture must have 2 doors; got ${spec.body.doors}",
                jsonPaths = listOf(
                    "$.body.architecture",
                    "$.body.doors",
                ),
            ),
        )
    }
}

/**
 * A coupe must have 2 doors. A coupe is by
 * definition a 2-door car (the "coupe" name
 * comes from the French "coupé" — a "cut"
 * carriage with a shortened roof). A 4-door
 * coupe is a sedan, not a coupe.
 *
 * HARD severity.
 */
object CoupeMustHave2DoorsRule : SpecRule {
    override val code: String = "VCOMP-RULE-COUPE-DOORS"
    override val name: String = "Coupe must have 2 doors"
    override fun check(spec: CompiledVehicleSpec): List<CompilationDiagnostic> {
        if (spec.body.architecture != com.elysium.vanguard.foundry.core.dsl.schema.BodyArchitecture.COUPE) {
            return emptyList()
        }
        if (spec.body.doors == 2) return emptyList()
        return listOf(
            CompilationDiagnostic.CrossAggregateInvariantViolation(
                ruleCode = code,
                reason = "COUPE body architecture must have 2 doors; got ${spec.body.doors}",
                jsonPaths = listOf(
                    "$.body.architecture",
                    "$.body.doors",
                ),
            ),
        )
    }
}

/**
 * A coupe or roadster must have 2 seats.
 * A "5-seat coupe" is a contradiction — the
 * roof line is too low for a 3rd row of seats
 * to be ergonomic.
 *
 * HARD severity.
 */
object CoupeAndRoadsterMax2SeatsRule : SpecRule {
    override val code: String = "VCOMP-RULE-2SEAT-BODY"
    override val name: String = "Coupe and roadster must have at most 2 seats"
    override fun check(spec: CompiledVehicleSpec): List<CompilationDiagnostic> {
        val twoSeatBody = spec.body.architecture in setOf(
            com.elysium.vanguard.foundry.core.dsl.schema.BodyArchitecture.COUPE,
            com.elysium.vanguard.foundry.core.dsl.schema.BodyArchitecture.ROADSTER,
        )
        if (!twoSeatBody) return emptyList()
        if (spec.body.seats <= 2) return emptyList()
        return listOf(
            CompilationDiagnostic.CrossAggregateInvariantViolation(
                ruleCode = code,
                reason = "${spec.body.architecture.name} body architecture " +
                    "must have at most 2 seats; got ${spec.body.seats}",
                jsonPaths = listOf(
                    "$.body.architecture",
                    "$.body.seats",
                ),
            ),
        )
    }
}

/**
 * A van must have 3+ doors. A van with 2 doors
 * cannot be loaded / unloaded from the side; the
 * sliding side door is the defining feature of a
 * van.
 *
 * HARD severity.
 */
object VanMustHave3PlusDoorsRule : SpecRule {
    override val code: String = "VCOMP-RULE-VAN-DOORS"
    override val name: String = "Van must have 3+ doors"
    override fun check(spec: CompiledVehicleSpec): List<CompilationDiagnostic> {
        if (spec.body.architecture != com.elysium.vanguard.foundry.core.dsl.schema.BodyArchitecture.VAN) {
            return emptyList()
        }
        if (spec.body.doors >= 3) return emptyList()
        return listOf(
            CompilationDiagnostic.CrossAggregateInvariantViolation(
                ruleCode = code,
                reason = "VAN body architecture must have 3+ doors (the side " +
                    "loading door is a defining feature); got ${spec.body.doors}",
                jsonPaths = listOf(
                    "$.body.architecture",
                    "$.body.doors",
                ),
            ),
        )
    }
}

/**
 * A pickup with 3 doors is unusual — production
 * pickups have 2 doors (regular cab) or 4 doors
 * (crew cab / double cab). A 3-door pickup would
 * have an asymmetric door layout (e.g. 2 on one
 * side, 1 on the other) which is not how pickups
 * are designed.
 *
 * SOFT severity — a warning, not a block. A
 * 3-door pickup can be valid for a specific
 * market's homologation rules.
 */
object PickupWith3DoorsRule : SpecRule {
    override val code: String = "VCOMP-RULE-PICKUP-3DOORS"
    override val name: String = "Pickup with 3 doors is unusual"
    override fun check(spec: CompiledVehicleSpec): List<CompilationDiagnostic> {
        if (spec.body.architecture != com.elysium.vanguard.foundry.core.dsl.schema.BodyArchitecture.PICKUP) {
            return emptyList()
        }
        if (spec.body.doors != 3) return emptyList()
        return listOf(
            CompilationDiagnostic.CrossAggregateInvariantViolation(
                ruleCode = code,
                reason = "PICKUP body architecture with 3 doors is " +
                    "unusual — production pickups have 2 doors " +
                    "(regular cab) or 4 doors (crew cab)",
                jsonPaths = listOf(
                    "$.body.architecture",
                    "$.body.doors",
                ),
                diagnosticSeverity = CompilationDiagnostic.Severity.SOFT,
            ),
        )
    }
}

/**
 * A 9-seat wagon must have 4+ doors. A 9-seat
 * vehicle requires side access for the 3rd
 * row; a 2-door wagon forces the 3rd row to
 * enter through the rear hatch, which is
 * impractical for adults.
 *
 * HARD severity.
 */
object WagonWith9SeatsMustHave4PlusDoorsRule : SpecRule {
    override val code: String = "VCOMP-RULE-9SEAT-WAGON"
    override val name: String = "9-seat wagon must have 4+ doors"
    override fun check(spec: CompiledVehicleSpec): List<CompilationDiagnostic> {
        if (spec.body.architecture != com.elysium.vanguard.foundry.core.dsl.schema.BodyArchitecture.WAGON) {
            return emptyList()
        }
        if (spec.body.seats != 9) return emptyList()
        if (spec.body.doors >= 4) return emptyList()
        return listOf(
            CompilationDiagnostic.CrossAggregateInvariantViolation(
                ruleCode = code,
                reason = "WAGON body architecture with 9 seats must have 4+ " +
                    "doors (3rd-row access); got ${spec.body.doors} doors",
                jsonPaths = listOf(
                    "$.body.architecture",
                    "$.body.seats",
                    "$.body.doors",
                ),
            ),
        )
    }
}

// ============================================================
// Combination rules
// ============================================================

/**
 * A `GASOLINE` engine with a `SINGLE_SPEED`
 * transmission is rare — most production
 * gasoline engines use a multi-speed gearbox
 * because the engine's narrow torque band
 * requires gear ratios to keep the engine
 * in its power band.
 *
 * A `SINGLE_SPEED` is an EV transmission.
 * A gasoline + single-speed is unusual but
 * not impossible (the C8 Corvette's earliest
 * prototypes tested a single-speed — it was
 * eventually rejected for driveability).
 *
 * SOFT severity (a warning, not a block).
 */
object GasolineWithSingleSpeedTransmissionRule : SpecRule {
    override val code: String = "VCOMP-RULE-GAS-SINGLE-SPEED"
    override val name: String = "Gasoline + single-speed transmission is unusual"
    override fun check(spec: CompiledVehicleSpec): List<CompilationDiagnostic> {
        if (spec.propulsion.energySource != com.elysium.vanguard.foundry.core.dsl.schema.EnergySource.GASOLINE) {
            return emptyList()
        }
        if (spec.driveline.transmission != com.elysium.vanguard.foundry.core.dsl.schema.Transmission.SINGLE_SPEED) {
            return emptyList()
        }
        return listOf(
            CompilationDiagnostic.CrossAggregateInvariantViolation(
                ruleCode = code,
                reason = "GASOLINE engine with SINGLE_SPEED transmission is " +
                    "unusual — a gasoline engine's torque band typically " +
                    "requires a multi-speed gearbox for driveability",
                jsonPaths = listOf(
                    "$.propulsion.energySource",
                    "$.driveline.transmission",
                ),
                diagnosticSeverity = CompilationDiagnostic.Severity.SOFT,
            ),
        )
    }
}
