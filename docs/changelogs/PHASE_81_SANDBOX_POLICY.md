# Phase 81 — Sandbox + Mount Policy (Universal Execution Engine: Sandbox and Mount Policy)

> **Status:** ✅ Shipped
> **Date:** 2026-07-19
> **Phase:** 81 / EV runtime / Sandbox and Mount Policy
> **Predecessor:** Phase 80 (Recovery Policy, Universal Execution Engine: Telemetry and Recovery)
> **Vertical:** EV runtime (`com.elysium.vanguard.core.orchestrator.*`)

---

## TL;DR

The Universal Execution Engine's
**Sandbox and Mount Policy** step is
operational. The typed spec for the
sandbox + bind mount configuration for
a workspace.

Per the master vision's Universal
Execution Engine (section 6), the
dispatch flow is:

```
Runtime Selection (Phase 76 first half)
    ↓
**Sandbox and Mount Policy** ← this phase
    ↓
Process Supervisor (Phase 78)
    ↓
Telemetry and Recovery (Phase 79 + 80)
```

The sandbox policy is the **typed spec**
for the workspace's sandbox. The policy
describes **WHAT** a process can access
(the bind mounts, the resource limits,
the network policy, the SELinux profile),
not **HOW** (the actual SELinux + bind
mount enforcement is in the OS).

The policy is **8 primitives**:

1. **`WorkspaceId`** — the typed id of
   a workspace.
2. **`MountMode`** (enum, 3 values) —
   the mount mode: `READ_ONLY` /
   `READ_WRITE` / `READ_ONLY_NO_EXEC`.
3. **`MountPurpose`** (sealed class, 6
   cases) — the typed purpose: `SystemLibraries`
   / `WorkspaceData` / `WorkspaceConfig`
   / `DeviceNodes` / `Tmpfs` / `Custom`.
4. **`MountEntry`** — a single bind
   mount.
5. **`NetworkPolicy`** (sealed class, 4
   cases) — the network policy: `Denied`
   / `LocalOnly` / `Allowlisted` / `Full`.
6. **`SecurityProfile`** (sealed class, 4
   cases) — the SELinux profile:
   `Permissive` / `Standard` / `Strict`
   / `Custom`.
7. **`SandboxLimits`** — the resource
   limits (memory, CPU, fds, processes,
   disk write).
8. **`SandboxPolicy`** — the full
   sandbox policy (the composition).

Plus a validator:

- **`SandboxPolicyValidator`** (sealed
  class) — the validator with `validate` +
  `isValid`.
- **`InMemorySandboxPolicyValidator`**
  (impl) — the stateless validator (used
  in tests + production).
- **`SandboxPolicyError`** (sealed class,
  4 cases) — the typed error envelope.

The policy is **pure-domain** (no I/O,
no Android dependencies). The validator
is used in tests + production (the
validator is stateless + pure).

---

## What shipped

### `WorkspaceId` (UUID value class)

The typed id of a workspace. The id is
a UUID (per the Foundry id convention).
The id is the join key the orchestrator
uses to find the workspace's sandbox
policy.

### `MountMode` (enum, 3 values)

The mount mode. The mode determines
whether the mounted path is read-only,
read-write, or read-only without exec:

- **`READ_ONLY`** — the process can read
  the path but not write to it.
- **`READ_WRITE`** — the process can
  read + write the path.
- **`READ_ONLY_NO_EXEC`** — the process
  can read the path but not write to it
  AND not execute binaries from it.

### `MountPurpose` (sealed class, 6 cases)

The typed purpose of a mount. The
purpose is used by the validator to
enforce invariants (e.g. a
`WorkspaceData` mount must be
`READ_WRITE`; a `SystemLibraries`
mount must be `READ_ONLY`):

- **`SystemLibraries`** — the OS
  libraries (e.g. `/usr/lib`, `/lib`).
  Always `READ_ONLY`.
- **`WorkspaceData(workspaceId)`** — the
  user's data (the user-selected folder).
  Always `READ_WRITE`.
- **`WorkspaceConfig(workspaceId)`** —
  the workspace config. Always
  `READ_WRITE`.
- **`DeviceNodes`** — the device nodes
  (e.g. `/dev/null`, `/dev/zero`).
  Always `READ_WRITE`.
- **`Tmpfs(sizeMb)`** — a tmpfs mount
  (an in-memory filesystem). Always
  `READ_WRITE`.
- **`Custom(name)`** — a custom purpose
  (a purpose that does not fit the
  standard categories). Mode is
  unconstrained.

### `MountEntry` (data class)

A single bind mount. The mount has:

- **`source`** — the path on the host
  (the path that is bind-mounted into the
  sandbox).
