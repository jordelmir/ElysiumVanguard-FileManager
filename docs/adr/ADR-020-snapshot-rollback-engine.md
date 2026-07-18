# ADR-020 — Snapshot / Rollback Engine for Workspaces

Status: **Accepted** (Phase 49, 2026-07-17)
Owners: Runtime
Supersedes: none
Superseded by: none

## Context

The Worldwide Vision doc (master order) requires a
runtime that can:

> "Create a snapshot before modifying an
> environment. Revert automatically when an
> operation fails."

The critical end-to-end integration test
(PHASE 9_WORLDWIDE_VISION) further requires that
the runtime can:

> "Stop the process. Restore the snapshot. Confirm
> that no writes happened outside the authorized
> workspace."

Until Phase 49 the runtime has neither piece.
`FilesystemBridge.kt` (the mount-allowlist seam for
proot) explicitly comments:

> "On-disk root of the time-travel snapshots
> directory. 9.6.3 leaves this null because Phase
> 9.4 hasn't shipped the snapshot engine yet; the
> bridge accepts the null and excludes the mount."

The TODO has been there since the FilesystemBridge
landed. Phase 49 closes it.

The challenge: a snapshot is more than a copy. The
runtime must:

1. Capture the **current** state of a workspace's
   rootfs (and its mount plan) without breaking
   running sessions.
2. Be **space-efficient** when possible — a 4 GB
   distro with a single config tweak should not
   consume 4 GB of snapshot storage.
3. Be **JVM-testable** end-to-end (the test must
   not need a device).
4. Integrate with the existing observability path
   (every snapshot / rollback is a
   `RuntimeEvent`).
5. Be safely rollbackable — a corrupted snapshot
   must not corrupt the workspace.

## Decision

We split the snapshot path into four small pieces:

1. **`WorkspaceSnapshot` (data class)** — the
   immutable record of a captured workspace
   state. Carries an `id`, `workspaceId`, `label`,
   `createdAtMs`, the `rootfsPath` of the snapshot
   copy, the `mountPlan` that was active at
   snapshot time, the `sizeBytes` (zero if unknown),
   and a `copyStrategy` (`HARDLINK` or `FULL_COPY`).
   The `id` is `snap-<systemTimeMs>-<counter>`.

2. **`SnapshotEngine` (interface)** — the
   runtime-side contract. Four operations:
   `snapshot(workspaceId, sourceRootfsPath,
   mountPlan, label)`,
   `rollback(workspaceId, snapshotId)`,
   `list(workspaceId)`, `delete(snapshotId)`.
   Each is JVM-pure — the impl decides whether the
   store is the filesystem, an in-memory map
   (tests), or a future object store.

3. **`FilesystemSnapshotEngine` (production
   impl)** — the on-disk engine. Snapshots live
   under
   `<filesDir>/workspaces/<workspaceId>/snapshots/<snapshotId>/`
   as `{manifest.json, rootfs/}`. The engine
   records which `copyStrategy` it used in the
   manifest, so a future audit can prove which
   snapshots are hardlink-based (cheap to keep) vs
   full copies (expensive).

4. **`WorkspaceManager` integration** — the
   manager gains four methods (`snapshot`,
   `rollback`, `listSnapshots`, `deleteSnapshot`)
   that delegate to the engine and publish
   `SnapshotCreatedEvent` / `SnapshotRestoredEvent`
   / `SnapshotDeletedEvent` on the
   `RuntimeEventBus`. The manager is the
   user-facing surface; the engine is the
   persistence seam.

### Why `cp -al` for hardlinks

POSIX `cp -al` is a hardlink-based recursive copy
of an entire directory tree. It is O(1) in space
per file (each file becomes a hardlink to the
source inode) and O(n) in time, where n is the
file count. On Android (ext4 internal storage) the
workspace's live rootfs and the snapshot directory
share the same filesystem, so hardlinks are
guaranteed to be possible. The fallback is a full
recursive copy via `cp -R` for the rare case where
the source is on a different filesystem (e.g. an
external SD card).

