package com.elysium.vanguard.foundry.core.dsl.compatibility

import com.elysium.vanguard.foundry.core.dsl.parser.CompilationDiagnostic
import com.elysium.vanguard.foundry.core.dsl.schema.ApiVersion
import com.elysium.vanguard.foundry.core.dsl.schema.Body
import com.elysium.vanguard.foundry.core.dsl.schema.BodyArchitecture
import com.elysium.vanguard.foundry.core.dsl.schema.CompiledVehicleSpec
import com.elysium.vanguard.foundry.core.dsl.schema.Driveline
import com.elysium.vanguard.foundry.core.dsl.schema.EnergySource
import com.elysium.vanguard.foundry.core.dsl.schema.Engine
import com.elysium.vanguard.foundry.core.dsl.schema.EngineConfiguration
import com.elysium.vanguard.foundry.core.dsl.schema.EngineOrientation
import com.elysium.vanguard.foundry.core.dsl.schema.LengthUnit
import com.elysium.vanguard.foundry.core.dsl.schema.Propulsion
import com.elysium.vanguard.foundry.core.dsl.schema.SpecClassification
import com.elysium.vanguard.foundry.core.dsl.schema.SpecMetadata
import com.elysium.vanguard.foundry.core.dsl.schema.Traction
import com.elysium.vanguard.foundry.core.dsl.schema.Transmission
import com.elysium.vanguard.foundry.core.dsl.schema.UnitValue
import com.elysium.vanguard.foundry.core.dsl.schema.VolumeUnit
import com.elysium.vanguard.foundry.core.dsl.schema.buildSpec
import com.elysium.vanguard.foundry.core.ontology.ids.ProjectId
import com.elysium.vanguard.foundry.core.ontology.primitives.RepresentationLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 2 / I-2.5 — the JVM tests for
 * [DefaultCompatibilityConstraintEngine].
 *
 * The constraint engine is step 8 of the 18-step
 * pipeline (per `.ai/skills/04-vehicle-dsl-compiler/
 * SKILL.md` section 6). The engine evaluates
 * market / regulatory / optimization constraints
 * against a spec.
 *
 * The tests cover:
 *   - The Urban One spec passes all 8 default
 *     constraints.
 *   - Each constraint fires on its triggering input
 *     with the right severity.
 *   - The engine is deterministic (same spec → same
 *     diagnostic list).
 *   - The constraint set has unique codes + non-blank
 *     name + version.
 */
class DefaultCompatibilityConstraintEngineTest {

    private val engine = DefaultCompatibilityConstraintEngine.withDefaultConstraints()

    // ============================================================
    // Canonical happy path: Urban One passes all constraints
    // ============================================================

    @Test
    fun `urban one spec passes all default compatibility constraints`() {
        val spec = urbanOneSpec()
        val diagnostics = engine.evaluate(spec)
        assertTrue(
            "expected Urban One to pass all constraints, got: $diagnostics",
            diagnostics.isEmpty(),
        )
    }

    // ============================================================
    // SUV wheelbase constraint
    // ============================================================

    @Test
    fun `SUV with short wheelbase is a REGULATORY violation`() {
        val spec = urbanOneSpec().copy(
            body = Body(
                architecture = BodyArchitecture.SUV,
                doors = 5,
                seats = 5,
                wheelbase = UnitValue.Length(value = 2.30, unit = LengthUnit.METER),
            ),
        )
        val diagnostics = engine.evaluate(spec)
        val rule = diagnostics.filterIsInstance<
            CompilationDiagnostic.CrossAggregateInvariantViolation,
        >().firstOrNull { it.ruleCode == "VCOMP-CONSTRAINT-SUV-WHEELBASE" }
        assertNotNull("expected SUV-wheelbase constraint to fire", rule)
        assertEquals(
            CompilationDiagnostic.Severity.REGULATORY,
            rule!!.diagnosticSeverity,
        )
    }

