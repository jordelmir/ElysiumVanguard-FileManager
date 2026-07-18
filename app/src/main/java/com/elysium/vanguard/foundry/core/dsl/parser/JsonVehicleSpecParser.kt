package com.elysium.vanguard.foundry.core.dsl.parser

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
import com.elysium.vanguard.foundry.core.dsl.schema.MassUnit
import com.elysium.vanguard.foundry.core.dsl.schema.EnergyUnit
import com.elysium.vanguard.foundry.core.dsl.schema.PowerUnit
import com.elysium.vanguard.foundry.core.dsl.schema.Propulsion
import com.elysium.vanguard.foundry.core.dsl.schema.SpecClassification
import com.elysium.vanguard.foundry.core.dsl.schema.SpecMetadata
import com.elysium.vanguard.foundry.core.dsl.schema.SpeedUnit
import com.elysium.vanguard.foundry.core.dsl.schema.Traction
import com.elysium.vanguard.foundry.core.dsl.schema.Transmission
import com.elysium.vanguard.foundry.core.dsl.schema.UnitValue
import com.elysium.vanguard.foundry.core.dsl.schema.VolumeUnit
import com.elysium.vanguard.foundry.core.ontology.ids.ProjectId
import com.elysium.vanguard.foundry.core.ontology.primitives.RepresentationLevel
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonPrimitive

/**
 * The JSON parser for the Vehicle Spec Language (VSL).
 *
 * Per `.ai/skills/04-vehicle-dsl-compiler/SKILL.md` section 6
 * step 1 (Lexing or structured decoding) + step 2 (Parsing) +
 * step 3 (Schema validation):
 *   - The parser consumes the JSON text via Gson (a battle-
 *     tested structured decoder — we don't need a hand-rolled
 *     lexer for the JSON surface).
 *   - The parser walks the JSON tree via a path-aware
 *     recursive descent (every error carries the JSON path).
 *   - The parser is **total**: malformed JSON is caught at
 *     the top + returned as a `CompilationDiagnostic.SyntaxError`.
 *   - The parser is **deterministic**: the same input
 *     produces the same output (the `parse` function
 *     produces the same `CompiledVehicleSpec` on every call).
 *
 * The parser is the **only path** from the text surface to
 * the typed schema. A future YAML surface would be a
 * separate parser that produces the same `CompiledVehicleSpec`.
 */
class JsonVehicleSpecParser : VehicleSpecParser {

    private val gson = Gson()

    // ============================================================
    // Public API
    // ============================================================

    override fun parse(text: String): Result<CompiledVehicleSpec> {
        val result = parseWithDiagnostics(text)
        return when (result) {
            is ParseResult.Success -> Result.success(result.spec)
            is ParseResult.Failure -> {
                // The first HARD diagnostic is the failure. SOFT
                // diagnostics are reported via the report
                // (Phase F2 second half, I-2.7).
                val firstHard = result.diagnostics.firstOrNull {
                    it.severity == CompilationDiagnostic.Severity.HARD ||
                        it.severity == CompilationDiagnostic.Severity.SAFETY_CRITICAL ||
                        it.severity == CompilationDiagnostic.Severity.REGULATORY
                } ?: result.diagnostics.first()
                Result.failure(firstHard)
            }
        }
    }

