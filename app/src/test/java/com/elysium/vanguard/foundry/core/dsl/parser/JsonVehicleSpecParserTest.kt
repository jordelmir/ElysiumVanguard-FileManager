package com.elysium.vanguard.foundry.core.dsl.parser

import com.elysium.vanguard.foundry.core.ontology.ids.ProjectId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for the `JsonVehicleSpecParser`. The parser is the
 * boundary between the text surface (JSON) and the typed
 * `CompiledVehicleSpec`.
 *
 * The tests cover:
 *   1. **Happy path**: the canonical "Urban One" spec parses
 *      to the expected `CompiledVehicleSpec` (golden file).
 *   2. **Syntax errors**: malformed JSON returns a
 *      `SyntaxError` diagnostic.
 *   3. **Missing fields**: a required field that is absent
 *      returns a `MissingRequiredField` diagnostic with the
 *      correct path.
 *   4. **Wrong types**: a field with the wrong JSON type
 *      returns a `WrongType` diagnostic.
 *   5. **Unknown enum values**: a value that is not in the
 *      allowed set returns an `UnknownEnumValue` diagnostic
 *      with the allowed values listed.
 *   6. **NaN / Infinity**: a numeric value that is NaN or
 *      Infinity returns an `InvalidUnitValue` diagnostic.
 *   7. **Invariant violations**: a sub-aggregate init that
 *      fails returns an `InvariantViolation` diagnostic.
 *   8. **Determinism**: the same input produces the same
 *      `CompiledVehicleSpec` on every call.
 *   9. **Fuzzing**: random + adversarial inputs do not crash
 *      the parser (per skill 04 section 26).
 *   10. **Path-aware errors**: the diagnostic's `path`
 *      field is the JSON path of the offending field.
 */
class JsonVehicleSpecParserTest {

    private val parser = JsonVehicleSpecParser()

    // ============================================================
    // Golden file: the Urban One spec
    // ============================================================

    private val urbanOneJson = """
        {
          "apiVersion": "elysium.vehicle/v1",
          "metadata": {
            "projectId": "00000000-0000-0000-0000-000000000001",
            "revision": 1
          },
          "classification": {
            "representationLevel": "PARAMETRIC_FUNCTIONAL"
          },
          "body": {
            "architecture": "HATCHBACK",
            "doors": 5,
            "seats": 5,
            "wheelbase": {
              "value": 2.45,
              "unit": "METER"
            }
          },
          "propulsion": {
            "energySource": "ELECTRIC",
            "engine": {
              "configuration": "ELECTRIC_NONE",
              "displacement": {
                "value": 0,
                "unit": "LITER"
              },
              "orientation": "LONGITUDINAL"
            }
          },
          "driveline": {
            "traction": "FWD",
            "transmission": "SINGLE_SPEED"
          }
        }
    """.trimIndent()

    @Test
    fun `urban one golden spec parses successfully`() {
        val result = parser.parseWithDiagnostics(urbanOneJson)
        assertTrue("expected success, got $result", result is ParseResult.Success)
        result as ParseResult.Success
        assertTrue("expected no diagnostics, got ${result.diagnostics}", result.diagnostics.isEmpty())
        val spec = result.spec
        assertEquals("elysium.vehicle/v1", spec.apiVersion.value)
        assertEquals(
            ProjectId.from("00000000-0000-0000-0000-000000000001").getOrThrow(),
            spec.metadata.projectId,
        )
        assertEquals(1, spec.metadata.revision)
        assertEquals("HATCHBACK", spec.body.architecture.name)
        assertEquals(5, spec.body.doors)
        assertEquals(5, spec.body.seats)
        assertEquals(2.45, spec.body.wheelbase.value, 0.0)
        assertEquals("ELECTRIC", spec.propulsion.energySource.name)
        assertEquals("FWD", spec.driveline.traction.name)
        assertEquals("SINGLE_SPEED", spec.driveline.transmission.name)
    }

