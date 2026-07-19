package com.elysium.vanguard.foundry.core.dsl.validator

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
import com.elysium.vanguard.foundry.core.dsl.schema.Propulsion
import com.elysium.vanguard.foundry.core.dsl.schema.SpecClassification
import com.elysium.vanguard.foundry.core.dsl.schema.SpecMetadata
import com.elysium.vanguard.foundry.core.dsl.schema.Traction
import com.elysium.vanguard.foundry.core.dsl.schema.Transmission
import com.elysium.vanguard.foundry.core.dsl.schema.buildSpec
import com.elysium.vanguard.foundry.core.dsl.validator.rules.DefaultRuleSet
import com.elysium.vanguard.foundry.core.ontology.ids.ProjectId
import com.elysium.vanguard.foundry.core.ontology.primitives.RepresentationLevel
import com.elysium.vanguard.foundry.core.dsl.schema.LengthUnit
import com.elysium.vanguard.foundry.core.dsl.schema.UnitValue
import com.elysium.vanguard.foundry.core.dsl.schema.VolumeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 2 / I-2.4 — the JVM tests for
 * [DefaultSpecValidator].
 *
 * The validator is the cross-aggregate invariant
 * checker. The tests cover:
 *   - The canonical "Urban One" spec passes (a
 *     HATCHBACK + ELECTRIC + FWD + SINGLE_SPEED is
 *     a well-known valid spec).
 *   - Every rule fires on its triggering input.
 *   - The validator is deterministic (same spec
 *     produces same diagnostics).
 *   - The `isValid` flag + the `blockingDiagnostics`
 *     + `warnings` filters are correct.
 *   - The rule set is well-formed (unique codes,
 *     non-blank name + version).
 *
 * The tests are JVM-friendly (no Android deps, no
 * I/O). The validator is pure-domain.
 */
class DefaultSpecValidatorTest {

    private val validator = DefaultSpecValidator.withDefaultRules()

    // ============================================================
    // Canonical happy path: Urban One passes all rules
    // ============================================================

    @Test
    fun `urban one spec passes all default rules`() {
        val spec = urbanOneSpec()
        val result = validator.validate(spec)
        assertTrue(
            "expected Urban One to pass all rules, got diagnostics: " +
                result.diagnostics,
            result.isValid,
        )
        assertEquals(0, result.diagnostics.size)
    }

    // ============================================================
    // EV transmission rule
    // ============================================================

    @Test
    fun `EV with manual transmission is a HARD violation`() {
        val spec = urbanOneSpec().copy(
            driveline = Driveline(
                traction = Traction.FWD,
                transmission = Transmission.MANUAL_6,
            ),
        )
        val result = validator.validate(spec)
        assertTrue("expected spec to be invalid", !result.isValid)
        val rule = result.blockingDiagnostics.firstOrNull {
            it is CompilationDiagnostic.CrossAggregateInvariantViolation &&
                it.ruleCode == "VCOMP-RULE-EV-TRANSMISSION"
        }
        assertNotNull("expected EV-transmission rule to fire", rule)
        val ev = rule as CompilationDiagnostic.CrossAggregateInvariantViolation
        assertEquals(
            CompilationDiagnostic.Severity.HARD,
            ev.diagnosticSeverity,
        )
    }

    @Test
    fun `gasoline with manual transmission does not fire EV transmission rule`() {
        val spec = urbanOneSpec().copy(
            propulsion = Propulsion(
                energySource = EnergySource.GASOLINE,
                engine = Engine(
                    configuration = EngineConfiguration.INLINE_4,
                    displacement = UnitValue.Volume(value = 2.0, unit = VolumeUnit.LITER),
                    orientation = EngineOrientation.TRANSVERSE,
                ),
            ),
            driveline = Driveline(
                traction = Traction.FWD,
                transmission = Transmission.MANUAL_6,
            ),
        )
        val result = validator.validate(spec)
        // The spec may have other diagnostics (e.g.
        // GASOLINE + SINGLE_SPEED if we hadn't changed
        // the transmission), but the EV rule MUST NOT
        // fire because the energy source is not ELECTRIC.
        val evRule = result.diagnostics.filterIsInstance<
            CompilationDiagnostic.CrossAggregateInvariantViolation,
        >().firstOrNull { it.ruleCode == "VCOMP-RULE-EV-TRANSMISSION" }
        assertEquals(null, evRule)
    }

    // ============================================================
    // V10/V12 + FWD rule
    // ============================================================

