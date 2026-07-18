# Phase 49 — Snapshot / Rollback Engine for Workspaces

Date: 2026-07-18
Status: **Shipped** — `assembleDebug` green, 1530 tests, 0 failures, 2 skipped.

## What landed

The runtime can now capture, restore, list, and
delete workspace snapshots. The
[FilesystemBridge] TODO that has been sitting
open since Phase 9.6.3 ("Phase 9.4 hasn't
shipped the snapshot engine yet") is closed.

A snapshot is a captured workspace state: the
live rootfs, the mount plan that was active at
snapshot time, and the env vars. The runtime
stores snapshots under
`<filesDir>/workspaces/<workspaceId>/snapshots/<snapshotId>/`
as `{manifest.json, rootfs/}`.

The engine tries POSIX `cp -al` first
(hardlink-based recursive copy — cheap, O(1)
space per file). On failure (cross-filesystem
or stripped-down Android) the engine falls
back to a full recursive copy. The chosen
strategy is recorded in the manifest so a
future "snapshot GC" job can prioritize
cheap-to-keep (hardlink) snapshots.

## Files

**Production (5 new + 4 modified):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/snapshots/WorkspaceSnapshot.kt` —
  the immutable record of a captured
  workspace state. Carries `id`, `workspaceId`,
  `label`, `createdAtMs`, `rootfsPath`,
  `mountPlan`, `sizeBytes`, and `copyStrategy`.
  Companion enum [CopyStrategy] is `HARDLINK`
  or `FULL_COPY`. Companion [MountPlan] is the
  bind-mount list + env that was active at
  snapshot time.
- `app/src/main/java/com/elysium/vanguard/core/runtime/snapshots/SnapshotEngine.kt` —
  the runtime-side contract. Four operations:
  `snapshot` (capture), `rollback` (restore),
  `list`, `delete`. Sealed result types
  [SnapshotResult] / [RollbackResult]. Typed
  errors [SnapshotError] (SnapshotNotFound,
  SourceNotFound, LiveRootfsNotFound,
  InvalidLabel, CopyFailed, IoError).
- `app/src/main/java/com/elysium/vanguard/core/runtime/snapshots/FilesystemSnapshotEngine.kt` —
  the production impl. Per-workspace
  ConcurrentHashMap lock to serialise
  snapshot / rollback / delete against the
  same workspace. `tryHardlinkCopy` tries
  `cp -al` then falls back to a full
  recursive copy. Manifest is hand-rolled
  JSON written via `org.json.JSONObject`.
  Atomic id generator: `snap-<systemTimeMs>-<counter>`.
- `app/src/main/java/com/elysium/vanguard/core/runtime/observability/RuntimeEvent.kt` —
  three new events: `SnapshotCreatedEvent`,
  `SnapshotRestoredEvent`,
  `SnapshotDeletedEvent`. Each carries
  `atMs`, `workspaceId`, and the snapshot id
  (+ label for the create / restore variants
  + copy strategy for create).
- `app/src/main/java/com/elysium/vanguard/core/runtime/observability/RuntimeEventLog.kt` —
  the file-backed audit log gains three new
  JSON Lines render paths + three new
  parse paths so the new events round-trip
  through the log file.
- `app/src/main/java/com/elysium/vanguard/core/runtime/workspaces/WorkspaceManager.kt` —
  the manager is the user-facing surface. New
  constructor parameter `snapshotEngine:
  SnapshotEngine? = null` (backwards-compat
  default). Four new methods:
  `snapshotWorkspace` /
  `rollbackWorkspace` / `listSnapshots` /
  `deleteSnapshot`. Each delegates to the
  engine and publishes the appropriate
  `RuntimeEvent` on success. The new
  `WorkspaceError.SnapshotEngineNotConfigured`
  error is returned when the manager was
  constructed without an engine.
- `app/src/main/java/com/elysium/vanguard/core/runtime/RuntimeModule.kt` —
  Hilt module gains a
  `provideSnapshotEngine(@ApplicationContext)`
  that returns a
  `FilesystemSnapshotEngine(<filesDir>/workspaces)`.
  The `provideWorkspaceManager` provider
  gains the engine parameter so production
  wiring gets the engine.
- `docs/adr/ADR-020-snapshot-rollback-engine.md` —
  the architectural decision record. Captures
  the four-piece split (WorkspaceSnapshot,
  SnapshotEngine, FilesystemSnapshotEngine,
  WorkspaceManager integration), the
  hardlink-first / full-copy-fallback copy
  strategy, the manager-publishes / engine-
  stores separation, and the revisit triggers
  for Windows VM snapshots + cloud sync.

**Tests (3 new):**

- `app/src/test/java/com/elysium/vanguard/core/runtime/snapshots/FilesystemSnapshotEngineTest.kt` —
  13 tests covering: snapshot creates manifest
  + rootfs, copy strategy is one of
  HARDLINK / FULL_COPY, blank label rejected,
  missing source rejected, mount plan
  persisted, rollback restores the live
  rootfs (destructive), rollback on unknown
  snapshot returns SnapshotNotFound, rollback
  on missing live rootfs returns
  LiveRootfsNotFound, list returns snapshots
  in chronological order, list returns empty
  for unknown workspace, delete removes the
  snapshot dir, delete returns false for
  unknown id, list is workspace-scoped.
- `app/src/test/java/com/elysium/vanguard/core/runtime/snapshots/WorkspaceSnapshotTest.kt` —
  9 tests covering: WorkspaceSnapshot
  init-block invariants (blank id /
  workspaceId / label / rootfsPath rejected;
  negative sizeBytes rejected; zero sizeBytes
  accepted), the `toString` override returns
  a structured form (not the FQN), MountPlan
  rejects duplicate guest paths, MountPlan
  EMPTY is empty.
- `app/src/test/java/com/elysium/vanguard/core/runtime/workspaces/WorkspaceManagerSnapshotTest.kt` —
  10 tests covering: `snapshotWorkspace`
  captures and publishes
  `SnapshotCreatedEvent`,
  `SnapshotEngineNotConfigured` error,
  `NotFound` error for unknown workspace,
  no event published on engine failure,
  `rollbackWorkspace` restores and publishes
  `SnapshotRestoredEvent`, unknown snapshot
  returns `SnapshotNotFound`,
  `listSnapshots` returns the engine's list,
  `listSnapshots` empty when no engine,
  `deleteSnapshot` publishes
  `SnapshotDeletedEvent` on success, no
  event when the snapshot did not exist.
  Includes a hand-rolled `FakeSnapshotEngine`
  that records every call in a thread-safe
  list.

## Why this matters

The critical end-to-end integration test from
the Worldwide Vision doc (PHASE_9_WORLDWIDE_VISION)
requires a real rollback step:

> "Stop the process. Restore the snapshot.
> Confirm that no writes happened outside
> the authorized workspace."

Until Phase 49 the runtime had no rollback.
Phase 49 lands the engine. Phase 50 will
add the allowlist mount policy + write audit
that the test's "no writes outside the
authorized workspace" assertion needs. Phase
52 will be the test itself.

The FilesystemBridge TODO is closed:
`FilesystemBridge.ElysiumNamespaces.timeTravelPath`
can now be wired in (the time-travel mount
exposes the snapshot directory as
`/elysium/time-travel` inside the distro).

The audit log gains three new event types
(`SnapshotCreated` / `SnapshotRestored` /
`SnapshotDeleted`) so the next observability
dashboard iteration can show per-workspace
snapshot history. The Phase 25
`RuntimeEventLog` had to grow three new
JSON Lines render paths and three new
parse paths — without those, the
`when` expression in `renderEvent` was
non-exhaustive and the build failed.

## Copy strategy on Android

POSIX `cp -al` is a hardlink-based
recursive copy. On Android the workspace's
live rootfs and the snapshot directory
share the same filesystem
(`/data/data/.../files/`), so hardlinks
are guaranteed to be possible. The engine
tries `/system/bin/cp -al src dst` first;
on exit non-zero it falls back to a full
recursive copy via `cp -R`, then to a
pure-JVM `Files.walkFileTree` recursion.

The chosen strategy is recorded in
[WorkspaceSnapshot.copyStrategy] so a future
"snapshot GC" job can prioritize
cheap-to-keep (hardlink) snapshots. The
test suite pins both outcomes as valid
("strategy must be HARDLINK or FULL_COPY").

## Architectural invariants (Phase 49)

- **Engine is the storage-IO layer. Manager is
  the orchestrator.** The engine has no idea
  about workspaces, events, or Hilt. The
  manager wraps every engine call, publishes
  the appropriate `RuntimeEvent`, and returns
  the result. The bus subscribers (UI, audit
  log, future Cloud sync) see every snapshot
  / rollback as a `RuntimeEvent` regardless
  of which engine was used.
- **Engine backwards-compat.** The
  `WorkspaceManager` constructor parameter
  `snapshotEngine: SnapshotEngine? = null`
  is a default. Every existing call site
  (5 of them in the test suite) compiles
  unchanged. A future test that wants to
  exercise the "no engine" path is the
  `bareManager` block in
  `WorkspaceManagerSnapshotTest`.
- **Per-workspace lock.** The engine
  serialises snapshot / rollback / delete
  against the same workspace via a
  `ConcurrentHashMap<String, Any>` of locks.
  Concurrent operations against *different*
  workspaces are fully concurrent. This is
  the same pattern `WorkspaceManager` uses
  for `addSession` / `removeSession` (Phase
  24 + the test that caught a race).
- **`cp` candidates are tried in order.**
  `/system/bin/cp` (Android) →
  `/bin/cp` (Linux) → `cp` (PATH). The
  first candidate that spawns successfully
  and exits 0 wins. The strategy recorded
  in the manifest is the strategy the engine
  decided, not the binary that ran.

## Test count

| Suite | Tests | Failures |
|---|---|---|
| `FilesystemSnapshotEngineTest` | 13 (new) | 0 |
| `WorkspaceSnapshotTest` | 9 (new) | 0 |
| `WorkspaceManagerSnapshotTest` | 10 (new) | 0 |
| **Project total** | **1530** | **0** |
| Skipped | 2 | (real-archive integration only) |

## What the test suite caught

- **`writeText` parent directory bug.** The
  first iteration of `setUp()` did
  `File(sourceRootfs, "etc/passwd").writeText(...)`
  without `mkdirs()`-ing the `etc/` parent
  first. `writeText` does not create parent
  directories. Caught by every test in the
  class (13 simultaneous failures with
  `FileNotFoundException` in `@Before`).
  Fixed by adding
  `File(this, "etc").mkdirs()`.
- **`createdAtMs` injection bug.** The
  engine's `snapshot` method had
  `nowMs: Long = System.currentTimeMillis()`
  as a default, ignoring the injected
  `clock`. The first snapshot test expected
  `createdAtMs = 1_700_000_000_000L` (the
  injected clock) but got
  `1_784_385_924_518L` (the wall clock).
  Fixed by changing the default to
  `nowMs: Long? = null` and resolving to
  `clock()` inside the method body. The
  test now passes both single-snapshot
  assertions (the injected value) and the
  "list returns in chronological order"
  assertion (the counter advances per call).
- **`Throwable.cause` / `Throwable.message`
  shadowing.** `SnapshotError.IoError`
  originally had `val cause: String`. The
  Kotlin compiler rejected it as hiding
  `Throwable.cause: Throwable?`. Same for
  `val message: String` (hides
  `Throwable.message: String?`). Fixed by
  renaming to `details`.
- **Constructor arity.** The new
  `WorkspaceError.SnapshotEngineNotConfigured(operation: String)`
  was first called with no args. The
  compiler caught the missing argument at
  three call sites in `WorkspaceManager`.

All four are exactly the kind of regressions
the test suite is supposed to surface.

## Next phase

The follow-up after Phase 49 is **Phase 50 —
allowlist mount policy + write audit**:
- A `MountPolicy` data class that declares
  which host paths a workspace is allowed to
  bind-mount. The proot launcher's `proot -b`
  flag list is the intersection of the
  workspace's `MountEntry` list and the
  policy's allowlist.
- A `WriteAudit` log that records every
  write the runtime detects outside the
  authorized mount. A future syscall-trace
  subsystem (Phase 50+) feeds writes into
  the log; Phase 50 wires the seam.
- An integration test that asserts the
  workspace cannot write outside the
  authorized path. This is steps 5 and 8 of
  the critical end-to-end test.

After Phase 50 the test is one phase away:
Phase 51 lands the signed distro format +
hash verification (steps 1-2), Phase 52 is
the test itself.
