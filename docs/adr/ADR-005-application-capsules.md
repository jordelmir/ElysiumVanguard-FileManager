# ADR-005 — Application Capsules

Status: **Accepted** (Phase 14, 2026-07-15)
Owners: Runtime
Supersedes: none
Superseded by: none

## Context

Master order §14 says: *"Each application installed is identified by a
capsule, a typed description of what the application is, what it
requires, what it can touch, and how it presents itself."* Until Phase
14, the runtime had `DistroInstaller` (rootfs-level provisioning),
`ProfileInstaller` (package-level provisioning), and a long list of
launchers, but no uniform description of an *application* — the thing
the user actually runs.

Without a typed capsule, three things were impossible:

1. **Honest compatibility reporting.** A user asking "will GIMP run on
   my device?" could not get a typed answer; they got a guess from the
   catalog metadata.
2. **Per-app permission boundaries.** Filesystem, clipboard, network,
   and GPU access were global per rootfs, not per application. A
   paranoid user could not run a web browser inside the same rootfs as
   a text editor and ask the runtime to wall them off.
3. **Lifecycle independence.** Apps that needed to ship their own
   runtime fallback (`linux-direct-arm64` → `linux-vm`) had no typed
   way to declare it. They were hard-coded in the launcher's
   `RuntimeResolver`.

The capsule is the contract that closes all three gaps.

## Decision

A capsule is an immutable, signed `data class ApplicationCapsule`
(see `app/src/main/java/com/elysium/vanguard/core/runtime/capsule/
ApplicationCapsule.kt`). The typed surface groups the master order's
"five-W" of an application:

- **What it is** — `id` (reverse-DNS), `displayName`, `version`,
  `description`, `entrypoint`, `environment`, `source`.
- **What it requires** — `runtime` (preferred + fallbacks),
  `architecture`, `resources` (memory / disk).
- **What it can touch** — `permissions` (files / clipboard / network)
  and the rootfs-level `storage` and `network` policies.
- **How it presents** — `display` (SEAMLESS / WINDOW / FULLSCREEN),
  `gpu` (SOFTWARE / VULKAN / OPENGL / EXPERIMENTAL), `audio`.
- **Where it came from** — `compatibility` (runtime-known state) and
  `source` (OFFICIAL_REPO / COMMUNITY / USER_SUPPLIED).

The runtime enforces the capsule at three points:

1. **Construction time** — the data class's `init` block validates
   format invariants (blank id, non-hex signature, missing
   architecture, NUL bytes in environment).
2. **Install time** — `CapsuleRegistry.install(capsule)` stores it.
   The registry does not verify signatures; that's `CapsuleInspector`'s
   job.
3. **Inspect time** — `CapsuleInspector.inspect(capsule, device)`
   produces a typed `Result` with `Issue(severity, message)` entries.
   The UI and the provisioning pipeline branch on `severity` and
   message text, never on a free-form string from the runtime.

The inspector's policy is:

| Rule | Severity | Outcome |
|---|---|---|
| `capsule.architecture ∩ device.cpuArch = ∅` | ERROR | refuse install |
| `compatibility == UNSUPPORTED` | ERROR | refuse install |
| `compatibility == BLOCKED_BY_DRIVER` | ERROR | refuse install |
| `compatibility == REQUIRES_VM` and no `linux-vm*` fallback | ERROR | refuse install |
| `gpu == OPENGL` and `!device.hasOpenGL` | WARNING | install with caveat |
| `gpu == VULKAN` and `!device.hasVulkan` | WARNING | install with caveat |
| `device.totalMemoryMb < memoryRecommendedMb` | WARNING | install with caveat |

The split — `init` enforces format, `inspect` enforces policy — keeps
each layer independently testable. `init` runs in O(1) and can be
called inside hot paths. `inspect` runs once at install time and is
allowed to read the device capability set.

### Why a data class, not a sealed interface

