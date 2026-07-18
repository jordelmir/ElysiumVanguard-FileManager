package com.elysium.vanguard.foundry.core.dsl.schema

import com.elysium.vanguard.foundry.core.ontology.ids.ProjectId
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import com.elysium.vanguard.foundry.core.ontology.primitives.RepresentationLevel

/**
 * The compiled Vehicle Spec — the typed output of the
 * deterministic compiler (per `.ai/skills/04-vehicle-dsl-compiler/
 * SKILL.md` section 5).
 *
 * The schema is the **only** shape the downstream skills
 * (06 — 3D, 07 — digital twin, 09 — catalog, 10 — marketplace)
 * consume. The DSL text + the visual AST are alternate
 * surfaces; the `CompiledVehicleSpec` is the canonical typed
 * value.
 *
 * The schema is **append-only** (per `.ai/STANDARDS.md` 2.2 +
 * ADR-0006): a breaking change to the schema is a new
 * `apiVersion` + a migration tool. A silent lossy migration
 * is a contract violation (per skill 04 section 20).
 *
 * The `canonicalForm()` method is the deterministic UTF-8
 * byte sequence that the compiler hashes to produce the
 * `Spec.Artifact.id` (per skill 04 section 15 — artifact
 * hashing). Same `CompiledVehicleSpec` -> same canonical
 * form -> same SHA-256 -> same artifact ID.
 */
data class CompiledVehicleSpec(
    val apiVersion: ApiVersion,
    val metadata: SpecMetadata,
    val classification: SpecClassification,
    val body: Body,
    val propulsion: Propulsion,
    val driveline: Driveline,
) {
    init {
        // Per `.ai/STANDARDS.md` section 4 + skill 04 section 17:
        // a `vehicle` without a `representationLevel` is rejected.
        // The classification carries the level; the constructor
        // asserts it is set.
        require(classification.representationLevel != RepresentationLevel.UNKNOWN) {
            "CompiledVehicleSpec.classification.representationLevel must be set; " +
                "a vehicle without a representationLevel is rejected"
        }
    }

    /**
     * The deterministic UTF-8 byte sequence of the spec.
     * Same spec -> same bytes. The compiler hashes this
     * string to produce the `Spec.Artifact.id` (per skill 04
     * section 15).
     *
     * The format is the **canonical form** of the spec:
     * the metadata + the classification + the body + the
     * propulsion + the driveline, in a fixed order, with
     * deterministic separators.
     */
    fun canonicalForm(): String = buildString {
        append("vsl:v1")
        append("|api=").append(apiVersion.value)
        append("|").append(metadata.canonicalForm())
        append("|").append(classification.canonicalForm())
        append("|").append(body.canonicalForm())
        append("|").append(propulsion.canonicalForm())
        append("|").append(driveline.canonicalForm())
    }
}

/**
 * The VSL API version. A breaking change to the schema is a
 * new `apiVersion`; the migration tool (per skill 04 section
 * 18) is published alongside the new version.
 */
data class ApiVersion(val value: String) {
    init {
        require(value.isNotBlank()) {
            "ApiVersion must not be blank"
        }
        // The VSL uses `elysium.vehicle/v1` style semver
        // strings. The compiler validates the shape; the
        // migration is the responsibility of the upgrade
        // tool.
        require(API_VERSION_PATTERN_REGEX.matches(value)) {
            "ApiVersion must match $API_VERSION_PATTERN, got: $value"
        }
    }

    companion object {
        const val API_VERSION_PATTERN: String = "^elysium\\.vehicle/v\\d+(\\.\\d+)?$"

        // The regex is declared first because `V1` uses it in
        // its initializer. Declaring `V1` before the regex
        // would crash with `ExceptionInInitializerError`
        // (the regex would be null when `V1` is constructed).
        private val API_VERSION_PATTERN_REGEX = Regex(API_VERSION_PATTERN)

        val V1: ApiVersion = ApiVersion("elysium.vehicle/v1")

        fun isValid(value: String): Boolean = API_VERSION_PATTERN_REGEX.matches(value)
    }
}

/**
 * The spec metadata. The `projectId` ties the spec to a
 * `Project` aggregate; the `revision` is the human-visible
 * revision number (separate from the compiler's content
 * hash).
 */
data class SpecMetadata(
    val projectId: ProjectId,
    val revision: Int,
) {
    init {
        require(revision >= 1) {
            "SpecMetadata.revision must be >= 1, got $revision"
        }
    }

    fun canonicalForm(): String = "metadata:projectId=${projectId.value}|revision=$revision"
}

/**
 * The spec classification. The `representationLevel` is the
 * platform's truth-model declaration (per `.ai/STANDARDS.md`
 * section 4 + skill 04 section 17).
 */
data class SpecClassification(
    val representationLevel: RepresentationLevel,
) {
    init {
        // Per `.ai/STANDARDS.md` section 4 + skill 04 section 17:
        // a `vehicle` without a `representationLevel` is rejected.
        // The UNKNOWN sentinel is the "level not set" indicator.
        require(representationLevel != RepresentationLevel.UNKNOWN) {
            "SpecClassification.representationLevel must be set; " +
                "a vehicle without a representationLevel is rejected"
        }
    }

    fun canonicalForm(): String =
        "classification:level=${representationLevel.name}"
}

// ============================================================
// Body
// ============================================================

