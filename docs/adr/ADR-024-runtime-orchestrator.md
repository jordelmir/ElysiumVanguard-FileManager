# ADR-024 — Runtime Orchestrator (Manifest-Based Dispatch)

Status: **Accepted** (Phase 53, 2026-07-18)
Owners: Runtime
Supersedes: none
Superseded by: none

## Context

The master vision doc (PHASE 9_WORLDWIDE_VISION)
describes the "motor universal de ejecución"
as the heart of the platform:

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

Until Phase 53 the runtime had several
disjoint dispatchers:

- [DistroInstaller] (Phase 9.6.2) installs a
  Linux distro (the "what distro?" question).
- [LinuxProotSessionRunner] (Phase 30) starts
  a proot session for a LinuxProot session
  (the "how to run a Linux ELF?" question).
- [WindowsVmSessionRunner] (Phase 31) starts
  a QEMU VM for a WindowsVm session (the
  "how to run Windows?" question).
- [ApplicationCapsule] (Phase 14) describes
  an app's manifest declaratively.

But there was no SINGLE entry point that
takes a file + a workspace and answers:
"What is this file? What architecture is
it? What runtime should run it? Can the
device actually run it?" The dispatchers
were hard-coded to their specific session
type; the orchestrator's job (the
intelligence layer above them) did not
exist.

The master vision was explicit:

> "No debía 'probar comandos al azar'. Cada
> ejecución debía estar basada en un
> manifiesto declarativo y validado."

The orchestrator is the answer.

## Decision

We split the orchestrator into four small
pieces:

1. **`ExecutableInspector`** — the
   format/architecture detector. Reads a
   file's magic bytes + ELF/PE header and
   returns an [ExecutableMetadata] (format,
   architecture, interpreter, dependencies).
   Reuses the existing [MagicDetector] (Phase
   9.7.4) for format detection; the inspector
   adds architecture extraction (MagicDetector
   does not parse the e_machine / Machine
   fields).

2. **`RuntimeCapabilities`** + `RuntimeSelector`
   — the runtime selection. The selector
   takes a metadata + a capabilities object
   (what the device can run — e.g. has
   proot, has Wine, has QEMU) and returns a
   [RuntimeChoice] (the best runtime + a
   confidence score) or a typed rejection.
   The selection is rule-based, not
   heuristic: each rule is a small
   `when` over (format, architecture,
   capabilities).

3. **`ExecutionManifest`** + `RuntimeOrchestrator`
   — the user-facing entry point. The
   orchestrator takes a file + a workspace,
   runs the inspector + the selector, and
   returns an `ExecutionPlan` (the manifest
   the runtime will execute). A user can
   supply a pre-built `ExecutionManifest`
   (skip the inspector + selector) for
   reproducible runs.

4. **`RuntimeKind` enum** — the runtime
   backends the selector can choose from.
   Phase 53 ships the enum and the selection
   rules; the actual backend integration
   (Wine + Box64, QEMU, Remote) is a
   follow-up phase. The selection returns
   `RuntimeKind.ANDROID_NATIVE` for native
   ARM64 ELFs, `RuntimeKind.LINUX_PROOT` for
   Linux ELFs, `RuntimeKind.WINE_BOX64` for
   Windows PE on ARM64, `RuntimeKind.QEMU_VM`
   for x86/x86-64 ELFs, `RuntimeKind.REJECTED`
   for anything the device cannot run.

### Why a four-piece split

The pieces are independent:

- The inspector is a pure function over a
  file. It has no opinions about runtimes.
- The selector is a pure function over
  (metadata, capabilities). It has no
  opinions about the file.
- The manifest is a value type the user
  can construct. It is independent of the
  inspector + selector.
- The orchestrator wires the three
  together. It is the only piece that
  knows about all three.

A future Phase can replace any one piece
without touching the others. A test can
exercise the selector without writing a
real ELF file (the metadata is just a data
class). The separation is the same as the
existing [FilesystemSnapshotEngine] +
[WorkspaceManager] split.

### Why rule-based selection, not ML

The selector's job is to map a small
discrete space (format × architecture ×
capabilities) to a small discrete space
(runtime kind). The mapping is 100%
deterministic — there is no "what is the
optimal runtime for this binary?" question
that requires inference. A rule table is
the right shape:

```kotlin
when {
    metadata.format == ExecutableFormat.ELF &&
        metadata.architecture == Architecture.ARM64 ->
        RuntimeChoice(RuntimeKind.ANDROID_NATIVE, "native ARM64 ELF")
    metadata.format == ExecutableFormat.ELF &&
        metadata.architecture == Architecture.ARM64 &&
        !capabilities.hasNativePriveleges ->
        RuntimeChoice(RuntimeKind.LINUX_PROOT, "ARM64 ELF in proot distro")
    metadata.format == ExecutableFormat.ELF &&
        metadata.architecture == Architecture.X86_64 ->
        RuntimeChoice(RuntimeKind.QEMU_VM, "x86-64 ELF needs full emulation")
    metadata.format == ExecutableFormat.PE &&
        metadata.architecture == Architecture.X86_64 ->
        RuntimeChoice(RuntimeKind.WINE_BOX64, "x86-64 PE on ARM64 via Wine + Box64")
    // ... etc
    else -> RuntimeChoice(RuntimeKind.REJECTED, "no runtime can run this binary")
}
```

A future phase can layer an "advanced"
selector (a learned model) on top of the
rule table. The rule table is the
default; the learned model is an opt-in
that the user explicitly enables.

### Why a `ExecutionManifest` declarative format

The master vision:

> "Cada ejecución debía estar basada en un
> manifiesto declarativo y validado."

The manifest is the input to the runtime.
The user can:

- Run the orchestrator on a file and get
  an auto-generated manifest (the
  "I-just-want-it-to-work" path).
- Hand-write a manifest (the "I-know-
  exactly-what-I-want" path).
- Read a manifest from disk (the "I-
  share-my-app-as-a-capsule" path).

A reproducible run is one where the user
supplies the same manifest. The orchestrator
is deterministic given a manifest; the
inspector + selector are deterministic
given a file + capabilities.

### Why the orchestrator returns a plan, not an action

The orchestrator's `planExecution(file,
workspace)` returns an `ExecutionPlan`
(the manifest + a sanity check on the
manifest's viability) rather than starting
a process. The actual start is the
`SessionRunner`'s job — the orchestrator
is the planner, the runner is the executor.

This is the same model Android's PackageManager
uses: `getPackageInfo` (the planner) returns
metadata; `startActivity` (the executor)
starts the activity. A user can introspect
a plan before committing to execution.

## Consequences

Positive:

- The master vision's "manifest-based
  dispatch" is now real. A user can hand
  the orchestrator a file and get back a
  plan; a Phase 54+ runner can execute the
  plan.
- The rule table is a small, auditable
  piece. A user with a "why did the
  orchestrator pick Wine for this binary?"
  question can read the table and answer
  it.
- The inspector's output is a stable,
  serialisable `ExecutableMetadata`. A
  future Phase can persist the inspection
  result on the [ApplicationCapsule] (Phase
  14) — the capsule carries its
  pre-computed manifest + the binary
  payload.

Negative:

- The Phase 53 orchestrator returns a
  plan; the actual execution is a
  follow-up. The Phase 52 critical
  end-to-end test still goes through the
  WorkspaceManager + SessionRunner path
  (LinuxProotSessionRunner) for execution.
  The orchestrator's plans are advisory
  until a Phase 54+ runner consumes them.
- The orchestrator's rule table does NOT
  include dynamic dependencies (e.g. "this
  binary needs libssl.so.1.1 which is not
  in the proot distro"). A future phase
  adds a "dependency resolver" that
  consults the distro's package index.
  Phase 53 returns a runtime even if the
  runtime cannot actually run the binary
  because of missing dependencies; the
  runner surfaces the missing-dependency
  error.

## Revisit triggers

- If a future phase adds a "binary linter"
  (e.g. "this ELF has a hard-coded path
  `/sdcard/...`"), the inspector's output
  gains a `lintWarnings: List<String>`
  field. The orchestrator can refuse to
  plan a run that has lint warnings unless
  the user explicitly opts in.
- If the runtime gains a "remote execution"
  backend (Oracle Cloud, a remote Linux
  box, a Kubernetes pod), the selector
  gains a `RuntimeKind.REMOTE` branch.
  The rule table updates; the orchestrator
  is unchanged.
- If the runtime gains a per-binary override
  ("always run this binary in Wine even
  though the orchestrator would pick QEMU"),
  the [ApplicationCapsule] (Phase 14)
  gains an `executionHint` field. The
  orchestrator's plan consults the hint
  before consulting the rule table.