    override fun parseWithDiagnostics(text: String): ParseResult {
        val diagnostics = mutableListOf<CompilationDiagnostic>()

        // Step 1: Decode the JSON. A malformed input is a
        // single SyntaxError diagnostic.
        val root: JsonObject = try {
            gson.fromJson(text, JsonObject::class.java)
                ?: throw CompilationDiagnostic.SyntaxError(reason = "empty input")
        } catch (e: JsonParseException) {
            diagnostics.add(
                CompilationDiagnostic.SyntaxError(
                    reason = e.message ?: "malformed JSON",
                ),
            )
            return ParseResult.Failure(diagnostics.toList())
        } catch (e: CompilationDiagnostic.SyntaxError) {
            diagnostics.add(e)
            return ParseResult.Failure(diagnostics.toList())
        }

        // Step 2: Parse each top-level field. A missing
        // required field is a `MissingRequiredField` diagnostic.
        val apiVersion = parseApiVersion(root, "$", diagnostics) ?: return ParseResult.Failure(diagnostics.toList())
        val metadata = parseMetadata(root, "$.metadata", diagnostics) ?: return ParseResult.Failure(diagnostics.toList())
        val classification = parseClassification(root, "$.classification", diagnostics) ?: return ParseResult.Failure(diagnostics.toList())
        val body = parseBody(root, "$.body", diagnostics) ?: return ParseResult.Failure(diagnostics.toList())
        val propulsion = parsePropulsion(root, "$.propulsion", diagnostics) ?: return ParseResult.Failure(diagnostics.toList())
        val driveline = parseDriveline(root, "$.driveline", diagnostics) ?: return ParseResult.Failure(diagnostics.toList())

        // Step 3: Assemble the spec. The init blocks enforce
        // the cross-aggregate invariants (e.g. ELECTRIC +
        // INLINE_4). A thrown `IllegalArgumentException` is
        // converted to an `InvariantViolation` diagnostic.
        val spec = try {
            CompiledVehicleSpec(
                apiVersion = apiVersion,
                metadata = metadata,
                classification = classification,
                body = body,
                propulsion = propulsion,
                driveline = driveline,
            )
        } catch (e: IllegalArgumentException) {
            diagnostics.add(
                CompilationDiagnostic.InvariantViolation(
                    field = "CompiledVehicleSpec",
                    path = "$",
                    reason = e.message ?: "unknown",
                ),
            )
            return ParseResult.Failure(diagnostics.toList())
        }

        return ParseResult.Success(spec = spec, diagnostics = diagnostics.toList())
    }

    // ============================================================
    // Per-field parsers
    // ============================================================

    private fun parseApiVersion(
        root: JsonObject,
        path: String,
        diagnostics: MutableList<CompilationDiagnostic>,
    ): ApiVersion? {
        val element = root.get("apiVersion")
            ?: return missingField("apiVersion", path, diagnostics)
        return if (!element.isJsonPrimitive) {
            wrongType("apiVersion", path, "string", element::class.java.simpleName, diagnostics)
            null
        } else {
            val value = element.asString
            if (!ApiVersion.isValid(value)) {
                diagnostics.add(
                    CompilationDiagnostic.UnknownEnumValue(
                        field = "apiVersion",
                        path = path,
                        allowed = ApiVersion.API_VERSION_PATTERN.let { listOf(it) },
                        actual = value,
                    ),
                )
                null
            } else {
                ApiVersion(value)
            }
        }
    }

    private fun parseMetadata(
        root: JsonObject,
        path: String,
        diagnostics: MutableList<CompilationDiagnostic>,
    ): SpecMetadata? {
        val element = root.getAsJsonObject("metadata")
            ?: return missingField("metadata", path, diagnostics)
        val projectIdElement = element.get("projectId")
            ?: return missingField("projectId", "$path.projectId", diagnostics)
        val revisionElement = element.get("revision")
            ?: return missingField("revision", "$path.revision", diagnostics)

        if (!projectIdElement.isJsonPrimitive) {
            wrongType("projectId", "$path.projectId", "string",
                projectIdElement::class.java.simpleName, diagnostics)
            return null
        }
        if (!revisionElement.isJsonPrimitive || !revisionElement.asJsonPrimitive.isNumber) {
            wrongType("revision", "$path.revision", "integer",
                revisionElement::class.java.simpleName, diagnostics)
            return null
        }
        val projectId = ProjectId.from(projectIdElement.asString).getOrElse {
            diagnostics.add(
                CompilationDiagnostic.InvariantViolation(
                    field = "projectId",
                    path = "$path.projectId",
                    reason = it.message ?: "invalid UUID",
                ),
            )
            return null
        }
        val revision = revisionElement.asInt
        return try {
            SpecMetadata(projectId = projectId, revision = revision)
        } catch (e: IllegalArgumentException) {
            diagnostics.add(
                CompilationDiagnostic.InvariantViolation(
                    field = "revision",
                    path = "$path.revision",
                    reason = e.message ?: "unknown",
                ),
            )
            null
        }
    }

