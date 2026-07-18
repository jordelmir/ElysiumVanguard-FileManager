# ADR-021 — Mount Allowlist Policy + Audit Log

Status: **Accepted** (Phase 50, 2026-07-18)
Owners: Runtime + Security
Supersedes: none
Superseded by: none

## Context

The critical end-to-end integration test from
the Worldwide Vision doc (PHASE 9_WORLDWIDE_VISION)
requires the runtime to "Confirm that no writes
happened outside the authorized workspace."

Until Phase 50 the runtime has no concept of an
"authorized workspace" at the mount level. The
[FilesystemBridge] produces a mount list from
the runtime's namespace configuration, but
nothing checks whether the mount list is what
the user actually authorized.

The challenge: a proot session is user-space. It
**cannot** prevent writes to a path it can see
(PRoot is not a security boundary — it is
filesystem + syscall compatibility in user
space). The only practical way to constrain a
proot session is:

1. **The mount allowlist is the policy.** The
   proot `-b` flag list is the set of paths the
   session can see. If a path is not in the
   allowlist, the session cannot see it, and
   therefore cannot write to it. The allowlist
   is the policy.
2. **Read-only by default.** The Phase 9.6.3
   `MountEntry.readOnly` default is `true`. A
   workspace must opt-in to write access.
3. **Audit every mount decision.** Every
   proposed mount that passes (or fails) the
   policy is recorded in an append-only log. The
   log is the proof the user can present later:
   "these are the only paths the session ever
   saw."

## Decision

We split the policy path into four small
pieces:

1. **`MountPolicy` (data class)** — the
   workspace's allowlist. A list of
   `MountPolicyEntry` records, each with a
   `hostPathPrefix` (the path or path-prefix
   allowed) and a `readOnly` flag. The policy
   also has a `defaultReadOnly: Boolean` and a
   `mode: Mode` (the policy's allowlist model).

2. **`MountPolicyMode` (enum)** — `ALLOWLIST`
   (default; only listed paths are visible),
   `BLOCKLIST` (all paths visible except
   listed), `OPEN` (no policy; every path
   visible). The default mode is `ALLOWLIST`:
   a fresh workspace is locked down, the
   user explicitly opts-in to paths.

3. **`MountPolicyEnforcer` (class)** — the
   runtime-side check. Takes a [MountPolicy]
   and a proposed list of [MountEntry]
   instances; returns a [MountEnforcementResult]
   that is either `Allowed(filteredMounts)` or
   `Denied(violations)`. The filtered list is
   the intersection of the proposed mounts and
   the policy allowlist. The violations list
   names each entry that was denied + the
   reason.

4. **`MountAuditLog` (interface + file-backed
   impl)** — the append-only record of every
   mount decision. Each entry is a
   [MountAuditEntry] (timestamp, workspaceId,
   sessionId, hostPath, guestPath, decision,
   reason). The interface is JVM-testable;
   the file impl is an NDJSON file at
   `<filesDir>/runtime/mount-audit.ndjson`.

### Why `ALLOWLIST` is the default

A fresh workspace has no policy. The runtime
must assume the user has NOT authorized any
mounts. The `ALLOWLIST` mode + an empty
allowlist means "no mounts allowed". The user
must explicitly add paths to the policy
(typically: `/sdcard/photos`,
`/elysium/vault/<some-folder>`, etc.). The
opposite default (`OPEN`) would mean a fresh
workspace can mount any path on the host —
including `/sdcard/private`, `/data/data/*`,
or `/proc`. That's the wrong default for a
security-conscious runtime.

The `BLOCKLIST` mode is for advanced users
who want a permissive workspace with a small
list of forbidden paths. The `OPEN` mode is
for the rare "I really do want this session
to see everything" case. Both are explicit
opt-ins.

### Why the enforcer returns a filtered list, not a boolean

The user said:

> "Mount only the folder the user selected."

The enforcer is the place that produces the
*actual* mount list the launcher feeds to
proot. If the user requests three mounts and
the policy only allows two, the enforcer
returns the two that are allowed. The caller
(proot launcher) does not need to know about
the policy at all — it gets a clean
`MountEntry` list and feeds it to `proot -b`.

This is the same model Android's Storage
Access Framework uses: the user picks a
folder, the app receives a URI, the URI is
the only path the app can read. The runtime
applies the same model at the mount level.

### Why the audit log is a separate file

The audit log is durable, append-only, and
sized for forensic use. The runtime event
log (`RuntimeEventLog`) is the in-process
event stream; a single event is fine-grained
enough for UI but not for forensic
reconstruction (a user wants "what was
mounted at 14:32" as a single queryable
record, not a stream of network-decision
events). A separate NDJSON file with one
entry per mount decision is the right shape.

The mount audit log is consulted by:

- The user, via the workspace detail screen
  (Phase 50+ UI).
- The runtime itself, on a "denied" decision
  to publish a `MountPolicyViolationEvent`
  on the bus.
- A future "policy violation detector" job
  that surfaces suspicious patterns
  (e.g. a session that requests 50 mounts
  in 10 seconds).

### Why the enforcer is not the proot launcher

The proot launcher consumes `MountEntry`
lists and shells out. The enforcer consumes
`MountEntry` lists + a `MountPolicy` and
returns a filtered `MountEntry` list. The
two are independent: a future "scoped
container runtime" (Docker, runc) could use
the same enforcer without using proot at
all.

The integration point is the launcher: the
`LinuxProotSessionRunner` (Phase 30) reads
the workspace's mount policy, runs the
enforcer on the requested mount list, and
feeds the filtered list to the proot
launcher. Phase 50 wires the enforcer
interface; the integration with the runner
is a Phase 51 follow-up.

## Consequences

Positive:

- The critical end-to-end integration test
  now has a real "no writes outside
  authorized workspace" path. The
  allowlist is the policy; the audit log is
  the proof; a future syscall-tracing
  subsystem can layer on top to detect
  *attempts* (not just successful mounts).
- The runtime's security model is
  "default-deny" at the mount level. A
  fresh workspace is locked down; the user
  must explicitly opt-in to paths.
- The audit log is a small, focused
  forensic surface. A user with a security
  question can attach the log file to a
  support ticket and the support team can
  answer "what was this session allowed to
  see" in one query.

Negative:

- The proot `-b` list is the policy, but
  proot is not a security boundary. A
  well-crafted binary inside the session
  can still escape the namespace via
  `/proc/self/exe` -> `/proc/<pid>/root` and
  read paths the proot namespace would
  otherwise hide. The allowlist is the
  *defense in depth*, not the sole line of
  defense. The runtime's broader security
  story (PRoot is not a security boundary)
  is documented in the Worldwide Vision
  doc and is a Phase 53+ concern (real
  isolation requires a Linux kernel +
  namespaces + SELinux on Android).
- The mount allowlist is path-prefix
  matching. A workspace that allows
  `/sdcard/photos` also allows
  `/sdcard/photos-private`. A future
  "exact path match" mode is a trivial
  follow-up; Phase 50 ships prefix
  matching because it is the 80/20 case.

## Revisit triggers

- If the runtime gains a real
  namespace-based isolation (Linux
  containers, Android `isolatedProcess`),
  the allowlist moves from "mount filter"
  to "namespace capability set". The
  enforcer interface accommodates the
  change (it consumes a list of proposed
  capabilities and returns the
  intersection with the policy), but the
  current impl would be replaced.
- If the runtime gains per-file mount
  permissions (vs path-prefix), the
  `MountPolicyEntry` grows a `pathGlob`
  field. The enforcer's matching logic
  becomes glob-aware.
- If the audit log grows to > 100 MB, a
  log rotation job is needed. Phase 50
  ships a single NDJSON file; rotation is
  a Phase 60+ concern.
