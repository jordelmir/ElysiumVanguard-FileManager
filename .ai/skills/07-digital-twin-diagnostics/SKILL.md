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
among:

- **Vehicle functions** (the
  functions a vehicle performs:
  propulsion, steering, braking,
  HVAC, infotainment, ADAS).
- **Subsystems** (the `Subsystem`s
  that implement the functions:
  the powertrain, the chassis,
  the electrical, the
  electronics, the software).
- **Components** (the
  `PartDefinition`s +
  `PartInstance`s that compose
  the subsystems: the battery
  pack, the motor, the ECU).
- **Signals** (the telemetry
  signals the components emit:
  the cell voltage, the
  temperature, the current).
- **Fault codes** (the DTCs
  the components emit when a
  fault is detected: `P0301`,
  `B1342`, `C1234`).
- **Symptoms** (the user-
  observable manifestations:
  reduced power, warning
  lights, abnormal noise).
- **Root causes** (the actual
  reasons for a fault: a
  failed cell, a wiring
  break, a software bug).
- **Repair actions** (the
  interventions the mechanic
  applies: replace the cell,
  repair the wiring, reflash
  the ECU).
- **Procedures** (the
  step-by-step instructions
  for the repair action: the
  torque sequence, the
  safety precautions, the
  tools required).
- **Verification tests** (the
  tests that confirm the
  repair: the cell voltage
  after replacement, the
  fault code after the
  reflash).

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

## 3. In-scope

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

## 4. Out-of-scope

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

## 5. Inputs

- The `Spec.Artifact` (the vehicle's spec, from
  skill 04).
- The canonical 3D asset (from skill 06).
- The fault model (a domain query against the
  catalog, skill 09).
- The telemetry stream (from the field).
- The user's brand + project context.

## 6. Outputs

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

## 7. Workflow

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

## 8. Quality gates

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

## 9. Failure modes

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

## 10. Coordination contract

- **Input from**: skill 04 (spec), skill 06
  (3D), the field (telemetry).
- **Output to**: skill 09 (catalog), skill 10
  (marketplace), skill 11 (mobile), skill 13
  (regulatory).
- **Triggered by**: every telemetry batch, every
  fault detection, every diagnostic request.
- **Frequency**: per telemetry batch (real-time).

## 11. Forbidden patterns

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

## 12. The diagnostic flow in the Elysium
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

## 13. Working with this skill

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
