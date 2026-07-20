# Phase 98 — USB OTG Inspector (Production Wiring)

| Field        | Value                                                                |
|--------------|----------------------------------------------------------------------|
| Phase        | 98                                                                   |
| Date         | 2026-07-20                                                           |
| Commit       | (this commit)                                                        |
| Depends on   | Phase 93 (`InspectUsbOtgDevice` sealed class)                        |
| ADR          | (none — same pattern as ADR-035)                                     |

## What this phase does

Phase 93 added `FileAction.InspectUsbOtgDevice` (the resolver
returns it for `.usbotg` files). Phase 95 wired the
`FileActionSheet` to the long-press handler. **Until this phase,
the actual inspection was a stub**: the ViewModel emitted a
`"(queued)"` success message and never looked at a USB device.

Phase 98 closes that gap:

- **`UsbOtgHandler`** — reads the block-device path from the
  descriptor file body (or treats the action's path as the
  literal), and delegates the partition discovery + mount to
  the inspector.
- **`UsbOtgInspector` interface** +
  **`AndroidUsbOtgInspector`** production impl that wraps
  Android's `UsbManager` + the production `ProcessLauncher`.
- **Descriptor format** supporting:
  - **Literal path**: `dev/block/sda1` on the first line.
  - **Auto-detect**: `auto` keyword → first attached
    mass-storage device.
  - **Comments**: lines starting with `#` are skipped.
- **`FileActionModule`** — new `@Provides` for the inspector.
- **`FileActionViewModel.dispatchAction`** —
  `InspectUsbOtgDevice` branch now calls the handler; the
  `(queued)` stub is gone.
- **7 new tests**. 3492/3492 green.

## Files added

| File | Purpose |
|------|---------|
| `core/fileactions/handlers/UsbOtgHandler.kt` | Handler + inspector interface + `UsbDeviceSummary` + `UsbPartition` + `UsbOtgInspectResult` sealed class |
| `core/fileactions/production/AndroidUsbOtgInspector.kt` | Production inspector with `UsbManager` + `ProcessLauncher` |

## Files modified

| File | Change |
|------|--------|
| `core/fileactions/FileActionModule.kt` | Added `provideUsbOtgInspector(...)` `@Provides` |
| `features/fileactions/FileActionViewModel.kt` | Added `usbOtgHandler` constructor param + updated `InspectUsbOtgDevice` dispatch |
| `app/src/test/.../FileActionViewModelTest.kt` | Added `RecordingUsbOtgInspector` + `usbOtgHandler` to `buildViewModel` helper |

## Algorithm — `mount -o ro <partition>`

For a `.usbotg` descriptor with content:
```
# my USB stick
/dev/block/sda1
```

The handler:
1. Reads the file, extracts the first non-blank, non-comment
   line as the block path.
2. Calls `inspector.findByBlockPath(path)`. The inspector
   maps the path to an attached `UsbDevice` via the kernel
   `/sys/block/` virtual filesystem (when available) or
   falls back to a synthetic summary.
3. Calls `inspector.firstReadablePartition(device)`. The
   inspector reads `/proc/partitions` to enumerate the
   partitions on the device and returns the first one.
4. Calls `inspector.mountReadOnly(partition)`. The inspector
   spawns:
   ```
   mount -o ro /dev/block/sda1 <filesDir>/fileaction-scratch/mnt/<productName>/
   ```
5. Returns `Mounted` on exit 0; `Failure` on non-zero /
   launcher exception.

For a `.usbotg` descriptor with `auto` on the first line, the
handler calls `inspector.findFirstMassStorageDevice()` which
enumerates `UsbManager.deviceList` and returns the first device
with mass-storage class (08 / 06).

## Descriptor format

```
# optional comment
/dev/block/sda1    (or `auto`)
```

Multiple descriptor files can co-exist (one per USB stick). The
File Manager's long-press → "Contextual actions" → "Inspect
USB OTG" action resolves the file's `blockDevice` field (the
file path) and reads the body.

## Test count

3492/3492 green (7 new):
- 7 `UsbOtgHandlerTest` (literal path, auto-detect, descriptor
  file body, no device, missing partition, empty body, missing
  file)

## What this does NOT do (deferred)

- **SAF-based fallback** — Phase 98 uses `mount -o ro` via
  `ProcessLauncher`. Stock Android (no Termux) can use
  `StorageManager` + SAF, but that gives a tree-URI not a
  `java.io.File` path. Phase 98+ can add a SAF-based fallback.
- **Permission prompt UI** — the `UsbManager` may require
  user permission to access the device. Phase 98 silently
  fails if the permission is not granted; Phase 98+ will
  add a permission-request flow.
- **Unmount** — the inspector mounts but never unmounts. A
  `unmount` action is Phase 98+ work.

## Build verification

- `./gradlew testDebugUnitTest` — 3492/3492 green
- `./gradlew clean assembleDebug` — APK built (102 MB)
