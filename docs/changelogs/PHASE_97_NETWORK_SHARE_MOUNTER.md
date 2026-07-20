# Phase 97 — Network Share Mounter (SMB / WebDAV / SFTP Production Wiring)

| Field        | Value                                                                |
|--------------|----------------------------------------------------------------------|
| Phase        | 97                                                                   |
| Date         | 2026-07-20                                                           |
| Commit       | (this commit)                                                        |
| Depends on   | Phase 93 (MountNetworkShare sealed class) + Phase 95 (FileActionSheet) |
| ADR          | [ADR-036](../adr/ADR-036-network-share-mounter.md)                   |

## What this phase does

Phase 93 added `FileAction.MountNetworkShare` (the resolver
returns it for `.smb` / `.webdav` / `.davs` / `.cifs` files)
and the icon mapping. Phase 95 wired the `FileActionSheet` to
the long-press handler. **Until this phase, the actual mount
was a stub**: the ViewModel emitted a `"(queued)"` success
message and never mounted anything.

Phase 97 closes that gap:

- **`NetworkShareHandler`** — reads the URL + credentials
  from the file body, validates the URL scheme matches the
  declared protocol, delegates to the mounter.
- **`NetworkShareMounter` interface** + **`ProcessLauncherNetworkShareMounter`**
  production impl that spawns the host `mount` command via
  the production `ProcessLauncher`.
- **3 protocol-specific command builders** (SMB via
  `mount -t cifs`, WebDAV via `mount -t davfs`, SFTP via
  `mount -t fuse.sshfs`).
- **Descriptor format** supporting both embedded credentials
  (`smb://user:pass@server/share`) and `key=value` lines
  (`username=foo`, `password=bar`).
- **`FileActionModule`** — new `@Provides` for the mounter.
- **`FileActionViewModel.dispatchAction`** — `MountNetworkShare`
  branch now calls the handler; the `(queued)` stub is gone.
- **17 new tests** (10 handler + 7 mounter). 3485/3485 green.

## Files added

| File | Purpose |
|------|---------|
| `core/fileactions/handlers/NetworkShareHandler.kt` | Handler + mounter interface + `NetworkShareMountResult` sealed class |
| `core/fileactions/production/ProcessLauncherNetworkShareMounter.kt` | Production mounter with 3 command builders |

## Files modified

| File | Change |
|------|--------|
| `core/fileactions/FileActionModule.kt` | Added `provideNetworkShareMounter(...)` |
| `features/fileactions/FileActionViewModel.kt` | Added `networkShareHandler` param + updated `MountNetworkShare` dispatch |
| `app/src/test/.../FileActionViewModelTest.kt` | Added `RecordingNetworkShareMounter` + `networkShareHandler` to `buildViewModel` helper |

## Algorithm — `mount -t cifs` (SMB)

For a `.smb` descriptor with content:
```
# my home share
smb://192.168.1.10/jordan
username=jordan
password=secret
```

The handler:
1. Reads the file, extracts the first non-blank, non-comment
   line as the URL.
2. Parses `key=value` lines for credentials (overridden by
   embedded credentials if the URL has them).
3. Validates `smb://` is compatible with `NetworkProtocol.SMB`.
4. Calls `mounter.mount(url, protocol, username, password, name)`.

The mounter:
1. Creates `<filesDir>/fileaction-scratch/mnt/<name>/` as the
   mount point.
2. Normalizes `smb://server/share` → `//server/share`.
3. Spawns:
   ```
   mount -t cifs -o user=jordan,pass=secret,nobrl,cache=loose,uid=0,gid=0 \
       //192.168.1.10/jordan <filesDir>/fileaction-scratch/mnt/home/
   ```
4. Returns `Mounted(url, protocol, mountPoint)` on exit 0;
   `Failure(message)` on non-zero / launcher exception.

## Algorithm — `mount -t davfs` (WebDAV)

For a `.webdav` descriptor with content:
```
davs://files.example.com/jordan
username=jordan
```

The mounter:
1. Rewrites `davs://` → `https://`, `dav://` → `http://`.
2. Spawns:
   ```
   mount -t davfs -o uid=0,gid=0,username=jordan \
       https://files.example.com/jordan <mountpoint>
   ```

## Algorithm — `mount -t fuse.sshfs` (SFTP)

For a `.sftp` descriptor with content:
```
sftp://server.example.com/home/jordan
username=jordan
```

The mounter:
1. Prepends `jordan@` to the host.
2. Spawns:
   ```
   mount -t fuse.sshfs -o allow_other,uid=0,gid=0 \
       jordan@server.example.com/home/jordan <mountpoint>
   ```

## Test count

3485/3485 green (17 new):
- 10 `NetworkShareHandlerTest` (URL parsing, embedded
  credentials, missing file, scheme mismatch, davs/dav
  acceptance, 3 URL-splitter unit tests)
- 7 `ProcessLauncherNetworkShareMounterTest` (SMB with
  + without creds, WebDAV dav/davs rewrite, SFTP fuse.sshfs,
  launcher-throws, mount-point-on-disk)

## What this does NOT do (deferred)

- **Encrypted credentials storage** — descriptor file
  stores the password in plain text. Phase 99+ will move
  the credentials to the Tink-encrypted `ElysiumKeyStore`.
- **Real `waitFor()` on `LaunchedProcess`** — the 60s
  polling loop stand-in is still in place. Phase 100.
- **Android-native WebDAV via `StorageManager`** — the
  `mount -t davfs` path requires Termux + davfs2 installed.
  Phase 97+ can add a SAF-based fallback for stock devices.

## Build verification

- `./gradlew testDebugUnitTest` — 3485/3485 green
- `./gradlew assembleDebug` — APK built