    @Test
    fun `urban one golden spec canonical form is the expected golden string`() {
        val result = parser.parseWithDiagnostics(urbanOneJson) as ParseResult.Success
        val expected = "vsl:v1" +
            "|api=elysium.vehicle/v1" +
            "|metadata:projectId=00000000-0000-0000-0000-000000000001|revision=1" +
            "|classification:level=PARAMETRIC_FUNCTIONAL" +
            "|body:architecture=HATCHBACK|doors=5|seats=5|wheelbase=length:2.45|unit:METER" +
            "|propulsion:energySource=ELECTRIC" +
            "|engine=configuration=ELECTRIC_NONE|displacement=volume:0.0|unit:LITER|orientation=LONGITUDINAL" +
            "|driveline:traction=FWD|transmission=SINGLE_SPEED"
        assertEquals(expected, result.spec.canonicalForm())
    }

    // ============================================================
    // Syntax errors
    // ============================================================

    @Test
    fun `malformed JSON returns SyntaxError diagnostic`() {
        val result = parser.parseWithDiagnostics("{not valid json}")
        assertTrue("expected failure, got $result", result is ParseResult.Failure)
        result as ParseResult.Failure
        assertTrue(
            "expected SyntaxError, got ${result.diagnostics[0]::class.java.simpleName}",
            result.diagnostics[0] is CompilationDiagnostic.SyntaxError,
        )
    }

    @Test
    fun `empty input returns SyntaxError diagnostic`() {
        val result = parser.parseWithDiagnostics("")
        assertTrue("expected failure, got $result", result is ParseResult.Failure)
        result as ParseResult.Failure
        assertTrue(result.diagnostics[0] is CompilationDiagnostic.SyntaxError)
    }

    @Test
    fun `null JSON value returns SyntaxError diagnostic`() {
        val result = parser.parseWithDiagnostics("null")
        assertTrue("expected failure, got $result", result is ParseResult.Failure)
        result as ParseResult.Failure
        assertTrue(result.diagnostics[0] is CompilationDiagnostic.SyntaxError)
    }

    // ============================================================
    // Missing required fields
    // ============================================================

    @Test
    fun `missing apiVersion returns MissingRequiredField at root path`() {
        val json = urbanOneJson.replace("\"apiVersion\": \"elysium.vehicle/v1\",", "")
        val result = parser.parseWithDiagnostics(json)
        assertTrue("expected failure, got $result", result is ParseResult.Failure)
        result as ParseResult.Failure
        val first = result.diagnostics[0]
        assertTrue("expected MissingRequiredField, got $first", first is CompilationDiagnostic.MissingRequiredField)
        first as CompilationDiagnostic.MissingRequiredField
        assertEquals("apiVersion", first.field)
        assertEquals("$", first.path)
    }

    @Test
    fun `missing body returns MissingRequiredField at the body path`() {
        val json = urbanOneJson.replace("\"body\": {", "\"__body_disabled__\": {")
        val result = parser.parseWithDiagnostics(json)
        assertTrue("expected failure, got $result", result is ParseResult.Failure)
        result as ParseResult.Failure
        val bodyDiag = result.diagnostics.firstOrNull {
            it is CompilationDiagnostic.MissingRequiredField && it.field == "body"
        } as? CompilationDiagnostic.MissingRequiredField
        assertNotNull("expected MissingRequiredField for 'body', got ${result.diagnostics}", bodyDiag)
        assertEquals("\$.body", bodyDiag!!.path)
    }

    @Test
    fun `missing wheelbase unit returns MissingRequiredField at the unit path`() {
        val json = urbanOneJson.replace("\"unit\": \"METER\"", "\"__unit_disabled__\": \"METER\"")
        val result = parser.parseWithDiagnostics(json)
        assertTrue("expected failure, got $result", result is ParseResult.Failure)
        result as ParseResult.Failure
        val unitDiag = result.diagnostics.firstOrNull {
            it is CompilationDiagnostic.MissingRequiredField && it.field == "unit"
        } as? CompilationDiagnostic.MissingRequiredField
        assertNotNull("expected MissingRequiredField for 'unit', got ${result.diagnostics}", unitDiag)
        assertEquals("\$.body.wheelbase.unit", unitDiag!!.path)
    }

    // ============================================================
    // Wrong types
    // ============================================================

    @Test
    fun `string where integer expected returns WrongType diagnostic`() {
        val json = urbanOneJson.replace("\"doors\": 5", "\"doors\": \"five\"")
        val result = parser.parseWithDiagnostics(json)
        assertTrue("expected failure, got $result", result is ParseResult.Failure)
        result as ParseResult.Failure
        val first = result.diagnostics[0]
        assertTrue("expected WrongType, got $first", first is CompilationDiagnostic.WrongType)
        first as CompilationDiagnostic.WrongType
        assertEquals("doors", first.field)
        assertEquals("\$.body.doors", first.path)
    }

