# Vehicle Spec Language (VSL) — Grammar

> **Status:** Phase F2 (I-2.1 + I-2.3) — the grammar + the typed schema.
> **Owner skill:** [skill 04 — Vehicle DSL and Compiler](../../.ai/skills/04-vehicle-dsl-compiler/SKILL.md).
> **Format:** EBNF (W3C-style). The grammar is the source of truth for
> both the text surface (YAML/JSON) and the visual AST.

---

## 0. Why a grammar

The VSL is the user-facing surface for "build me a vehicle". A
grammar is the **machine-readable contract** that:

- Drives the parser (the lexer + the syntax tree).
- Drives the editor (syntax highlighting, autocomplete, go-to-definition).
- Drives the validator (per-step invariant checks).
- Drives the golden tests (byte-identical output on the same input).

The grammar is **one language with N surfaces** (per skill 04
section 20 — "two DSLs for the same concept" is a contract
violation). The text surface is YAML; the visual surface is a
typed AST; the storage surface is the canonical form of the
`CompiledVehicleSpec`.

---

## 1. Top-level document

A VSL document is a `VehicleDefinition` (the input) or a
`CompiledVehicleSpec` (the output). The grammar is the same for
both surfaces — the only difference is the source.

```ebnf
document        = spec_metadata spec_classification body propulsion driveline ;

spec_metadata   = "metadata" "{" project_id revision "}" ;
project_id      = "projectId" ":" string_literal ;
revision        = "revision" ":" integer ;

spec_classification
                = "classification" "{" representation_level "}" ;
representation_level
                = "representationLevel" ":" representation_level_enum ;
representation_level_enum
                = "OEM_EXACT"
                | "OEM_PARTIAL"
                | "PARAMETRIC_FUNCTIONAL"
                | "CONCEPTUAL"
                | "VISUAL_ONLY" ;
```

The `representationLevel` is **required** (per `.ai/STANDARDS.md`
section 4 + skill 04 section 17). A missing or unknown level is
a compile error.

---

## 2. Body

The `body` is the vehicle's physical envelope. The body is a
typed structure (NOT a flat map of `key=value` pairs).

```ebnf
body            = "body" "{" architecture doors seats wheelbase "}" ;
architecture    = "architecture" ":" architecture_enum ;
architecture_enum
                = "SEDAN"
                | "COUPE"
                | "HATCHBACK"
                | "WAGON"
                | "SUV"
                | "CROSSOVER"
                | "PICKUP"
                | "VAN"
                | "ROADSTER" ;
doors           = "doors" ":" integer ( 2 | 3 | 4 | 5 ) ;
seats           = "seats" ":" integer ( 1 .. 9 ) ;
wheelbase       = "wheelbase" ":" length_value ;
```

Every numeric value with a unit is a **typed `UnitValue`** (per
skill 04 section 6.4 — unit normalization is step 4 of the
pipeline). A value without a unit is a typed
`VehicleDefinitionInvalid` error.

---

## 3. Propulsion

The `propulsion` is the energy source + the engine. The
propulsion is required for a non-`VISUAL_ONLY` spec; a
`VISUAL_ONLY` spec may omit the propulsion (no engineering
data, just a visual surface).

```ebnf
propulsion      = "propulsion" "{" energy_source engine "}" ;
energy_source   = "energySource" ":" energy_source_enum ;
energy_source_enum
                = "GASOLINE"
                | "DIESEL"
                | "ELECTRIC"
                | "HYDROGEN"
                | "HYBRID"
                | "PLUG_IN_HYBRID"
                | "CNG"
                | "LPG" ;

engine          = "engine" "{" configuration displacement orientation "}" ;
configuration   = "configuration" ":" engine_configuration_enum ;
engine_configuration_enum
                = "INLINE_3"
                | "INLINE_4"
                | "INLINE_5"
                | "INLINE_6"
                | "V6"
                | "V8"
                | "V10"
                | "V12"
                | "BOXER_4"
                | "BOXER_6"
                | "ROTARY"
                | "WANKEL"
                | "ELECTRIC_NONE" ;   (* no engine; pure EV *)
displacement    = "displacement" ":" volume_value ;
orientation     = "orientation" ":" engine_orientation_enum ;
engine_orientation_enum
                = "TRANSVERSE"
                | "LONGITUDINAL" ;
```

For an `ELECTRIC` energy source, the `engine.configuration` MUST
be `ELECTRIC_NONE` and the `displacement` MUST be `zero` (the
ICE engine is replaced by an electric motor + battery pack, which
are declared in the `electrical` block — Phase 3).

---

## 4. Driveline

The `driveline` is the traction + the transmission.

```ebnf
driveline       = "driveline" "{" traction transmission "}" ;
traction        = "traction" ":" traction_enum ;
traction_enum   = "FWD"     (* front-wheel drive *)
                | "RWD"     (* rear-wheel drive *)
                | "AWD"     (* all-wheel drive *)
                | "QUAD" ;  (* four-motor independent *)

transmission    = "transmission" ":" transmission_enum ;
transmission_enum
                = "MANUAL_5"
                | "MANUAL_6"
                | "AUTOMATIC_6"
                | "AUTOMATIC_8"
                | "AUTOMATIC_9"
                | "AUTOMATIC_10"
                | "DCT_6"
                | "DCT_7"
                | "DCT_8"
                | "CVT"
                | "SINGLE_SPEED"   (* most EVs *)
                | "TWO_SPEED" ;    (* high-performance EVs *)
```

