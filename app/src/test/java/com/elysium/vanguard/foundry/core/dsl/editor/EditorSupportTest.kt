package com.elysium.vanguard.foundry.core.dsl.editor

import com.elysium.vanguard.foundry.core.dsl.compatibility.DefaultCompatibilityConstraintEngine
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 2 / I-2.8 — the JVM tests for the editor
 * support (`SourceMap` + `DiagnosticAnnotation` +
 * `LiveSpecValidator`).
 *
 * The editor support is the bridge between the
 * schema (the spec) and the source code (the JSON
 * the user is editing). The bridge has three
 * components:
 *
 *   1. `SourceMap` — the JSON path → source
 *      position map. Built during parsing.
 *   2. `DiagnosticAnnotation` — the source-
 *      position-annotated diagnostic the editor
 *      renders.
 *   3. `LiveSpecValidator` — the validator that
 *      runs on every keystroke; composes the
 *      SpecValidator + the CompatibilityConstraintEngine
 *      + the SourceMap.
 *
 * The tests cover:
 *   - The SourceMap builds correctly + rejects
 *     bad paths.
 *   - The DiagnosticAnnotation converts from
 *     a CompilationDiagnostic with the right
 *     severity.
 *   - The LiveSpecValidator runs both the
 *     validator + the constraint engine, builds
 *     the annotations, and exposes the
 *     diagnostics + isValid.
 *   - The annotations are sorted by (line,
 *     column) for deterministic display.
 */
class EditorSupportTest {

    private val validator = DefaultSpecValidator.withDefaultRules()
    private val constraintEngine = DefaultCompatibilityConstraintEngine.withDefaultConstraints()
    private val liveValidator = LiveSpecValidator(validator, constraintEngine)

    // ============================================================
    // SourceMap
    // ============================================================

    @Test
    fun `empty source map has no positions`() {
        val map = SourceMap.EMPTY
        assertTrue(map.isEmpty())
        assertEquals(0, map.size)
    }

    @Test
    fun `source map with positions is not empty`() {
        val map = SourceMap.EMPTY.with(
            "$.body.architecture",
            SourcePosition(line = 5, column = 17),
        )
        assertFalse(map.isEmpty())
        assertEquals(1, map.size)
        val pos = map.positionFor("$.body.architecture")
        assertNotNull(pos)
        assertEquals(5, pos!!.line)
        assertEquals(17, pos.column)
    }

    @Test
    fun `source map withAll adds multiple positions`() {
        val map = SourceMap.EMPTY.withAll(
            mapOf(
                "$.body.architecture" to SourcePosition(line = 5, column = 17),
                "$.body.doors" to SourcePosition(line = 6, column = 12),
                "$.propulsion.energySource" to SourcePosition(line = 10, column = 20),
            ),
        )
        assertEquals(3, map.size)
    }

