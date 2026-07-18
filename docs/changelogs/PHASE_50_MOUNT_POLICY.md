# Phase 50 — Mount Allowlist Policy + Audit Log

Date: 2026-07-18
Status: **Shipped** — `assembleDebug` green, 1566 tests, 0 failures, 2 skipped.

## What landed

The runtime now has an explicit mount allowlist
policy. Every workspace declares which host
paths a session is allowed to bind-mount, and
every decision is recorded in a file-backed
audit log. This is steps 5 and 8 of the
critical end-to-end integration test
("Mount only the folder the user selected" +
"Confirm no writes outside the authorized
workspace").

The default policy is `ALLOWLIST` + empty
entries — a fresh workspace cannot mount
anything. The user must explicitly add paths
to the policy before a session can mount them.
The defense in depth: proot's user-space
namespace hides paths that are not mounted,
so a path not in the allowlist is invisible
to the session, and therefore unwritable.

## Files

**Production (4 new + 4 modified):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/policy/MountPolicy.kt` —
  the value types. `MountPolicy` carries the
  `mode` (ALLOWLIST / BLOCKLIST / OPEN) and a
  list of `MountPolicyEntry` records. Two
  convenience constants: `MountPolicy.LOCKED_DOWN`
  (ALLOWLIST + empty + defaultReadOnly=true)
  and `MountPolicy.OPEN` (OPEN mode + no
  default read-only). The `MountPolicyEntry`
  exposes a `normalisedPrefix` getter that
  strips a single trailing slash so `/sdcard/`
  and `/sdcard` match the same paths.
- `app/src/main/java/com/elysium/vanguard/core/runtime/policy/MountPolicyEnforcer.kt` —
  the runtime-side check. Takes a policy + a
  proposed `MountEntry` list and returns
  either `MountEnforcementResult.Allowed(filteredMounts)`
  or `MountEnforcementResult.Denied(allowedMounts, violations)`.
  In ALLOWLIST mode, a mount is allowed iff
  its hostPath starts with a policy entry's
  `normalisedPrefix`; in BLOCKLIST mode, a
  mount is allowed iff it does NOT match; in
  OPEN mode, every mount is allowed.
  Read-only tightening: a policy entry with
  `readOnly = true` forces the matching mount
  to be read-only even if the proposed mount
  says writeable. The policy can only TIGHTEN,
  never LOOSEN.
- `app/src/main/java/com/elysium/vanguard/core/runtime/policy/MountAuditLog.kt` —
  the append-only audit log. `MountAuditEntry`
  is a single decision (timestamp,
  workspaceId, sessionId, hostPath, guestPath,
  decision, reason). The interface
  (`MountAuditLog`) is JVM-testable; the
  production impl is `FileMountAuditLog` which
  writes one JSON object per line to
  `<filesDir>/runtime/mount-audit.ndjson`.
  Append is `@Synchronized`; the file's
  parent dir is created on construction.
- `app/src/main/java/com/elysium/vanguard/core/runtime/workspaces/WorkspaceManager.kt` —
  the manager gains two new constructor
  parameters (both default `null` for
  backwards-compat): `mountPolicyEnforcer:
  MountPolicyEnforcer?` and `mountAuditLog:
  MountAuditLog?`. The new method
  `enforceMountPolicy(workspaceId, sessionId, policy, mounts)`
  is the user-facing entry point. On
  success, every decision is appended to the
  audit log AND published on the
  `RuntimeEventBus`.
- `app/src/main/java/com/elysium/vanguard/core/runtime/observability/RuntimeEvent.kt` —
  two new events: `MountAllowedEvent` (with
  `readOnly: Boolean`) and
  `MountPolicyViolationEvent` (with `reason:
  String`).
- `app/src/main/java/com/elysium/vanguard/core/runtime/observability/RuntimeEventLog.kt` —
  the file-backed audit log gains two new
  JSON Lines render paths + two parse paths so
  the new events round-trip through the log
  file.
- `app/src/main/java/com/elysium/vanguard/core/runtime/RuntimeModule.kt` —
  Hilt module gains two new providers:
  `provideMountPolicyEnforcer` (stateless;
  singleton) and `provideMountAuditLog` (the
  `FileMountAuditLog` at the standard
  location). The `provideWorkspaceManager`
  provider now passes both into the manager.
- `docs/adr/ADR-021-mount-allowlist-policy.md` —
  the architectural decision record. Captures
  the four-piece split (MountPolicy,
  MountPolicyEnforcer, MountAuditLog,
  WorkspaceManager integration), the
  "default-deny" rationale, the
  path-prefix-matching model, and the
  revisit triggers (real namespace isolation,
  per-file permissions, log rotation).

**Tests (3 new):**

- `app/src/test/java/com/elysium/vanguard/core/runtime/policy/MountPolicyEnforcerTest.kt` —
  18 tests covering: MountPolicyEntry
  init-block invariants (non-blank, absolute,
  non-trailing-slash-only); MountPolicy
  init-block invariants (no duplicate prefixes,
  LOCKED_DOWN + OPEN convenience constants);
  ALLOWLIST mode (allows a mount whose
  hostPath matches an entry's prefix,
  allows a sub-path, denies a mount outside
  the allowlist, denies a mount whose prefix
  is a sibling not a child — the
  "/sdcard/photos" vs "/sdcard/photos-private"
  case, tightens a writeable mount to
  read-only, does not loosen a read-only
  mount, empty entries deny every mount);
  BLOCKLIST mode (allows a mount outside the
  blocklist, denies a mount that matches a
  blocklist entry); OPEN mode (allows every
  mount regardless of entries); mixed result
  (returns both allowed subset and
  violations).
- `app/src/test/java/com/elysium/vanguard/core/runtime/policy/FileMountAuditLogTest.kt` —
  9 tests covering: parent directory
  creation, empty log returns empty readAll,
  append + readAll round-trip in append
  order, survives a fresh log instance
  reading the same file, clear truncates,
  size grows as entries are appended, JSON
  escaping (quotes / backslashes / newlines /
  tabs), MountAuditEntry rejects unknown
  decision, accepts every documented
  decision.
- `app/src/test/java/com/elysium/vanguard/core/runtime/workspaces/WorkspaceManagerMountPolicyTest.kt` —
  9 tests covering: enforceMountPolicy
  returns Allowed when the policy permits
  every mount, returns Denied when the policy
  denies a mount, appends one audit entry per
  allowed mount, appends a Denied entry for
  each violation, publishes a MountAllowedEvent
  for each allowed mount, publishes a
  MountPolicyViolationEvent for each denied
  mount (with a reason that mentions the
  allowlist), returns NotFound for an unknown
  workspace id, is permissive when no enforcer
  is configured (backwards-compat), tightens
  a writeable mount to read-only and records
  the decision in the event's readOnly field.
  Includes a hand-rolled `FakeMountAuditLog`
  that records every appended entry in a
  thread-safe list.

## Why this matters

The critical end-to-end integration test from
the Worldwide Vision doc requires:

> "Mount only the folder the user selected.
> ... Confirm that no writes happened outside
> the authorized workspace."

Until Phase 50 the runtime had no enforcement
of either. The proot launcher's `proot -b`
flag list was whatever the runtime asked for.
Phase 50 closes the loop:

- A workspace declares its policy
  (`MountPolicy` with allowlist entries).
- A session proposes a mount list (the
  `MountEntry` list the runner would feed to
  proot).
- The `MountPolicyEnforcer` returns the
  filtered list (the intersection of the
  proposed list and the policy's allowlist)
  + any violations.
- The runner (Phase 51+) feeds the filtered
  list to proot, not the original list.
- The audit log records every decision for
  forensic reconstruction.

A user with a "did this session write to
/secret/keys.txt?" question can now attach
the `mount-audit.ndjson` file to a support
ticket and the support team can answer in
one query. Before Phase 50 the question
could not be answered at all.

## What the test suite caught

- **`/sdcard/photos` vs `/sdcard/photos-private`**
  — the test `ALLOWLIST denies a mount whose
  prefix is a sibling, not a child` pins the
  path-prefix matching (not string-prefix
  matching). A naive `String.startsWith` on
  the raw prefix would let `/sdcard/photos-private`
  through; the enforcer's matching checks
  for `/sdcard` or `/sdcard/...` only. Caught
  by the test on the first run.
- **Trailing-slash normalisation.** The
  `MountPolicyEntry` originally had a comment
  saying "we normalise trailing slashes" but
  the field stored the raw value. The test
  `MountPolicyEntry exposes a normalised
  prefix with a trailing slash stripped`
  caught the discrepancy. Fixed by adding
  a `normalisedPrefix` getter that does the
  stripping at read time, and updating the
  enforcer to use it.
- **Duplicate prefix check vs overlapping
  prefix.** The init block originally checked
  for "duplicate exact prefix"; the test
  expected an exception for any overlap. The
  test was wrong (overlapping prefixes are
  fine — the first match wins), so the test
  was rewritten to use truly duplicate
  prefixes. This is the kind of
  test-discovered regression the suite is
  supposed to surface: the original code was
  correct, the test was over-eager.
- **`Tightened` vs `Allowed` decision.** The
  original `recordMountDecisions` method
  used a wrong condition
  (`entry.readOnly && policy.defaultReadOnly`)
  that always fired for the default policy.
  Every Allowed-branch entry was being
  recorded as `Tightened` instead of
  `Allowed`. The test caught it on the first
  run; fixed by always using `Allowed` in
  the Allowed branch and reserving
  `ReadOnlyTightened` for a future phase
  that compares the proposed list against
  the filtered list.

## Architectural invariants (Phase 50)

- **Default-deny.** A fresh workspace has
  `MountPolicy.LOCKED_DOWN` (ALLOWLIST + empty).
  The user must explicitly add paths to the
  policy before a session can mount them.
- **The policy can only TIGHTEN, never
  LOOSEN.** A policy entry with `readOnly =
  true` forces the matching mount to be
  read-only even if the proposed mount says
  writeable. A policy entry with `readOnly =
  false` does NOT loosen a proposed read-only
  mount.
- **Audit log is append-only.** The only
  mutation is `clear()` (used by tests).
  The file's parent dir is created on
  construction; concurrent appends are
  serialised by `@Synchronized`.
- **Manager is permissive when no enforcer
  is configured.** The new constructor
  parameters default to `null`, preserving
  every existing test (5+ in the WorkspaceManager
  test suite). When the manager has no
  enforcer, `enforceMountPolicy` returns
  `Allowed(proposed.toList())` without
  writing to the audit log. Production
  (Hilt) wires the enforcer; tests that
  exercise the policy wire it explicitly.

## Test count

| Suite | Tests | Failures |
|---|---|---|
| `MountPolicyEnforcerTest` | 18 (new) | 0 |
| `FileMountAuditLogTest` | 9 (new) | 0 |
| `WorkspaceManagerMountPolicyTest` | 9 (new) | 0 |
| **Project total** | **1566** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Next phase

The follow-up after Phase 50 is **Phase 51 —
signed distro format + hash verification**:
- A signed-manifest format for distro rootfs
  downloads: the manifest carries the SHA-256
  hash of the rootfs + an Ed25519 signature
  over the hash.
- The `DistroInstaller` (Phase 9.6.2) gains
  hash verification at download time: a
  distro whose hash does not match the
  manifest is rejected with a typed error.
- The `ContentHash` value class (from
  Foundry Phase F1) becomes the canonical
  hash type the runtime uses.
- A test that downloads a signed distro +
  verifies the hash + fails on a tampered
  hash. This is steps 1 and 2 of the
  critical end-to-end test.

After Phase 51 the test is one phase away:
Phase 52 is the test itself.