The capsule is **immutable, transported, and serialized**. A sealed
interface buys us polymorphism (e.g. `Capsule.Linux`, `Capsule.Win`)
at the cost of two more types to wire through Hilt. We get the same
polymorphism via the `runtime: RuntimeRequirement` field, which lists
`preferred` and `fallbacks` as `RuntimeId` strings. The runtime
resolves them; the capsule stays simple.

The signature is a string, not a typed `Signature` object, so capsules
can be parsed from JSON manifests without dragging in JCA at the
catalog level. Verification (Phase 12.4) re-uses the same Ed25519
manifest-signer path.

### Why a thread-safe `CapsuleRegistry`

The catalog install path runs in a worker thread; the launcher reads
from the main thread; the inspector lives in a Hilt singleton. A
plain `mutableMapOf` would race on `install` and `list`. A
`synchronized(lock)` block keeps the read path O(1) (lock is held
briefly) and the write path correct (no torn writes). The test
`registry is thread-safe under concurrent install` exercises 8 × 50
concurrent installs.

### Why `compatibility` is on the capsule, not the device

The runtime's *current* verdict on this capsule is a property of the
capsule + the device, not of either alone. Putting it on the capsule
lets the catalog pre-bake "VERIFIED on Pixel" / "PARTIAL on Samsung"
states. Putting it on the device would mean the device has to know
about every capsule ever shipped. The inspector fuses the two at
inspect time.

## Consequences

### Positive

- **Single source of truth.** Every layer (catalog, runtime, UI,
  provisioning) reads the same `ApplicationCapsule`. No more
  duplicated "what is this app" descriptions.
- **Testable policy.** `CapsuleInspector` is pure JVM. The Phase 14
  test file pins every rule with a `Result` assertion.
- **Per-app boundaries.** The `permissions` and `network` fields
  give the runtime enough information to wall apps off in later
  phases (per-capsule cgroup / iptables / eBPf hooks).
- **Format invariants at construction.** A bad capsule never enters
  the runtime; the `init` block throws before the registry sees it.

### Negative

- **Two enforcement layers (`init` + `inspect`).** Future contributors
  must remember to add a rule in both places. We mitigate this by
  keeping the `init` block to *format* checks and the `inspect` block
  to *policy* checks — never overlap.
- **Reverse-DNS is a convention, not a guarantee.** A user can ship a
  capsule named `com.google.chrome` and we will not catch the typo
  upstream of signature verification. We mitigate this by
  categorising `USER_SUPPLIED` capsules as "trust the user; show the
  source in the UI" rather than auto-rejecting.
- **Signature string is opaque to the registry.** A typo in a 192-char
  hex string passes the regex; the verifier catches it at install
  time. We accept this because the verifier is the source of truth
  for the bytes — anything we added to the regex would just be a
  duplicate check.

## Alternatives considered

1. **Use a sealed interface `Capsule.Linux` / `Capsule.Win`.** Rejected
   for Phase 14: the OS-specific fields (env vars, path conventions)
   are already covered by `RuntimeRequirement`. The polymorphism
   doesn't pay for itself until Phase 19 (WinLayer). We can introduce
   it then without breaking the registry's wire format.
2. **Skip the registry; just persist `capsules.json`.** Rejected: the
   registry's value is the *testable in-memory model*. Persistence is
   a separate concern; we wire it up in a later phase behind a
   `CapsuleStore` interface.
3. **Enforce everything in `init`.** Rejected: `init` has no access
   to `DeviceCapabilities`. Inspect time is the right place for
   device-dependent policy.

## Revisit triggers

- Adding a new runtime (e.g. macOS-via-Translated) — the
  `RuntimeRequirement` shape and the inspector's VM-fallback rule may
  need to broaden.
- A real catalog ships the first malicious capsule — we may want a
  signature-cache inside the registry to avoid re-verifying on every
  lookup.
- The user wants per-capsule resource limits (cgroup-based) — that's
  Phase 15 territory; this ADR does not block it.
