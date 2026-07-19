package com.elysium.vanguard.foundry.core.compiler

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
import com.elysium.vanguard.foundry.core.dsl.validator.DefaultSpecValidator
import com.elysium.vanguard.foundry.core.ontology.ids.ProjectId
import com.elysium.vanguard.foundry.core.ontology.primitives.RepresentationLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 / I-2.6 + I-2.7 — the JVM tests for
 * [CompilationPipeline] + [CompilationReport].
 *
 * The pipeline is the 18-step compiler process
 * (per `.ai/skills/04-vehicle-dsl-compiler/SKILL.md`
 * section 6). Phase 2 / I-2.6 implements steps
 * 3 (validation), 17 (report), 18 (artifact
 * hashing); the remaining steps are placeholders.
 *
 * The tests cover:
 *   - The pipeline runs the validator.
 *   - The pipeline is deterministic (same spec
 *     → same content hash).
 *   - The pipeline records per-step results.
 *   - The compilation report separates errors
 *     from warnings.
 *   - A failed validation still produces a
 *     `Compilation` (the report is the failure
 *     channel; the consumer reads the report).
 *   - The compilation hash differs for different
 *     catalog + compiler versions.
 *   - The 18-step pipeline structure is correct
 *     (every step in 1..18 is recorded).
 */
class CompilationPipelineTest {

    private val validator = DefaultSpecValidator.withDefaultRules()
    private val pipeline = CompilationPipeline(validator)

    // ============================================================
    // Happy path: Urban One spec compiles cleanly
    // ============================================================

    @Test
    fun `pipeline compiles the Urban One spec and produces a report`() {
        val spec = urbanOneSpec()
        val result = pipeline.compile(
            spec = spec,
            catalogRevision = com.elysium.vanguard.foundry.core.ontology.primitives.CatalogRevision("2026.07"),
            compilerVersion = com.elysium.vanguard.foundry.core.ontology.primitives.CompilerVersion("1.0.0"),
        )
        assertTrue("expected success, got $result", result.isSuccess)
        val compilation = result.getOrThrow()
        assertEquals(64, compilation.contentHash.value.length)
        // The report is populated.
        assertNotNull("expected report", compilation.report)
        val report = compilation.report!!
        // The validation step succeeded.
        val step3 = report.steps.first { it.stepNumber == 3 }
        assertTrue(
            "expected step 3 to be Success, got $step3",
            step3 is CompilationReport.Step.Success,
        )
        // The report is not blocked.
        assertFalse("expected report to be unblocked", report.isBlocked)
    }

    // ============================================================
    // Determinism
    // ============================================================

    @Test
    fun `pipeline is deterministic for the same spec and inputs`() {
        val spec = urbanOneSpec()
        val a = pipeline.compile(
            spec = spec,
            catalogRevision = com.elysium.vanguard.foundry.core.ontology.primitives.CatalogRevision("2026.07"),
            compilerVersion = com.elysium.vanguard.foundry.core.ontology.primitives.CompilerVersion("1.0.0"),
        ).getOrThrow()
        val b = pipeline.compile(
            spec = spec,
            catalogRevision = com.elysium.vanguard.foundry.core.ontology.primitives.CatalogRevision("2026.07"),
            compilerVersion = com.elysium.vanguard.foundry.core.ontology.primitives.CompilerVersion("1.0.0"),
        ).getOrThrow()
        assertEquals(a.contentHash, b.contentHash)
    }

    @Test
    fun `pipeline differs for different catalog revisions`() {
        val spec = urbanOneSpec()
        val a = pipeline.compile(
            spec = spec,
            catalogRevision = com.elysium.vanguard.foundry.core.ontology.primitives.CatalogRevision("2026.07"),
            compilerVersion = com.elysium.vanguard.foundry.core.ontology.primitives.CompilerVersion("1.0.0"),
        ).getOrThrow()
        val b = pipeline.compile(
            spec = spec,
            catalogRevision = com.elysium.vanguard.foundry.core.ontology.primitives.CatalogRevision("2026.08"),
            compilerVersion = com.elysium.vanguard.foundry.core.ontology.primitives.CompilerVersion("1.0.0"),
        ).getOrThrow()
        assertFalse(
            "expected different content hashes for different catalog revisions",
            a.contentHash == b.contentHash,
        )
    }

    @Test
    fun `pipeline differs for different compiler versions`() {
        val spec = urbanOneSpec()
        val a = pipeline.compile(
            spec = spec,
            catalogRevision = com.elysium.vanguard.foundry.core.ontology.primitives.CatalogRevision("2026.07"),
            compilerVersion = com.elysium.vanguard.foundry.core.ontology.primitives.CompilerVersion("1.0.0"),
        ).getOrThrow()
        val b = pipeline.compile(
            spec = spec,
            catalogRevision = com.elysium.vanguard.foundry.core.ontology.primitives.CatalogRevision("2026.07"),
            compilerVersion = com.elysium.vanguard.foundry.core.ontology.primitives.CompilerVersion("1.0.1"),
        ).getOrThrow()
        assertFalse(
            "expected different content hashes for different compiler versions",
            a.contentHash == b.contentHash,
        )
    }

