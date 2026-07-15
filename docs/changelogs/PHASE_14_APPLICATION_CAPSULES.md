# Phase 14 — Application Capsules

Date: 2026-07-15
Status: **Shipped** — `assembleDebug` green, 1071 tests, 0 failures, 2 skipped.

## What landed

The runtime now has a typed description of an application — the
[ApplicationCapsule](../adr/ADR-005-application-capsules.md) — and a
small inspector that validates it against the device's capabilities.

### Files

**Production (3 new):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/capsule/ApplicationCapsule.kt`
  — the typed contract. Group: identity, requirements, permissions,
  presentation, source. `init` enforces format invariants (blank id,
  non-hex signature, missing architecture, NUL in env vars).
- `app/src/main/java/com/elysium/vanguard/core/runtime/capsule/CapsuleRegistry.kt`
  — thread-safe `Map<String, ApplicationCapsule>`. `synchronized(lock)`
  for the read and write paths; concurrent-install test exercises 8 × 50
  threads with 0 races.
- `app/src/main/java/com/elysium/vanguard/core/runtime/capsule/CapsuleInspector.kt`
  — pure-JVM policy. Architecture match, `BLOCKED_BY_DRIVER` /
  `UNSUPPORTED` / `REQUIRES_VM` rules, GPU / memory warnings. Returns
  a typed `Result(isValid, issues)` with `Issue(severity, message)`.

**Tests (1 new):**

- `app/src/test/java/com/elysium/vanguard/core/runtime/capsule/CapsuleRegistryAndInspectorTest.kt`
  — 23 tests across the three concerns. Pins the reverse-DNS regex
  (GIMP rejected, org.elysium.gimp accepted), the format invariants,
  the registry's thread-safety, and every inspector rule.

**ADR (1 new):**

- `docs/adr/ADR-005-application-capsules.md` — context, decision,
  consequences, alternatives, revisit triggers.

### What the data class covers

The `ApplicationCapsule` is the master-order §14 "five-W" of an app:

- **What it is** — `id` (reverse-DNS), `displayName`, `version`,
  `description`, `entrypoint`, `environment`, `source`.
- **What it requires** — `runtime: RuntimeRequirement` (preferred +
  fallbacks), `architecture: Set<CpuArch>`, `resources: CapsuleResources`.
- **What it can touch** — `permissions: CapsulePermissions` (files /
  clipboard / network), `storage: StoragePolicy` (PRIVATE / SHARED /
  BOUND), `network: NetworkPolicy` (per-target).
- **How it presents** — `display: DisplayMode`, `gpu: GpuProfile`,
  `audio: AudioProfile`.
- **Where it came from** — `compatibility: CompatibilityState`,
  `source: PackageSource`.

### What the inspector pins

| Rule | Severity |
|---|---|
| `capsule.architecture ∩ device.cpuArch = ∅` | ERROR |
| `compatibility == UNSUPPORTED` | ERROR |
| `compatibility == BLOCKED_BY_DRIVER` | ERROR |
| `compatibility == REQUIRES_VM` and no `linux-vm*` fallback | ERROR |
| `gpu == OPENGL` and `!device.hasOpenGL` | WARNING |
| `gpu == VULKAN` and `!device.hasVulkan` | WARNING |
| `device.totalMemoryMb < memoryRecommendedMb` | WARNING |

The split — `init` for format, `inspect` for policy — is enforced by
the tests: `init`-block tests live in the "capsule init" group,
policy tests in the "inspector" group.

## Why this matters

Before Phase 14, the runtime could install a rootfs and install a
profile, but had no typed description of the *thing the user actually
runs*. That meant:

- No way to ask "will GIMP work on this device?" with a typed answer.
- No per-app permission boundaries (filesystem, clipboard, network,
  GPU were rootfs-global).
- No typed way to declare a runtime fallback chain (`linux-direct`
  → `linux-vm`).

The capsule closes all three. Phase 15 (network enforcement) and
Phase 16 (end-to-end provisioning) read the same `permissions` and
`network` fields and the same `RuntimeRequirement.fallbacks` chain.

## Test count

| Suite | Tests | Failures |
|---|---|---|
| `CapsuleRegistryAndInspectorTest` | 23 | 0 |
| **Project total** | **1071** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Bug found and fixed during this phase

A test on the inspector's `compatibility` field exercised a `BLOCKED_BY_DRIVER` path that was using
`capsule.compatibility.name.lowercase()` to look up a driver key — the enum
constant name `"BLOCKED_BY_DRIVER"` is not a real driver family.
Simplified the rule to: "if the capsule says it's blocked, it's
blocked" (the runtime will override the verdict in a later phase once
we wire driver-availability detection in).

Also caught a regex bug: the original `ID_REGEX` accepted bare names
like "GIMP" because it required at least one character but not a dot.
Tightened to require at least one dot — the convention is
reverse-DNS — while keeping the inner-segment rule lenient enough to
accept `com.example.v10` and `org.elysium.app2` (common in
version-stamped catalogs).

## Next phase

**Phase 15 — Real network enforcement.** The `NetworkBroker` from
Phase 13 is a pure-JVM decision engine; Phase 15 wires the decision
to the system firewall. The `NetworkPolicy` from Phase 14's capsule
is the per-app source of truth, so the enforcer reads
`capsule.network.publishedPorts` / `allowedRemoteHosts` to build the
cgroup / iptables rules.
