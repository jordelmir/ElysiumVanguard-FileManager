# Phase 53 — Runtime Orchestrator (Manifest-Based Dispatch)

Date: 2026-07-18
Status: **Shipped** — `assembleDebug` green, 1645 tests, 0 failures, 2 skipped.

## What landed

The runtime can now plan a binary's
execution end-to-end. Given a file + the
device's capabilities, the orchestrator
inspects the file (format + architecture),
selects the best runtime (ANDROID_NATIVE /
LINUX_PROOT / WINE_BOX64 / WINE_FEX /
QEMU_VM / REMOTE), and returns an
`ExecutionPlan` (the manifest + a sanity
check). The actual execution is the
[SessionRunner]'s job (Phase 30+); the
orchestrator is the planner.

This is the master vision's
"motor universal de ejecución":

> ```text
> User Action
>     ↓
> File Type / Manifest Detection
>     ↓
> Compatibility Resolver
>     ↓
> Architecture Detection
>     ↓
> Runtime Selection
>     ├── Android Runtime
>     ├── Native ARM64 ELF
>     ├── Linux PRoot/chroot
>     ├── Wine + Box64/FEX
>     ├── QEMU VM
>     └── Remote Execution
>     ↓
> Sandbox and Mount Policy
>     ↓
> Process Supervisor
>     ↓
> Telemetry and Recovery
> ```

Phases 49-52 delivered the sandbox and
mount policy + the snapshot / rollback.
Phase 53 delivers the *intelligence layer*
above them: the orchestrator that answers
"what runtime should this binary run on?"

## Files

**Production (4 new):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/orchestrator/ExecutableMetadata.kt` —
  the value types. `ExecutableFormat`
  (UNKNOWN / ELF / PE / MSI / MACHO / WASM /
  SCRIPT / JAVA_CLASS), `Architecture`
  (UNKNOWN / ARM32 / ARM64 / X86 / X86_64 /
  RISCV64), and `ExecutableMetadata` (the
  data class with format, architecture,
  interpreter, detectedSizeBytes, notes).
  The `isRunnable` property is true iff
  the format is not `UNKNOWN`.
- `app/src/main/java/com/elysium/vanguard/core/runtime/orchestrator/ExecutableInspector.kt` —
  the format + architecture detector.
  Reads a file's first 4 KB and returns
  the metadata. Uses the existing
  [MagicDetector] (Phase 9.7.4) for format
  detection; the inspector adds
  architecture extraction (ELF `e_machine`
  at offset 18; PE chained MZ → PE
  signature → COFF header `Machine` field;
  shebang line for scripts). The
  inspector is a pure function over the
  file's bytes — no I/O beyond reading
  the head.
- `app/src/main/java/com/elysium/vanguard/core/runtime/orchestrator/RuntimeSelector.kt` —
  the rule-based selector. The selector is
  a stateless function over an
  `ExecutableMetadata` + a
  `RuntimeCapabilities` (what the device
  can run). Returns a `RuntimeChoice`
  (Selected / Rejected) with a
  human-readable reason. The selection
  rules cover every combination:
  - ELF ARM64 → ANDROID_NATIVE (or
    LINUX_PROOT if no Android native)
  - ELF ARM32 → LINUX_PROOT
  - ELF x86 / x86-64 → QEMU_VM (or REMOTE
    if no QEMU)
  - ELF RISC-V → Rejected (no runtime yet)
  - PE x86 / x86-64 → WINE_BOX64 (or
    WINE_FEX, or QEMU_VM as last resort)
  - PE ARM64 → WINE_BOX64 (Wine on the
    host architecture)
  - MSI → WINE_BOX64 (Wine's msiexec)
  - WASM → ANDROID_NATIVE
  - Mach-O → Rejected
  - Script → ANDROID_NATIVE
  - Unknown format → Rejected
- `app/src/main/java/com/elysium/vanguard/core/runtime/orchestrator/ExecutionManifest.kt` —
  the declarative manifest + the user-
  facing entry point. `ExecutionManifest`
  is the value type the runner consumes
  (binaryPath, runtime, interpreter,
  workspaceId, commandLineArgs,
  environmentVariables, workingDirectory,
  selectionReason). `ExecutionPlan` is the
  orchestrator's output (Ready / Rejected).
  `RuntimeOrchestrator` is the entry
  point with three methods:
  - `planExecution(binaryPath,
    capabilities, workspaceId?, ...)`:
    inspect + select + manifest. The full
    flow.
  - `planFromManifest(manifest)`: the user
    supplies a pre-built manifest. The
    orchestrator validates (a sanity
    check) and returns a Ready plan.
  - `inspect(binaryPath)`: the raw
    inspector output. The selector is not
    consulted.
- `docs/adr/ADR-024-runtime-orchestrator.md` —
  the design record. Captures the
  four-piece split (inspector / selector /
  manifest / orchestrator), the
  rule-based selection rationale, the
  manifest-as-input/output contract, and
  the revisit triggers (binary linter,
  remote execution, per-binary override).

**Tests (3 new):**

- `app/src/test/java/com/elysium/vanguard/core/runtime/orchestrator/ExecutableInspectorTest.kt` —
  17 tests covering: ELF detection for
  ARM64 / ARM32 / x86-64 / x86 / RISC-V 64;
  PE detection for x86-64 / x86 / ARM64;
  PE with a missing PE signature (returns
  UNKNOWN architecture); script detection
  via shebang (`#!/bin/sh`,
  `#!/usr/bin/env python3`); WASM
  detection; Java class detection; edge
  cases (missing file, empty file,
  truncated ELF). Includes
  helper-methods that build minimal ELF
  and PE byte sequences for the test.
