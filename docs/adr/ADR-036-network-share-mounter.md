# ADR-036 — Network Share Mounter (SMB / WebDAV / SFTP Production Wiring)

| Field        | Value                                                                |
|--------------|----------------------------------------------------------------------|
| Status       | Accepted                                                             |
| Date         | 2026-07-20                                                           |
| Phase        | 97                                                                   |
| Supersedes   | (none)                                                               |
| Superseded by | (none)                                                              |

## Context

The vision (Section 1) calls for "remote-mount file actions": a
user creates a `.smb` / `.webdav` / `.davs` / `.cifs` descriptor
file containing the share URL (+ optional credentials), long-
presses it, and the platform mounts the share as a local folder
the File Manager can browse.

Phase 93 (`FileActionResolver`) added the `MountNetworkShare`
sealed-class variant and a 6-line `iconFor()` mapping. Phase 95
wired the `FileActionSheet` to the long-press handler. **The
production impl was a stub**: the ViewModel emitted a
"(queued)" success message but never actually mounted anything.

This ADR + the Phase 97 code close that gap.

## Decision

### 1. `NetworkShareHandler` + `NetworkShareMounter` interface

The handler reads the URL + credentials from the file body
and validates the URL scheme matches the declared protocol.
The mounter does the actual mount.

```kotlin
class NetworkShareHandler @javax.inject.Inject constructor(
    private val mounter: NetworkShareMounter,
) {
    suspend fun mount(action: FileAction.MountNetworkShare): NetworkShareMountResult
}

interface NetworkShareMounter {
    suspend fun mount(
        url: String,
        protocol: NetworkProtocol,
        username: String?,
        password: String?,
        descriptorName: String,
    ): NetworkShareMountResult
}
```

The split mirrors `InstallPackageHandler` / `PackageInstaller`
(Phase 93) and `DiskImageHandler` / `DiskImageBackend`
(Phase 93): the handler is the surface that reads the file +
validates; the mounter is the surface that does the IO.

### 2. `ProcessLauncherNetworkShareMounter` production impl

Three protocol-specific command builders, all wrapped in
`mount` + the production `ProcessLauncher`:

| Protocol | Command                                                          |
|----------|------------------------------------------------------------------|
| SMB      | `mount -t cifs -o user=...,pass=...,nobrl,cache=loose,uid=0,gid=0 //server/share <mp>` |
| WebDAV   | `mount -t davfs -o uid=0,gid=0[,username=...] http(s)://server/path <mp>` |
| SFTP     | `mount -t fuse.sshfs -o allow_other,uid=0,gid=0[,password_stdin] user@host:path <mp>` |

The SMB URL is normalized from `smb://server/share` to
`//server/share` (RFC 1001/1002 form). The WebDAV URL has its
scheme rewritten from `dav://` to `http://` and `davs://` to
`https://`. The SFTP URL prepends the username.

**Why `nobrl` + `cache=loose` for SMB?** These are the most
common ARM-Android compatibility issues with the cifs kernel
module. `nobrl` disables byte-range locks (a frequent source
of `-5` IO errors on Android); `cache=loose` relaxes the cache
invalidation policy (faster but looser consistency).

### 3. The 60-second polling `waitFor` stand-in

`ProcessLauncherNetworkShareMounter.waitForExit` uses the
same 60-second polling loop as the disk-image and package
backends (see ADR-035 §"Algorithm" for the rationale). The
production `ProcessLauncher` does not expose `waitFor()`;
Phase 100 will add a real `LaunchedProcess.exitCode()`.

### 4. `FileActionViewModel.dispatchAction` updated

The `MountNetworkShare` branch in the VM now calls
`networkShareHandler.mount(action)` and pattern-matches on
the result. The `(queued)` stub is gone.

```kotlin
is FileAction.MountNetworkShare -> {
    val result = networkShareHandler.mount(action)
    when (result) {
        is NetworkShareMountResult.Mounted -> FileActionOutcome.Success(
            message = "Mounted ${result.protocol.name} at ${result.mountPoint}"
        )
        is NetworkShareMountResult.Failure -> FileActionOutcome.Failure(
            message = result.message
        )
    }
}
```