    private fun parseClassification(
        root: JsonObject,
        path: String,
        diagnostics: MutableList<CompilationDiagnostic>,
    ): SpecClassification? {
        val element = root.getAsJsonObject("classification")
            ?: return missingField("classification", path, diagnostics)
        val levelElement = element.get("representationLevel")
            ?: return missingField("representationLevel", "$path.representationLevel", diagnostics)
        if (!levelElement.isJsonPrimitive) {
            wrongType("representationLevel", "$path.representationLevel", "string",
                levelElement::class.java.simpleName, diagnostics)
            return null
        }
        val levelName = levelElement.asString
        val level: RepresentationLevel = parseEnum<RepresentationLevel>(
            value = levelName,
            path = "$path.representationLevel",
            allowed = RepresentationLevel.values().map { it.name },
            field = "representationLevel",
            diagnostics = diagnostics,
        ) ?: return null
        return try {
            SpecClassification(representationLevel = level)
        } catch (e: IllegalArgumentException) {
            diagnostics.add(
                CompilationDiagnostic.InvariantViolation(
                    field = "representationLevel",
                    path = "$path.representationLevel",
                    reason = e.message ?: "unknown",
                ),
            )
            null
        }
    }

    private fun parseBody(
        root: JsonObject,
        path: String,
        diagnostics: MutableList<CompilationDiagnostic>,
    ): Body? {
        val element = root.getAsJsonObject("body")
            ?: return missingField("body", path, diagnostics)
        val architecture = element.get("architecture")
            ?: return missingField("architecture", "$path.architecture", diagnostics)
        val doors = element.get("doors")
            ?: return missingField("doors", "$path.doors", diagnostics)
        val seats = element.get("seats")
            ?: return missingField("seats", "$path.seats", diagnostics)
        val wheelbase = element.getAsJsonObject("wheelbase")
            ?: return missingField("wheelbase", "$path.wheelbase", diagnostics)

        val architectureValue: BodyArchitecture = parseEnum<BodyArchitecture>(
            value = architecture.asStringOrNull() ?: return wrongType(
                "architecture", "$path.architecture", "string",
                "non-string", diagnostics,
            ),
            path = "$path.architecture",
            allowed = BodyArchitecture.values().map { it.name },
            field = "architecture",
            diagnostics = diagnostics,
        ) ?: return null

        if (!doors.isJsonPrimitive || !doors.asJsonPrimitive.isNumber) {
            wrongType("doors", "$path.doors", "integer", "non-integer", diagnostics)
            return null
        }
        if (!seats.isJsonPrimitive || !seats.asJsonPrimitive.isNumber) {
            wrongType("seats", "$path.seats", "integer", "non-integer", diagnostics)
            return null
        }

        val wheelbaseLength = parseLength(wheelbase, "$path.wheelbase", diagnostics) ?: return null

        return try {
            Body(
                architecture = architectureValue,
                doors = doors.asInt,
                seats = seats.asInt,
                wheelbase = wheelbaseLength,
            )
        } catch (e: IllegalArgumentException) {
            diagnostics.add(
                CompilationDiagnostic.InvariantViolation(
                    field = "body",
                    path = path,
                    reason = e.message ?: "unknown",
                ),
            )
            null
        }
    }

    private fun parsePropulsion(
        root: JsonObject,
        path: String,
        diagnostics: MutableList<CompilationDiagnostic>,
    ): Propulsion? {
        val element = root.getAsJsonObject("propulsion")
            ?: return missingField("propulsion", path, diagnostics)
        val energySourceElement = element.get("energySource")
            ?: return missingField("energySource", "$path.energySource", diagnostics)
        val engineElement = element.getAsJsonObject("engine")
            ?: return missingField("engine", "$path.engine", diagnostics)

        val energySourceValue: EnergySource = parseEnum<EnergySource>(
            value = energySourceElement.asStringOrNull() ?: return wrongType(
                "energySource", "$path.energySource", "string",
                "non-string", diagnostics,
            ),
            path = "$path.energySource",
            allowed = EnergySource.values().map { it.name },
            field = "energySource",
            diagnostics = diagnostics,
        ) ?: return null

        val engine = parseEngine(engineElement, "$path.engine", diagnostics) ?: return null

        return try {
            Propulsion(energySource = energySourceValue, engine = engine)
        } catch (e: IllegalArgumentException) {
            diagnostics.add(
                CompilationDiagnostic.InvariantViolation(
                    field = "propulsion",
                    path = path,
                    reason = e.message ?: "unknown",
                ),
            )
            null
        }
    }