- `app/src/test/java/com/elysium/vanguard/core/runtime/orchestrator/RuntimeSelectorTest.kt` —
  22 tests covering every rule path: ELF
  ARM64 → ANDROID_NATIVE; ARM64 ELF
  without Android native → LINUX_PROOT;
  ARM32 ELF → LINUX_PROOT; ARM32 ELF
  without linuxProot → Rejected; x86-64
  ELF → QEMU_VM (or REMOTE, or Rejected);
  RISC-V 64 → Rejected; PE x86-64 →
  WINE_BOX64 (or WINE_FEX, or QEMU_VM as
  last resort); PE x86 → WINE_BOX64;
  PE ARM64 → WINE_BOX64; PE without Wine
  or QEMU → Rejected; MSI → WINE_BOX64;
  WASM → ANDROID_NATIVE; Mach-O →
  Rejected; script → ANDROID_NATIVE;
  UNKNOWN format → Rejected.
- `app/src/test/java/com/elysium/vanguard/core/runtime/orchestrator/RuntimeOrchestratorTest.kt` —
  11 tests covering: `planExecution` on a
  native ARM64 ELF → Ready with
  ANDROID_NATIVE; `planExecution` on an
  x86-64 PE → Ready with WINE_BOX64;
  `planExecution` on a shebang script →
  Ready with ANDROID_NATIVE;
  `planExecution` on an unclassifiable
  file → Rejected (UnknownFormat);
  `planExecution` on a missing file →
  Rejected; `planExecution` threads
  commandLineArgs and environmentVariables
  through to the manifest; `planExecution`
  on x86-64 ELF → QEMU_VM;
  `planExecution` on x86-64 ELF with no
  QEMU and no remote → Rejected
  (NoCapableRuntime); `planFromManifest`
  returns Ready with the user's manifest
  unchanged; `inspect` returns the raw
  metadata; `inspect` on a missing file
  returns UNKNOWN metadata.

## Why this matters