    @Test
    fun `V12 engine with FWD is a SAFETY_CRITICAL violation`() {
        val spec = urbanOneSpec().copy(
            propulsion = Propulsion(
                energySource = EnergySource.GASOLINE,
                engine = Engine(
                    configuration = EngineConfiguration.V12,
                    displacement = UnitValue.Volume(value = 6.0, unit = VolumeUnit.LITER),
                    orientation = EngineOrientation.LONGITUDINAL,
                ),
            ),
        )
        val result = validator.validate(spec)
        assertTrue("expected spec to be invalid", !result.isValid)
        val rule = result.blockingDiagnostics.firstOrNull {
            it is CompilationDiagnostic.CrossAggregateInvariantViolation &&
                it.ruleCode == "VCOMP-RULE-V12-FWD"
        }
        assertNotNull("expected V12-FWD rule to fire", rule)
        val v12 = rule as CompilationDiagnostic.CrossAggregateInvariantViolation
        assertEquals(
            CompilationDiagnostic.Severity.SAFETY_CRITICAL,
            v12.diagnosticSeverity,
        )
    }

    @Test
    fun `V12 with RWD passes the V12-FWD rule`() {
        val spec = urbanOneSpec().copy(
            propulsion = Propulsion(
                energySource = EnergySource.GASOLINE,
                engine = Engine(
                    configuration = EngineConfiguration.V12,
                    displacement = UnitValue.Volume(value = 6.0, unit = VolumeUnit.LITER),
                    orientation = EngineOrientation.LONGITUDINAL,
                ),
            ),
            driveline = Driveline(
                traction = Traction.RWD,
                transmission = Transmission.AUTOMATIC_8,
            ),
        )
        val result = validator.validate(spec)
        val rule = result.diagnostics.filterIsInstance<
            CompilationDiagnostic.CrossAggregateInvariantViolation,
        >().firstOrNull { it.ruleCode == "VCOMP-RULE-V12-FWD" }
        assertEquals(null, rule)
    }

    // ============================================================
    // Body shape rules
    // ============================================================

    @Test
    fun `roadster with 4 doors is a HARD violation`() {
        val spec = urbanOneSpec().copy(
            body = Body(
                architecture = BodyArchitecture.ROADSTER,
                doors = 4,
                seats = 2,
                wheelbase = UnitValue.Length(value = 2.45, unit = LengthUnit.METER),
            ),
        )
        val result = validator.validate(spec)
        val rule = result.blockingDiagnostics.firstOrNull {
            it is CompilationDiagnostic.CrossAggregateInvariantViolation &&
                it.ruleCode == "VCOMP-RULE-ROADSTER-DOORS"
        }
        assertNotNull("expected roadster-doors rule to fire", rule)
    }

    @Test
    fun `roadster with 2 doors and 2 seats passes`() {
        val spec = urbanOneSpec().copy(
            body = Body(
                architecture = BodyArchitecture.ROADSTER,
                doors = 2,
                seats = 2,
                wheelbase = UnitValue.Length(value = 2.45, unit = LengthUnit.METER),
            ),
        )
        val result = validator.validate(spec)
        val rule = result.diagnostics.filterIsInstance<
            CompilationDiagnostic.CrossAggregateInvariantViolation,
        >().firstOrNull { it.ruleCode == "VCOMP-RULE-ROADSTER-DOORS" }
        assertEquals(null, rule)
        assertTrue(result.isValid)
    }

    @Test
    fun `coupe with 4 doors is a HARD violation`() {
        val spec = urbanOneSpec().copy(
            body = Body(
                architecture = BodyArchitecture.COUPE,
                doors = 4,
                seats = 2,
                wheelbase = UnitValue.Length(value = 2.45, unit = LengthUnit.METER),
            ),
        )
        val result = validator.validate(spec)
        val rule = result.blockingDiagnostics.firstOrNull {
            it is CompilationDiagnostic.CrossAggregateInvariantViolation &&
                it.ruleCode == "VCOMP-RULE-COUPE-DOORS"
        }
        assertNotNull("expected coupe-doors rule to fire", rule)
    }

    @Test
    fun `coupe with 4 seats is a HARD violation`() {
        val spec = urbanOneSpec().copy(
            body = Body(
                architecture = BodyArchitecture.COUPE,
                doors = 2,
                seats = 4,
                wheelbase = UnitValue.Length(value = 2.45, unit = LengthUnit.METER),
            ),
        )
        val result = validator.validate(spec)
        val rule = result.blockingDiagnostics.firstOrNull {
            it is CompilationDiagnostic.CrossAggregateInvariantViolation &&
                it.ruleCode == "VCOMP-RULE-2SEAT-BODY"
        }
        assertNotNull("expected 2seat-body rule to fire", rule)
    }