    @Test
    fun `SUV with long wheelbase passes the SUV constraint`() {
        val spec = urbanOneSpec().copy(
            body = Body(
                architecture = BodyArchitecture.SUV,
                doors = 5,
                seats = 5,
                wheelbase = UnitValue.Length(value = 2.70, unit = LengthUnit.METER),
            ),
        )
        val diagnostics = engine.evaluate(spec)
        val rule = diagnostics.filterIsInstance<
            CompilationDiagnostic.CrossAggregateInvariantViolation,
        >().firstOrNull { it.ruleCode == "VCOMP-CONSTRAINT-SUV-WHEELBASE" }
        assertEquals(null, rule)
    }

    // ============================================================
    // V8 displacement constraint
    // ============================================================

    @Test
    fun `V8 with small displacement is a REGULATORY violation`() {
        val spec = urbanOneSpec().copy(
            propulsion = Propulsion(
                energySource = EnergySource.GASOLINE,
                engine = Engine(
                    configuration = EngineConfiguration.V8,
                    displacement = UnitValue.Volume(value = 3.5, unit = VolumeUnit.LITER),
                    orientation = EngineOrientation.LONGITUDINAL,
                ),
            ),
        )
        val diagnostics = engine.evaluate(spec)
        val rule = diagnostics.filterIsInstance<
            CompilationDiagnostic.CrossAggregateInvariantViolation,
        >().firstOrNull { it.ruleCode == "VCOMP-CONSTRAINT-V8-DISPLACEMENT" }
        assertNotNull("expected V8-displacement constraint to fire", rule)
    }

    @Test
    fun `V8 with 5_0L displacement passes the V8 constraint`() {
        val spec = urbanOneSpec().copy(
            propulsion = Propulsion(
                energySource = EnergySource.GASOLINE,
                engine = Engine(
                    configuration = EngineConfiguration.V8,
                    displacement = UnitValue.Volume(value = 5.0, unit = VolumeUnit.LITER),
                    orientation = EngineOrientation.LONGITUDINAL,
                ),
            ),
        )
        val diagnostics = engine.evaluate(spec)
        val rule = diagnostics.filterIsInstance<
            CompilationDiagnostic.CrossAggregateInvariantViolation,
        >().firstOrNull { it.ruleCode == "VCOMP-CONSTRAINT-V8-DISPLACEMENT" }
        assertEquals(null, rule)
    }

    // ============================================================
    // VAN 9 seats wheelbase constraint
    // ============================================================

    @Test
    fun `9-seat van with short wheelbase is a REGULATORY violation`() {
        val spec = urbanOneSpec().copy(
            body = Body(
                architecture = BodyArchitecture.VAN,
                doors = 5,
                seats = 9,
                wheelbase = UnitValue.Length(value = 2.50, unit = LengthUnit.METER),
            ),
        )
        val diagnostics = engine.evaluate(spec)
        val rule = diagnostics.filterIsInstance<
            CompilationDiagnostic.CrossAggregateInvariantViolation,
        >().firstOrNull { it.ruleCode == "VCOMP-CONSTRAINT-VAN-WHEELBASE" }
        assertNotNull("expected VAN-wheelbase constraint to fire", rule)
    }

    // ============================================================
    // Hybrid transmission constraint
    // ============================================================

    @Test
    fun `HYBRID with SINGLE_SPEED is a REGULATORY violation`() {
        val spec = urbanOneSpec().copy(
            propulsion = Propulsion(
                energySource = EnergySource.HYBRID,
                engine = Engine(
                    configuration = EngineConfiguration.INLINE_4,
                    displacement = UnitValue.Volume(value = 1.5, unit = VolumeUnit.LITER),
                    orientation = EngineOrientation.TRANSVERSE,
                ),
            ),
            driveline = Driveline(
                traction = Traction.FWD,
                transmission = Transmission.SINGLE_SPEED,
            ),
        )
        val diagnostics = engine.evaluate(spec)
        val rule = diagnostics.filterIsInstance<
            CompilationDiagnostic.CrossAggregateInvariantViolation,
        >().firstOrNull { it.ruleCode == "VCOMP-CONSTRAINT-HYBRID-TRANSMISSION" }
        assertNotNull("expected HYBRID-transmission constraint to fire", rule)
    }