The engine picks the strategy by trying
`cp -al` first and falling back to `cp -R` if the
hardlink copy exits non-zero. The strategy used is
recorded in the manifest so callers can size their
storage budget. Hardlink snapshots are cheap to
keep indefinitely; full copies should be deleted
on a schedule.

### Why a separate `SnapshotEngine` interface

Two reasons:

1. **JVM-testable** without an Android device. The
   production impl can use `ProcessBuilder` +
   `/system/bin/cp`; the test impl uses
   `java.nio.file.Files.walkFileTree` with a
   `SimpleFileVisitor` to copy in-process. The
   same `WorkspaceManager` drives both, so the
   business logic is verified end-to-end in
   unit tests.

2. **Future swap-out** to a different storage
   backend. A cloud-backed snapshot store, an
   `adb`-pulled device-archive store, or a
   squashfs-based store would all implement
   `SnapshotEngine` without touching
   `WorkspaceManager`.

### Why the manager — not the engine — publishes events

Phase 39 established that the manager is the
single source of truth for "what just happened to
a workspace". The engine is a pure
storage-IO layer; it has no idea which workspace
an operation belongs to in the manager's mental
model. The manager wraps every engine call,
publishes the appropriate event, and returns the
result. The bus subscribers (UI, audit log,
future Cloud sync) see every snapshot /
rollback as a `RuntimeEvent` regardless of which
engine was used.

### Why `MountSnapshot` is recorded in the manifest

A snapshot is not just "the rootfs files at this
moment". It is "the rootfs files AND the bind
mounts AND the env vars AND the resource limits
that were in effect when the snapshot was taken".
A rollback that restores the rootfs but leaves
the live session with a different mount plan
would be incoherent — a write that should have
been confined to the snapshotted mount would
silently end up on the live one.

The `MountSnapshot` data class captures the
mount plan in the same JSON format the runtime
already uses (`MountEntry` from `FilesystemBridge`).
A future "rollback to mount plan" operation is a
trivial follow-up; Phase 49 records the data but
does not act on it.

## Consequences

Positive:

- The critical end-to-end integration test now
  has a real rollback step to assert against
  (Phase 52 will be the test itself).
- `FilesystemBridge.ElysiumNamespaces.timeTravelPath`
  can be wired in. The "time-travel" mount
  exposes the snapshot directory as
  `/elysium/time-travel` inside the distro, so a
  user can `ls /elysium/time-travel` from inside
  a proot session and inspect their snapshots.
- Workspace users can experiment freely — a
  broken config is a one-tap rollback, not a
  reinstall.
- The audit trail gains three new event types;
  the next observability dashboard iteration
  can show snapshot history per workspace.

Negative:

- A full-copy snapshot is `O(size_of_rootfs)` in
  storage. A user who takes 10 snapshots of a 4
  GB rootfs without a cleanup policy will eat 40
  GB of internal storage. Mitigation: the
  manifest records the strategy; a future
  "snapshot GC" job (Phase 53+ follow-up) can
  delete full copies that have no surviving
  dependents. The hardlink path is essentially
  free.
- The engine uses `ProcessBuilder` to invoke
  `/system/bin/cp`. On a stripped-down Android
  build the binary may not exist. The fallback
  in `FilesystemSnapshotEngine` is a pure-JVM
  `Files.walkFileTree` copy that works without
  `/system/bin/cp` at all (slower, but always
  works). The engine tries the fast path first
  and the slow path on failure.

## Revisit triggers

- If a workspace needs to snapshot a Windows VM
  (not a Linux proot) the engine will need a
  QCOW2-based backend. The interface
  accommodates this (the engine is a swap-out
  point), but the current impl is proot-only.
- If the runtime gains a cloud sync path
  (Phase Cloud) the snapshot store should
  support both local and remote snapshots. The
  manifest's `id` is a string today; a future
  remote snapshot would need a URL field.
- If the runtime gains per-file snapshotting
  (not whole-rootfs), the engine interface
  needs a `Path`-based API. Phase 49 snapshots
  whole directories, which is the 80/20 case
  for "user wants to roll back a config change".