    @Test
    fun `van with 2 doors is a HARD violation`() {
        val spec = urbanOneSpec().copy(
            body = Body(
                architecture = BodyArchitecture.VAN,
                doors = 2,
                seats = 8,
                wheelbase = UnitValue.Length(value = 3.0, unit = LengthUnit.METER),
            ),
        )
        val result = validator.validate(spec)
        val rule = result.blockingDiagnostics.firstOrNull {
            it is CompilationDiagnostic.CrossAggregateInvariantViolation &&
                it.ruleCode == "VCOMP-RULE-VAN-DOORS"
        }
        assertNotNull("expected van-doors rule to fire", rule)
    }

    @Test
    fun `pickup with 3 doors is a SOFT warning`() {
        val spec = urbanOneSpec().copy(
            body = Body(
                architecture = BodyArchitecture.PICKUP,
                doors = 3,
                seats = 5,
                wheelbase = UnitValue.Length(value = 3.2, unit = LengthUnit.METER),
            ),
        )
        val result = validator.validate(spec)
        val rule = result.warnings.firstOrNull {
            it is CompilationDiagnostic.CrossAggregateInvariantViolation &&
                it.ruleCode == "VCOMP-RULE-PICKUP-3DOORS"
        }
        assertNotNull("expected pickup-3doors rule to fire as a warning", rule)
    }

    @Test
    fun `pickup with 2 doors passes the 3-doors rule`() {
        val spec = urbanOneSpec().copy(
            body = Body(
                architecture = BodyArchitecture.PICKUP,
                doors = 2,
                seats = 2,
                wheelbase = UnitValue.Length(value = 3.2, unit = LengthUnit.METER),
            ),
        )
        val result = validator.validate(spec)
        val rule = result.diagnostics.filterIsInstance<
            CompilationDiagnostic.CrossAggregateInvariantViolation,
        >().firstOrNull { it.ruleCode == "VCOMP-RULE-PICKUP-3DOORS" }
        assertEquals(null, rule)
    }

    @Test
    fun `pickup with 4 doors passes the 3-doors rule`() {
        val spec = urbanOneSpec().copy(
            body = Body(
                architecture = BodyArchitecture.PICKUP,
                doors = 4,
                seats = 5,
                wheelbase = UnitValue.Length(value = 3.2, unit = LengthUnit.METER),
            ),
        )
        val result = validator.validate(spec)
        val rule = result.diagnostics.filterIsInstance<
            CompilationDiagnostic.CrossAggregateInvariantViolation,
        >().firstOrNull { it.ruleCode == "VCOMP-RULE-PICKUP-3DOORS" }
        assertEquals(null, rule)
    }

    @Test
    fun `9-seat wagon with 2 doors is a HARD violation`() {
        val spec = urbanOneSpec().copy(
            body = Body(
                architecture = BodyArchitecture.WAGON,
                doors = 2,
                seats = 9,
                wheelbase = UnitValue.Length(value = 3.0, unit = LengthUnit.METER),
            ),
        )
        val result = validator.validate(spec)
        val rule = result.blockingDiagnostics.firstOrNull {
            it is CompilationDiagnostic.CrossAggregateInvariantViolation &&
                it.ruleCode == "VCOMP-RULE-9SEAT-WAGON"
        }
        assertNotNull("expected 9seat-wagon rule to fire", rule)
    }

    // ============================================================
    // Gasoline + single-speed rule
    // ============================================================

    @Test
    fun `gasoline with single-speed is a SOFT warning`() {
        val spec = urbanOneSpec().copy(
            propulsion = Propulsion(
                energySource = EnergySource.GASOLINE,
                engine = Engine(
                    configuration = EngineConfiguration.INLINE_4,
                    displacement = UnitValue.Volume(value = 1.6, unit = VolumeUnit.LITER),
                    orientation = EngineOrientation.TRANSVERSE,
                ),
            ),
            driveline = Driveline(
                traction = Traction.FWD,
                transmission = Transmission.SINGLE_SPEED,
            ),
        )
        val result = validator.validate(spec)
        val rule = result.warnings.firstOrNull {
            it is CompilationDiagnostic.CrossAggregateInvariantViolation &&
                it.ruleCode == "VCOMP-RULE-GAS-SINGLE-SPEED"
        }
        assertNotNull("expected gas-single-speed rule to fire as a warning", rule)
        // The spec is still valid (a SOFT warning is
        // not a blocking diagnostic).
        assertTrue("spec should be valid (SOFT warning)", result.isValid)
    }

    // ============================================================
    // Determinism
    // ============================================================

