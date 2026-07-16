# ADR-001 — Runtime Backend Abstraction (Strategy Pattern)

Status: **Accepted** (Phase 9.6.3, originally; documented
in Phase 26, 2026-07-15)
Owners: Runtime
Supersedes: none
Superseded by: none

## Context

Elysium Vanguard runs Linux guests on Android. The
mechanism for "run a Linux command in a rootfs" varies
per device:

- **Termux `proot`** (a vendored `libproot.so` in
  `jniLibs/<abi>/`) — true chroot-ish behavior, no root
  needed, available on most devices.
- **`prctl(PR_SET_NO_NEW_PRIVS, 1, …)` + `unshare`** —
  namespace isolation, requires the device to support
  the unshare flags.
- **Direct exec** — run the rootfs' own shell binary
  with `PATH` and `LD_LIBRARY_PATH` pointed at the
  rootfs; the device's loader runs the rootfs' ELFs
  directly. Works everywhere but no chroot.
- **Jailed shell** — sub-process of `/system/bin/sh`
  with the rootfs as cwd. No syscall translation, no
  ELF execution from inside the distro. Cheap and
  works everywhere; weak.

A user that hardcodes one launcher backend bakes in
assumptions about the device. A Pixel 7 with Termux
proot can run a Debian rootfs with `apt-get install`;
an unbranded Android tablet without proot cannot. The
runtime must pick the right backend per device, and
the choice must be testable without a real Android
device.

## Decision

The runtime uses a **strategy pattern** with two
collaborator interfaces:

1. **`DistroLauncher`** — the actual launch backend.
   Each implementation is a class that knows how to
   spawn a process inside a rootfs. The interface
   carries:
   - `kind: LauncherKind` — a tag (JAILED_SHELL /
     NATIVE_PROOT / NAMESPACE_UNSHARE / DIRECT_EXEC)
     the UI and log scrubbers use to identify the
     backend.
   - `capabilities: LauncherCapabilities` — what the
     backend can do (e.g. can run ELFs, can run apt,
     has full namespace isolation).
   - `launch(session): ProcessHandle` — the spawn
     method.

2. **`LauncherResolver`** — the strategy picker. Given
   a `rootfsDir`, the resolver returns the right
   `DistroLauncher` for the device. The default
   production impl (`LauncherResolutionResolver`)
   probes the device at startup: checks for the
   vendored proot library, checks for unshare support,
   then prefers NATIVE_PROOT → falls back to DIRECT_EXEC
   → falls back to JAILED_SHELL.

`DistroManager` takes a `LauncherResolver` constructor
parameter. Tests inject a deterministic resolver
(`{ rootfs -> DirectExecDistroLauncher(...) }`);
production wires the Hilt-managed
`LauncherResolutionResolver.DEFAULT`.

The four concrete launchers:

| Class | Kind | When used |
|---|---|---|
| `NativeProotLauncher` | NATIVE_PROOT | Termux `proot` library present (the typical case on a Pixel) |
| `DirectExecDistroLauncher` | DIRECT_EXEC | No proot, but the device's loader can run the rootfs' ELFs |
| `JailedDistroLauncher` | JAILED_SHELL | Last-resort fallback; the user gets a shell but no package install |
| `NamespaceUnshareLauncher` | NAMESPACE_UNSHARE | (Future) when unshare is supported; the strongest isolation |

The `LauncherKind` enum is what the UI surfaces
("running under proot") and what the log scrubbers
match against. The class name is internal.

### Why a strategy pattern, not a config file

A config-file approach ("which launcher?" in
`elysium.toml`) would let a power user override the
device's default. The strategy pattern is the
*testable* form: the resolver is a Kotlin interface;
the Hilt module injects the production impl; tests
inject a fake. A config file is a future addition
on top of the resolver (the resolver reads the
config, then probes the device).

### Why a `LauncherKind` enum, not just the class

The runtime's UI, log scrubber, and exporter tag log
lines with `LauncherKind` instead of the class name.
A future exporter that ships logs to a support
endpoint serialises the kind, not the class. The
class name is internal; the kind is the public
contract.

### Why a single `LauncherResolver`, not one per distributor

The right backend is a property of the *device*, not
of the distributor. A Debian rootfs and an Alpine
rootfs on the same device should use the same
backend. The resolver is keyed on `rootfsDir` (the
runtime may have multiple rootfs dirs and the resolver
returns the right launcher for each).

## Consequences

### Positive

- **Testable end-to-end.** The resolver + the four
  launchers are pure JVM. A test wires a fake
  resolver and asserts on the launcher's spawn
  arguments. The Hilt module wires the production
  impl; the device probe is integration territory.
- **Per-device selection.** A Pixel 7 with proot
  gets the proot launcher; a tablet without proot
  gets the direct-exec fallback. The user does not
  configure; the runtime probes.
- **New backends are a class addition.** A future
  WSL2-style backend, a Crostini-style backend, or a
  custom-research backend (e.g. Wasm-based) drops in
  as a new `DistroLauncher` implementation. The
  resolver gains a new branch; the manager's contract
  is unchanged.
- **Public contract is the kind, not the class.** The
  UI surfaces the kind; the log scrubbers match the
  kind. A user reading the changelog sees "added
  DIRECT_EXEC launcher", not "added
  DirectExecDistroLauncher class".

### Negative

- **The resolver's device probe is integration-only.**
  The `LauncherResolutionResolver.DEFAULT` probes the
  device at startup; the JVM test path uses a fake
  resolver. The integration test (on-device) is the
  smoke that catches a misconfigured probe.
- **The kind enum is closed.** A new kind is a code
  change. A future backend that does not fit
  `LauncherKind` needs the enum extended.
- **The resolver is synchronous.** A slow probe (e.g.
  reading a 50 MB `libproot.so` to check its magic
  bytes) blocks the call. A future phase caches the
  probe result; for now the probe is in-memory and
  fast.

## Alternatives considered

1. **Hardcode the launcher backend.** The simplest
   thing that works. Rejected: a user on a tablet
   without proot would get a JAILED_SHELL even when
   DIRECT_EXEC would work. The strategy pattern is
   the right shape for "device-dependent choice".
2. **Config file (`elysium.toml`).** Lets a power
   user override. Rejected: the strategy pattern
   *is* the override (the test wires any resolver
   the user wants). A config file is a future
   addition on top.
3. **One launcher per distributor.** Rejected: the
   backend is a property of the device, not the
   distributor. A Pixel 7 with proot and an Alpine
   rootfs should use proot for Alpine AND for Debian.

## Revisit triggers

- A new launcher backend ships (Crostalina, a
  research project, etc.). The `DistroLauncher`
  interface gains a class; the `LauncherResolver`
  gains a probe branch; the `LauncherKind` enum
  gains a value.
- The probe is slow on first launch. A future
  phase caches the probe result in the Hilt
  singleton; the resolver is a `lazy { … }` field.
- A user wants to override the resolver with a
  config file. The resolver reads `elysium.toml`
  on init; the device probe is the fallback.
