package com.elysium.vanguard.foundry.core.dsl.compatibility

import com.elysium.vanguard.foundry.core.dsl.parser.CompilationDiagnostic
import com.elysium.vanguard.foundry.core.dsl.schema.BodyArchitecture
import com.elysium.vanguard.foundry.core.dsl.schema.CompiledVehicleSpec
import com.elysium.vanguard.foundry.core.dsl.schema.EnergySource
import com.elysium.vanguard.foundry.core.dsl.schema.EngineConfiguration
import com.elysium.vanguard.foundry.core.dsl.schema.Traction
import com.elysium.vanguard.foundry.core.dsl.schema.Transmission
import com.elysium.vanguard.foundry.core.dsl.schema.UnitValue

/**
 * Phase 2 / I-2.5 — the **Default Compatibility
 * Constraint Engine**.
 *
 * The default engine applies a [CompatibilityConstraintSet]
 * of constraints in order and aggregates the diagnostics.
 * The default constraint set is the 8 cross-aggregate
 * constraints the platform ships.
 *
 * The engine is **pure-domain**: no I/O, no Android
 * dependencies. The engine is JVM-testable end-to-end.
 *
 * The default engine's constraint set:
 *
 *   - `VCOMP-CONSTRAINT-SUV-WHEELBASE` — SUV body
 *     architecture requires wheelbase >= 2.5m
 *     (REGULATORY).
 *   - `VCOMP-CONSTRAINT-V8-DISPLACEMENT` — V8+
 *     engine requires displacement >= 4.0L
 *     (REGULATORY).
 *   - `VCOMP-CONSTRAINT-VAN-SEATS` — VAN body
 *     architecture with 9 seats requires wheelbase
 *     >= 3.0m (REGULATORY).
 *   - `VCOMP-CONSTRAINT-HYBRID-TRANSMISSION` —
 *     HYBRID / PLUG_IN_HYBRID propulsion requires
 *     a multi-speed transmission (CVT or automatic;
 *     not SINGLE_SPEED) (REGULATORY).
 *   - `VCOMP-CONSTRAINT-QUAD-MOTORS` — QUAD traction
 *     requires ELECTRIC or HYBRID propulsion
 *     (REGULATORY).
 *   - `VCOMP-CONSTRAINT-PICKUP-CREW-4WD` — PICKUP
 *     body with 5 seats and RWD is unusual
 *     (OPTIMIZATION; AWD is more common).
 *   - `VCOMP-CONSTRAINT-WAGON-CARGO` — WAGON body
 *     with 2 doors is unusual (OPTIMIZATION;
 *     wagons are typically 4+ doors for cargo
 *     access).
 *   - `VCOMP-CONSTRAINT-DIESEL-LIGHT-TRUCK` —
 *     DIESEL engine in a 2-seater is unusual
 *     (OPTIMIZATION; diesel is more common in
 *     trucks / SUVs).
 */
class DefaultCompatibilityConstraintEngine(
    private val constraintSet: CompatibilityConstraintSet,
) : CompatibilityConstraintEngine {

    override fun evaluate(spec: CompiledVehicleSpec): List<CompilationDiagnostic> =
        constraintSet.constraints.flatMap { constraint ->
            constraint.check(spec)
        }

    companion object {
        /**
         * Build a default engine with the platform's
         * default constraint set.
         */
        fun withDefaultConstraints(): DefaultCompatibilityConstraintEngine =
            DefaultCompatibilityConstraintEngine(
                constraintSet = DefaultCompatibilityConstraintSet,
            )
    }
}

/**
 * A named collection of [CompatibilityConstraint]s.
 * The set is a value object (the same set can be
 * reused across engines).
 */
data class CompatibilityConstraintSet(
    val name: String,
    val version: String,
    val constraints: List<CompatibilityConstraint>,
) {
    init {
        require(name.isNotBlank()) { "CompatibilityConstraintSet.name must not be blank" }
        require(version.isNotBlank()) { "CompatibilityConstraintSet.version must not be blank" }
        val codes = constraints.map { it.code }
        require(codes.size == codes.toSet().size) {
            "CompatibilityConstraintSet has duplicate constraint codes: " +
                codes.groupBy { it }.filterValues { it.size > 1 }.keys
        }
    }
}