### 5. Hilt wiring

`FileActionModule` adds a new `@Provides` for the mounter,
sharing the same `<filesDir>/fileaction-scratch/` mount-
point dir as the disk-image backend. The mounter is
`@Singleton` (stateless; all state lives in the per-share
mount-point directory).

## Consequences

### Positive

- **End-to-end real path**. The user creates a `.smb` file,
  long-presses → taps "Contextual actions" → picks
  "Mount as SMB" → `mount -t cifs` runs against the host.
  No mocks in the path.
- **Three protocols in one mounter**. SMB / WebDAV / SFTP
  share the same handle / mount / unmount lifecycle; the
  command builder is the only difference.
- **JVM-testable**. 10 handler tests + 7 mounter tests
  cover the descriptor parsing, the command construction,
  and the success / failure paths.

### Negative

- **Requires Termux (or root)** for the cifs / davfs / sshfs
  tools. A stock Android device without Termux cannot
  mount a network share. Phase 97+ can add a fallback to
  `android.provider.DocumentsContract` for SAF-based
  WebDAV mounting.
- **Polling waitFor is still 60s**. Long mount operations
  (large SMB shares over slow links) can time out. Phase
  100 will replace the polling with a real `waitFor()`.
- **No credentials storage**. The descriptor file stores
  the password in plain text. The Phase 99+ security pass
  will move the credentials to the Tink-encrypted
  `ElysiumKeyStore` (Phase 76 foundation).

## Alternatives considered

- **Android's `StorageManager` for WebDAV**: rejected for
  Phase 97 because `StorageManager` is scoped to SAF tree
  URIs, not to a generic WebDAV URL. The `mount -t davfs`
  path is the Linux-standard approach and works on any
  Termux-equipped device.
- **JVM `jcifs-ng` library**: rejected because the SMB
  implementation needs the actual `mount -t cifs` syscall
  for the kernel-side FUSE / cifs module; a pure-Java SMB
  client would not give us a "browse as folder" UX
  (the File Manager uses `java.io.File`, which doesn't
  know about `jcifs-ng`'s `SmbFile`).
- **Storing the credentials in `SharedPreferences` (encrypted)**:
  rejected for Phase 97 because the descriptor-file
  format is the simplest UX (drop a file → it works). The
  encryption pass is Phase 99+.

## Related

- **Phase 93 (commit `b0b2a7d`)**: `MountNetworkShare` sealed
  class + the resolver's 6-line icon mapping.
- **Phase 95 (commit `8283ecf`)**: `FileActionSheet` wired
  to the long-press handler — the `"(queued)"` stub the
  user saw in Phase 95 is the exact thing Phase 97 replaces.
- **ADR-035**: The Hilt pattern for narrow environment
  interfaces. The `NetworkShareMounter` interface is the
  same pattern: a thin seam the handler consumes, with a
  `ProcessLauncher`-backed production impl.

## File map (new files)

| File | Purpose |
|------|---------|
| `core/fileactions/handlers/NetworkShareHandler.kt` | The handler + mounter interface + `NetworkShareMountResult` |
| `core/fileactions/production/ProcessLauncherNetworkShareMounter.kt` | The 3-protocol production mounter |

## File map (modified files)

- `core/fileactions/FileActionModule.kt` — added
  `provideNetworkShareMounter(...)` `@Provides` method.
- `features/fileactions/FileActionViewModel.kt` — added
  `networkShareHandler` constructor param + updated
  `MountNetworkShare` dispatch branch.
- `app/src/test/.../FileActionViewModelTest.kt` — added
  `RecordingNetworkShareMounter` + `networkShareHandler`
  parameter to the `buildViewModel` helper.

## Test count

3485/3485 tests green (17 new this phase: 10 handler + 7
mounter).