---

## 5. Typed unit values

Every numeric value with a physical dimension is a typed
`UnitValue` with an explicit unit. The unit is normalized to SI
in step 4 of the pipeline.

```ebnf
length_value    = "{" "value" ":" decimal_literal "unit" ":" length_unit "}" ;
volume_value    = "{" "value" ":" decimal_literal "unit" ":" volume_unit "}" ;
mass_value      = "{" "value" ":" decimal_literal "unit" ":" mass_unit "}" ;
energy_value    = "{" "value" ":" decimal_literal "unit" ":" energy_unit "}" ;
power_value     = "{" "value" ":" decimal_literal "unit" ":" power_unit "}" ;
speed_value     = "{" "value" ":" decimal_literal "unit" ":" speed_unit "}" ;

length_unit     = "METER" | "CENTIMETER" | "MILLIMETER" | "INCH" | "FOOT" ;
volume_unit     = "LITER" | "CUBIC_CENTIMETER" | "CUBIC_INCH" ;
mass_unit       = "KILOGRAM" | "GRAM" | "POUND" | "OUNCE" ;
energy_unit     = "KILOWATT_HOUR" | "MEGAJOULE" | "BTU" | "WATT_HOUR" ;
power_unit      = "KILOWATT" | "MEGAWATT" | "HORSEPOWER" ;
speed_unit      = "KILOMETER_PER_HOUR" | "METER_PER_SECOND" | "MILE_PER_HOUR" ;
```

A value with a non-finite (`NaN` / `±Infinity`) or a unit that
the units library does not recognize is a typed compile error
(per skill 04 section 25 — security).

---

## 6. Identifier

A `string_literal` is a quoted UTF-8 string. An `integer` is a
signed 64-bit integer. A `decimal_literal` is an IEEE-754
double. A `boolean` is `true` or `false`.

```ebnf
string_literal  = '"' character* '"' ;
integer         = "-"? digit+ ;
decimal_literal = "-"? digit+ ( "." digit+ )? ;
boolean         = "true" | "false" ;
```

---

## 7. Example document

The example below is the canonical "Urban One" spec — a compact
electric vehicle. The document is the **input** to the compiler;
the compiler produces the `CompiledVehicleSpec` (the **output**)
by:

1. Lexing / parsing the YAML → syntax tree.
2. Schema validation (every field is a known type).
3. Unit normalization (every value with a unit → SI).
4. Alias resolution (every alias → canonical name).
5. Applicability resolution (every part → applicable check).
6. ... (steps 7-18 per skill 04 section 6).
7. Content-hashing the canonical form → `Spec.Artifact.id`.

```yaml
apiVersion: elysium.vehicle/v1
kind: VehicleDefinition

metadata:
  projectId: PROJECT-001
  revision: 1

classification:
  representationLevel: PARAMETRIC_FUNCTIONAL

body:
  architecture: HATCHBACK
  doors: 5
  seats: 5
  wheelbase:
    value: 2.45
    unit: METER

propulsion:
  energySource: ELECTRIC
  engine:
    configuration: ELECTRIC_NONE
    displacement:
      value: 0
      unit: LITER
    orientation: LONGITUDINAL

driveline:
  traction: FWD
  transmission: SINGLE_SPEED
```

---

## 8. Forbidden patterns

Per skill 04 section 20:

- **Two DSLs for the same concept.** A "visual DSL" + a "text
  DSL" + a "JSON spec" + a "YAML spec" is four ways to say the
  same thing. The DSL is one language with N surfaces.
- **Untyped spec output.** A `Spec.Artifact` is a typed value,
  not a `Map<String, Any>`. Every field has a type.
- **DSL strings as identifiers.** A `Part` referenced by `name`
  is a smell. The DSL uses typed IDs.
- **A `vehicle` without a `representationLevel`.** A missing
  level is a compile error.
- **A raw scalar where a fact is required.** A
  `part.capacity = 75` is rejected (per `.ai/STANDARDS.md`
  section 3).

---

## 9. Cross-references

- **Skill 04 (compiler):** [`.ai/skills/04-vehicle-dsl-compiler/SKILL.md`](../../.ai/skills/04-vehicle-dsl-compiler/SKILL.md)
- **Skill 03 (ontology):** [`.ai/skills/03-vehicle-domain-ontology/SKILL.md`](../../.ai/skills/03-vehicle-domain-ontology/SKILL.md)
- **Standards (truth model):** `.ai/STANDARDS.md` section 2 + 3
- **AGENTS (artifact contract):** `.ai/AGENTS.md` section 12
- **Foundry docs:** `docs/foundry/`
- **Roadmap (Phase F2):** `docs/foundry/implementation-roadmap.md`
