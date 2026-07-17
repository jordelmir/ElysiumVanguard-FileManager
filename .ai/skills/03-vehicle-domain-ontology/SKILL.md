---
name: vehicle-domain-ontology
description: The single source of truth for vehicle, brand, project, part, and assembly domain types. Every other skill that touches domain data consumes from this one. The ontology is the API; the database is an implementation detail.
---

# Skill 03 — Vehicle Domain Ontology

## 1. Mission

Define and maintain the **single source of truth**
for the vehicle, brand, project, part, and
assembly domain types. Every other skill that
touches domain data — the DSL compiler (skill 04),
the 3D pipeline (skill 06), the digital twin
(skill 07), the marketplace (skill 10), the mobile
UX (skill 11) — consumes from this one.

The ontology is the **API** of the domain. The
database is an implementation detail. The mobile
local DB is an implementation detail. The cloud
event store is an implementation detail. The
ontology is the contract; the rest is plumbing.

## 2. In-scope

- Defining the domain types: `Brand`, `Project`,
  `Vehicle`, `Subsystem`, `Part`, `Assembly`,
  `Revision`, `Variant`, `Compatibility`,
  `Diagnostic`, `Fault`, `RepairAction`,
  `Authorship`, `Contribution`, `RoyaltyContract`,
  `License`, `Listing`, `Order`, `Escrow`,
  `Settlement`.
- Defining the **mandatory core entities**
  (the platform's canonical automotive
  taxonomy; the ontology is the shared
  kernel between every bounded context,
  per `docs/architecture/domain-map.md`):
  - `VehicleProgram` — the strategic
    container for a vehicle line (a brand
    decides to make a vehicle; the
    VehicleProgram is the result).
  - `VehicleRevision` — an immutable
    project revision of a vehicle. Every
    change creates a new revision; the
    previous revision is preserved.
  - `VehicleConfiguration` — a configured
    instance of a `VehicleRevision` (a
    specific battery + motor + chassis
    combo selected by a user).
  - `Platform` — a shared mechanical /
    electrical / software architecture
    that multiple vehicle programs
    inherit (a skateboard platform that
    multiple vehicle bodies use).
  - `BodyArchitecture` — the body + chassis
    + exterior form. A `Platform` has a
    `BodyArchitecture` (or several).
  - The full core entity list (per
    section 2.2) extends the platform
    across gasoline, diesel, hybrid,
    electric, hydrogen, motorcycles,
    commercial vehicles, and future
    mobility categories without
    duplicating foundational concepts.