// ============================================================
// Default constraints
// ============================================================

/**
 * SUV body architecture requires wheelbase >= 2.5m
 * (the EU regulatory standard for the B-segment
 * SUV class).
 *
 * REGULATORY severity.
 */
object SuvRequiresLongWheelbaseConstraint : CompatibilityConstraint {
    override val code: String = "VCOMP-CONSTRAINT-SUV-WHEELBASE"
    override val name: String = "SUV requires wheelbase >= 2.5m"
    override fun check(spec: CompiledVehicleSpec): List<CompilationDiagnostic> {
        if (spec.body.architecture != BodyArchitecture.SUV) {
            return emptyList()
        }
        if (spec.body.wheelbase.value >= 2.5) {
            return emptyList()
        }
        return listOf(
            CompilationDiagnostic.CrossAggregateInvariantViolation(
                ruleCode = code,
                reason = "SUV body architecture requires wheelbase " +
                    ">= 2.5m (EU B-segment standard); got " +
                    "${spec.body.wheelbase.value} ${spec.body.wheelbase.unitName()}",
                jsonPaths = listOf(
                    "$.body.architecture",
                    "$.body.wheelbase",
                ),
                diagnosticSeverity = CompilationDiagnostic.Severity.REGULATORY,
            ),
        )
    }
}

/**
 * V8+ engine requires displacement >= 4.0L. A V8
 * with less than 4.0L is a "small-block" V8, but
 * the platform's "V8" classification implies a
 * large-block V8 (the kind used in muscle cars +
 * trucks).
 *
 * REGULATORY severity.
 */
object V8RequiresLargeDisplacementConstraint : CompatibilityConstraint {
    override val code: String = "VCOMP-CONSTRAINT-V8-DISPLACEMENT"
    override val name: String = "V8 engine requires displacement >= 4.0L"
    override fun check(spec: CompiledVehicleSpec): List<CompilationDiagnostic> {
        val bigEngine = spec.propulsion.engine.configuration in setOf(
            EngineConfiguration.V8,
            EngineConfiguration.V10,
            EngineConfiguration.V12,
        )
        if (!bigEngine) return emptyList()
        val liters = when (val u = spec.propulsion.engine.displacement) {
            is UnitValue.Volume -> when (u.unit) {
                com.elysium.vanguard.foundry.core.dsl.schema.VolumeUnit.LITER -> u.value
                com.elysium.vanguard.foundry.core.dsl.schema.VolumeUnit.CUBIC_CENTIMETER -> u.value / 1000.0
                com.elysium.vanguard.foundry.core.dsl.schema.VolumeUnit.CUBIC_INCH -> u.value * 0.0163871
            }
            else -> 0.0
        }
        if (liters >= 4.0) return emptyList()
        return listOf(
            CompilationDiagnostic.CrossAggregateInvariantViolation(
                ruleCode = code,
                reason = "${spec.propulsion.engine.configuration.name} engine " +
                    "requires displacement >= 4.0L; got ${"%.2f".format(liters)}L",
                jsonPaths = listOf(
                    "$.propulsion.engine.configuration",
                    "$.propulsion.engine.displacement",
                ),
                diagnosticSeverity = CompilationDiagnostic.Severity.REGULATORY,
            ),
        )
    }
}

/**
 * VAN body with 9 seats requires wheelbase >= 3.0m
 * (the EU regulatory standard for a 9-seat van).
 *
 * REGULATORY severity.
 */