    private fun parseEngine(
        element: JsonObject,
        path: String,
        diagnostics: MutableList<CompilationDiagnostic>,
    ): Engine? {
        val configurationElement = element.get("configuration")
            ?: return missingField("configuration", "$path.configuration", diagnostics)
        val displacementElement = element.getAsJsonObject("displacement")
            ?: return missingField("displacement", "$path.displacement", diagnostics)
        val orientationElement = element.get("orientation")
            ?: return missingField("orientation", "$path.orientation", diagnostics)

        val configuration: EngineConfiguration = parseEnum<EngineConfiguration>(
            value = configurationElement.asStringOrNull() ?: return wrongType(
                "configuration", "$path.configuration", "string",
                "non-string", diagnostics,
            ),
            path = "$path.configuration",
            allowed = EngineConfiguration.values().map { it.name },
            field = "configuration",
            diagnostics = diagnostics,
        ) ?: return null

        val displacement = parseVolume(displacementElement, "$path.displacement", diagnostics) ?: return null

        val orientation: EngineOrientation = parseEnum<EngineOrientation>(
            value = orientationElement.asStringOrNull() ?: return wrongType(
                "orientation", "$path.orientation", "string",
                "non-string", diagnostics,
            ),
            path = "$path.orientation",
            allowed = EngineOrientation.values().map { it.name },
            field = "orientation",
            diagnostics = diagnostics,
        ) ?: return null

        return Engine(
            configuration = configuration,
            displacement = displacement,
            orientation = orientation,
        )
    }

    private fun parseDriveline(
        root: JsonObject,
        path: String,
        diagnostics: MutableList<CompilationDiagnostic>,
    ): Driveline? {
        val element = root.getAsJsonObject("driveline")
            ?: return missingField("driveline", path, diagnostics)
        val tractionElement = element.get("traction")
            ?: return missingField("traction", "$path.traction", diagnostics)
        val transmissionElement = element.get("transmission")
            ?: return missingField("transmission", "$path.transmission", diagnostics)

        val traction: Traction = parseEnum<Traction>(
            value = tractionElement.asStringOrNull() ?: return wrongType(
                "traction", "$path.traction", "string",
                "non-string", diagnostics,
            ),
            path = "$path.traction",
            allowed = Traction.values().map { it.name },
            field = "traction",
            diagnostics = diagnostics,
        ) ?: return null

        val transmission: Transmission = parseEnum<Transmission>(
            value = transmissionElement.asStringOrNull() ?: return wrongType(
                "transmission", "$path.transmission", "string",
                "non-string", diagnostics,
            ),
            path = "$path.transmission",
            allowed = Transmission.values().map { it.name },
            field = "transmission",
            diagnostics = diagnostics,
        ) ?: return null

        return Driveline(traction = traction, transmission = transmission)
    }

    // ============================================================
    // Unit-value parsers
    // ============================================================

    private fun parseLength(
        element: JsonObject,
        path: String,
        diagnostics: MutableList<CompilationDiagnostic>,
    ): UnitValue.Length? {
        val value = element.get("value")?.asDoubleOrNull(parseFinite = true, path, diagnostics) ?: return null
        val unitName = element.get("unit")?.asStringOrNull() ?: return missingField("unit", "$path.unit", diagnostics)
        val unit: LengthUnit = parseEnum<LengthUnit>(
            value = unitName,
            path = "$path.unit",
            allowed = LengthUnit.values().map { it.name },
            field = "unit",
            diagnostics = diagnostics,
        ) ?: return null
        return UnitValue.Length(value = value, unit = unit as LengthUnit)
    }

    private fun parseVolume(
        element: JsonObject,
        path: String,
        diagnostics: MutableList<CompilationDiagnostic>,
    ): UnitValue.Volume? {
        val value = element.get("value")?.asDoubleOrNull(parseFinite = true, path, diagnostics) ?: return null
        val unitName = element.get("unit")?.asStringOrNull() ?: return missingField("unit", "$path.unit", diagnostics)
        val unit: VolumeUnit = parseEnum<VolumeUnit>(
            value = unitName,
            path = "$path.unit",
            allowed = VolumeUnit.values().map { it.name },
            field = "unit",
            diagnostics = diagnostics,
        ) ?: return null
        return UnitValue.Volume(value = value, unit = unit as VolumeUnit)
    }

    @Suppress("unused")
    private fun parseMass(
        element: JsonObject,
        path: String,
        diagnostics: MutableList<CompilationDiagnostic>,
    ): UnitValue.Mass? {
        val value = element.get("value")?.asDoubleOrNull(parseFinite = true, path, diagnostics) ?: return null
        val unitName = element.get("unit")?.asStringOrNull() ?: return missingField("unit", "$path.unit", diagnostics)
        val unit: MassUnit = parseEnum<MassUnit>(
            value = unitName,
            path = "$path.unit",
            allowed = MassUnit.values().map { it.name },
            field = "unit",
            diagnostics = diagnostics,
        ) ?: return null
        return UnitValue.Mass(value = value, unit = unit as MassUnit)
    }