- Defining the **vehicle representation types**
  (the platform's "what kind of truth is this
  vehicle?" answer):
  - `VehicleRepresentationLevel` — the enum
    (`OEM_EXACT`, `OEM_PARTIAL`,
    `PARAMETRIC_FUNCTIONAL`, `CONCEPTUAL`,
    `VISUAL_ONLY`). Owned here, on the `Vehicle`
    aggregate. Append-only transitions.
  - `EngineeringFact<T>` — the value class
    that wraps every engineering value with
    its provenance. The shape is mirrored in
    every language the platform uses.
  - `VerificationStatus` — the enum
    (`OEM_VERIFIED`, `REGULATORY_VERIFIED`,
    `LAB_VERIFIED`, `ENGINEER_REVIEWED`,
    `COMMUNITY_CORROBORATED`, `AI_INFERRED`,
    `UNKNOWN`).
  - `SourceType` — the enum
    (`OEM_DOC`, `REGULATORY_FILING`,
    `LAB_REPORT`, `ENGINEER_MEMO`,
    `TELEMETRY`, `AI_INFERENCE`,
    `USER_INPUT`, `COMMUNITY`).
- Defining the strongly-typed IDs:
  `BrandId`, `ProjectId`, `VehicleId`,
  `SubsystemId`, `PartId`, `AssemblyId`,
  `RevisionId`, `VariantId`, `OrderId`, etc.
- Defining the relations: a `Vehicle` HAS-MANY
  `Subsystem`; a `Subsystem` HAS-MANY `Part`; a
  `Part` HAS-MANY `Revision`; a `Project` OWNS
  `Authorship`; a `Vehicle` HAS-ONE
  `VehicleRepresentationLevel`; a `Part` HAS-MANY
  `EngineeringFact<T>`; etc.
- Defining the invariants: a `Part` MUST have at
  least one `Revision`; a `Revision` MUST be
  signed by an `Authorship`; a `RoyaltyContract`
  MUST reference at least one `Contribution`;
  a `Vehicle` MUST declare a
  `VehicleRepresentationLevel`; an
  `EngineeringFact` MUST have a non-empty
  `Source`, `Source type`, `Vehicle applicability`,
  `Revision`, `Confidence`, `Verification status`,
  and `Timestamp`; etc.
- Maintaining the schema version. Every ontology
  change is a versioned migration. Consumers
  MUST refuse a schema version they do not
  recognize.
- Documenting the cardinality of every relation
  (1:1, 1:N, N:M) and the cascade rules
  (delete, archive, anonymize).

## 3. Out-of-scope

- The storage layer (skill 08 owns the event
  store; the mobile skill owns the local DB).
- The DSL syntax (skill 04).
- The 3D model metadata (skill 06).
- The diagnostic fault model (skill 07).
- The royalty calculation (skill 09).

The ontology says "a `Part` HAS-MANY `Revision`".
The DSL says "use the `part` keyword to add a
part". The 3D pipeline says "a `Revision` is
versioned by content hash". The diagnostic
pipeline says "a `Fault` references a `Part`".
Each is its own concern; the ontology is the
glue.

## 4. Inputs

- PRDs from skill 02. The ontology answers "what
  types does the PRD need?".
- ADRs from the orchestrator. The ontology
  answers "what types does the ADR commit us
  to?".
- Existing schema versions. The ontology MUST be
  backward-compatible (or filed as a breaking
  change with a migration plan).
- Regulatory scope from skill 13. The ontology
  answers "what types does the regulation
  require?" (e.g. UN R156 requires a software
  bill of materials; the ontology MUST have a
  `SoftwareComponent` type).

## 5. Outputs

- `docs/ontology/<version>/types.md` — the
  current type definitions. Versioned.
- `docs/ontology/<version>/relations.md` — the
  relations, with cardinality and cascade.
- `docs/ontology/<version>/invariants.md` — the
  invariants.
- `docs/ontology/<version>/ids.md` — the
  strongly-typed IDs.
- `docs/ontology/<version>/changelog.md` — the
  version-to-version delta.
- `docs/ontology/index.md` — the version picker.
  Always points to the latest non-deprecated
  version.

In the codebase, the types are mirrored as:

- `domain/types/Vehicle.kt` (etc.) — pure data
  classes. No behavior, no framework deps.
- `domain/ids/BrandId.kt` (etc.) — value classes
  wrapping `Uuid` or `ULong`. No behavior.
- `domain/invariants/VehicleInvariants.kt` (etc.)
  — pure functions that take a domain object
  and return `Result<Unit>` or a list of
  violations.
- `domain/migrations/<from>_<to>.kt` — pure
  functions that take a `JsonNode` of the old
  schema and return a `JsonNode` of the new
  schema. Idempotent. Testable on a fixture.

The codebase mirror is the **only** place the
types are defined. The docs are documentation
of the codebase, not the source of truth.

In addition, the following are the canonical
shapes for the provenance-bearing types. Every
language the platform uses mirrors these:

```kotlin
// The Vehicle's "what kind of truth is this?" enum.
// Append-only. The transition is signed.
enum class VehicleRepresentationLevel {
    OEM_EXACT,
    OEM_PARTIAL,
    PARAMETRIC_FUNCTIONAL,
    CONCEPTUAL,
    VISUAL_ONLY
}

// Where the fact came from. Required on every fact.
enum class SourceType {
    OEM_DOC,
    REGULATORY_FILING,
    LAB_REPORT,
    ENGINEER_MEMO,
    TELEMETRY,
    AI_INFERENCE,
    USER_INPUT,
    COMMUNITY
}

// The fact's verification status. AI_INFERRED
// MUST NOT silently become VERIFIED — the
// transition is a human review + a signed
// counter-signature.
enum class VerificationStatus {
    OEM_VERIFIED,
    REGULATORY_VERIFIED,
    LAB_VERIFIED,
    ENGINEER_REVIEWED,
    COMMUNITY_CORROBORATED,
    AI_INFERRED,
    UNKNOWN
}

// The provenance-bearing value wrapper. Every
// engineering fact in the platform is an
// EngineeringFact<T>. A fact without all
// required fields is rejected at the invariant
// check (skill 14).
data class EngineeringFact<T>(
    val value: T,
    val source: String,
    val sourceType: SourceType,
    val jurisdiction: String? = null,
    val vehicleApplicability: String,
    val revision: String,
    val confidence: Float,                 // [0.0, 1.0]
    val verificationStatus: VerificationStatus,
    val reviewer: String? = null,
    val timestamp: String                  // ISO-8601
)
```

