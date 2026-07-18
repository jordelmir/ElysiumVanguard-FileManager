# Phase 56 — Vanguard Build (Local Toolchains + Remote Oracle Builds)

Date: 2026-07-18
Status: **Shipped** — `assembleDebug` green, 1696 tests, 0 failures, 2 skipped.

## What landed

The Vanguard Build pillar is real. The
runtime can:
- Detect which of the 9 supported
  toolchains are installed on the device
  (Rust, C/C++, Java/Kotlin, Gradle,
  Node.js, Python, Go, WebAssembly,
  Linux ARM64 catch-all).
- Run a local build using the installed
  toolchain. The runner delegates to the
  existing [ProcessLauncher] (Phase 36) to
  spawn the toolchain binary.
- Stub the remote build client. Phase 56
  ships the [RemoteBuildClient] interface
  + the [RemoteBuildResult] value type;
  the production `HttpRemoteBuildClient`
  is a Phase 60+ follow-up that requires
  actual server setup.

## Files

**Production (2 new):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/build/ToolchainRegistry.kt` —
  the value types. `ToolchainKind` (enum
  with 9 values + `displayName`); the
  `ToolchainRegistry` (data class with a
  `Map<ToolchainKind, ToolchainInstall>`)
  + the `detect()` / `detectAt(baseDirs)`
  factories that probe standard locations
  for each toolchain binary; the
  `ToolchainInstall` (binary path +
  optional version). The registry is
  immutable; a user with a "toolchain was
  uninstalled" complaint can re-detect.
  `withOverride` lets the caller add a
  custom toolchain path.
- `app/src/main/java/com/elysium/vanguard/core/runtime/build/LocalBuildRunner.kt` —
  the runner that consumes a [BuildRequest]
  + a [ToolchainRegistry] and returns a
  [BuildResult]. The runner:
  1. Validates the request: `forceRemote`
     is rejected (no remote client in
     Phase 56); un-installed toolchains
     are rejected with a typed
     [LocalBuildError.ToolchainNotInstalled].
  2. Builds the command line:
     `<toolchain binary> <user command>`.
  3. Delegates to the
     [com.elysium.vanguard.core.runtime.runner.ProcessLauncher]
     to spawn the build.
  4. Returns a [BuildResult.Success]
     (exit code 0) or a typed
     [LocalBuildError.SpawnFailed].

  Also ships the [RemoteBuildClient]
  interface + [RemoteBuildResult] value
  type. The interface is the seam for a
  future `HttpRemoteBuildClient` (Phase
  60+); a test injects a fake client.

**ADR:**

- `docs/adr/ADR-026-vanguard-build.md` —
  the design record. Captures the
  five-piece split (ToolchainKind /
  ToolchainRegistry / BuildRequest /
  LocalBuildRunner / RemoteBuildClient),
  the local + remote rationale (different
  trade-offs: local = fast + private +
  offline, remote = unlimited + online),
  the registry + runner split, the
  RemoteBuildClient-as-stub rationale
  (server setup is a follow-up), and the
  revisit triggers (per-toolchain version
  manager, build cache, authentication).

**Tests (2 new):**

- `app/src/test/java/com/elysium/vanguard/core/runtime/build/ToolchainRegistryTest.kt` —
  11 tests covering: ToolchainKind enum
  has the 9 expected kinds; displayName
  is human-readable; ToolchainInstall
  rejects a blank binaryPath; detectAt
  returns an empty registry when no
  binaries are present; detectAt finds
  Rust when cargo is present; detectAt
  finds multiple toolchains when their
  binaries are present; detectAt picks
  the first match per toolchain when
  multiple candidates exist; isInstalled
  returns false for an un-installed
  toolchain; installFor returns null for
  an un-installed toolchain; withOverride
  replaces the install for a toolchain;
  withOverride preserves other toolchains.
- `app/src/test/java/com/elysium/vanguard/core/runtime/build/LocalBuildRunnerTest.kt` —
  7 tests covering: build rejects a
  request with `forceRemote` set; build
  rejects a request for an un-installed
  toolchain; build delegates to the
  launcher on a valid request; build
  threads the request's env vars through
  to the launcher; build surfaces a
  spawn failure as a typed `SpawnFailed`
  error; BuildRequest rejects a blank
  project path; BuildRequest rejects an
  empty command. Includes a
  `FakeProcessLauncher` that records
  every call.

## Why this matters

The master vision says:

> "Para proyectos compatibles con ARM64:
> - Rust, C/C++, Java/Kotlin, Gradle,
>   Node.js, Python, Go, WebAssembly..."

Until Phase 56 the user had to install
toolchains manually (Termux, or a full
Linux distro in proot) and shell out to
them by hand. Phase 56 ships the
abstraction: a registry that detects
what's installed, a runner that uses
what's installed, a remote client for
what isn't.

A user with a Rust project on their
device can now:
1. Run `ToolchainRegistry.detect()` to
   see if `cargo` is installed.
2. Construct a `BuildRequest(projectPath,
   kind = RUST, command = ["build",
   "--release"])`.
3. Hand the request to the
   `LocalBuildRunner` with the registry.
4. The runner runs `cargo build
   --release` in the project dir and
   returns the result.

The runner is the executor; the
orchestrator-style design from Phase 53
applies. The Vanguard Build subsystem is
the "use toolchain X to compile project
Y" orchestrator.

## Architectural invariants (Phase 56)

- **Local vs remote is a per-request
  choice.** `BuildRequest.forceRemote`
  forces the remote path; otherwise the
  runner uses the local toolchain if
  available. A user with a small Rust
  project compiles locally; a user with
  a large C++ game opts into remote.

- **The registry is JVM-testable.** The
  `detectAt(baseDirs)` factory takes an
  explicit list of base dirs; a test
  injects a temp directory with a fake
  `cargo` binary, calls
  `ToolchainRegistry.detectAt(listOf(tmp))`,
  and asserts the registry reports Rust
  as installed. The test does NOT
  depend on the host's actual toolchain
  installation.

- **The runner validates before
  spawning.** `forceRemote` is rejected
  with a typed error before the
  ProcessLauncher is called. Un-installed
  toolchains are rejected the same way.
  The runner never spawns a process it
  cannot handle.

- **The RemoteBuildClient is a seam.**
  The interface ships; the production
  impl is a follow-up. A test injects a
  `FakeRemoteBuildClient` that records
  every call.

## Test count

| Suite | Tests | Failures |
|---|---|---|
| `ToolchainRegistryTest` | 11 (new) | 0 |
| `LocalBuildRunnerTest` | 7 (new) | 0 |
| **Project total** | **1696** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Next phase

The follow-up after Phase 56 is **Phase
57 — Vanguard AI (the agent operator)**.
The master vision says:

> "La IA no era un simple chatbot.
> Funcionaba como agente de la plataforma:
> - Instalar una distro.
> - Resolver dependencias.
> - Crear un entorno Windows.
> - Seleccionar Wine, Box64, FEX o QEMU.
> - Diagnosticar errores.
> - Interpretar logs.
> - Optimizar flags de compilación.
> - Generar scripts.
> - Explicar consumo de recursos.
> - Reparar configuraciones dañadas.
> - Crear snapshots antes de modificar un
>   entorno.
> - Revertir automáticamente cuando una
>   operación falle."

Phase 57 ships the AI agent's value
types (the input / output schemas for
"install a distro", "create a Windows
environment", etc.) + a `VanguardAgent`
that consumes a `NaturalLanguageGoal` and
returns a `Plan`. The agent is a thin
planner; the actual LLM call is a Phase
60+ concern (requires API setup).
