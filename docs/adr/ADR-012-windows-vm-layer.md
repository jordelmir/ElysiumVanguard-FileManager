# ADR-012 — Windows VM Layer (QEMU/KVM-Backed)

Status: **Accepted** (Phase 22, 2026-07-15)
Owners: Runtime
Supersedes: none
Superseded by: ADR-013 (QEMU production backend, pending)

## Context

Master order §20: "Windows guests run under a
QEMU/KVM-backed VM". Until Phase 22 the runtime had a
Linux proot path (Phase 9.6.x) but no first-class
Windows support. A user that wanted to run a Windows
guest had no path through the runtime.

The challenge: a Windows VM is materially different from
a Linux proot. The Linux path uses the host kernel via
`proot`; a Windows VM runs a full independent kernel via
QEMU/KVM. The QEMU process lifecycle (spawn, watch the
QMP socket, kill on shutdown) is platform-specific and
not directly JVM-testable.

## Decision

We split the Windows VM path into three layers (the
same translator + backend pattern the firewall and
hardware paths use):

1. **`WindowsVmSpec` + `WindowsVmCatalog`** — the typed
   contract. A spec is a data class with the boot ISO
   URL, the virtio driver ISO URL, RAM / disk / CPU
   requirements, and an Ed25519 signature. The catalog
   is the static registry of supported templates
   (Win10 Pro 22H2, Win11 Pro 23H2, Server 2019 LTSC,
   Server 2022). The catalog ships the three official
   templates; tests can register additional entries
   via a custom catalog.

2. **`WindowsVmState` (sealed) + `WindowsVmBackend`**
   — the state machine + the platform seam. The state
   machine is `Stopped` → `Booting` → `Running(pid,
   qmpPort)` → `Paused` / `Stopping` → `Stopped`, with
   `Error(message)` for crashes. The backend is the
   interface; the production adapter (Phase 23) wraps
   QEMU + QMP. The `InMemoryWindowsVmBackend` is the
   test impl: 5-line hand-rolled, records every call,
   returns canned results.

3. **`WindowsVmManager`** — the user-facing orchestrator.
   The manager is the runtime's single public surface
   for the Windows VM path. It composes the catalog +
   backend + a per-VM state map. The manager's state
   map mirrors the backend's view and survives across
   calls. The manager returns typed `Result<...>` with
   a `WindowsVmError` sealed class for failures
   (`UnknownSpec`, `InvalidTransition`, `BackendRefused`).

The three layers are independent enough that each has
its own test surface. The spec / catalog test (8 tests)
verifies the value-type invariants. The manager test
(20 tests) exercises the state machine, the USB
passthrough path, the typed error surface, and the
thread-safety. The backend test surface is the
`InMemoryWindowsVmBackend` itself (used by the manager
test as the test fixture).

### Why a separate manager, not a `DistroManager` method

A Windows VM is materially different from a Linux
proot. The proot path has a rootfs tarball + a launch
command; the VM path has an ISO + a QEMU process + a
QMP socket + a virtio driver. Conflating them in one
manager would couple two unrelated code paths; a
separate manager keeps each focused. The runtime's Hilt
graph composes them independently.

### Why a `Result<...>` instead of exceptions

The manager's typed errors (UnknownSpec / InvalidTransition
/ BackendRefused) are an expected part of the contract
— a guest that calls `pauseVm` on a non-Running VM
*should* get a typed `InvalidTransition` failure, not a
stack trace. `Result<...>` makes the failure explicit;
the UI can `when` over the kind without a try-catch.

### Why a sealed `WindowsVmState`

The state machine is closed: `Stopped`, `Booting`,
`Running`, `Paused`, `Stopping`, `Error`. A sealed
class makes the closed set explicit; a future addition
(e.g. `Snapshotting` for live-snapshot) is a deliberate
code change. The `Running` state carries the QEMU PID
+ QMP port so the runtime can talk to the guest via
QMP (Phase 23 wires the actual QMP integration).