The `Vehicle` aggregate carries the level:

```kotlin
data class Vehicle(
    val id: VehicleId,
    val brandId: BrandId,
    val projectId: ProjectId,
    val name: String,
    val representationLevel: VehicleRepresentationLevel,  // REQUIRED
    val currentRevision: RevisionId,
    val createdAt: String,
    val updatedAt: String
)
```

A `Vehicle` without `representationLevel` is a
**contract violation**. The DSL compiler (skill
04) refuses to emit a `Spec.Artifact` without a
level; the verifier (skill 14) rejects the PR.

The full standard — the meaning of each level,
the transition protocol, the UI requirement, the
ineligibility for marketplace / royalty /
regulatory / diagnostic — is in
[`.ai/STANDARDS.md`](../../STANDARDS.md) section 4.

## 6. Workflow

1. **Receive a request.** From the orchestrator
   (or from a skill that needs a new type). The
   request describes the concept in domain
   language: "we need a `Subscription` to model
   the user's monthly access to the AI council".
2. **Check for collisions.** Search the existing
   ontology for any similar concept. A new type
   that overlaps an existing one is a smell.
3. **Define the type.** Write the data class
   first. The data class is the spec.
4. **Define the ID.** A strongly-typed ID
   (`typealias SubscriptionId = Uuid` + a marker
   data class, or a `@JvmInline value class`).
5. **Define the relations.** What does
   `Subscription` relate to? `User`? `Brand`?
   `Plan`?
6. **Define the invariants.** What must always
   be true? E.g. "a `Subscription` MUST have a
   non-empty `Plan` and a non-empty
   `validUntil`". A `Vehicle` MUST have a
   `representationLevel`; every `Part` field of
   type `T` that is engineering-grade MUST be
   wrapped in `EngineeringFact<T>` with all
   required metadata.
7. **Define the schema version.** The new
   ontology version is `MAJOR.MINOR`. A new
   type is a MINOR bump; a breaking change to an
   existing type is a MAJOR bump. A new
   representation level or a new verification
   status is a MAJOR bump.
8. **Write the migration.** The migration is a
   pure function `(old_schema: JsonNode) ->
   new_schema: JsonNode`. The migration is
   idempotent (running it twice produces the
   same result). A migration that downgrades a
   `VehicleRepresentationLevel` is a violation.
9. **File the ontology ADR.** The ADR
   documents: the new type, the new ID, the
   relations, the invariants, the schema
   version, the migration plan, the
   alternative considered, the consumer
   impact.
10. **Emit the docs.** The 5 markdown files in
    `docs/ontology/<version>/`. Mirror the
    codebase.

## 7. Quality gates

- Every domain type has at least one test (a
  pure-function test of the invariants).
- Every domain type has an ID.
- Every relation has a cardinality + cascade.
- Every invariant has a test.
- Every schema version bump has a migration
  plan.
- The migration is idempotent (the test runs
  the migration twice on a fixture and asserts
  the second run is a no-op).
- The docs and the code are in sync. A drift
  between the docs and the code is a quality
  gate failure.
- Every `Vehicle` has a `representationLevel`.
  An invariant test rejects a `Vehicle`
  constructed without one.
- Every `EngineeringFact<T>` has all required
  metadata fields. An invariant test rejects
  a fact missing any field. The test is
  parameterized over each field.
- Every transition of
  `VehicleRepresentationLevel` is forward-only
  (`VISUAL_ONLY → CONCEPTUAL →
  PARAMETRIC_FUNCTIONAL → OEM_PARTIAL →
  OEM_EXACT`). A test rejects a regression.