object Van9SeatsRequiresLongWheelbaseConstraint : CompatibilityConstraint {
    override val code: String = "VCOMP-CONSTRAINT-VAN-WHEELBASE"
    override val name: String = "9-seat van requires wheelbase >= 3.0m"
    override fun check(spec: CompiledVehicleSpec): List<CompilationDiagnostic> {
        if (spec.body.architecture != BodyArchitecture.VAN) return emptyList()
        if (spec.body.seats != 9) return emptyList()
        if (spec.body.wheelbase.value >= 3.0) return emptyList()
        return listOf(
            CompilationDiagnostic.CrossAggregateInvariantViolation(
                ruleCode = code,
                reason = "VAN body architecture with 9 seats requires " +
                    "wheelbase >= 3.0m (EU 9-seater standard); got " +
                    "${spec.body.wheelbase.value} ${spec.body.wheelbase.unitName()}",
                jsonPaths = listOf(
                    "$.body.architecture",
                    "$.body.seats",
                    "$.body.wheelbase",
                ),
                diagnosticSeverity = CompilationDiagnostic.Severity.REGULATORY,
            ),
        )
    }
}

/**
 * HYBRID / PLUG_IN_HYBRID propulsion requires a
 * multi-speed transmission. A SINGLE_SPEED is for
 * pure EVs; a hybrid needs the engine's gears to
 * stay in the engine's power band.
 *
 * REGULATORY severity.
 */
object HybridRequiresMultiSpeedTransmissionConstraint : CompatibilityConstraint {
    override val code: String = "VCOMP-CONSTRAINT-HYBRID-TRANSMISSION"
    override val name: String = "Hybrid requires multi-speed transmission"
    override fun check(spec: CompiledVehicleSpec): List<CompilationDiagnostic> {
        val isHybrid = spec.propulsion.energySource in setOf(
            EnergySource.HYBRID,
            EnergySource.PLUG_IN_HYBRID,
        )
        if (!isHybrid) return emptyList()
        if (spec.driveline.transmission != Transmission.SINGLE_SPEED) {
            return emptyList()
        }
        return listOf(
            CompilationDiagnostic.CrossAggregateInvariantViolation(
                ruleCode = code,
                reason = "${spec.propulsion.energySource.name} propulsion " +
                    "requires a multi-speed transmission (the engine " +
                    "needs gears to stay in its power band); got SINGLE_SPEED",
                jsonPaths = listOf(
                    "$.propulsion.energySource",
                    "$.driveline.transmission",
                ),
                diagnosticSeverity = CompilationDiagnostic.Severity.REGULATORY,
            ),
        )
    }
}

/**
 * QUAD traction (four-motor independent) requires
 * ELECTRIC or HYBRID propulsion. QUAD means four
 * motors — one per wheel — which is mechanically
 * incompatible with a single ICE engine.
 *
 * REGULATORY severity.
 */
object QuadRequiresElectricOrHybridConstraint : CompatibilityConstraint {
    override val code: String = "VCOMP-CONSTRAINT-QUAD-PROPULSION"
    override val name: String = "QUAD traction requires ELECTRIC or HYBRID propulsion"
    override fun check(spec: CompiledVehicleSpec): List<CompilationDiagnostic> {
        if (spec.driveline.traction != Traction.QUAD) return emptyList()
        if (spec.propulsion.energySource in setOf(
                EnergySource.ELECTRIC,
                EnergySource.HYBRID,
                EnergySource.PLUG_IN_HYBRID,
            )
        ) return emptyList()
        return listOf(
            CompilationDiagnostic.CrossAggregateInvariantViolation(
                ruleCode = code,
                reason = "QUAD traction (four-motor independent) requires " +
                    "ELECTRIC, HYBRID, or PLUG_IN_HYBRID propulsion; got " +
                    spec.propulsion.energySource.name,
                jsonPaths = listOf(
                    "$.driveline.traction",
                    "$.propulsion.energySource",
                ),
                diagnosticSeverity = CompilationDiagnostic.Severity.REGULATORY,
            ),
        )
    }
}

/**
 * PICKUP body with 5 seats and RWD is unusual —
 * 5-seat pickups are typically AWD or 4WD for
 * off-road capability. RWD pickups are usually
 * 2-seat regular cabs.
 *
 * OPTIMIZATION severity (a warning, not a block).
 */