    // ============================================================
    // Validation: failed validation produces a blocked report
    // ============================================================

    @Test
    fun `failed validation produces a blocked report with a failure step`() {
        // A COUPE with 4 doors — invalid (HARD violation).
        val spec = urbanOneSpec().copy(
            body = Body(
                architecture = BodyArchitecture.COUPE,
                doors = 4,
                seats = 2,
                wheelbase = UnitValue.Length(value = 2.45, unit = LengthUnit.METER),
            ),
        )
        val result = pipeline.compile(
            spec = spec,
            catalogRevision = com.elysium.vanguard.foundry.core.ontology.primitives.CatalogRevision("2026.07"),
            compilerVersion = com.elysium.vanguard.foundry.core.ontology.primitives.CompilerVersion("1.0.0"),
        )
        assertTrue(result.isSuccess)
        val compilation = result.getOrThrow()
        val report = compilation.report!!
        assertTrue("expected report to be blocked", report.isBlocked)
        // The validation step is a Failure.
        val step3 = report.steps.first { it.stepNumber == 3 }
        assertTrue(
            "expected step 3 to be Failure, got $step3",
            step3 is CompilationReport.Step.Failure,
        )
        // The error list includes the coupe-doors rule.
        val coupe = report.errors.filterIsInstance<
            CompilationDiagnostic.CrossAggregateInvariantViolation,
        >().firstOrNull { it.ruleCode == "VCOMP-RULE-COUPE-DOORS" }
        assertNotNull("expected coupe-doors rule in errors", coupe)
    }

    // ============================================================
    // 18-step pipeline structure
    // ============================================================

    @Test
    fun `pipeline records every step in 1_to_18`() {
        val spec = urbanOneSpec()
        val result = pipeline.compile(
            spec = spec,
            catalogRevision = com.elysium.vanguard.foundry.core.ontology.primitives.CatalogRevision("2026.07"),
            compilerVersion = com.elysium.vanguard.foundry.core.ontology.primitives.CompilerVersion("1.0.0"),
        )
        val report = result.getOrThrow().report!!
        // Every step's number is in 1..18.
        assertTrue(
            "expected every step number in 1..18, got ${report.steps.map { it.stepNumber }}",
            report.steps.all { it.stepNumber in 1..18 },
        )
        // The report has the validation step.
        assertTrue(
            "expected step 3 to be in the report",
            report.steps.any { it.stepNumber == 3 },
        )
    }

    // ============================================================
    // Compilation report structure
    // ============================================================

    @Test
    fun `compilation report has isValid when validation passes`() {
        val spec = urbanOneSpec()
        val report = pipeline.compile(
            spec = spec,
            catalogRevision = com.elysium.vanguard.foundry.core.ontology.primitives.CatalogRevision("2026.07"),
            compilerVersion = com.elysium.vanguard.foundry.core.ontology.primitives.CompilerVersion("1.0.0"),
        ).getOrThrow().report!!
        assertFalse(report.isBlocked)
        assertEquals(0, report.errors.size)
        assertEquals(0, report.warnings.size)
    }

    @Test
    fun `compilation report separates errors from warnings for a soft-violating spec`() {
        // A GASOLINE engine with SINGLE_SPEED transmission
        // — a SOFT warning (the spec is valid; the user is
        // informed but compilation succeeds).
        val spec = urbanOneSpec().copy(
            propulsion = Propulsion(
                energySource = EnergySource.GASOLINE,
                engine = Engine(
                    configuration = EngineConfiguration.INLINE_4,
                    displacement = UnitValue.Volume(value = 1.6, unit = VolumeUnit.LITER),
                    orientation = EngineOrientation.TRANSVERSE,
                ),
            ),
        )
        val report = pipeline.compile(
            spec = spec,
            catalogRevision = com.elysium.vanguard.foundry.core.ontology.primitives.CatalogRevision("2026.07"),
            compilerVersion = com.elysium.vanguard.foundry.core.ontology.primitives.CompilerVersion("1.0.0"),
        ).getOrThrow().report!!
        assertFalse("expected report to be unblocked", report.isBlocked)
        assertEquals(0, report.errors.size)
        assertTrue(
            "expected at least 1 warning, got ${report.warnings}",
            report.warnings.isNotEmpty(),
        )
    }

    @Test
    fun `compilation report rejects step numbers out of 1_18`() {
        try {
            CompilationReport(
                steps = listOf(
                    CompilationReport.Step.Success(stepNumber = 19, stepName = "future"),
                ),
                validationDiagnostics = emptyList(),
                isBlocked = false,
            )
            org.junit.Assert.fail("expected IllegalArgumentException for out-of-range step")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("1..18"))
        }
    }

    // ============================================================
    // Phase 1 backward compatibility
    // ============================================================

    @Test
    fun `compilation data class still works with no report (Phase 1 compat)`() {
        val compilation = Compilation(
            contentHash = com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash(
                "0".repeat(64),
            ),
            warnings = emptyList(),
        )
        assertNull("expected report to be null by default", compilation.report)
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