    @Suppress("unused")
    private fun parseEnergy(
        element: JsonObject,
        path: String,
        diagnostics: MutableList<CompilationDiagnostic>,
    ): UnitValue.Energy? {
        val value = element.get("value")?.asDoubleOrNull(parseFinite = true, path, diagnostics) ?: return null
        val unitName = element.get("unit")?.asStringOrNull() ?: return missingField("unit", "$path.unit", diagnostics)
        val unit: EnergyUnit = parseEnum<EnergyUnit>(
            value = unitName,
            path = "$path.unit",
            allowed = EnergyUnit.values().map { it.name },
            field = "unit",
            diagnostics = diagnostics,
        ) ?: return null
        return UnitValue.Energy(value = value, unit = unit as EnergyUnit)
    }

    @Suppress("unused")
    private fun parsePower(
        element: JsonObject,
        path: String,
        diagnostics: MutableList<CompilationDiagnostic>,
    ): UnitValue.Power? {
        val value = element.get("value")?.asDoubleOrNull(parseFinite = true, path, diagnostics) ?: return null
        val unitName = element.get("unit")?.asStringOrNull() ?: return missingField("unit", "$path.unit", diagnostics)
        val unit: PowerUnit = parseEnum<PowerUnit>(
            value = unitName,
            path = "$path.unit",
            allowed = PowerUnit.values().map { it.name },
            field = "unit",
            diagnostics = diagnostics,
        ) ?: return null
        return UnitValue.Power(value = value, unit = unit as PowerUnit)
    }

    @Suppress("unused")
    private fun parseSpeed(
        element: JsonObject,
        path: String,
        diagnostics: MutableList<CompilationDiagnostic>,
    ): UnitValue.Speed? {
        val value = element.get("value")?.asDoubleOrNull(parseFinite = true, path, diagnostics) ?: return null
        val unitName = element.get("unit")?.asStringOrNull() ?: return missingField("unit", "$path.unit", diagnostics)
        val unit: SpeedUnit = parseEnum<SpeedUnit>(
            value = unitName,
            path = "$path.unit",
            allowed = SpeedUnit.values().map { it.name },
            field = "unit",
            diagnostics = diagnostics,
        ) ?: return null
        return UnitValue.Speed(value = value, unit = unit as SpeedUnit)
    }

    // ============================================================
    // Helpers
    // ============================================================

    private inline fun <reified E : Enum<E>> parseEnum(
        value: String,
        path: String,
        allowed: List<String>,
        field: String,
        diagnostics: MutableList<CompilationDiagnostic>,
    ): E? {
        val match = allowed.firstOrNull { it == value }
        if (match == null) {
            diagnostics.add(
                CompilationDiagnostic.UnknownEnumValue(
                    field = field,
                    path = path,
                    allowed = allowed,
                    actual = value,
                ),
            )
            return null
        }
        return enumValueOf<E>(match)
    }

    private fun missingField(
        field: String,
        path: String,
        diagnostics: MutableList<CompilationDiagnostic>,
    ): Nothing? {
        diagnostics.add(CompilationDiagnostic.MissingRequiredField(field = field, path = path))
        return null
    }

    private fun wrongType(
        field: String,
        path: String,
        expected: String,
        actual: String,
        diagnostics: MutableList<CompilationDiagnostic>,
    ): Nothing? {
        diagnostics.add(
            CompilationDiagnostic.WrongType(
                field = field,
                path = path,
                expected = expected,
                actual = actual,
            ),
        )
        return null
    }

    private fun JsonElement.asStringOrNull(): String? =
        if (this is JsonPrimitive && isString) asString else null

    private fun JsonElement.asDoubleOrNull(
        parseFinite: Boolean,
        path: String,
        diagnostics: MutableList<CompilationDiagnostic>,
    ): Double? = when {
        this !is JsonPrimitive || !isNumber -> {
            wrongType("value", path, "number", "non-number", diagnostics)
            null
        }
        !parseFinite -> asDouble
        asDouble.isNaN() || asDouble.isInfinite() -> {
            diagnostics.add(
                CompilationDiagnostic.InvalidUnitValue(
                    path = path,
                    rawValue = asString,
                ),
            )
            null
        }
        else -> asDouble
    }
}