- **`target`** — the path in the sandbox
  (the path the process sees inside the
  sandbox).
- **`mode`** — the mount mode
  (`READ_ONLY` / `READ_WRITE` /
  `READ_ONLY_NO_EXEC`).
- **`purpose`** — the typed purpose of
  the mount.

`source` and `target` MAY be equal
(e.g. a system library mount
`source = /usr/lib, target = /usr/lib`
is a common pattern; the mount system
re-applies the path inside the sandbox's
rootfs). The check is intentionally NOT
enforced at the type level.

### `NetworkPolicy` (sealed class, 4 cases)

The network policy. The policy determines
the network access for the process:

- **`Denied`** — no network access. The
  process is **completely isolated** from
  the network (no loopback, no internet).
- **`LocalOnly`** — only loopback
  (127.0.0.1 + ::1). The process can
  access loopback but no external hosts.
- **`Allowlisted(allowlist)`** — only
  allowlisted hosts (by hostname). The
  allowlist MUST be non-empty + contain
  no blank hostnames.
- **`Full`** — full network access.

### `SecurityProfile` (sealed class, 4 cases)

The SELinux security profile. The profile
determines the SELinux policy for the
process. Per the master vision (section
9): "Prohibición de ejecutar como root
salvo necesidad comprobada." The
platform defaults to `Standard` for new
workspaces; `Permissive` is for
debugging; `Strict` is for untrusted
code.

- **`Permissive`** — no SELinux
  enforcement.
- **`Standard`** — standard SELinux
  policy (allowlist-based).
- **`Strict`** — strict SELinux policy
  (deny by default).
- **`Custom(selinuxContext)`** — a
  custom SELinux context. The context
  MUST be non-blank + in SELinux format
  (e.g. "user:role:type:level").

### `SandboxLimits` (data class)

The resource limits. The limits determine
the **resource consumption cap** for the
process. A limit of 0 means **unlimited**.

The data class has:

- **`maxMemoryMb: Long`** — the maximum
  memory in megabytes (0 = unlimited).
- **`maxCpuPercent: Int`** — the maximum
  CPU percent (0-100, 0 = unlimited).
- **`maxOpenFileDescriptors: Int`** —
  the maximum number of open file
  descriptors (0 = unlimited).
- **`maxProcesses: Int`** — the maximum
  number of processes (the process + its
  children) (0 = unlimited).
- **`maxDiskWriteMb: Long`** — the maximum
  disk write in megabytes (0 = unlimited).

The class has two factories:

- **`DEFAULT`** — memory 2GB, CPU 50%,
  fds 1024, processes 64, disk write
  100MB.
- **`UNLIMITED`** — all 0s (the process
  can consume unbounded resources).

### `SandboxPolicy` (data class)

The full sandbox policy. The policy is
the composition of:

- **`workspaceId`** — the workspace the
  policy is for.
- **`mounts`** — the list of bind mounts
  (the filesystem configuration).
- **`limits`** — the resource limits.
- **`network`** — the network policy.
- **`security`** — the SELinux security
  profile.
- **`signature`** — the policy's
  signature.

The policy has two helper methods:

- **`mountForTarget(target)`** — get a
  mount by target path. Returns `null` if
  no mount has the given target.
- **`allowsHost(host)`** — check whether
  the policy allows the given host. The
  check uses the network policy
  (`Denied` → false; `LocalOnly` → false;
  `Allowlisted(allowlist)` → `host in
  allowlist`; `Full` → true).

### `SandboxPolicyValidator` (sealed class)

The typed validator. The interface has:

- **`validate(policy)`** — validate a
  policy. Returns a list of
  `SandboxPolicyError`s (the list is
  empty if the policy is valid).
- **`isValid(policy)`** — check whether
  a policy is valid (the convenience
  predicate).

### `InMemorySandboxPolicyValidator` (impl)

The in-memory implementation. The
validator is **stateless** (no mutable
fields); the same impl is used in
production. The validator is thread-safe.

The validator enforces 3 rules:

- **Rule 1: No duplicate mount targets.**
  Two mounts with the same target path
  are rejected (the order of mounts is
  significant; the same target twice is
  ambiguous).
- **Rule 2: SystemLibraries mounts must
  be READ_ONLY.** A read-write mount on
  a read-only purpose (e.g.
  `SystemLibraries`) is rejected.
- **Rule 3: WorkspaceData mounts must be
  READ_WRITE.** A read-only mount on a
  read-write purpose (e.g. `WorkspaceData`)
  is rejected.

### `SandboxPolicyError` (sealed class, 4 cases)

The typed error envelope. The 4
variants:

- **`InvalidWorkspaceIdFormat(rawInput, parseFailure)`**
  — the workspace id string was not a
  valid UUID.