    // ============================================================
    // QUAD propulsion constraint
    // ============================================================

    @Test
    fun `QUAD with GASOLINE is a REGULATORY violation`() {
        val spec = urbanOneSpec().copy(
            propulsion = Propulsion(
                energySource = EnergySource.GASOLINE,
                engine = Engine(
                    configuration = EngineConfiguration.V8,
                    displacement = UnitValue.Volume(value = 5.0, unit = VolumeUnit.LITER),
                    orientation = EngineOrientation.LONGITUDINAL,
                ),
            ),
            driveline = Driveline(
                traction = Traction.QUAD,
                transmission = Transmission.AUTOMATIC_8,
            ),
        )
        val diagnostics = engine.evaluate(spec)
        val rule = diagnostics.filterIsInstance<
            CompilationDiagnostic.CrossAggregateInvariantViolation,
        >().firstOrNull { it.ruleCode == "VCOMP-CONSTRAINT-QUAD-PROPULSION" }
        assertNotNull("expected QUAD-propulsion constraint to fire", rule)
    }

    @Test
    fun `QUAD with ELECTRIC passes the QUAD constraint`() {
        val spec = urbanOneSpec().copy(
            driveline = Driveline(
                traction = Traction.QUAD,
                transmission = Transmission.SINGLE_SPEED,
            ),
        )
        val diagnostics = engine.evaluate(spec)
        val rule = diagnostics.filterIsInstance<
            CompilationDiagnostic.CrossAggregateInvariantViolation,
        >().firstOrNull { it.ruleCode == "VCOMP-CONSTRAINT-QUAD-PROPULSION" }
        assertEquals(null, rule)
    }

    // ============================================================
    // Optimization constraints
    // ============================================================

    @Test
    fun `5-seat pickup with RWD is an OPTIMIZATION suggestion`() {
        val spec = urbanOneSpec().copy(
            body = Body(
                architecture = BodyArchitecture.PICKUP,
                doors = 4,
                seats = 5,
                wheelbase = UnitValue.Length(value = 3.2, unit = LengthUnit.METER),
            ),
            driveline = Driveline(
                traction = Traction.RWD,
                transmission = Transmission.AUTOMATIC_8,
            ),
        )
        val diagnostics = engine.evaluate(spec)
        val rule = diagnostics.filterIsInstance<
            CompilationDiagnostic.CrossAggregateInvariantViolation,
        >().firstOrNull { it.ruleCode == "VCOMP-CONSTRAINT-PICKUP-AWD" }
        assertNotNull("expected pickup-AWD optimization to fire", rule)
        assertEquals(
            CompilationDiagnostic.Severity.OPTIMIZATION,
            rule!!.diagnosticSeverity,
        )
    }

    @Test
    fun `2-door wagon is an OPTIMIZATION suggestion`() {
        val spec = urbanOneSpec().copy(
            body = Body(
                architecture = BodyArchitecture.WAGON,
                doors = 2,
                seats = 5,
                wheelbase = UnitValue.Length(value = 2.80, unit = LengthUnit.METER),
            ),
        )
        val diagnostics = engine.evaluate(spec)
        val rule = diagnostics.filterIsInstance<
            CompilationDiagnostic.CrossAggregateInvariantViolation,
        >().firstOrNull { it.ruleCode == "VCOMP-CONSTRAINT-WAGON-2DOORS" }
        assertNotNull("expected wagon-2doors optimization to fire", rule)
    }