- The AI council (skill 05) votes on every
  new `VehicleRepresentationLevel` value or
  every new `VerificationStatus` value.

## 8. Failure modes

- **The user wants a type that overlaps an
  existing one.** The skill escalates to the
  orchestrator. The orchestrator arbitrates
  between extending the existing type vs.
  introducing the new one.
- **The proposed invariant is unprovable.** The
  skill rewrites it as a testable invariant or
  surfaces the unprovability to the orchestrator.
- **The migration is lossy.** The skill surfaces
  the loss to the orchestrator. A lossy
  migration is an ADR + a user opt-in.
- **The schema version is bumped but the
  consumers are not updated.** The skill
  publishes a compatibility note + a
  deprecation schedule.

## 9. Coordination contract

- **Input from**: skill 02 (PRD), skill 00
  (orchestrator), skill 13 (regulatory).
- **Output to**: every other skill. The ontology
  is the contract; the others are consumers.
- **Triggered by**: a new PRD that needs a new
  type, a new ADR that commits the platform to a
  new type, a regulatory requirement.
- **Frequency**: a new version of the ontology
  per release. The docs + code + tests are
  versioned together.

## 10. Forbidden patterns

- **Two types for the same concept.** A
  `Vehicle` and a `Car` and a `Model` and a
  `Product` is four types for the same concept.
  The ontology picks one.
- **Conflating the canonical pairs.** Per
  section 12, a `PartDefinition` is not
  a `PartInstance`; an `AssemblyDefinition`
  is not an `AssemblyInstance`; a
  `GeometryAsset` is not a `PartDefinition`;
  a `VehicleDefinition` is not a
  `VehicleRevision`; a `VehicleDefinition`
  is not a `VehicleUnit`; a `DiagnosticTarget`
  is not a `DiagnosticableEntity`. A
  conflation is a contract violation; the
  verifier rejects the model.
- **Anemic IDs.** `Part` with a `partId: Long`
  is a smell. `Part` with a `id: PartId` is
  correct. The ID is a type, not a primitive.
- **Untyped relationships.** A `Map<String,
  Any>` field on a `Part` is a smell. The
  relationship is a typed field.
- **Mutable types.** A domain type is a data
  class with `val` fields. Mutable types are an
  implementation detail; the ontology is
  immutable.
- **"Soft" invariants.** "The `name` SHOULD be
  non-empty" is a smell. The invariant is
  "MUST be non-empty". A SHOULD is a lint rule;
  a MUST is a test.
- **Cross-cutting concerns in the domain.**
  Auth, validation, audit, telemetry — these
  are NOT in the domain. They live in the
  application layer (skill 08). The domain
  types are pure data.
- **Schema drift.** The docs say `X`; the code
  says `Y`. The orchestrator blocks release.
- **A `Vehicle` without a
  `representationLevel`.** Every `Vehicle`
  aggregate MUST carry a
  `VehicleRepresentationLevel`. A default
  value is a smell; the level is a deliberate
  declaration by the spec author.
- **A `Part` field of type `T` that is
  engineering-grade but not wrapped in
  `EngineeringFact<T>`.** The provenance is
  not optional. A raw `Float` for a torque
  value is a contract violation.
- **`AI_INFERRED` masquerading as `VERIFIED`.**
  A fact with `verificationStatus =
  AI_INFERRED` and a `confidence` of `1.0` is
  still `AI_INFERRED`. Confidence does not
  promote verification. The transition is a
  human review + a signed counter-signature
  (skill 09).
- **A `VehicleRepresentationLevel` regression.**
  A vehicle cannot move from `OEM_EXACT` to
  `VISUAL_ONLY`. The transition is append-only.
  A regression is a contract violation.

## 11. Anti-patterns in the wild

- **The "we'll just use a Map<String, Any>" repo.**
  The data is untyped, the queries are
  unsearchable, the migrations are a nightmare.
- **The "the database is the schema" repo.** The
  database is an implementation detail. The
  schema is in the code.
- **The "we have 5 versions of `User`" repo.**
  The drift is a feature, not a bug, until it
  is.
- **The "we added a column without a migration"
  repo.** The old rows are now wrong.
- **The "the foreign key is implicit" repo.** A
  relation without a typed field is a bug
  waiting to happen.