    @Test
    fun `validator is deterministic for the same spec`() {
        val spec = urbanOneSpec().copy(
            body = Body(
                architecture = BodyArchitecture.COUPE,
                doors = 4,
                seats = 4,
                wheelbase = UnitValue.Length(value = 2.45, unit = LengthUnit.METER),
            ),
        )
        val result1 = validator.validate(spec)
        val result2 = validator.validate(spec)
        val result3 = validator.validate(spec)
        assertEquals(result1.diagnostics, result2.diagnostics)
        assertEquals(result2.diagnostics, result3.diagnostics)
    }

    // ============================================================
    // Validation result filters
    // ============================================================

    @Test
    fun `validation result separates blocking diagnostics from warnings`() {
        // A spec with one HARD violation + one SOFT warning
        val spec = urbanOneSpec().copy(
            body = Body(
                architecture = BodyArchitecture.COUPE,
                doors = 4,  // HARD: coupe must have 2 doors
                seats = 2,
                wheelbase = UnitValue.Length(value = 2.45, unit = LengthUnit.METER),
            ),
            propulsion = Propulsion(
                energySource = EnergySource.GASOLINE,
                engine = Engine(
                    configuration = EngineConfiguration.INLINE_4,
                    displacement = UnitValue.Volume(value = 1.6, unit = VolumeUnit.LITER),
                    orientation = EngineOrientation.TRANSVERSE,
                ),
            ),
            driveline = Driveline(
                traction = Traction.FWD,
                transmission = Transmission.SINGLE_SPEED,  // SOFT: gas + single-speed
            ),
        )
        val result = validator.validate(spec)
        assertTrue(!result.isValid)
        assertTrue(
            "expected at least 1 blocking diagnostic, got ${result.blockingDiagnostics}",
            result.blockingDiagnostics.isNotEmpty(),
        )
        assertTrue(
            "expected at least 1 warning, got ${result.warnings}",
            result.warnings.isNotEmpty(),
        )
        // The blocking diagnostics include the HARD
        // COUPE-DOORS rule.
        val coupeDoors = result.blockingDiagnostics.filterIsInstance<
            CompilationDiagnostic.CrossAggregateInvariantViolation,
        >().firstOrNull { it.ruleCode == "VCOMP-RULE-COUPE-DOORS" }
        assertNotNull(coupeDoors)
        // The warnings include the SOFT gas-single-speed rule.
        val gasWarning = result.warnings.filterIsInstance<
            CompilationDiagnostic.CrossAggregateInvariantViolation,
        >().firstOrNull { it.ruleCode == "VCOMP-RULE-GAS-SINGLE-SPEED" }
        assertNotNull(gasWarning)
    }

    // ============================================================
    // Rule set integrity
    // ============================================================

    @Test
    fun `default rule set has unique codes`() {
        val codes = DefaultRuleSet.rules.map { it.code }
        assertEquals(
            "expected all rule codes to be unique, got duplicates",
            codes.size,
            codes.toSet().size,
        )
    }

    @Test
    fun `default rule set has a name and a version`() {
        assertTrue(DefaultRuleSet.name.isNotBlank())
        assertTrue(DefaultRuleSet.version.isNotBlank())
    }

    @Test
    fun `rule set rejects blank name`() {
        try {
            SpecRuleSet(name = "", version = "v1", rules = emptyList())
            fail("expected IllegalArgumentException for blank name")
        } catch (e: IllegalArgumentException) {
            assertTrue("expected message to contain 'name', got ${e.message}", e.message!!.contains("name"))
        }
    }

    @Test
    fun `rule set rejects blank version`() {
        try {
            SpecRuleSet(name = "test", version = "", rules = emptyList())
            fail("expected IllegalArgumentException for blank version")
        } catch (e: IllegalArgumentException) {
            assertTrue("expected message to contain 'version', got ${e.message}", e.message!!.contains("version"))
        }
    }

    @Test
    fun `rule set rejects duplicate codes`() {
        val r1 = object : SpecRule {
            override val code = "DUPLICATE-001"
            override val name = "r1"
            override fun check(spec: CompiledVehicleSpec) = emptyList<CompilationDiagnostic>()
        }
        val r2 = object : SpecRule {
            override val code = "DUPLICATE-001"
            override val name = "r2"
            override fun check(spec: CompiledVehicleSpec) = emptyList<CompilationDiagnostic>()
        }
        try {
            SpecRuleSet(name = "test", version = "v1", rules = listOf(r1, r2))
            fail("expected IllegalArgumentException for duplicate codes")
        } catch (e: IllegalArgumentException) {
            assertTrue("expected message to contain 'duplicate', got ${e.message}", e.message!!.contains("duplicate"))
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