data class Body(
    val architecture: BodyArchitecture,
    val doors: Int,
    val seats: Int,
    val wheelbase: UnitValue.Length,
) {
    init {
        require(doors in ALLOWED_DOORS) {
            "Body.doors must be one of $ALLOWED_DOORS, got $doors"
        }
        require(seats in ALLOWED_SEATS_RANGE) {
            "Body.seats must be in $ALLOWED_SEATS_RANGE, got $seats"
        }
        require(wheelbase.value > 0) {
            "Body.wheelbase must be positive, got ${wheelbase.value}"
        }
    }

    fun canonicalForm(): String = buildString {
        append("body:architecture=").append(architecture.name)
        append("|doors=").append(doors)
        append("|seats=").append(seats)
        append("|wheelbase=").append(wheelbase.canonicalForm())
    }

    companion object {
        val ALLOWED_DOORS: Set<Int> = setOf(2, 3, 4, 5)
        val ALLOWED_SEATS_RANGE: IntRange = 1..9
    }
}

enum class BodyArchitecture {
    SEDAN,
    COUPE,
    HATCHBACK,
    WAGON,
    SUV,
    CROSSOVER,
    PICKUP,
    VAN,
    ROADSTER,
}

// ============================================================
// Propulsion
// ============================================================

data class Propulsion(
    val energySource: EnergySource,
    val engine: Engine,
) {
    init {
        // For an `ELECTRIC` energy source, the engine
        // configuration MUST be `ELECTRIC_NONE` and the
        // displacement MUST be zero. (The ICE engine is
        // replaced by an electric motor + battery pack,
        // which are declared in the `electrical` block —
        // Phase 3.)
        if (energySource == EnergySource.ELECTRIC) {
            require(engine.configuration == EngineConfiguration.ELECTRIC_NONE) {
                "Propulsion.energySource is ELECTRIC but Propulsion.engine.configuration " +
                    "is ${engine.configuration.name}; expected ELECTRIC_NONE"
            }
            require(engine.displacement.value == 0.0) {
                "Propulsion.energySource is ELECTRIC but Propulsion.engine.displacement " +
                    "is ${engine.displacement.value} ${engine.displacement.unit.name}; " +
                    "expected zero"
            }
        }
    }

    fun canonicalForm(): String = buildString {
        append("propulsion:energySource=").append(energySource.name)
        append("|engine=").append(engine.canonicalForm())
    }
}

enum class EnergySource {
    GASOLINE,
    DIESEL,
    ELECTRIC,
    HYDROGEN,
    HYBRID,
    PLUG_IN_HYBRID,
    CNG,
    LPG,
}

data class Engine(
    val configuration: EngineConfiguration,
    val displacement: UnitValue.Volume,
    val orientation: EngineOrientation,
) {
    fun canonicalForm(): String = buildString {
        append("configuration=").append(configuration.name)
        append("|displacement=").append(displacement.canonicalForm())
        append("|orientation=").append(orientation.name)
    }
}

enum class EngineConfiguration {
    INLINE_3,
    INLINE_4,
    INLINE_5,
    INLINE_6,
    V6,
    V8,
    V10,
    V12,
    BOXER_4,
    BOXER_6,
    ROTARY,
    WANKEL,
    ELECTRIC_NONE,
}

enum class EngineOrientation {
    TRANSVERSE,
    LONGITUDINAL,
}

// ============================================================
// Driveline
// ============================================================

data class Driveline(
    val traction: Traction,
    val transmission: Transmission,
) {
    fun canonicalForm(): String = buildString {
        append("driveline:traction=").append(traction.name)
        append("|transmission=").append(transmission.name)
    }
}

enum class Traction {
    FWD,   // front-wheel drive
    RWD,   // rear-wheel drive
    AWD,   // all-wheel drive
    QUAD,  // four-motor independent
}

enum class Transmission {
    MANUAL_5,
    MANUAL_6,
    AUTOMATIC_6,
    AUTOMATIC_8,
    AUTOMATIC_9,
    AUTOMATIC_10,
    DCT_6,
    DCT_7,
    DCT_8,
    CVT,
    SINGLE_SPEED,   // most EVs
    TWO_SPEED,      // high-performance EVs
}

// ============================================================
// Helper: build a valid spec + fail with a typed error
// ============================================================

/**
 * Build a `CompiledVehicleSpec` with the given parameters.
 * The function returns a `Result<CompiledVehicleSpec, FoundryError>`
 * so the parser can pattern-match on the typed error envelope
 * (per `.ai/STANDARDS.md` 7).
 *
 * The function is **total**: every input that passes the
 * data-class `init` checks returns a `Result.success`; an
 * ill-formed input is caught at the data-class boundary
 * and surfaced as a `Result.failure`.
 */
fun buildSpec(
    apiVersion: ApiVersion,
    metadata: SpecMetadata,
    classification: SpecClassification,
    body: Body,
    propulsion: Propulsion,
    driveline: Driveline,
): Result<CompiledVehicleSpec> = try {
    Result.success(
        CompiledVehicleSpec(
            apiVersion = apiVersion,
            metadata = metadata,
            classification = classification,
            body = body,
            propulsion = propulsion,
            driveline = driveline,
        ),
    )
} catch (e: IllegalArgumentException) {
    Result.failure(
        FoundryError.VehicleDefinitionInvalid(
            field = "CompiledVehicleSpec",
            reason = e.message ?: "unknown",
        ),
    )
}