## 12. Core distinction (canonical separation)

**Never** conflate these pairs. A conflation
is a contract violation; the verifier
(skill 14) rejects the model. The pair is
the platform's "this is the design, this
is the artifact" split.

- **`PartDefinition`** vs **`PartInstance`.**
  A `PartDefinition` is the reusable
  technical definition ("a 75 kWh NMC
  prismatic battery pack with these
  dimensions + this chemistry + this
  BMS"). A `PartInstance` is a physical
  or digital occurrence in a specific
  vehicle ("the battery pack in VIN
  ABC123, manufactured on 2026-07-01").
  A `PartDefinition` is versioned; a
  `PartInstance` is manufactured.
- **`AssemblyDefinition`** vs
  **`AssemblyInstance`.** An
  `AssemblyDefinition` is the reusable
  composition ("the powertrain subsystem
  is composed of a motor + a battery
  pack + an inverter"). An
  `AssemblyInstance` is the installed
  composition ("the powertrain
  subsystem in VIN ABC123, with
  battery pack #B-2026-001").
- **`GeometryAsset`** vs **`PartDefinition`.**
  A `GeometryAsset` is the visual or
  engineering representation (a glTF, a
  STEP, a USD). A `PartDefinition` is
  the typed engineering contract that
  the geometry represents. The geometry
  may change without changing the
  `PartDefinition` (a new mesh); the
  `PartDefinition` may change without
  changing the geometry (a new
  chemistry).
- **`VehicleDefinition`** vs
  **`VehicleRevision`.** A
  `VehicleDefinition` is the
  configuration specification ("a
  2-seat, 200 km range EV under $15k").
  A `VehicleRevision` is an immutable
  project revision of that definition.
  Every change creates a new revision;
  the previous revision is preserved.
- **`VehicleDefinition`** vs **`VehicleUnit`.**
  A `VehicleDefinition` is the
  configuration specification. A
  `VehicleUnit` is a manufactured
  physical unit (a specific car with a
  specific VIN). A `VehicleDefinition`
  can produce N `VehicleUnit`s.
- **`DiagnosticTarget`** vs
  **`DiagnosticableEntity`.** A
  `DiagnosticTarget` is a component or
  function addressable by diagnosis
  (a battery cell, a motor winding,
  an ECU software module). A
  `DiagnosticableEntity` is the
  physical or digital entity the
  target refers to.

A pair that is conflated is a smell.
The ontology is the single source of
truth; the pair is the discipline.

## 13. Mandatory core entities (full list)

The platform's mandatory core entities
(per section 2) cover the **canonical
automotive taxonomy** + the **core
entity set** below. The ontology MUST
have a typed entity for every name in
this list. A missing entity is a
contract violation.

### 13.1 Powertrain and propulsion

- **`PropulsionArchitecture`.** The
  high-level strategy: ICE, BEV,
  HEV, PHEV, FCEV. A
  `VehicleDefinition` has exactly
  one `PropulsionArchitecture`.
- **`Powertrain`.** The aggregate
  that produces + delivers motive
  power. A `Powertrain` is composed
  of an `Engine` (or a `Motor` +
  a `BatteryPack`) + a
  `Transmission` + the
  `Driveline`.
- **`EnergyStorage`.** The energy
  reservoir. A `BatteryPack` (a
  kind of `EnergyStorage`) + a
  `FuelTank` (a kind of
  `EnergyStorage`) + a
  `HydrogenTank` (a kind of
  `EnergyStorage`).
- **`Transmission`.** The
  gear-reduction + the clutch (or
  the torque converter). A
  `ManualTransmission` + an
  `AutomaticTransmission` + a
  `DCT` + a `CVT` + a
  `SingleSpeedReducer` (for
  BEVs).

### 13.2 Chassis and dynamics

- **`Chassis`.** The structural
  frame. A `Unibody` + a
  `BodyOnFrame` + a
  `Spaceframe`.
- **`Suspension`.** The
  wheel-attachment + the
  damping. A `MacPhersonStrut` +
  a `DoubleWishbone` + a
  `MultiLink` + an `AirSuspension`
  + an `ActiveSuspension`.
- **`Steering`.** The
  direction-control. A
  `RackAndPinion` + a
  `RecirculatingBall` + a
  `SteerByWire`.
- **`BrakeSystem`.** The
  friction + the hydraulic (or
  electric). A `DiscBrake` + a
  `DrumBrake` + a
  `RegenerativeBrake` + an
  `ElectronicStabilityProgram`.
- **`ThermalSystem`.** The
  temperature-management. A
  `CoolingSystem` + a
  `HVAC` + a
  `BatteryThermalManagement`.

### 13.3 Electrical and electronics

- **`ElectricalArchitecture`.** The
  high-level strategy: 12V
  (legacy), 48V (mild hybrid),
  400V (modern BEV), 800V
  (next-gen BEV), zonal (modern
  domain controller). A
  `VehicleDefinition` has
  exactly one
  `ElectricalArchitecture`.
- **`ElectronicControlUnit`.** A
  domain controller (a `VCU`,
  a `BCM`, a `TCU`, a
  `BMS`, an `ADASController`,
  a `CockpitController`).
- **`NetworkBus`.** The
  communication fabric. A
  `CANBus` + a `LINBus` + a
  `FlexRayBus` + an
  `AutomotiveEthernet` + a
  `ZonalEthernet`.
- **`Harness`.** The wiring
  assembly. A `Harness` has
  many `Connector`s.
- **`Connector`.** The
  electrical-mechanical interface
  (a `DeutschConnector` + an
  `AMPConnector` + a
  `USCARConnector`).
- **`Sensor`.** The
  perception. A `Camera` + a
  `Radar` + a `Lidar` + an
  `UltrasonicSensor` + a
  `IMU` + a `GNSS` + a
  `WheelSpeedSensor`.
- **`Actuator`.** The action.
  An `ElectricMotor` (for
  power seats, windows,
  etc.) + a
  `SolenoidValve` + a
  `Relay`.

### 13.4 Domain + identity + cross-cutting

- **`PartDefinition`.** The
  reusable technical definition.
- **`PartInstance`.** The physical
  or digital occurrence in a
  specific vehicle.
- **`InterfacePort`.** The
  typed port an entity exposes
  or consumes (per section 16 —
  interface taxonomy).
- **`CompatibilityConstraint`.**
  The rule that determines
  whether two `PartDefinition`s
  can be combined in a
  `VehicleDefinition`.
- **`DiagnosticBinding`.** The
  link between a
  `DiagnosticTarget` and a
  `FaultCode` + a
  `RepairAction` + a
  `DiagnosticProcedure`.
- **`Procedure`.** A
  `RepairProcedure` + a
  `MaintenanceProcedure` + an
  `AssemblyProcedure` + a
  `DisassemblyProcedure`.
- **`EngineeringArtifact`.** A
  content-addressed + signed
  artifact (a glTF, a STEP, a
  USD, a compilation report,
  a homologation package).
- **`EvidenceReference`.** A
  reference to the source of an
  `EngineeringFact<T>` (an OEM
  doc, a regulatory filing, a
  lab report, an engineer memo,
  a telemetry stream, an AI
  inference, a user input, a
  community contribution).

### 13.5 Cross-cutting taxonomies

The cross-cutting entities (per
section 2's `EngineeringFact<T>`,
`VerificationStatus`, `SourceType`,
`VehicleRepresentationLevel`) apply
to every category. A new category
that introduces a new foundational
concept is an ADR + a vote in the AI
council (skill 05).

### 13.6 Per-category extensions

The platform supports these
per-category extensions without
duplicating foundational concepts:

- **Gasoline.** A
  `Powertrain.Subsystem` with an
  `InternalCombustionEngine` + a
  `FuelSystem` + an
  `ExhaustSystem`.
- **Diesel.** A
  `Powertrain.Subsystem` with a
  `CompressionIgnitionEngine` +
  a `DieselFuelSystem` + an
  `ExhaustAftertreatmentSystem`.
- **Hybrid.** A
  `Powertrain.Subsystem` with
  both an
  `InternalCombustionEngine` +
  an `ElectricDrive` + a
  `BatteryPack` + a power-split
  device.
- **Electric.** A
  `Powertrain.Subsystem` with an
  `ElectricDrive` + a
  `BatteryPack` + a `Charger` +
  a `ThermalManagementSubsystem`.
- **Hydrogen.** A
  `Powertrain.Subsystem` with a
  `FuelCellStack` + a
  `HydrogenTank` + a
  `ThermalManagementSubsystem`.
- **Motorcycles.** A
  `VehicleDefinition` with 2
  wheels + a
  `RiderErgonomics.Subsystem` +
  a smaller
  `Powertrain.Subsystem`.
- **Commercial vehicles.** A
  `VehicleDefinition` with a
  `CargoBody.Subsystem` + a
  `PayloadMounting.Subsystem` +
  a
  `Telematics.Subsystem`.
- **Future mobility categories.**
  A new category (a drone, a
  robotaxi, an eVTOL) is added
  as a new `VehicleDefinition`
  that composes existing
  entities. The platform does
  **not** duplicate the
  foundational concepts.

## 14. Strongly typed identifiers

**Do not** pass raw strings for
domain identity. Every aggregate has
a `@JvmInline value class` ID that
wraps a `UUID` (or a `ULong` when
the platform's locale favors it).

```kotlin
@JvmInline
value class VehicleRevisionId(val value: UUID)

@JvmInline
value class PartDefinitionId(val value: UUID)

@JvmInline
value class ArtifactId(val value: UUID)
```

Validate construction at the
boundaries. A string that is not a
valid `UUID` is rejected at the
boundary; the rejection is a typed
`FoundryError` (per
`.ai/STANDARDS.md` section 7).

A `Map<String, Any>` field on a
`Part` is a smell. A `Map<String,
String>` is a worse smell. A
`String` field is the worst
smell. The ID is a type, not a
primitive.

## 15. Interface taxonomy

The platform supports at least these
`InterfacePort` kinds. A new kind
is an ADR + a vote in the AI
council (skill 05).

**Mechanical:**

- `MECHANICAL_MOUNT`
- `BOLT_PATTERN`
- `SHAFT`
- `SPLINE`
- `BEARING_SEAT`

**Fluid:**

- `FLUID_INLET`
- `FLUID_OUTLET`
- `VACUUM`
- `FUEL`
- `REFRIGERANT`
- `COOLANT`
- `LUBRICATION`
- `EXHAUST`

**Electrical (low voltage):**

- `LOW_VOLTAGE_POWER`
- `GROUND`

**Electrical (high voltage):**

- `HIGH_VOLTAGE_POWER`

**Signal:**

- `ANALOG_SIGNAL`
- `DIGITAL_SIGNAL`
- `PWM`

**Network:**

- `CAN_HIGH`
- `CAN_LOW`
- `LIN`
- `K_LINE`
- `AUTOMOTIVE_ETHERNET`

**Optical:**

- `OPTICAL`

A `PartDefinition` exposes a set of
`InterfacePort`s; a `PartInstance`
in a `VehicleDefinition` binds
those ports to compatible ports
on other `PartInstance`s. An
incompatible binding is a typed
`CompatibilityConstraintViolation`
error (per `.ai/STANDARDS.md`
section 7).

## 16. Units of measure

Use a units-of-measure library (or
explicit value objects). **Never**
store ambiguous naked numbers.

Bad:

```json
{ "torque": 40 }
```

Correct:

```json
{
  "torque": {
    "value": 40,
    "unit": "N_M"
  }
}
```

Normalize internally to canonical SI
units while preserving source units.
A user input in `lbf_ft` is
normalized to `N_M` internally;
the user input is preserved in the
audit trail.

A value without a unit is a
contract violation; the verifier
(skill 14) rejects the value. A
value with an unknown unit is a
typed `UnitUnknown` error (the
error is added to the canonical
list per `.ai/AGENTS.md` section
10).

## 17. Alias resolution

Maintain **locale-aware aliases**.
The alias table is a per-locale
list of names that resolve to the
canonical name.

Example:

```json
{
  "canonical": "LOWER_CONTROL_ARM",
  "aliases": [
    "tijereta",
    "brazo inferior",
    "trapecio",
    "parrilla",
    "lower control arm"
  ]
}
```

Alias resolution returns **candidates
and confidence**. It MUST NOT
silently force an ambiguous match.
A user input "tijereta" with three
possible canonical candidates
returns a list with the confidence
per candidate; the user picks.

A silent alias resolution is a
contract violation. The resolver
is a typed `Result` (Kotlin) / an
`Either` (Haskell) / a tagged
union (TypeScript).

## 18. Applicability

Every `PartDefinition` application
MAY depend on:

- **Manufacturer.** The OEM that
  produced the part.
- **Platform.** The platform
  ("the MEB platform" for VW's
  BEV).
- **Model.** The model name
  ("Golf", "ID.4").
- **Model year.** The year the
  model was produced.
- **Production date.** The exact
  production date of the
  `VehicleUnit`.
- **Market.** The market / region
  ("EU", "US", "CN", "BR").
- **Body.** The body style
  ("sedan", "hatchback", "SUV").
- **Trim.** The trim level
  ("base", "sport", "luxury").
- **Engine code.** The engine
  identifier ("EA888").
- **Transmission code.** The
  transmission identifier
  ("DQ381").
- **Steering side.** The
  steering side ("LHD", "RHD").
- **Option codes.** The optional
  equipment codes.
- **VIN range.** The VIN range
  the part is applicable to.

**Never** declare universal
compatibility based only on name
similarity. A "Battery Pack X"
that fits a "Golf" by name is
NOT a declaration of mechanical
compatibility. The compatibility
is the `CompatibilityConstraint`
rule + the engineering review,
not the 3D viewer (per
`.ai/AGENTS.md` section 5.1).

A `PartDefinition` without an
applicability rule is a smell;
the rule may be "all" but it is
a rule. A `VehicleDefinition`
that references a `PartDefinition`
without the applicable rule
firing is a typed
`CompatibilityConstraintViolation`
error.

## 19. Definition of done

The ontology is accepted only when
it can represent:

1. **A 2005 gasoline compact sedan.**
   The classic case: a `Chassis`
   + an `InternalCombustionEngine`
   + a `ManualTransmission` + a
   `FuelSystem` + a
   `Suspension` + a `Steering` +
   a `BrakeSystem` + a
   `LowVoltageElectricalArchitecture`
   + a `CANBus` + a `Harness` +
   a `Sensor` set.
2. **A modern BEV with zonal
   electronics.** The cutting-edge
   case: a `Chassis` + an
   `ElectricDrive` + a
   `BatteryPack` + a
   `SingleSpeedReducer` + a
   `ZonalElectricalArchitecture`
   + an `AutomotiveEthernet` + a
   `CockpitController` + an
   `ADASController` + a
   `Sensor` set (camera + radar
   + lidar).
3. **A hybrid.** The mixed case: a
   `Chassis` + both an
   `InternalCombustionEngine` +
   an `ElectricDrive` + a
   `BatteryPack` + a
   `PowerSplitDevice` + a
   `BatteryThermalManagement`.
4. **A motorcycle.** The
   constrained case: 2 wheels +
   a `RiderErgonomics` + a
   smaller `Powertrain` + a
   `LowVoltageElectricalArchitecture`.
5. **A conceptual vehicle with
   incomplete data.** The
   `VISUAL_ONLY` /
   `CONCEPTUAL` case: a
   `VehicleDefinition` with a
   `representationLevel` of
   `CONCEPTUAL`, an
   `IncompletenessManifest` of
   the missing fields, + a
   flag in the UI that says
   "this is not validated".
6. **A single part shared across
   multiple vehicle revisions.**
   The reuse case: a
   `PartDefinition` referenced
   by 3 `VehicleRevision`s; the
   `PartDefinition` is content-
   addressed; the
   `VehicleRevision`s point to
   the same `PartDefinitionId` +
   the same `ArtifactId`.

A `VehicleDefinition` that the
ontology cannot represent is a
failure of the ontology. The
verifier (skill 14) runs the 6
canonical cases as a property test.

## 20. Working with this skill

When invoked, this skill:

1. Reads the request (PRD, ADR, or user).
2. Checks the existing ontology.
3. Drafts the new type + ID + relations +
   invariants + migration.
4. Files the ontology ADR.
5. Mirrors the type to the codebase.
6. Writes the tests for the invariants.
7. Updates the docs.
8. Reports back to the orchestrator with the
   version bump, the migration plan, and the
   consumer impact.
