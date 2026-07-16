# Phase 22 — Windows VM Layer (QEMU/KVM-Backed)

Date: 2026-07-15
Status: **Shipped** — `assembleDebug` green, 1186 tests, 0 failures, 2 skipped.

## What landed

The runtime now has a typed Windows VM path. The
spec + catalog + state machine + manager + in-memory
backend are JVM-testable end-to-end. The production
QEMU integration is the seam; Phase 23 wires the
actual QEMU + QMP.

### Files

**Production (5 new):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/windows/WindowsVmSpec.kt`
  — the typed contract. A `WindowsVmSpec` carries the
  boot ISO URL, virtio driver ISO URL, optional
  pre-installed disk image URL, RAM / disk / CPU
  requirements, `requiresKvm` + `requiresSwtpm` flags,
  and an Ed25519 signature.
- `app/src/main/java/com/elysium/vanguard/core/runtime/windows/WindowsVmState.kt`
  — the sealed state machine. `Stopped` → `Booting`
  → `Running(pid, qmpPort)` → `Paused` / `Stopping` →
  `Stopped`, with `Error(message)`. `Running` carries
  the QEMU PID + QMP socket port for the QMP
  integration.
- `app/src/main/java/com/elysium/vanguard/core/runtime/windows/WindowsVmBackend.kt`
  — the platform seam. `WindowsVmBackend` interface +
  `InMemoryWindowsVmBackend` test impl (5-line
  hand-rolled, records every call, returns canned
  results).
- `app/src/main/java/com/elysium/vanguard/core/runtime/windows/WindowsVmCatalog.kt`
  — the static registry. `WindowsVmCatalog.official()`
  ships three templates (Win10 Pro 22H2, Win11 Pro
  23H2, Server 2019 LTSC). The catalog is
  extensible; tests can register additional entries.
- `app/src/main/java/com/elysium/vanguard/core/runtime/windows/WindowsVmManager.kt`
  — the user-facing orchestrator. The manager is the
  runtime's single public surface for the Windows VM
  path. Returns typed `Result<...>` with a
  `WindowsVmError` sealed class for failures
  (`UnknownSpec` / `InvalidTransition` /
  `BackendRefused`).

**Tests (1 new):**

- `app/src/test/java/com/elysium/vanguard/core/runtime/windows/WindowsVmManagerTest.kt`
  — 20 tests covering the catalog (3 official
  templates, duplicate id rejection), the spec
  validation (blank id, recommended < minimum,
  negative resources, blank boot ISO URL), the state
  machine (start, stop, pause, resume, refresh, list
  running), the USB passthrough path, the typed error
  surface, and the manager's thread-safety under
  8 × 20 concurrent `startVm` calls.

**ADR (1 new):**

- `docs/adr/ADR-012-windows-vm-layer.md` — context,
  decision, the three-layer split (spec+state+catalog
  / backend+state-machine / manager+orchestrator),
  consequences, alternatives, revisit triggers.

### Why this matters

Master order §20 names Windows as a first-class
guest target. Until Phase 22, a user that wanted to
run a Windows guest had no path through the runtime.
Phase 22 closes the JVM-testable half of that gap:
the spec + catalog + state machine + manager are
the contract; Phase 23 wires the QEMU + QMP
production adapter that satisfies it.

The split — translator (spec + state + catalog) +
backend (interface) + manager (orchestrator) — keeps
the policy logic JVM-testable end-to-end. The
20 unit tests cover the state machine + USB
passthrough + typed errors without an Android device
or a QEMU installation.

### The three-layer split

| Layer | What it owns | Tests |
|---|---|---|
| `WindowsVmSpec` + `WindowsVmCatalog` | The contract (data class + value invariants + registry) | 5 unit tests (catalog + spec validation) |
| `WindowsVmState` + `WindowsVmBackend` | The state machine + the platform seam | The `InMemoryWindowsVmBackend` is the test surface; the state is sealed-exhaustive |
| `WindowsVmManager` | The orchestrator (catalog + backend + state map + typed errors) | 15 unit tests (state machine, USB, errors, thread-safety) |

The production QEMU adapter (Phase 23) drops into
the `WindowsVmBackend` slot; the manager does not
change.

## Test count

| Suite | Tests | Failures |
|---|---|---|
| `WindowsVmManagerTest` | 20 | 0 |
| **Project total** | **1186** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Next phase

**Phase 23 — QEMU production backend.** The
`QemuWindowsVmBackend` implements `WindowsVmBackend`
by spawning the QEMU process, opening the QMP socket,
and translating the QMP wire format into
`WindowsVmState` transitions. The runtime's Hilt
module wires the production backend; tests use the
in-memory backend. The manager + state machine
contract is unchanged.

Then **§22 Workspaces** (master order) — the
multi-session UI / state isolation layer. The Windows
VM path is one of the inputs to workspaces (a
workspace can run one Windows VM + multiple Linux
proots).