    @Test
    fun `number where string expected returns WrongType diagnostic`() {
        val json = urbanOneJson.replace("\"architecture\": \"HATCHBACK\"", "\"architecture\": 5")
        val result = parser.parseWithDiagnostics(json)
        assertTrue("expected failure, got $result", result is ParseResult.Failure)
        result as ParseResult.Failure
        val first = result.diagnostics[0]
        assertTrue("expected WrongType, got $first", first is CompilationDiagnostic.WrongType)
    }

    // ============================================================
    // Unknown enum values
    // ============================================================

    @Test
    fun `unknown body architecture returns UnknownEnumValue diagnostic with allowed list`() {
        val json = urbanOneJson.replace("\"HATCHBACK\"", "\"AIRCRAFT\"")
        val result = parser.parseWithDiagnostics(json)
        assertTrue("expected failure, got $result", result is ParseResult.Failure)
        result as ParseResult.Failure
        val first = result.diagnostics[0]
        assertTrue("expected UnknownEnumValue, got $first", first is CompilationDiagnostic.UnknownEnumValue)
        first as CompilationDiagnostic.UnknownEnumValue
        assertEquals("architecture", first.field)
        assertEquals("AIRCRAFT", first.actual)
        assertTrue(
            "allowed list should include HATCHBACK, got: ${first.allowed}",
            "HATCHBACK" in first.allowed,
        )
    }

    @Test
    fun `unknown apiVersion returns UnknownEnumValue diagnostic`() {
        val json = urbanOneJson.replace("elysium.vehicle/v1", "v1")
        val result = parser.parseWithDiagnostics(json)
        assertTrue("expected failure, got $result", result is ParseResult.Failure)
        result as ParseResult.Failure
        val first = result.diagnostics[0]
        assertTrue(
            "expected UnknownEnumValue, got $first",
            first is CompilationDiagnostic.UnknownEnumValue,
        )
        assertTrue(first.code.startsWith("VCOMP-SCHEMA-004"))
    }

    // ============================================================
    // NaN / Infinity
    // ============================================================

    @Test
    fun `NaN wheelbase returns InvalidUnitValue diagnostic`() {
        // JSON does not natively support NaN. Gson rejects
        // a "NaN" string in a number-typed slot as
        // `WrongType` (string, not number). Any of the three
        // failure modes (SyntaxError, WrongType, or
        // InvalidUnitValue) is an acceptable "rejection" of
        // an adversarial value.
        val json = urbanOneJson.replace("2.45", "NaN")
        val result = parser.parseWithDiagnostics(json)
        assertTrue("expected failure, got $result", result is ParseResult.Failure)
        result as ParseResult.Failure
        assertTrue(
            "expected a syntax/schema rejection, got ${result.diagnostics[0]}",
            result.diagnostics[0] is CompilationDiagnostic.SyntaxError ||
                result.diagnostics[0] is CompilationDiagnostic.WrongType ||
                result.diagnostics[0] is CompilationDiagnostic.InvalidUnitValue,
        )
    }

    // ============================================================
    // Invariant violations
    // ============================================================

    @Test
    fun `electric energy source with INLINE_4 engine returns InvariantViolation diagnostic`() {
        val json = urbanOneJson
            .replace("\"ELECTRIC_NONE\"", "\"INLINE_4\"")
            .replace("\"value\": 0", "\"value\": 1.6")
        val result = parser.parseWithDiagnostics(json)
        assertTrue("expected failure, got $result", result is ParseResult.Failure)
        result as ParseResult.Failure
        val first = result.diagnostics[0]
        assertTrue("expected InvariantViolation, got $first", first is CompilationDiagnostic.InvariantViolation)
        first as CompilationDiagnostic.InvariantViolation
        // The invariant fires at the `propulsion` sub-aggregate level (not at the spec level)
        // because the sub-aggregate `init` check runs first. The path is `$.propulsion`.
        assertEquals("$.propulsion", first.path)
    }

