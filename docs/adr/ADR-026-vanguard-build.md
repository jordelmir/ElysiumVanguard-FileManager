# ADR-026 — Vanguard Build (Local Toolchains + Remote Oracle Builds)

Status: **Accepted** (Phase 56, 2026-07-18)
Owners: Runtime + Build
Supersedes: none
Superseded by: none

## Context

The master vision doc (PHASE_9_WORLDWIDE_VISION)
names a "Vanguard Build" pillar:

> "Para proyectos compatibles con ARM64:
> - Rust, C/C++, Java/Kotlin, Gradle,
>   Node.js, Python, Go, WebAssembly,
>   aplicaciones Linux ARM64.
> - ...
> Para builds pesados o toolchains
> incompatibles:
> - Remote ephemeral builds (Oracle Free,
>   etc.) with signed request / response,
>   ephemeral containers, SBOM + hashes,
>   artifact delivery."

The user wants to compile, build, and run
projects from the device. ARM64-compatible
projects (Rust, C/C++, Java, Node, Python,
Go, WASM) build locally on the device.
Heavy or x86-only projects build remotely
on Oracle Free (or any ephemeral
container service).

Until Phase 56 the runtime had no build
subsystem. The user had to:
- Use Termux + manual toolchain setup
  (which works but is not Elysium-managed).
- Use a remote CI (which works but is not
  integrated with the runtime).
- Use proot to install a full Linux distro
  and build inside it (which works but is
  heavy and slow).

Phase 56 ships the Vanguard Build subsystem:
- Local toolchain detection (which
  toolchains are installed on the device).
- Local build runner (compile a project
  using the local toolchains).
- Remote build client (send a build
  request to a remote server; the server
  does the heavy lifting; the client
  receives the artifact).

## Decision

We split Vanguard Build into five small
pieces:

1. **`ToolchainKind`** (enum) — the
   supported toolchains. Phase 56 ships
   RUST, C_CPP, JAVA_KOTLIN, GRADLE, NODE,
   PYTHON, GO, WEBASSEMBLY (WAT /
   AssemblyScript), LINUX_ARM64 (the
   catch-all for "any Linux ARM64
   binary").

2. **`ToolchainRegistry`** — the locally
   installed toolchains. The registry has
   a `detect()` factory that probes
   standard locations for each toolchain
   (`/usr/bin/cargo`, `/usr/bin/go`, etc.)
   and returns a populated registry. The
   registry is a `Map<ToolchainKind,
   File>` (the toolchain's binary path).

3. **`BuildRequest`** — the user's build
   request. The request carries:
   - `projectPath`: the absolute path to
     the project root.
   - `kind`: the [ToolchainKind].
   - `command`: the build command (e.g.
     `cargo build --release`).
   - `environmentVariables`: env vars
     to set.
   - `forceRemote`: when true, the build
     goes to the remote server even if
     the local toolchain is available.
     Useful for very large projects or
     when the user wants a clean build
     on a known-fast machine.

4. **`LocalBuildRunner`** — the runner
   that compiles the project locally
   using the local toolchain. The runner
   delegates to the existing
   [com.elysium.vanguard.core.runtime.runner.ProcessLauncher]
   to spawn the toolchain binary; the
   runner captures stdout / stderr and
   returns a [BuildResult].

5. **`RemoteBuildClient`** (interface) +
   `HttpRemoteBuildClient` (production
   impl, Phase 56 ships a stub) — the
   runner that sends the build request to
   a remote server. Phase 56 ships the
   interface; the production impl is a
   follow-up (requires actual server
   setup).

### Why a local + remote split

The split mirrors Android's
"compile-against-SDK" vs "build-server"
workflow:

- **Local build**: fast (no network
  round-trip), private (the source code
  never leaves the device), offline
  (works without network). Limited to
  what the device can compile; a
  Snapdragon Android device compiles
  ARM64 binaries, not x86-64.
- **Remote build**: slow (network
  round-trip + queue), public (the source
  leaves the device), online (requires
  network). Unlimited — a beefy server
  with a fast x86-64 CPU compiles
  anything.

The user picks the strategy per project.
A small Rust CLI compiles locally; a
large C++ game compiles remotely.

### Why a registry + runner split

The registry is the "what's installed"
piece; the runner is the "use it" piece.
A future phase can replace the registry
with a different source (e.g. a remote
toolchain catalogue) without touching the
runner. The runner is the orchestrator.

### Why the `RemoteBuildClient` is a stub for v0

The actual remote server is a separate
project. Phase 56 ships the interface +
a `FakeRemoteBuildClient` (for tests).
The real `HttpRemoteBuildClient` is a
follow-up that requires:
- A server (Oracle Free tier, or a
  user's own server).
- Authentication (the user has an
  account; the runtime holds a token).
- Artifact delivery (the server returns
  the built binary; the client receives
  it).

Phase 56 ships the seam; the production
impl is a Phase 60+ concern (when the
server is real).

## Consequences

Positive:

- The Vanguard Build pillar is now real
  at the configuration layer. The user
  can detect which toolchains are
  installed, build a project locally,
  and (Phase 60+) build a project
  remotely.
- Local build uses the existing
  [ProcessLauncher] (Phase 36) — no
  new spawn mechanism.
- The registry is JVM-testable. A test
  injects a fake toolchain directory
  with a fake binary; the registry
  reports the toolchain as installed.

Negative:

- The `RemoteBuildClient` is a stub in
  Phase 56. The user can integrate with
  a real remote server by implementing
  the interface; the production impl is
  a follow-up.
- The `BuildResult` does NOT yet
  include SBOM (Software Bill of
  Materials). The master vision says
  SBOM is part of the remote build
  contract; Phase 56 ships the
  artifact (binary + manifest); SBOM
  generation is a follow-up.

## Revisit triggers

- If the runtime gains a per-toolchain
  version manager (e.g. "use Rust
  1.75.0 not 1.74.0"), the
  `ToolchainRegistry` grows a `version:
  String?` field. The runner consults
  the version when spawning the
  toolchain.
- If the runtime gains a "build cache"
  (a remote cache of pre-built artifacts
  for popular crates / npm packages), the
  `LocalBuildRunner` consults the cache
  before invoking the local toolchain.
  Phase 60+ concern.
- If the remote build gains authentication
  (the user has an Oracle account), the
  `RemoteBuildClient` interface gains an
  `authToken: String?` parameter.