- **`DuplicateMountTarget(target)`** —
  two mounts in the policy have the same
  target path.
- **`ReadWriteMountOnReadOnlyPurpose(target, purpose)`**
  — a mount with a `READ_ONLY`-required
  purpose is not `READ_ONLY`.
- **`ReadOnlyMountOnReadWritePurpose(target, purpose)`**
  — a mount with a `READ_WRITE`-required
  purpose is not `READ_WRITE`.

---

## Design decisions

### Why a sealed class for `MountPurpose`, not an enum?

`MountPurpose` has 6 cases; 2 of them
carry data (`WorkspaceData(workspaceId)`,
`WorkspaceConfig(workspaceId)`,
`Tmpfs(sizeMb)`, `Custom(name)`). An
enum cannot carry data; a sealed class
captures the **data-bearing cases** while
preserving the **exhaustive** `when`.

A data class would be possible but would
require a discriminator field + a manual
exhaustiveness check. The sealed class
is the **canonical Kotlin pattern** for
closed hierarchies with mixed data.

### Why is `WorkspaceData` data-bearing (not `WorkspaceData` as a data object)?

`WorkspaceData` carries the
`workspaceId` because the mount
purpose is **scoped to a specific
workspace**. The validator can check
that the mount's `workspaceId` matches
the policy's `workspaceId` (a future
increment). The data-bearing pattern
captures the **typed relationship**
between the mount and the workspace.

### Why is `NetworkPolicy.Allowlisted` a set, not a list?

A `Set<String>` is the **canonical
representation** for an allowlist:
the order does not matter; duplicates
are rejected automatically. A list
would require manual deduplication +
manual lookup. The set is the
**typed** representation.

### Why is `SecurityProfile.Custom.selinuxContext` a string, not a typed value?

The SELinux context is in the format
`"user:role:type:level"`. A typed value
class would require 4 fields + a custom
parser. A string is the **canonical
SELinux representation**; the validator
only checks that the string is non-blank
+ contains a colon. A future increment
may add a typed `SeLinuxContext` value
class with the 4 fields.

### Why is the validator's "no duplicate targets" rule enforced?

Two mounts with the same target path
are **ambiguous** (the order of mounts
is significant; the same target twice
is a typo). The validator rejects
duplicate targets at the type level
(typed error). The fix is to merge the
two mounts into a single mount with
the union of the modes.

### Why is `SandboxLimits.UNLIMITED` all 0s, not a separate flag?

A separate flag (`isUnlimited: Boolean`)
would require a runtime check. A limit
of 0 is **idiomatic** for "unlimited"
in Linux (`ulimit -t 0` = unlimited CPU
time). The 0 sentinel is the **typed**
representation; the OS-level enforcement
maps 0 to unlimited automatically.

### Why is `source == target` allowed for `MountEntry`?

In container environments, system
mounts like `/usr/lib` → `/usr/lib` and
`/dev` → `/dev` are common. The mount
system re-applies the path inside the
sandbox's rootfs. The check
`source != target` was overly defensive;
the realistic cases use the same path
on both sides.

---

## Tests

26 new tests in `SandboxPolicyTest`. The
tests cover:

- **MountPurpose invariants** (2 tests):
  Tmpfs sizeMb <= 0, Custom blank name.
- **MountEntry invariants** (2 tests):
  accepts a well-formed configuration,
  accepts source == target (system mount
  pattern).
- **NetworkPolicy invariants** (2 tests):
  Allowlisted empty allowlist, Allowlisted
  blank hostnames.
- **SecurityProfile invariants** (2
  tests): Custom blank selinuxContext,
  Custom selinuxContext without colons.
- **SandboxLimits invariants** (5 tests):
  negative maxMemoryMb, maxCpuPercent
  out of range, negative
  maxOpenFileDescriptors, negative
  maxProcesses, negative maxDiskWriteMb.
- **SandboxPolicy — mountForTarget +
  allowsHost** (8 tests): rejects empty
  mounts, mountForTarget returns the
  mount for the target, mountForTarget
  returns null for an unknown target,
  allowsHost returns false for Denied,
  allowsHost returns false for LocalOnly,
  allowsHost returns true for allowlisted
  host, allowsHost returns false for
  non-allowlisted host, allowsHost
  returns true for Full.
- **InMemorySandboxPolicyValidator**
  (4 tests): reports a duplicate mount
  target, reports a read-write mount on
  a read-only purpose, reports a read-only
  mount on a read-write purpose, returns
  empty list for a valid policy.
- **Realistic scenario** (1 test): a
  workspace with system libraries
  (read-only) + workspace data
  (read-write) + device nodes
  (read-write) + a tmpfs.

