# ADR-014: Compatibility matrix and testing policy

- Status: Draft
- Date: 2026-07-13
- Owners: Elysium Vanguard QA

## Context

The app supports multiple backends (PRoot, WinLayer, AVF, QEMU, Remote), each
with different capabilities, performance characteristics and failure modes.
Without a defined compatibility matrix and testing policy, regressions in one
backend can go undetected while another backend evolves. Tests need to be
categorized by the backend they exercise and the device capabilities they
require.

## Decision

Define a compatibility matrix and testing policy with four tiers:

### Compatibility tiers

| Tier | Description | Devices | Backends | Target |
|---|---|---|---|---|
| T0 | Core | All devices | Terminal | Every commit |
| T1 | Standard | API 26+ | PRoot | Every PR |
| T2 | Extended | API 33+ | PRoot + Display + Network | Nightly |
| T3 | Full | API 33+ with AVF | All backends | Release candidate |

### Test categorization

Every test is tagged with one or more of:

```
@Tag("tier:T0")   // Core terminal tests — run on every commit
@Tag("tier:T1")   // PRoot runtime tests — run on every PR
@Tag("tier:T2")   // Display, network, audio — run nightly
@Tag("tier:T3")   // WinLayer, AVF, QEMU, Remote — run on RC

@Tag("backend:terminal")
@Tag("backend:proot")
@Tag("backend:winlayer")
@Tag("backend:avf")
@Tag("backend:qemu")
@Tag("backend:remote")

@Tag("hardware:camera")
@Tag("hardware:gps")
@Tag("hardware:bluetooth")
@Tag("hardware:usb")
@Tag("hardware:display")
@Tag("hardware:network")
@Tag("hardware:audio")
```

### Test types

| Type | Requirement | Example |
|---|---|---|
| Unit | No Android dependency, JVM-only | TerminalParser test |
| Integration | Robolectric or mock Android | Session lifecycle test |
| Instrumentation | Physical device or emulator | DisplayService VNC connect |
| Fuzz | Random input, bounded time | TerminalParser fuzz (10M iterations) |
| Stress | High load, long duration | 100 concurrent sessions |
| Compatibility | Multiple ROM/API level combinations | PRoot on API 26, 30, 33, 34 |

### Testing infrastructure

| Test type | Runner | Frequency | Target |
|---|---|---|---|
| Unit | JVM (JUnit 5) | Every commit | 100% of T0 |
| Integration | Robolectric | Every PR | 100% of T0+T1 |
| Instrumentation | Firebase Test Lab | Nightly | T0+T1+T2 |
| Fuzz | Custom runner | Nightly | Parser, input, state machine |
| Stress | Custom runner | RC | T0+T1+T2+T3 |

### Fuzz testing

The terminal parser and session state machine are fuzzed nightly:
- 10 million random byte sequences for the parser.
- 1 million random state transitions for the state machine.
- Fuzz runs report unique crash-inducing inputs and regressions.
- CI blocks on any fuzz crash, not just known ones.

### Compatibility tracking

A `docs/compatibility/COMPATIBILITY_MATRIX.md` file documents per-device test
results for each release. Community-contributed results are accepted via PR.

## Invariants

1. Every code change must pass T0 tests.
2. Every PR must pass T0+T1 tests.
3. Every nightly must pass T0+T1+T2 tests (where hardware is available).
4. Every RC must pass T0+T1+T2+T3 tests (on reference hardware).
5. A fuzz crash is treated as a P0 bug.
6. Backend-specific tests must not fail due to unrelated backend changes.

## Alternatives considered

### Single test suite for all backends

Rejected. Test execution time and device requirements differ by tier. A single
suite would block CI for hardware-dependent tests that cannot run in CI.

### No compatibility matrix

Rejected. Without a matrix, users cannot predict which backends work on their
device and developers cannot prioritize fixes by impact.

## Consequences

- Test organization must support tagging and tiered execution.
- CI configuration mirrors the tier structure.
- Fuzz testing generates artifacts that must be stored and analyzed.
- The compatibility matrix is a living document maintained by the QA team.
- Backend-specific failures are isolated and do not block unrelated changes.

## Revisit triggers

- Test execution time exceeds CI time limits for any tier.
- Number of device-specific entries in the compatibility matrix exceeds 50.
- Fuzz testing does not find new bugs in 3 consecutive months.