The master vision doc names the
"motor universal de ejecución" as the
heart of the platform. Until Phase 53 the
runtime had the pieces (proot launcher,
QEMU VM, signed distros, snapshot /
rollback, mount allowlist) but no
INTELLIGENCE LAYER above them. A user
with a "what runtime should this binary
run on?" question had to know the answer
themselves.

Phase 53 closes the gap. The orchestrator
inspects the file, consults the device's
capabilities, and returns a plan. The
plan is the manifest the runner consumes.
A user can hand-write a manifest (the
"I-know-exactly-what-I-want" path) or let
the orchestrator generate one (the
"I-just-want-it-to-work" path).

The rule table is a small, auditable
piece. A user with a "why did the
orchestrator pick Wine for this binary?"
question can read the table and answer
it. A future phase can layer a learned
model on top of the rule table; Phase 53
ships the table.

## What the test suite caught

- **`bytes[9..15] = ByteArray(7) { 0 }`
  in test helper.** The Kotlin syntax
  `bytes[9..15] = ...` assigns a range,
  but the right-hand side must be a
  `Byte`, not a `ByteArray`. The compiler
  caught it on the first build. Fixed by
  replacing the range assignment with a
  `for` loop over the range indices.

- **Truncated ELF test expectation.** The
  test originally expected
  `format = UNKNOWN` for a 5-byte file
  with the ELF magic. But the inspector
  classifies the FORMAT (not the
  architecture) from the magic; the
  format IS ELF. The architecture is
  UNKNOWN because the head is too short
  for the e_machine field at offset 18.
  Fixed by asserting `format = ELF,
  architecture = UNKNOWN` (the inspector's
  actual behaviour).

- **PE with missing PE signature —
  ArrayIndexOutOfBoundsException.** The
  test set `e_lfanew = 0x100` but the byte
  array was only 256 bytes (indices 0..255).
  The inspector's `peOffset + 4` access
  went out of bounds. Fixed by sizing the
  array to `0x120` (288 bytes).

All three are exactly the kind of
regressions the test suite is supposed to
surface — both the test code and the
production code get pinned by the suite.

## Architectural invariants (Phase 53)

- **The orchestrator is a pure function over
  (file, capabilities, optional manifest).**
  No state, no I/O beyond reading the file's
  head. Multiple threads can call
  `planExecution` concurrently with no
  synchronisation.
- **The manifest is the contract.** A user
  can introspect a plan, modify the
  manifest, and re-plan. The runner
  consumes the manifest, not the file +
  capabilities — the runtime does not
  re-inspect.
- **The rule table is auditable.** Every
  rule is a small `when` over
  (format, architecture, capabilities). A
  user with a "why this runtime?" question
  can read the table and answer it. A
  future Phase adds a learned-model
  layer; the rule table is the default.

## Test count

| Suite | Tests | Failures |
|---|---|---|
| `ExecutableInspectorTest` | 17 (new) | 0 |
| `RuntimeSelectorTest` | 22 (new) | 0 |
| `RuntimeOrchestratorTest` | 11 (new) | 0 |
| **Project total** | **1645** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Next phase

The follow-up after Phase 53 is **Phase 54
— Wine + Box64 + DXVK**:
- The orchestrator's `WINE_BOX64` branch
  is currently a no-op (the rule says
  "select Wine + Box64" but no Wine
  backend exists). Phase 54 wires the
  Wine + Box64 stack: a Wine prefix
  manager, a Box64 binary translator,
  and the proot launcher that runs the
  pair.
- DXVK (Direct3D 9/10/11 → Vulkan) and
  VKD3D-Proton (Direct3D 12 → Vulkan) for
  GPU-accelerated Windows games.
- A `WineSessionRunner` (parallel to the
  existing `LinuxProotSessionRunner` +
  `WindowsVmSessionRunner`) that
  consumes an `ExecutionManifest` and
  starts a Wine + Box64 session.

After Phase 54 the orchestrator's
`WINE_BOX64` branch is real — the user
can install a Windows app and the
runtime will pick the right backend
automatically.