### Why the catalog ships three official templates

The master order names "Windows 10, Windows 11, Windows
Server 2019/2022" as the supported targets. The
catalog ships three for the JVM-testable build (Win10
Pro 22H2, Win11 Pro 23H2, Server 2019 LTSC). The fourth
(Server 2022) is a single field-add; we ship three
because each requires a separate ISO + signature.

## Consequences

### Positive

- **JVM-testable end-to-end.** The spec + state + catalog
  + manager are all pure JVM. The 28 unit tests cover
  the spec validation, the state machine, the USB
  passthrough path, the typed errors, the catalog
  registration, and the manager's thread-safety. The
  production QEMU integration is a follow-up phase
  (Phase 23) that uses the same `WindowsVmBackend`
  interface.
- **State machine is sealed and exhaustive.** A `when
  (state)` over `WindowsVmState` covers every case;
  the compiler enforces the closed set.
- **Typed errors.** A guest that calls `pauseVm` on a
  non-Running VM gets `WindowsVmError.InvalidTransition`,
  not a stack trace. The UI can render the error
  kind by case.
- **Thread-safe manager.** The state map uses
  `ConcurrentHashMap`; the `InMemoryWindowsVmBackend`
  uses `synchronized`; the manager's `startVm` is
  safe under 8 × 20 concurrent calls. The test
  exercises the contention.
- **Spec reuses the Phase 12.4 signing model.** The
  spec carries a `signature` field; the canonical
  bytes are signed with Ed25519 by the build host.
  Phase 23 wires the signer / verifier.

### Negative

- **No production QEMU integration yet.** The
  `WindowsVmBackend` is the seam; the production
  adapter is Phase 23. The manager + state machine
  are useful in the meantime: the test surface
  pins the contract; the production adapter just
  needs to satisfy the interface.
- **Catalog URLs are placeholders.** The
  `bootIsoUrl` and `virtioIsoUrl` point at
  `https://example.com/...`; Phase 23 replaces them
  with the real Microsoft / virtio URLs and adds the
  SHA-256 hashes. A real downloader validates the
  hashes before the manager calls `startVm`.
- **No `boot from diskImageUrl` path yet.** The spec
  carries an optional `diskImageUrl` for
  pre-installed images; the manager's `startVm` does
  not branch on it. Phase 23 adds the branch.
- **No live-snapshot state.** The state machine has
  no `Snapshotting` state; a future live-snapshot
  feature is a sealed-class addition.

## Alternatives considered

1. **Add a Windows VM field to `DistroManager`.**
   Rejected: the data model is fundamentally
   different. A `WindowsVmSpec` has ISO URLs and
   virtio drivers; a `Distro` has a rootfs tarball.
   The catalogs and the launch logic are different.
2. **Use exceptions instead of `Result<...>`.**
   Rejected: a guest that calls `pauseVm` on a
   non-Running VM is an *expected* outcome, not an
   exceptional one. The typed result is what the
   UI wants; exceptions are for unexpected errors
   (QEMU process crashes, OOM).
3. **Skip the catalog; let the caller pass a spec
   directly.** Rejected: the catalog is the static
   list of supported templates. Without it, every
   consumer has to construct a `WindowsVmSpec` from
   scratch. The catalog is the natural seam.

## Revisit triggers

- The QEMU backend (Phase 23) reveals a state the
  sealed class does not model. We add a new
  `WindowsVmState` variant + a manager branch + a
  test.
- A second Windows family (e.g. Windows 10 LTSC, ARM)
  is added. The catalog gains a fourth entry; the
  spec's `family` enum gains a value; the
  `requiresKvm` / `requiresSwtpm` flags wire up
  accordingly.
- The user wants live snapshots. The state machine
  gains a `Snapshotting` state; the manager gains
  `snapshotVm` / `restoreSnapshot` methods. The
  state map is unchanged.