    @Test
    fun `diesel in 2-seater is an OPTIMIZATION suggestion`() {
        val spec = urbanOneSpec().copy(
            propulsion = Propulsion(
                energySource = EnergySource.DIESEL,
                engine = Engine(
                    configuration = EngineConfiguration.INLINE_4,
                    displacement = UnitValue.Volume(value = 1.5, unit = VolumeUnit.LITER),
                    orientation = EngineOrientation.TRANSVERSE,
                ),
            ),
            body = Body(
                architecture = BodyArchitecture.COUPE,
                doors = 2,
                seats = 2,
                wheelbase = UnitValue.Length(value = 2.50, unit = LengthUnit.METER),
            ),
        )
        val diagnostics = engine.evaluate(spec)
        val rule = diagnostics.filterIsInstance<
            CompilationDiagnostic.CrossAggregateInvariantViolation,
        >().firstOrNull { it.ruleCode == "VCOMP-CONSTRAINT-DIESEL-2SEAT" }
        assertNotNull("expected diesel-2seat optimization to fire", rule)
    }

    // ============================================================
    // Determinism
    // ============================================================

    @Test
    fun `engine is deterministic for the same spec`() {
        val spec = urbanOneSpec()
        val a = engine.evaluate(spec)
        val b = engine.evaluate(spec)
        val c = engine.evaluate(spec)
        assertEquals(a, b)
        assertEquals(b, c)
    }

    // ============================================================
    // Constraint set integrity
    // ============================================================

    @Test
    fun `default constraint set has unique codes`() {
        val codes = DefaultCompatibilityConstraintSet.constraints.map { it.code }
        assertEquals(
            "expected all constraint codes to be unique",
            codes.size,
            codes.toSet().size,
        )
    }

    @Test
    fun `default constraint set has a name and a version`() {
        assertTrue(DefaultCompatibilityConstraintSet.name.isNotBlank())
        assertTrue(DefaultCompatibilityConstraintSet.version.isNotBlank())
    }

    @Test
    fun `constraint set rejects blank name`() {
        try {
            CompatibilityConstraintSet(
                name = "",
                version = "v1",
                constraints = emptyList(),
            )
            fail("expected IllegalArgumentException for blank name")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("name"))
        }
    }

    @Test
    fun `constraint set rejects blank version`() {
        try {
            CompatibilityConstraintSet(
                name = "test",
                version = "",
                constraints = emptyList(),
            )
            fail("expected IllegalArgumentException for blank version")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("version"))
        }
    }

    @Test
    fun `constraint set rejects duplicate codes`() {
        val c1 = object : CompatibilityConstraint {
            override val code = "DUPLICATE-001"
            override val name = "c1"
            override fun check(spec: CompiledVehicleSpec) = emptyList<CompilationDiagnostic>()
        }
        val c2 = object : CompatibilityConstraint {
            override val code = "DUPLICATE-001"
            override val name = "c2"
            override fun check(spec: CompiledVehicleSpec) = emptyList<CompilationDiagnostic>()
        }
        try {
            CompatibilityConstraintSet(
                name = "test",
                version = "v1",
                constraints = listOf(c1, c2),
            )
            fail("expected IllegalArgumentException for duplicate codes")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("duplicate"))
        }
    }

    // ============================================================
    // Fixtures
    // ============================================================

    private fun urbanOneSpec(): CompiledVehicleSpec = buildSpec(
        apiVersion = ApiVersion.V1,
        metadata = SpecMetadata(
            projectId = ProjectId.from("00000000-0000-0000-0000-000000000001").getOrThrow(),
            revision = 1,
        ),
        classification = SpecClassification(
            representationLevel = RepresentationLevel.PARAMETRIC_FUNCTIONAL,
        ),
        body = Body(
            architecture = BodyArchitecture.HATCHBACK,
            doors = 5,
            seats = 5,
            wheelbase = UnitValue.Length(value = 2.45, unit = LengthUnit.METER),
        ),
        propulsion = Propulsion(
            energySource = EnergySource.ELECTRIC,
            engine = Engine(
                configuration = EngineConfiguration.ELECTRIC_NONE,
                displacement = UnitValue.Volume(value = 0.0, unit = VolumeUnit.LITER),
                orientation = EngineOrientation.LONGITUDINAL,
            ),
        ),
        driveline = Driveline(
            traction = Traction.FWD,
            transmission = Transmission.SINGLE_SPEED,
        ),
    ).getOrThrow()
}
