package com.elysium.vanguard.foundry.core.dsl.schema

import com.elysium.vanguard.foundry.core.ontology.ids.ProjectId
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import com.elysium.vanguard.foundry.core.ontology.primitives.RepresentationLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for the `CompiledVehicleSpec` schema.
 *
 * The schema is the **typed output** of the VSL compiler
 * (per skill 04 section 5). The tests validate:
 *   1. The data-class invariants (every `init` block is enforced).
 *   2. The `canonicalForm()` is deterministic (same spec ->
 *      same bytes across JVMs, OSes, and Kotlin versions).
 *   3. The golden file: the canonical form of the "Urban One"
 *      spec matches the expected output (per skill 04 section 9).
 *   4. The factory function (`buildSpec`) returns a typed
 *      `FoundryError` on ill-formed input.
 *   5. The unit-value validation rejects `NaN` / `±Infinity`.
 *
 * The tests are JVM unit tests (no Android context required).
 */
class CompiledVehicleSpecTest {

    // ============================================================
    // Canonical sample: "Urban One" — a compact electric
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

    // ============================================================
    // Data-class invariants
    // ============================================================

    @Test
    fun `compiled vehicle spec rejects missing representationLevel`() {
        try {
            SpecClassification(representationLevel = RepresentationLevel.UNKNOWN)
            fail("expected IllegalArgumentException for UNKNOWN representation level")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "expected message to mention representationLevel, got: ${e.message}",
                e.message!!.contains("representationLevel"),
            )
        }
    }

    @Test
    fun `body rejects invalid door count`() {
        try {
            Body(
                architecture = BodyArchitecture.SEDAN,
                doors = 7, // not in {2, 3, 4, 5}
                seats = 5,
                wheelbase = UnitValue.Length(value = 2.45, unit = LengthUnit.METER),
            )
            fail("expected IllegalArgumentException for invalid door count")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("doors"))
        }
    }

    @Test
    fun `body rejects out-of-range seat count`() {
        try {
            Body(
                architecture = BodyArchitecture.SEDAN,
                doors = 4,
                seats = 15, // out of 1..9
                wheelbase = UnitValue.Length(value = 2.45, unit = LengthUnit.METER),
            )
            fail("expected IllegalArgumentException for out-of-range seat count")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("seats"))
        }
    }

    @Test
    fun `body rejects non-positive wheelbase`() {
        try {
            Body(
                architecture = BodyArchitecture.SEDAN,
                doors = 4,
                seats = 5,
                wheelbase = UnitValue.Length(value = 0.0, unit = LengthUnit.METER),
            )
            fail("expected IllegalArgumentException for non-positive wheelbase")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("wheelbase"))
        }
    }

    @Test
    fun `electric energy source requires ELECTRIC_NONE engine configuration`() {
        try {
            Propulsion(
                energySource = EnergySource.ELECTRIC,
                engine = Engine(
                    configuration = EngineConfiguration.INLINE_4, // wrong
                    displacement = UnitValue.Volume(value = 1.6, unit = VolumeUnit.LITER),
                    orientation = EngineOrientation.TRANSVERSE,
                ),
            )
            fail("expected IllegalArgumentException for ICE engine on ELECTRIC energy source")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "expected message to mention ELECTRIC_NONE, got: ${e.message}",
                e.message!!.contains("ELECTRIC_NONE"),
            )
        }
    }

    @Test
    fun `electric energy source requires zero displacement`() {
        try {
            Propulsion(
                energySource = EnergySource.ELECTRIC,
                engine = Engine(
                    configuration = EngineConfiguration.ELECTRIC_NONE,
                    displacement = UnitValue.Volume(value = 1.6, unit = VolumeUnit.LITER), // wrong
                    orientation = EngineOrientation.LONGITUDINAL,
                ),
            )
            fail("expected IllegalArgumentException for non-zero displacement on ELECTRIC")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("displacement"))
        }
    }

    @Test
    fun `gasoline energy source accepts INLINE_4 engine with 1_6L displacement`() {
        val propulsion = Propulsion(
            energySource = EnergySource.GASOLINE,
            engine = Engine(
                configuration = EngineConfiguration.INLINE_4,
                displacement = UnitValue.Volume(value = 1.6, unit = VolumeUnit.LITER),
                orientation = EngineOrientation.TRANSVERSE,
            ),
        )
        assertNotNull(propulsion)
        assertEquals(EngineConfiguration.INLINE_4, propulsion.engine.configuration)
    }

    @Test
    fun `metadata rejects revision less than 1`() {
        try {
            SpecMetadata(
                projectId = ProjectId.random(),
                revision = 0,
            )
            fail("expected IllegalArgumentException for revision < 1")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("revision"))
        }
    }

    @Test
    fun `apiVersion rejects blank string`() {
        try {
            ApiVersion(value = "")
            fail("expected IllegalArgumentException for blank apiVersion")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("blank"))
        }
    }

    @Test
    fun `apiVersion rejects malformed string`() {
        try {
            ApiVersion(value = "v1") // missing the elysium.vehicle prefix
            fail("expected IllegalArgumentException for malformed apiVersion")
        } catch (e: IllegalArgumentException) {
            // The error message contains the regex pattern with
            // escaped backslashes. The assertion is on the
            // "ApiVersion must match" prefix.
            assertTrue(
                "expected message to mention ApiVersion validation, got: ${e.message}",
                e.message!!.contains("ApiVersion must match"),
            )
        }
    }

    @Test
    fun `apiVersion accepts well-formed string`() {
        val v = ApiVersion("elysium.vehicle/v1")
        assertEquals("elysium.vehicle/v1", v.value)
        val v2 = ApiVersion("elysium.vehicle/v2.5")
        assertEquals("elysium.vehicle/v2.5", v2.value)
    }

    // ============================================================
    // UnitValue: NaN / Infinity rejection (skill 04 section 25)
    // ============================================================

    @Test
    fun `unit value rejects NaN`() {
        try {
            UnitValue.Length(value = Double.NaN, unit = LengthUnit.METER)
            fail("expected CompilationNonDeterministic for NaN")
        } catch (e: FoundryError.CompilationNonDeterministic) {
            assertTrue(e.message!!.contains("must be finite"))
        }
    }

    @Test
    fun `unit value rejects positive infinity`() {
        try {
            UnitValue.Length(value = Double.POSITIVE_INFINITY, unit = LengthUnit.METER)
            fail("expected CompilationNonDeterministic for +Infinity")
        } catch (e: FoundryError.CompilationNonDeterministic) {
            assertTrue(e.message!!.contains("must be finite"))
        }
    }

    @Test
    fun `unit value rejects negative infinity`() {
        try {
            UnitValue.Length(value = Double.NEGATIVE_INFINITY, unit = LengthUnit.METER)
            fail("expected CompilationNonDeterministic for -Infinity")
        } catch (e: FoundryError.CompilationNonDeterministic) {
            assertTrue(e.message!!.contains("must be finite"))
        }
    }

    @Test
    fun `unit value accepts zero for displacement (electric engine)`() {
        // A zero displacement is legal for an electric engine.
        val v = UnitValue.Volume(value = 0.0, unit = VolumeUnit.LITER)
        assertEquals(0.0, v.value, 0.0)
    }

    // ============================================================
    // Determinism + canonical form
    // ============================================================

    @Test
    fun `canonical form is deterministic across two runs on the same spec`() {
        val specA = urbanOneSpec()
        val specB = urbanOneSpec()
        assertEquals(
            "same inputs -> same canonical form (determinism contract)",
            specA.canonicalForm(),
            specB.canonicalForm(),
        )
    }

    @Test
    fun `canonical form preserves fields in a fixed order`() {
        val spec = urbanOneSpec()
        val canonical = spec.canonicalForm()
        // The canonical form has a fixed order: api, metadata,
        // classification, body, propulsion, driveline.
        val apiIndex = canonical.indexOf("api=")
        val metaIndex = canonical.indexOf("metadata:")
        val classIndex = canonical.indexOf("classification:")
        val bodyIndex = canonical.indexOf("body:")
        val propIndex = canonical.indexOf("propulsion:")
        val drivIndex = canonical.indexOf("driveline:")
        assertTrue("api= must come before metadata:", apiIndex < metaIndex)
        assertTrue("metadata must come before classification:", metaIndex < classIndex)
        assertTrue("classification must come before body:", classIndex < bodyIndex)
        assertTrue("body must come before propulsion:", bodyIndex < propIndex)
        assertTrue("propulsion must come before driveline:", propIndex < drivIndex)
    }

    @Test
    fun `canonical form contains all required fields`() {
        val spec = urbanOneSpec()
        val canonical = spec.canonicalForm()
        assertTrue(canonical.contains("vsl:v1"))
        assertTrue(canonical.contains("elysium.vehicle/v1"))
        assertTrue(canonical.contains("HATCHBACK"))
        assertTrue(canonical.contains("ELECTRIC"))
        assertTrue(canonical.contains("FWD"))
        assertTrue(canonical.contains("SINGLE_SPEED"))
        assertTrue(canonical.contains("PARAMETRIC_FUNCTIONAL"))
    }

    @Test
    fun `two specs with different projectIds have different canonical forms`() {
        val specA = urbanOneSpec().copy(
            metadata = urbanOneSpec().metadata.copy(
                projectId = ProjectId.from("00000000-0000-0000-0000-000000000001").getOrThrow(),
            ),
        )
        val specB = urbanOneSpec().copy(
            metadata = urbanOneSpec().metadata.copy(
                projectId = ProjectId.from("00000000-0000-0000-0000-000000000002").getOrThrow(),
            ),
        )
        assertTrue(
            "different projectId -> different canonical form",
            specA.canonicalForm() != specB.canonicalForm(),
        )
    }

    @Test
    fun `two specs with different revisions have different canonical forms`() {
        val specA = urbanOneSpec()
        val specB = urbanOneSpec().copy(
            metadata = specA.metadata.copy(revision = specA.metadata.revision + 1),
        )
        assertTrue(
            "different revision -> different canonical form",
            specA.canonicalForm() != specB.canonicalForm(),
        )
    }

    // ============================================================
    // buildSpec error envelope
    // ============================================================

    @Test
    fun `buildSpec returns success for a valid spec`() {
        // The sub-aggregates validate at construction (fail-fast
        // pattern). The `buildSpec` factory is a thin wrapper
        // that converts any future `IllegalArgumentException`
        // thrown from the `CompiledVehicleSpec` constructor into
        // a `Result.failure(FoundryError.VehicleDefinitionInvalid)`.
        // The positive path is tested here.
        val projectId = ProjectId.random()
        val result = buildSpec(
            apiVersion = ApiVersion.V1,
            metadata = SpecMetadata(
                projectId = projectId,
                revision = 1,
            ),
            classification = SpecClassification(
                representationLevel = RepresentationLevel.PARAMETRIC_FUNCTIONAL,
            ),
            body = Body(
                architecture = BodyArchitecture.SEDAN,
                doors = 4,
                seats = 5,
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
                transmission = Transmission.MANUAL_6,
            ),
        )
        assertTrue("expected success, got $result", result.isSuccess)
        val spec = result.getOrThrow()
        assertEquals(projectId, spec.metadata.projectId)
    }

    // ============================================================
    // Golden file: the Urban One spec canonical form
    // ============================================================
    //
    // Per skill 04 section 9, the compiler's output is asserted
    // byte-identical to a golden file. The golden form is the
    // expected `canonicalForm()` of the "Urban One" spec.
    //
    // The form is:
    //   vsl:v1|api=elysium.vehicle/v1|metadata:projectId=<UUID>|revision=1
    //   |classification:level=PARAMETRIC_FUNCTIONAL
    //   |body:architecture=HATCHBACK|doors=5|seats=5|wheelbase=length:2.45|unit:METER
    //   |propulsion:energySource=ELECTRIC|engine=configuration=ELECTRIC_NONE|displacement=volume:0.0|unit:LITER|orientation=LONGITUDINAL
    //   |driveline:traction=FWD|transmission=SINGLE_SPEED

    @Test
    fun `golden urban one canonical form`() {
        val spec = urbanOneSpec()
        val expected = "vsl:v1" +
            "|api=elysium.vehicle/v1" +
            "|metadata:projectId=00000000-0000-0000-0000-000000000001|revision=1" +
            "|classification:level=PARAMETRIC_FUNCTIONAL" +
            "|body:architecture=HATCHBACK|doors=5|seats=5|wheelbase=length:2.45|unit:METER" +
            "|propulsion:energySource=ELECTRIC" +
            "|engine=configuration=ELECTRIC_NONE|displacement=volume:0.0|unit:LITER|orientation=LONGITUDINAL" +
            "|driveline:traction=FWD|transmission=SINGLE_SPEED"
        assertEquals(expected, spec.canonicalForm())
    }
}