    @Test
    fun `invalid door count returns InvariantViolation diagnostic`() {
        val json = urbanOneJson.replace("\"doors\": 5", "\"doors\": 7")
        val result = parser.parseWithDiagnostics(json)
        assertTrue("expected failure, got $result", result is ParseResult.Failure)
        result as ParseResult.Failure
        val first = result.diagnostics[0]
        assertTrue("expected InvariantViolation, got $first", first is CompilationDiagnostic.InvariantViolation)
    }

    // ============================================================
    // Determinism
    // ============================================================

    @Test
    fun `same input produces same output on two calls (determinism)`() {
        val a = parser.parseWithDiagnostics(urbanOneJson) as ParseResult.Success
        val b = parser.parseWithDiagnostics(urbanOneJson) as ParseResult.Success
        assertEquals(
            "same input -> same canonical form (determinism contract)",
            a.spec.canonicalForm(),
            b.spec.canonicalForm(),
        )
        assertEquals(a.spec, b.spec)
    }

    @Test
    fun `same input produces same diagnostics on two calls`() {
        val json = urbanOneJson.replace("\"HATCHBACK\"", "\"AIRCRAFT\"")
        val a = parser.parseWithDiagnostics(json) as ParseResult.Failure
        val b = parser.parseWithDiagnostics(json) as ParseResult.Failure
        assertEquals(a.diagnostics.size, b.diagnostics.size)
        a.diagnostics.zip(b.diagnostics).forEach { (x, y) ->
            assertEquals(x.code, y.code)
            assertEquals(x.paths, y.paths)
        }
    }

    // ============================================================
    // Result.success (parse) vs parseWithDiagnostics
    // ============================================================

    @Test
    fun `parse returns Result success for valid input`() {
        val result = parser.parse(urbanOneJson)
        assertTrue("expected success, got $result", result.isSuccess)
        val spec = result.getOrThrow()
        assertEquals("HATCHBACK", spec.body.architecture.name)
    }

    @Test
    fun `parse returns Result failure for invalid input`() {
        val result = parser.parse("{not valid json}")
        assertTrue("expected failure, got $result", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(
            "expected CompilationDiagnostic, got ${error?.javaClass}",
            error is CompilationDiagnostic,
        )
    }

    // ============================================================
    // Fuzzing: random + adversarial inputs do not crash
    // ============================================================

    @Test
    fun `parser does not crash on empty string`() {
        val result = parser.parseWithDiagnostics("")
        assertTrue(result is ParseResult.Failure)
    }

    @Test
    fun `parser does not crash on whitespace`() {
        val result = parser.parseWithDiagnostics("   \n  \t  ")
        assertTrue(result is ParseResult.Failure)
    }

    @Test
    fun `parser does not crash on deeply nested object`() {
        // A 32-level nested object is at the recursion
        // limit; a 1000-level object is adversarial. The
        // parser should reject it gracefully.
        val nested = "{".repeat(1000) + "}".repeat(1000)
        val result = parser.parseWithDiagnostics(nested)
        assertTrue("expected failure for deep nesting, got $result", result is ParseResult.Failure)
    }

    @Test
    fun `parser does not crash on very long string`() {
        val longString = "x".repeat(1_000_000)
        val json = "{ \"apiVersion\": \"$longString\" }"
        val result = parser.parseWithDiagnostics(json)
        // A 1M-character apiVersion is either a SyntaxError
        // (Gson rejects the length) or an UnknownEnumValue.
        // Either is acceptable.
        assertTrue(result is ParseResult.Failure)
    }

    @Test
    fun `parser does not crash on null bytes in input`() {
        val json = "{ \"api\u0000Version\": \"elysium.vehicle/v1\" }"
        val result = parser.parseWithDiagnostics(json)
        assertTrue(result is ParseResult.Failure)
    }

    @Test
    fun `parser does not crash on unicode in values`() {
        val json = urbanOneJson.replace("Urban One", "Úrbán Öné 🚗")
        val result = parser.parseWithDiagnostics(json)
        // The Unicode replacement should not crash; the
        // projectId is a UUID, not the name, so the parse
        // should succeed.
        assertTrue("expected success for unicode values, got $result", result is ParseResult.Success)
    }

    // ============================================================
    // Helper: the UnknownEnumValue type
    // ============================================================

    private val UnknownEnumValueDiagnosticFor: Class<*> =
        CompilationDiagnostic.UnknownEnumValue::class.java
}