    @Test
    fun `source map rejects blank path`() {
        try {
            SourceMap.EMPTY.with("", SourcePosition(1, 1))
            fail("expected IllegalArgumentException for blank path")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("path"))
        }
    }

    @Test
    fun `source map rejects path without dollar prefix`() {
        try {
            SourceMap.EMPTY.with("body.architecture", SourcePosition(1, 1))
            fail("expected IllegalArgumentException for path without dollar prefix")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "expected message to contain '\$', got ${e.message}",
                e.message!!.contains("\$"),
            )
        }
    }

    @Test
    fun `source position rejects line less than 1`() {
        try {
            SourcePosition(line = 0, column = 1)
            fail("expected IllegalArgumentException for line < 1")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("line"))
        }
    }

    @Test
    fun `source position rejects column less than 1`() {
        try {
            SourcePosition(line = 1, column = 0)
            fail("expected IllegalArgumentException for column < 1")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("column"))
        }
    }

    @Test
    fun `source position display string is human readable`() {
        val pos = SourcePosition(line = 12, column = 5)
        assertEquals("line 12, column 5", pos.displayString())
    }

    // ============================================================
    // DiagnosticAnnotation: diagnostic → annotation conversion
    // ============================================================

    @Test
    fun `HARD diagnostic converts to ERROR annotation`() {
        val diagnostic = CompilationDiagnostic.CrossAggregateInvariantViolation(
            ruleCode = "VCOMP-RULE-TEST",
            reason = "test",
            jsonPaths = listOf("$.body.architecture"),
        )
        val sourceMap = SourceMap.EMPTY.with(
            "$.body.architecture",
            SourcePosition(line = 5, column = 17),
        )
        val annotations = diagnostic.toAnnotations(sourceMap)
        assertEquals(1, annotations.size)
        assertEquals(DiagnosticAnnotation.Severity.ERROR, annotations[0].severity)
    }

    @Test
    fun `SOFT diagnostic converts to WARNING annotation`() {
        val diagnostic = CompilationDiagnostic.CrossAggregateInvariantViolation(
            ruleCode = "VCOMP-RULE-TEST",
            reason = "test",
            jsonPaths = listOf("$.body.architecture"),
            diagnosticSeverity = CompilationDiagnostic.Severity.SOFT,
        )
        val sourceMap = SourceMap.EMPTY.with(
            "$.body.architecture",
            SourcePosition(line = 5, column = 17),
        )
        val annotations = diagnostic.toAnnotations(sourceMap)
        assertEquals(1, annotations.size)
        assertEquals(DiagnosticAnnotation.Severity.WARNING, annotations[0].severity)
    }

    @Test
    fun `OPTIMIZATION diagnostic converts to OPTIMIZATION annotation`() {
        val diagnostic = CompilationDiagnostic.CrossAggregateInvariantViolation(
            ruleCode = "VCOMP-CONSTRAINT-TEST",
            reason = "test",
            jsonPaths = listOf("$.body.architecture"),
            diagnosticSeverity = CompilationDiagnostic.Severity.OPTIMIZATION,
        )
        val sourceMap = SourceMap.EMPTY.with(
            "$.body.architecture",
            SourcePosition(line = 5, column = 17),
        )
        val annotations = diagnostic.toAnnotations(sourceMap)
        assertEquals(1, annotations.size)
        assertEquals(
            DiagnosticAnnotation.Severity.OPTIMIZATION,
            annotations[0].severity,
        )
    }

    @Test
    fun `REGULATORY diagnostic converts to REGULATORY annotation`() {
        val diagnostic = CompilationDiagnostic.CrossAggregateInvariantViolation(
            ruleCode = "VCOMP-CONSTRAINT-TEST",
            reason = "test",
            jsonPaths = listOf("$.body.architecture"),
            diagnosticSeverity = CompilationDiagnostic.Severity.REGULATORY,
        )
        val sourceMap = SourceMap.EMPTY.with(
            "$.body.architecture",
            SourcePosition(line = 5, column = 17),
        )
        val annotations = diagnostic.toAnnotations(sourceMap)
        assertEquals(DiagnosticAnnotation.Severity.REGULATORY, annotations[0].severity)
    }

    @Test
    fun `SAFETY_CRITICAL diagnostic converts to SAFETY_CRITICAL annotation`() {
        val diagnostic = CompilationDiagnostic.CrossAggregateInvariantViolation(
            ruleCode = "VCOMP-RULE-TEST",
            reason = "test",
            jsonPaths = listOf("$.body.architecture"),
            diagnosticSeverity = CompilationDiagnostic.Severity.SAFETY_CRITICAL,
        )
        val sourceMap = SourceMap.EMPTY.with(
            "$.body.architecture",
            SourcePosition(line = 5, column = 17),
        )
        val annotations = diagnostic.toAnnotations(sourceMap)
        assertEquals(
            DiagnosticAnnotation.Severity.SAFETY_CRITICAL,
            annotations[0].severity,
        )
    }

    @Test
    fun `diagnostic with no path match returns no annotations`() {
        val diagnostic = CompilationDiagnostic.CrossAggregateInvariantViolation(
            ruleCode = "VCOMP-RULE-TEST",
            reason = "test",
            jsonPaths = listOf("$.body.architecture"),
        )
        val sourceMap = SourceMap.EMPTY  // empty — no positions
        val annotations = diagnostic.toAnnotations(sourceMap)
        assertEquals(0, annotations.size)
    }

    @Test
    fun `diagnostic with multiple paths returns one annotation per matched path`() {
        val diagnostic = CompilationDiagnostic.CrossAggregateInvariantViolation(
            ruleCode = "VCOMP-RULE-TEST",
            reason = "test",
            jsonPaths = listOf("$.body.architecture", "$.body.doors"),
        )
        val sourceMap = SourceMap.EMPTY.withAll(
            mapOf(
                "$.body.architecture" to SourcePosition(line = 5, column = 17),
                "$.body.doors" to SourcePosition(line = 6, column = 12),
            ),
        )
        val annotations = diagnostic.toAnnotations(sourceMap)
        assertEquals(2, annotations.size)
    }

    @Test
    fun `annotation rejects empty paths`() {
        try {
            DiagnosticAnnotation(
                position = SourcePosition(1, 1),
                severity = DiagnosticAnnotation.Severity.ERROR,
                message = "test",
                code = "TEST",
                paths = emptyList(),
            )
            fail("expected IllegalArgumentException for empty paths")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("paths"))
        }
    }

    // ============================================================
    // LiveSpecValidator
    // ============================================================

    @Test
    fun `live validator returns empty result for the Urban One spec`() {
        val spec = urbanOneSpec()
        val sourceMap = urbanOneSourceMap()
        val result = liveValidator.validate(spec, sourceMap)
        assertTrue(
            "expected Urban One to be valid, got diagnostics: " +
                result.diagnostics,
            result.isValid,
        )
        assertEquals(0, result.diagnostics.size)
        assertEquals(0, result.annotations.size)
    }

    @Test
    fun `live validator produces annotations for an invalid spec`() {
        val spec = urbanOneSpec().copy(
            body = Body(
                architecture = BodyArchitecture.COUPE,
                doors = 4,  // HARD violation
                seats = 2,
                wheelbase = UnitValue.Length(value = 2.45, unit = LengthUnit.METER),
            ),
        )
        val sourceMap = urbanOneSourceMap()
        val result = liveValidator.validate(spec, sourceMap)
        assertFalse("expected spec to be invalid", result.isValid)
        // The validator's HARD violation produces an
        // ERROR annotation at the body.doors position.
        val errorAnnotation = result.annotations.firstOrNull {
            it.severity == DiagnosticAnnotation.Severity.ERROR
        }
        assertNotNull("expected an ERROR annotation", errorAnnotation)
    }

    @Test
    fun `live validator produces REGULATORY annotation for an SUV constraint violation`() {
        val spec = urbanOneSpec().copy(
            body = Body(
                architecture = BodyArchitecture.SUV,
                doors = 5,
                seats = 5,
                wheelbase = UnitValue.Length(value = 2.30, unit = LengthUnit.METER),
            ),
        )
        val sourceMap = urbanOneSourceMap()
        val result = liveValidator.validate(spec, sourceMap)
        // The SUV-wheelbase constraint fires.
        val reg = result.annotations.firstOrNull {
            it.severity == DiagnosticAnnotation.Severity.REGULATORY
        }
        assertNotNull("expected a REGULATORY annotation", reg)
    }

    @Test
    fun `live validator produces WARNING annotation for a SOFT violation`() {
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
        val sourceMap = urbanOneSourceMap()
        val result = liveValidator.validate(spec, sourceMap)
        // The gas-single-speed rule fires as a SOFT
        // warning. The annotation is a WARNING.
        val warning = result.annotations.firstOrNull {
            it.severity == DiagnosticAnnotation.Severity.WARNING
        }
        assertNotNull("expected a WARNING annotation", warning)
    }

    @Test
    fun `live validator annotations are sorted by line then column`() {
        val spec = urbanOneSpec().copy(
            body = Body(
                architecture = BodyArchitecture.COUPE,
                doors = 4,  // HARD: coupe-doors
                seats = 4,  // HARD: 2-seat-body
                wheelbase = UnitValue.Length(value = 2.45, unit = LengthUnit.METER),
            ),
        )
        val sourceMap = urbanOneSourceMap()
        val result = liveValidator.validate(spec, sourceMap)
        // The annotations should be in (line, column) order.
        val sorted = result.annotations.sortedWith(
            compareBy({ it.position.line }, { it.position.column }),
        )
        assertEquals(sorted, result.annotations)
    }

    @Test
    fun `live validator is deterministic for the same spec + source map`() {
        val spec = urbanOneSpec()
        val sourceMap = urbanOneSourceMap()
        val a = liveValidator.validate(spec, sourceMap)
        val b = liveValidator.validate(spec, sourceMap)
        assertEquals(a.diagnostics, b.diagnostics)
        assertEquals(a.annotations, b.annotations)
        assertEquals(a.isValid, b.isValid)
    }

    // ============================================================
    // Fixtures
    // ============================================================

    private fun urbanOneSourceMap(): SourceMap = SourceMap.EMPTY.withAll(
        mapOf(
            "$.apiVersion" to SourcePosition(2, 5),
            "$.metadata.projectId" to SourcePosition(3, 17),
            "$.metadata.revision" to SourcePosition(4, 14),
            "$.classification.representationLevel" to SourcePosition(6, 27),
            "$.body.architecture" to SourcePosition(8, 20),
            "$.body.doors" to SourcePosition(9, 11),
            "$.body.seats" to SourcePosition(10, 11),
            "$.body.wheelbase" to SourcePosition(11, 17),
            "$.propulsion.energySource" to SourcePosition(14, 22),
            "$.propulsion.engine.configuration" to SourcePosition(15, 28),
            "$.propulsion.engine.displacement" to SourcePosition(16, 28),
            "$.driveline.traction" to SourcePosition(20, 18),
            "$.driveline.transmission" to SourcePosition(21, 23),
        ),
    )

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
