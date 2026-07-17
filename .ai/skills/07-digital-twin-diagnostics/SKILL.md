---
name: digital-twin-diagnostics
description: The digital twin, the telemetry ingestion, the fault model, the diagnostic flow, the repair actions. The bridge between "the user has a vehicle" and "the field has the data to fix it".
---

# Skill 07 — Digital Twin and Diagnostics

## 1. Mission

**Turn the generated vehicle into an
interactive diagnostic and repair model.**

The system must **diagnose functions and
systems**, not blindly replace the component
named by a DTC. A `P0301` (cylinder 1
misfire) is a **symptom**; the root cause may
be a spark plug, an ignition coil, a fuel
injector, a compression issue, or a wiring
fault. The diagnostic graph walks the
function tree from the symptom to the root
cause; the mechanic sees the path, not just
the code.

The digital twin is the "living" version of
the spec (per skill 04's `Spec.Artifact`);
the diagnostics are the "what's wrong and
how do I fix it" answer; the field
mechanic's flow is the UX. The skill is the
bridge between "the user has a vehicle" and
"the field has the data to fix it".

## 2. Diagnostic graph

The platform models the relationships
among **20 typed entities** that
together form the diagnostic graph.
Every entity is a typed value with
`EngineeringFact<T>` provenance (per
`.ai/STANDARDS.md` section 3).

### 2.1 The 20 entities

- **Vehicle function.** A function
  the vehicle performs
  (propulsion, steering, braking,
  HVAC, infotainment, ADAS).
- **Subsystem.** A `Subsystem`
  that implements a function
  (powertrain, chassis, electrical,
  electronics, software).
- **Component.** A `PartDefinition`
  + `PartInstance` that composes
  a subsystem (battery pack, motor,
  ECU).
- **Connector.** A physical
  electrical-mechanical interface
  (a `DeutschConnector`, an
  `AMPConnector`, a `USCARConnector`).
- **Pin.** A single contact in a
  connector (a `Pin 1` of a `J1`
  connector, carrying a
  `HIGH_VOLTAGE_POWER` signal).
- **Fuse.** A circuit protection
  device (a 30A blade fuse, a
  thermal fuse, a resettable
  PTC).
- **Relay.** An electrically-
  controlled switch (a 12V SPDT
  relay, a solid-state relay).
- **Power feed.** The path of
  electrical current from a
  source to a load (battery +
  fuse + relay + connector + pin
  + load).
- **Ground.** The return path of
  electrical current (chassis
  ground, signal ground,
  isolated ground).
- **Signal.** A telemetry signal
  (cell voltage, temperature,
  current, RPM, MAP, MAF).
- **Network.** A communication
  bus (CAN, LIN, FlexRay,
  Automotive Ethernet).
- **DTC** (Diagnostic Trouble
  Code). A code the ECUs emit
  when a fault is detected
  (`P0301`, `B1342`, `C1234`).
- **Symptom.** A user-observable
  manifestation (reduced power,
  warning light, abnormal noise).
- **PID** (Parameter ID). A
  live-data value the ECU
  exposes on a network
  (engine RPM, vehicle speed,
  O2 sensor voltage).
- **Mode 06 result.** A
  pass/fail result of a self-
  test the ECU runs internally
  (an O2 sensor heater test, a
  misfire monitor test).
- **Freeze frame.** A snapshot
  of telemetry at the moment
  a DTC was set (RPM at
  detection, vehicle speed at
  detection, engine load at
  detection).
- **Test.** A diagnostic
  procedure the mechanic runs
  (a voltage drop test, a
  compression test, a fuel
  pressure test).
- **Tool.** The equipment the
  test requires (a multimeter,
  a compression gauge, a
  scan tool).
- **Procedure.** A step-by-step
  repair instruction (a
  torque sequence, a safety
  precaution, a tool list).
- **Cause hypothesis.** A
  candidate root cause with
  a confidence score.
- **Evidence.** The data that
  supports or refutes a
  hypothesis (a voltage
  reading, a freeze frame
  value, a Mode 06 result).
- **Root cause** (covered
  below as a separate entity
  class for the walk).
- **Repair action** (covered
  below as a separate entity
  class).
- **Verification test** (covered
  below as a separate entity
  class).

### 2.2 Additional entities

- **Root cause.** The actual
  reason for a fault (a failed
  cell, a wiring break, a
  software bug).
- **Repair action.** The
  intervention the mechanic
  applies (replace the cell,
  repair the wiring, reflash
  the ECU).
- **Verification test.** The
  test that confirms the
  repair (the cell voltage
  after replacement, the
  fault code after the
  reflash).

### 2.3 Graph properties

The graph is a **typed DAG** (a
directed acyclic graph with
typed nodes + typed edges).
A cycle in the graph is a
contract violation; the
verifier (skill 14) rejects
the graph.

A diagnostic walk is a
**topological traversal** of
the graph from the symptom
to the root cause. The
traversal is deterministic;
the same symptom + the same
telemetry produces the same
walk. The traversal respects
the platform's verification
levels (per `.ai/STANDARDS.md`
section 3.2): a fault whose
root cause is `AI_INFERRED`
is flagged; a fault whose
root cause is `OEM_VERIFIED`
or `LAB_VERIFIED` is
authoritative.

A diagnostic that returns the
DTC as the answer (without
the walk) is a contract
violation. The system
diagnoses functions and
systems, not blindly replaces
the component named by a
DTC.

## 3. Diagnostic workflow

The mechanic's diagnostic process
is a **12-step workflow**. Every
step is recorded as evidence
(per section 5); every step
updates the cause-hypothesis
confidence; the mechanic
repairs **only after
confirmation** (step 10).

1. **Confirm complaint.** The
   mechanic confirms the
   complaint with the user
   (what they saw, when, how
   often, under what
   conditions).
2. **Identify affected
   function.** The mechanic
   identifies the affected
   function (propulsion,
   steering, braking, etc.)
   using the diagnostic graph.
3. **Read codes and freeze
   frame.** The mechanic reads
   the DTCs + the freeze frame
   (per skill 11's OBD-II
   reader).
4. **Check battery, supply,
   grounds and common
   dependencies.** The
   mechanic checks the
   battery voltage + the
   power feeds + the grounds
   + the common dependencies
   (a fuse, a relay, a
   connector). A "no power"
   or "no ground" finding
   blocks further diagnosis
   until resolved.
5. **Inspect network health.**
   The mechanic inspects the
   network health (CAN bus
   error frames, LIN bus
   errors, Ethernet link
   status). A network failure
   can manifest as a DTC
   without an actual
   component failure.
6. **Evaluate live data.** The
   mechanic evaluates the
   live PIDs (per
   `EngineeringFact<T>`). A
   PID outside the expected
   range is evidence.
7. **Execute minimally
   invasive tests.** The
   mechanic executes the
   minimally invasive tests
   first (a voltage drop, a
   visual inspection, a
   smoke test). A destructive
   test is only when the
   non-destructive tests are
   inconclusive.
8. **Record evidence.** The
   mechanic records every
   reading, every observation,
   every test result as
   `EngineeringFact<T>` with
   the source (the tool, the
   test) + the verification
   status.
9. **Update hypothesis
   confidence.** The mechanic
   updates the cause-
   hypothesis confidence:
   evidence that supports the
   hypothesis raises the
   confidence; evidence that
   refutes it lowers the
   confidence; evidence that
   eliminates the hypothesis
   removes it.
10. **Repair only after
    confirmation.** The
    mechanic repairs **only
    after** the cause-
    hypothesis confidence
    crosses the threshold
    (default: 0.85). A repair
    on a low-confidence
    hypothesis is a contract
    violation; the platform
    refuses the action.
11. **Validate after repair.**
    The mechanic runs the
    verification tests (the
    post-repair checks per
    the procedure).
12. **Record final cause.** The
    mechanic records the
    final cause + the
    evidence in the audit
    trail. A "could not
    reproduce" or "intermittent
    fault" is a valid final
    cause; a "we replaced
    the part" without evidence
    is a contract violation.

## 4. DTC binding

A DTC binding MUST specify
**10 fields**. A binding without
all 10 is a contract violation;
the verifier rejects the
binding.

- **Standard or OEM namespace.**
  The namespace (SAE J2012 for
  OBD-II, the OEM's proprietary
  namespace for OEM-specific
  codes). A DTC in the wrong
  namespace is rejected.
- **Module.** The ECU that
  emitted the DTC (the BMS,
  the VCU, the TCU, the BCM).
- **Applicability.** The
  `VehicleDefinition`s the
  DTC applies to (per skill 03
  section 18). A DTC that does
  not apply to the vehicle is
  not a fault.
- **Detection condition when
  known.** The detection
  condition (e.g. "cell voltage
  > 4.2V for > 100ms"). A DTC
  without a detection condition
  is a hypothesis, not a fact.
- **Affected function.** The
  function the DTC affects
  (propulsion, steering, etc.).
- **Candidate causes.** The
  list of candidate root
  causes (per the diagnostic
  graph). The list is
  exhaustive for the known
  causes; an empty list is a
  contract violation.
- **Exclusions.** The
  conditions under which the
  DTC is NOT applicable
  (e.g. "not applicable during
  the first 5 minutes of
  operation when the BMS is
  calibrating").
- **Required tests.** The
  tests the mechanic MUST run
  to confirm the cause (a
  voltage drop, a Mode 06
  result, a freeze frame
  analysis).
- **Evidence level.** The
  verification status required
  for the binding
  (`OEM_VERIFIED` for SAE
  codes, `LAB_VERIFIED` for
  OEM-specific codes).
- **Source.** The source of
  the binding (an SAE
  document, an OEM service
  manual, a lab report).

**A DTC is not automatically
equivalent to a failed part.**
A `P0301` is a symptom; the
root cause may be a spark plug,
an ignition coil, a fuel
injector, a compression issue,
or a wiring fault. The mechanic
walks the graph; the platform
refuses to recommend a
replacement before the tests
are run.

## 5. 3D behavior

When a diagnostic target is
selected in the 3D viewport
(per skill 11), the platform
MUST show:

- **Isolate the relevant
  subsystem.** Hide unrelated
  subsystems; highlight the
  selected subsystem.
- **Highlight power and ground
  routes.** Show the power
  feed from the source to the
  load; show the ground path
  from the load to chassis
  ground.
- **Display connector
  relationships.** Show every
  connector the selected
  component mates with; show
  the pin assignments.
- **Display upstream and
  downstream dependencies.**
  Show the components that
  feed the selected component
  (upstream) and the components
  the selected component feeds
  (downstream).
- **Show confidence and
  evidence.** For every
  highlighted element, show
  the cause-hypothesis
  confidence + the evidence
  (per section 3).
- **Avoid displaying unrelated
  markers.** A diagnostic view
  that shows 1000 markers is
  a usability failure; the
  view is curated.
- **Provide a non-3D accessible
  representation.** A blind
  mechanic uses the list view
  + the audio cues + the
  haptic feedback (per skill
  11).

A 3D view that violates the 7
behaviors is a contract
violation; the verifier rejects
the release.

## 6. Repair procedure model

A `Procedure` (a repair
procedure) MUST include
**13 elements**. A procedure
without all 13 is a contract
violation; the verifier rejects
the procedure.

- **Applicability.** The
  `VehicleDefinition`s the
  procedure applies to.
- **Prerequisites.** The
  conditions that must be
  met before the procedure
  starts (the battery
  disconnected, the system
  depressurized, the safety
  locks engaged).
- **Safety warnings.** The
  hazards the mechanic faces
  (high voltage, stored
  energy, sharp edges, hot
  surfaces) + the PPE
  required.
- **Required tools.** The
  tools the procedure needs
  (a torque wrench, a
  multimeter, a scan tool).
- **Consumables.** The
  materials that are used up
  (a sealant, a coolant, a
  thread locker).
- **One-time-use parts.** The
  parts that MUST be replaced
  (a crush washer, a single-
  use bolt, a gasket).
- **Removal sequence.** The
  step-by-step removal
  (numbered, with the per-
  step torque + the per-step
  visual cue).
- **Inspection.** The
  inspection criteria (the
  surface finish, the
  tolerance, the
  contamination check).
- **Installation sequence.**
  The step-by-step
  installation (numbered, with
  the per-step torque + the
  per-step visual cue).
- **Torques with sources.**
  Every torque value is a
  `BigDecimal` with a unit
  (`N_M` or `lbf_ft`) + the
  source (the OEM service
  manual, a lab test). An
  unknown torque MUST stay
  unknown; the platform does
  **not** invent a torque
  value.
- **Calibration.** The post-
  install calibration (a
  zero, a span, a learn).
- **Validation.** The post-
  install verification tests
  (a voltage check, a leak
  test, a function test).
- **Abort conditions.** The
  conditions under which the
  mechanic MUST stop the
  procedure (a damaged
  thread, a contaminated
  surface, an unexpected
  finding) + the recovery
  action.
- **Revision.** The
  `RevisionId` of the
  procedure.

## 7. Definition of done

The diagnostic + repair flow is
accepted only when the canonical
misfire scenario passes. The
misfire scenario (DTC `P0301`)
is the canonical test case.

The flow is accepted when
**every** condition below is
true for a misfire DTC:

- **The correct cylinder is
  identified.** The diagnostic
  graph walks from the symptom
  to the cylinder; the walk
  is deterministic; the
  cylinder is the correct one.
- **Ignition, injection,
  compression, air leak and
  wiring hypotheses are
  represented.** The
  diagnostic graph has at
  least 5 cause hypotheses
  (the spark plug + the
  ignition coil + the fuel
  injector + the compression
  + the wiring) for the
  misfire.
- **The system does not
  recommend replacement
  before tests.** The
  platform refuses to
  recommend a part
  replacement before the
  minimally invasive tests
  are run; the
  recommendation is
  evidence-driven.
- **Evidence updates
  hypothesis confidence.**
  Every reading updates the
  cause-hypothesis confidence;
  a hypothesis whose
  confidence drops below the
  threshold is removed.
- **Repair validation clears
  only after post-repair
  checks.** The platform
  refuses to clear the DTC
  until the post-repair
  verification tests pass.

A diagnostic + repair flow
that does not pass the misfire
scenario is a contract
violation. The verifier (skill
14) runs the scenario as a
property test on every
release.

## 8. In-scope

- Running the digital twin simulation (per
  vehicle, per scenario).
- Ingesting telemetry from the field (CAN bus,
  OBD-II, ROS 2, vendor-specific protocols).
- Modeling faults: which parts can fail, which
  fault codes they emit, which telemetry signals
  are correlated, which root causes are likely.
- Computing health metrics: state of charge,
  state of health, remaining useful life, mean
  time between failures, etc.
- Producing the diagnostic flow: from a fault
  code + telemetry → root cause → repair action
  → parts list → labor estimate.
- Generating the repair manual: a human-
  readable document the mechanic follows.
- Validating the fix: a closed-loop check that
  the telemetry after the repair matches the
  expected pattern.

## 9. Out-of-scope

- The 3D model (skill 06).
- The marketplace (skill 10).
- The mobile UX (skill 11).
- The regulatory submission (skill 13).
- The royalty calculation (skill 09).

The twin says "the battery pack is at 23% SoC
with a cell voltage imbalance on cell #42". The
marketplace sells the replacement cell. The
mobile UX shows the diagnostic to the mechanic.
The regulatory submission audits the diagnostic
flow. Each is its own concern.

## 10. Inputs

- The `Spec.Artifact` (the vehicle's spec, from
  skill 04).
- The canonical 3D asset (from skill 06).
- The fault model (a domain query against the
  catalog, skill 09).
- The telemetry stream (from the field).
- The user's brand + project context.

## 11. Outputs

- A `DigitalTwinState` artifact (the current
  state of the twin).
- A `FaultReport` artifact (a detected fault +
  root cause + repair action + parts list +
  labor estimate).
- A `RepairManual` artifact (a human-readable
  document the mechanic follows).
- A `HealthMetric` stream (per metric, per
  timestamp).
- A `DiagnosticTrace` artifact (the closed-loop
  record of: fault detected → root cause →
  repair → telemetry after repair → fix
  validated).

The artifacts are content-addressed + signed.
The trace lands in the catalog (skill 09) for
the audit trail.

## 12. Workflow

1. **Receive telemetry.** The pipeline ingests
   the telemetry stream. The format is
   standardized (the runtime emits a typed
   `TelemetryFrame` event).
2. **Update the digital twin.** The twin is
   updated with the new telemetry. The twin is a
   deterministic simulation; given the same
   inputs, the twin produces the same outputs.
3. **Detect faults.** The fault model is
   evaluated against the current twin state +
   the new telemetry. A fault detection is a
   `FaultReport` artifact.
4. **Diagnose.** The diagnostic flow walks the
   fault model: from the fault code → the root
   cause → the repair action. The flow is
   rule-based + ML-assisted. A diagnosis is a
   `FaultReport` artifact with a confidence
   score.
5. **Generate the repair manual.** A human-
   readable document the mechanic follows. The
   manual is in the user's language (i18n via
   the catalog).
6. **Validate the fix.** The closed-loop check:
   the telemetry after the repair is compared
   to the expected pattern. A fix that does not
   pass validation is re-escalated.
7. **Emit the trace.** The full
   `DiagnosticTrace` (fault → root cause →
   repair → validation) lands in the catalog.

## 13. Quality gates

- The twin is deterministic.
- The fault model has unit tests for every
  fault.
- The diagnostic flow has unit tests for
  every fault.
- The repair manual is reviewed by a human
  (the diagnostic flow's "human in the loop"
  gate).
- The closed-loop validation is automated.
- The trace is in the catalog.

## 14. Failure modes

- **Telemetry is missing or corrupt.** The
  twin falls back to the last-known state. A
  warning is emitted.
- **The fault model has no match.** The
  diagnostic flow escalates to the AI council
  (skill 05) for a new fault.
- **The diagnostic flow is uncertain.** The
  confidence score is low; the mechanic is
  asked to inspect.
- **The fix does not validate.** The repair is
  re-escalated. The fault is marked as
  unresolved.
- **The digital twin diverges from the
  physical vehicle.** The twin is
  re-calibrated from the next telemetry batch.
  A persistent divergence is a quality-gate
  failure.

## 15. Coordination contract

- **Input from**: skill 04 (spec), skill 06
  (3D), the field (telemetry).
- **Output to**: skill 09 (catalog), skill 10
  (marketplace), skill 11 (mobile), skill 13
  (regulatory).
- **Triggered by**: every telemetry batch, every
  fault detection, every diagnostic request.
- **Frequency**: per telemetry batch (real-time).

## 16. Forbidden patterns

- **Non-deterministic twins.** A twin that
  depends on wall-clock time, on the order of
  inputs, or on a random seed is a contract
  violation. The twin is reproducible.
- **Closed-loop fixes without validation.** A
  repair that the mechanic reports "fixed" but
  the telemetry disagrees with is a bug. The
  closed-loop check is mandatory.
- **Hallucinated fault codes.** A fault model
  that emits a code that is not in the catalog
  is a contract violation. Every fault code
  is a registered type.
- **Hallucinated root causes.** A diagnostic
  flow that suggests a root cause that is not
  in the fault model is a hallucination. The
  flow is rule-based + ML-assisted; the
  ML-assisted part has a confidence score +
  a fallback to "ask the AI council".
- **Missing audit trails.** Every diagnostic
  flow emits a `DiagnosticTrace`. A trace that
  is not in the catalog is a violation.
- **Real-time decisions without human review.**
  A diagnostic flow that automatically
  triggers a part order is a violation. The
  flow informs the user; the user decides.

## 17. The diagnostic flow in the Elysium
Automotive Foundry

The diagnostic flow is the platform's "what's
wrong and how do I fix it" answer. A mechanic in
the field, with a phone running the Elysium
mobile UX, plugs into the vehicle, reads the
fault codes, and the platform:

1. Ingests the fault codes + the recent
   telemetry.
2. Walks the fault model + the digital twin.
3. Produces a root cause + a confidence score.
4. Generates a repair manual: which parts to
   order, which tools to use, which steps to
   follow, which safety precautions to take.
5. After the repair, the mechanic plugs back
   in; the platform validates the fix.
6. The full trace lands in the catalog for
   the audit trail.

The trace is the platform's "we know what
happened, and we can prove it" answer.

## 18. Working with this skill

When invoked, this skill:

1. Receives the telemetry (or the fault code
   request).
2. Updates the twin + detects the fault.
3. Diagnoses + generates the repair manual.
4. Emits the trace.
5. Returns the `FaultReport` + the
   `RepairManual` to the orchestrator (or to
   the mobile UX directly).

The skill does not render the manual in 3D
(skill 11). The skill does not order the
replacement parts (skill 10). The skill does not
audit the regulatory submission (skill 13).
The skill produces the diagnostic; the next
skills consume it.
