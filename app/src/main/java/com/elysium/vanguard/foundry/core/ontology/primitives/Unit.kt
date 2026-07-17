package com.elysium.vanguard.foundry.core.ontology.primitives

import java.math.BigDecimal

/**
 * The platform's engineering unit system. Phase 1 supports the SI base
 * + the most common derived units. Phase 2 expands the taxonomy to
 * cover the full set required by the 3D pipeline (torque, pressure,
 * energy density, etc.) per `.ai/skills/03-vehicle-domain-ontology/
 * SKILL.md` section 1.
 *
 * Why a typed `Unit` and not a raw `String`: a `Map<String, Double>`
 * is forbidden (per `.ai/AGENTS.md` 24.1); an engineering value is
 * `{ value: BigDecimal, unit: Unit }` — both typed. The `Unit`
 * is locale-aware for display (per `.ai/STANDARDS.md` 24.2) but the
 * canonical storage is always SI.
 */
enum class Unit(
    val symbol: String,
    val siConversionFactor: BigDecimal,
) {
    // Base SI units (factor = 1.0)
    METER("m", BigDecimal.ONE),
    KILOGRAM("kg", BigDecimal.ONE),
    SECOND("s", BigDecimal.ONE),
    AMPERE("A", BigDecimal.ONE),
    KELVIN("K", BigDecimal.ONE),
    MOLE("mol", BigDecimal.ONE),
    CANDELA("cd", BigDecimal.ONE),

    // Common derived units (factor = 1.0, SI definition)
    NEWTON("N", BigDecimal.ONE),
    PASCAL("Pa", BigDecimal.ONE),
    JOULE("J", BigDecimal.ONE),
    WATT("W", BigDecimal.ONE),
    VOLT("V", BigDecimal.ONE),
    OHM("Ω", BigDecimal.ONE),
    HERTZ("Hz", BigDecimal.ONE),

    // Common imperial / US customary conversions (to SI base)
    MILLIMETER("mm", BigDecimal("0.001")),
    CENTIMETER("cm", BigDecimal("0.01")),
    KILOMETER("km", BigDecimal("1000")),
    INCH("in", BigDecimal("0.0254")),
    FOOT("ft", BigDecimal("0.3048")),
    MILE("mi", BigDecimal("1609.344")),
    POUND("lb", BigDecimal("0.45359237")),
    KILOWATT("kW", BigDecimal("1000")),
    MEGAWATT("MW", BigDecimal("1000000")),
    KILOWATT_HOUR("kWh", BigDecimal("3600000")),
    LITER("L", BigDecimal("0.001")),
    GALLON_US("gal", BigDecimal("0.003785411784")),
    DEGREE_CELSIUS("°C", BigDecimal.ONE), // Affine conversion — Phase 2
    DEGREE_FAHRENHEIT("°F", BigDecimal.ONE), // Affine conversion — Phase 2
    ;

    /**
     * Convert a value in this unit to the SI base for the same dimension.
     * For affine units (°C, °F) the conversion is documented in
     * Phase 2; for now the conversion is identity (the platform's
     * engineering data is always SI-stored).
     */
    fun toSiBase(value: BigDecimal): BigDecimal =
        value.multiply(siConversionFactor)
}
