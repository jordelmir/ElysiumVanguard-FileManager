# ADR-006: Filesystem bridge and Android storage mapping

- Status: Accepted
- Date: 2026-07-13
- Owners: Elysium Vanguard runtime
- Depends on: ADR-001

## Context

Guest Linux applications need access to Android storage (shared files,
downloads, documents) and vice versa. Without a filesystem bridge, the guest
and host exist in isolated filesystem namespaces, forcing users to copy files
through terminal commands or a separate file manager.

The current DistroInstaller extracts rootfs tarballs into the app's private
directory. Guest processes running under PRoot (or other backends) cannot
access `/sdcard` or external storage without explicit bind mounts.

## Decision

Implement a FilesystemBridge that manages bidirectional access between Android
storage paths and the guest rootfs:

### Bind mount configuration

The bridge defines a set of bind mounts applied before session start:

| Guest path | Host path | Access | Purpose |
|---|---|---|---|
| `/host/sdcard` | `/sdcard` | rw | Shared Android storage |
| `/host/downloads` | `/storage/emulated/0/Download` | rw | Download directory |
| `/host/documents` | `/storage/emulated/0/Documents` | rw | Document directory |
| `/host/tmp` | app cache dir | rw | Session temp files |
| `/host/config` | app-specific config dir | ro | App-to-guest config |

Bind mounts are implemented using the backend's native mount capability:
- PRoot: `-b /src:/dst` arguments.
- Native root: `mount --bind` (requires root, not assumed).
- VM: 9p/virtio-fs share.

### File transfer API

The bridge exposes a Kotlin interface for programmatic file transfer:

```kotlin
interface FilesystemBridge {
    suspend fun copyToGuest(hostPath: Path, guestPath: String): FileResult
    suspend fun copyFromGuest(guestPath: String, hostPath: Path): FileResult
    suspend fun listGuest(path: String): List<GuestEntry>
    suspend fun deleteGuest(path: String): FileResult
}
```

Copy operations verify the complete transfer via SHA-256 comparison. Partial
transfers are detected and reported. All operations are cancellable via the
session scope.

### Journaling

Every copy, move and delete operation is recorded in a write-ahead journal:
- Operation ID, source, destination, timestamp, file size, expected hash.
- On interrupt, the journal is replayed to completion or rolled back.
- The journal is stored in the app's private database (see ADR-017 for Room
  schema).

### Symlink handling

- Symlinks pointing outside the guest or host mount points are rejected.
- Absolute symlinks within the guest are preserved.
- Relative symlinks are preserved.

## Invariants

1. Every bind mount is applied before any guest process starts.
2. Copy operations are verified by hash, not just by return code.
3. The journal survives a crash and is replayed on next session start.
4. Symlinks cannot escape the configured mount boundaries.
5. Deleting a file from the guest requires confirmation if the file was not
   created by the guest (shared storage protection).

## Alternatives considered

### scp/rsync over loopback

Rejected. Adds network overhead, requires SSH inside the guest and does not
integrate with the runtime lifecycle.

### MTP or ADB-style file transfer

Rejected. Requires a separate daemon and protocol implementation with no
advantage over bind mounts for the local case.

## Consequences

- Guest applications see Android storage at well-known paths under `/host/`.
- File operations have integrity verification at the cost of hashing overhead.
- The journal adds complexity but is required for crash safety.
- PRoot backends may need special handling for `/host/` path resolution.

## Revisit triggers

- Bind mount semantics differ significantly between PRoot, native and VM
  backends.
- File transfer performance for large files (>1 GB) is unacceptable.
- Android scoped storage restrictions prevent access to target paths.