**Total orchestrator tests:** 165 (was
139; +26 new).
**Total project tests:** 3280 (was 3254;
+26 new).

**1 test-discovered bug fixed** during
this phase:

1. **`MountEntry.source != target`
   check is too strict.** The check was
   intended to prevent typos, but in
   real container environments, system
   mounts like `/usr/lib` → `/usr/lib`
   are common. Fix: remove the check;
   add a comment explaining the
   realistic cases. The realistic
   scenario test was failing on
   `source = /usr/lib, target = /usr/lib`
   for the `SystemLibraries` mount.

---

## Phase 81 closure

**The Universal Execution Engine's
Sandbox and Mount Policy step is
operational.** The chain is now:

```
RuntimeSelector (Phase 76 first half)
    ↓
SandboxPolicy (Phase 81, this phase) ← typed spec for sandbox + mounts
    ↓
RuntimeDispatcher (Phase 76 second half)
    ↓
ProcessLauncher (Phase 78)
    ↓
ProcessWatcher (Phase 79)
    ↓
RecoveryPolicy (Phase 80)
```

The chain is **typed end-to-end** (the
spec describes WHAT the sandbox does;
the OS enforces HOW the sandbox is
implemented). The next step in the
flow is:

- **Phase 82 — AndroidProcessLauncher**
  (the real production impl that uses
  `java.lang.Process` + `ProcessBuilder`
  + the sandbox policy to actually
  launch a sandboxed process on the
  Android device).

---

## What's next

The next concrete deliverable is up to
the user. The remaining work:

### Universal Execution Engine (next concrete)

- **Phase 82 — AndroidProcessLauncher**
  (the real production impl; uses
  `java.lang.Process` + `ProcessBuilder`).
  This is the **first Android-only
  piece** of the UEE (the tests will be
  in `androidTest`, not in `test`).
- **Phase 83 — CriticalE2E with real
  process launcher** (replace the
  InMemoryProcessLauncher in the
  Phase 71 / Phase 77 E2E tests with
  the real AndroidProcessLauncher).

### Elysium Linux (next concrete)

- **Phase 73 fourth half — Minimal rootfs
  + Mesa/Turnip/Box64/FEX/Wine
  integration** (the actual binary;
  reproducible build on a Linux build
  server with ARM64 cross-compilation).
- **Phase 72 — Capsule installer UI**
  (Compose) for the new Elysium Linux
  distro.

### Foundry program (next concrete)

- **Phase F7 (G9+G10) — Production
  hardening**: threat model + SLOs +
  on-call + runbooks + red team + CVE
  SLA + observability + multi-module
  split (per ADR-0023).

---

## Files

| File | Status | Role |
| --- | --- | --- |
| `app/src/main/java/com/elysium/vanguard/core/orchestrator/SandboxPolicy.kt` | new | WorkspaceId + MountMode + MountPurpose + MountEntry + NetworkPolicy + SecurityProfile + SandboxLimits + SandboxPolicy + SandboxPolicyValidator + InMemorySandboxPolicyValidator + SandboxPolicyError |
| `app/src/test/java/com/elysium/vanguard/core/orchestrator/SandboxPolicyTest.kt` | new | 26 JVM tests |

---

## The role in the bigger picture

The Sandbox + Mount Policy is the
**sandbox spec** in the Universal
Execution Engine. The chain is:

1. **File Type / Manifest Detection**
   (Phase 69, Capsule).
2. **Compatibility Resolver** (Phase 71,
   Critical E2E).
3. **Architecture Detection** (Phase 76,
   DeviceProfile).
4. **Runtime Selection** (Phase 76 first
   half, RuntimeSelector).
5. **Sandbox and Mount Policy** (Phase 81,
   this phase) ← typed spec for
   sandbox + bind mounts.
6. **Process Supervisor** (Phase 78,
   ProcessLauncher).
7. **Telemetry and Recovery** (Phase 79 +
   80).

The Sandbox + Mount Policy is the
**typed spec for the workspace's
sandbox**. The policy describes:

- The bind mounts (which host paths
  are exposed to the process; in
  which mode).
- The resource limits (memory, CPU,
  fds, processes, disk write).
- The network policy (denied, local
  only, allowlisted, full).
- The SELinux security profile
  (permissive, standard, strict,
  custom).

The Sandbox + Mount Policy is the
**typed spec for the critical 8-step
E2E** (vision's "Monte únicamente una
carpeta elegida por el usuario"): the
mounts list + the resource limits +
the network policy + the SELinux
profile together describe the
**workspace's security boundary**.
The actual SELinux + bind mount
enforcement is in the OS; the policy
is the **typed config** the OS uses to
enforce the boundary.