object Pickup5SeatsPrefersAwdConstraint : CompatibilityConstraint {
    override val code: String = "VCOMP-CONSTRAINT-PICKUP-AWD"
    override val name: String = "5-seat pickup with RWD is unusual"
    override fun check(spec: CompiledVehicleSpec): List<CompilationDiagnostic> {
        if (spec.body.architecture != BodyArchitecture.PICKUP) return emptyList()
        if (spec.body.seats != 5) return emptyList()
        if (spec.driveline.traction != Traction.RWD) return emptyList()
        return listOf(
            CompilationDiagnostic.CrossAggregateInvariantViolation(
                ruleCode = code,
                reason = "5-seat PICKUP with RWD is unusual — 5-seat " +
                    "pickups are typically AWD for off-road capability. " +
                    "Consider AWD or QUAD traction",
                jsonPaths = listOf(
                    "$.body.architecture",
                    "$.body.seats",
                    "$.driveline.traction",
                ),
                diagnosticSeverity = CompilationDiagnostic.Severity.OPTIMIZATION,
            ),
        )
    }
}

/**
 * WAGON body with 2 doors is unusual — wagons are
 * typically 4+ doors for cargo access.
 *
 * OPTIMIZATION severity.
 */
object Wagon2DoorsIsUnusualConstraint : CompatibilityConstraint {
    override val code: String = "VCOMP-CONSTRAINT-WAGON-2DOORS"
    override val name: String = "2-door wagon is unusual"
    override fun check(spec: CompiledVehicleSpec): List<CompilationDiagnostic> {
        if (spec.body.architecture != BodyArchitecture.WAGON) return emptyList()
        if (spec.body.doors != 2) return emptyList()
        return listOf(
            CompilationDiagnostic.CrossAggregateInvariantViolation(
                ruleCode = code,
                reason = "WAGON body architecture with 2 doors is unusual — " +
                    "wagons are typically 4+ doors for cargo access. " +
                    "Consider 4 or 5 doors",
                jsonPaths = listOf(
                    "$.body.architecture",
                    "$.body.doors",
                ),
                diagnosticSeverity = CompilationDiagnostic.Severity.OPTIMIZATION,
            ),
        )
    }
}

/**
 * DIESEL engine in a 2-seater is unusual — diesel
 * is more common in trucks / SUVs (the engine's
 * torque band is more useful for heavy loads).
 *
 * OPTIMIZATION severity.
 */
object DieselIn2SeaterIsUnusualConstraint : CompatibilityConstraint {
    override val code: String = "VCOMP-CONSTRAINT-DIESEL-2SEAT"
    override val name: String = "Diesel in 2-seater is unusual"
    override fun check(spec: CompiledVehicleSpec): List<CompilationDiagnostic> {
        if (spec.propulsion.energySource != EnergySource.DIESEL) return emptyList()
        if (spec.body.seats > 2) return emptyList()
        return listOf(
            CompilationDiagnostic.CrossAggregateInvariantViolation(
                ruleCode = code,
                reason = "DIESEL engine in a 2-seater is unusual — diesel " +
                    "is more common in trucks / SUVs where the engine's " +
                    "torque band is more useful. Consider GASOLINE for " +
                    "a 2-seater",
                jsonPaths = listOf(
                    "$.propulsion.energySource",
                    "$.body.seats",
                ),
                diagnosticSeverity = CompilationDiagnostic.Severity.OPTIMIZATION,
            ),
        )
    }
}

/**
 * The default constraint set. 8 cross-aggregate
 * constraints; the engine applies them in order.
 * The set is versioned for the same reasons as
 * the validator's `DefaultRuleSet`.
 */
val DefaultCompatibilityConstraintSet: CompatibilityConstraintSet =
    CompatibilityConstraintSet(
        name = "default",
        version = "v1.0.0",
        constraints = listOf(
            // Regulatory
            SuvRequiresLongWheelbaseConstraint,
            V8RequiresLargeDisplacementConstraint,
            Van9SeatsRequiresLongWheelbaseConstraint,
            HybridRequiresMultiSpeedTransmissionConstraint,
            QuadRequiresElectricOrHybridConstraint,
            // Optimization
            Pickup5SeatsPrefersAwdConstraint,
            Wagon2DoorsIsUnusualConstraint,
            DieselIn2SeaterIsUnusualConstraint,
        ),
    )
