package com.elysium.vanguard.foundry.core.dsl.schema

import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError

/**
 * Typed physical-unit values for the Vehicle Spec Language (VSL).
 *
 * Per `.ai/skills/04-vehicle-dsl-compiler/SKILL.md` section 5 +
 * section 6 step 4 (unit normalization) + section 25 (security):
 *   - Every numeric value with a physical dimension is a typed
 *     `UnitValue` with an explicit unit.
 *   - A value without a unit is a typed `VehicleDefinitionInvalid`
 *     error (a raw scalar is rejected per `.ai/STANDARDS.md` 3).
 *   - The unit is normalized to SI in the canonical form (a
 *     `wheelbase: 2.45 METER` is serialized as
 *     `wheelbase: 2.45 METER` — the value is preserved; the
 *     compiler does NOT silently convert units).
 *   - A non-finite value (`NaN` / `±Infinity`) is rejected at
 *     construction.
 *
 * The `UnitValue` is a **sealed family**; the consumer pattern-
 * matches on the variant. The variants share a common
 * `value + unit` shape + a `canonicalForm()` method that
 * produces a deterministic UTF-8 byte sequence.
 *
 * Why per-dimension types: the compiler validates that a
 * `Length` is paired with a length unit (e.g. `METER`), not a
 * mass unit (e.g. `KILOGRAM`). A `Wheelbase: 1500 KILOGRAM` is
 * a compile error — the type system catches it at the boundary.
 */
sealed class UnitValue {
    abstract val value: Double
    abstract fun unitName(): String
    abstract fun canonicalForm(): String

    /**
     * Assert the value is finite. A non-finite value is a typed
     * `CompilationNonDeterministic` error (per skill 04 section 25).
     *
     * **Note** (Phase F2 lesson): the parent's `init` block runs
     * BEFORE the child's `value` is set, so the check would always
     * see `0.0`. The check is therefore inlined into each child's
     * `init` block, called via `requireFinite(value)`.
     */
    companion object {
        fun requireFinite(value: Double) {
            if (value.isNaN() || value.isInfinite()) {
                throw FoundryError.CompilationNonDeterministic(
                    reason = "UnitValue must be finite, got $value",
                )
            }
        }
    }

    data class Length(
        override val value: Double,
        val unit: LengthUnit,
    ) : UnitValue() {
        init {
            requireFinite(value)
        }
        override fun unitName(): String = unit.name
        override fun canonicalForm(): String = "length:${value}|unit:${unit.name}"
    }

    data class Volume(
        override val value: Double,
        val unit: VolumeUnit,
    ) : UnitValue() {
        init {
            requireFinite(value)
        }
        override fun unitName(): String = unit.name
        override fun canonicalForm(): String = "volume:${value}|unit:${unit.name}"
    }

    data class Mass(
        override val value: Double,
        val unit: MassUnit,
    ) : UnitValue() {
        init {
            requireFinite(value)
        }
        override fun unitName(): String = unit.name
        override fun canonicalForm(): String = "mass:${value}|unit:${unit.name}"
    }

    data class Energy(
        override val value: Double,
        val unit: EnergyUnit,
    ) : UnitValue() {
        init {
            requireFinite(value)
        }
        override fun unitName(): String = unit.name
        override fun canonicalForm(): String = "energy:${value}|unit:${unit.name}"
    }

    data class Power(
        override val value: Double,
        val unit: PowerUnit,
    ) : UnitValue() {
        init {
            requireFinite(value)
        }
        override fun unitName(): String = unit.name
        override fun canonicalForm(): String = "power:${value}|unit:${unit.name}"
    }

    data class Speed(
        override val value: Double,
        val unit: SpeedUnit,
    ) : UnitValue() {
        init {
            requireFinite(value)
        }
        override fun unitName(): String = unit.name
        override fun canonicalForm(): String = "speed:${value}|unit:${unit.name}"
    }
}

/**
 * Length units. The canonical SI unit is `METER`. Other
 * units are converted at the boundary (per skill 04 section 6
 * step 4) or preserved for the human-facing display.
 */
enum class LengthUnit {
    METER,
    CENTIMETER,
    MILLIMETER,
    INCH,
    FOOT,
}

/**
 * Volume units. The canonical SI unit is `CUBIC_METER` (not
 * listed because the VSL uses `LITER` for engine displacement
 * — a derivative unit; 1 LITER = 0.001 CUBIC_METER).
 */
enum class VolumeUnit {
    LITER,
    CUBIC_CENTIMETER,
    CUBIC_INCH,
}

/**
 * Mass units. The canonical SI unit is `KILOGRAM`.
 */
enum class MassUnit {
    KILOGRAM,
    GRAM,
    POUND,
    OUNCE,
}

/**
 * Energy units. The canonical SI unit is `JOULE` (not listed
 * because the VSL uses `KILOWATT_HOUR` for battery capacity —
 * 1 kWh = 3,600,000 J).
 */
enum class EnergyUnit {
    KILOWATT_HOUR,
    MEGAJOULE,
    BTU,
    WATT_HOUR,
}

/**
 * Power units. The canonical SI unit is `WATT` (not listed
 * because the VSL uses `KILOWATT` for motor power —
 * 1 kW = 1000 W).
 */
enum class PowerUnit {
    KILOWATT,
    MEGAWATT,
    HORSEPOWER,
}

/**
 * Speed units. The canonical SI unit is `METER_PER_SECOND`
 * (not listed because the VSL uses `KILOMETER_PER_HOUR` —
 * 1 m/s = 3.6 km/h).
 */
enum class SpeedUnit {
    KILOMETER_PER_HOUR,
    METER_PER_SECOND,
    MILE_PER_HOUR,
}
