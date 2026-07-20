# Phase 96 — RuntimeInspect "al tocar inspect se cierra" Crash Fix

| Field        | Value                                                                |
|--------------|----------------------------------------------------------------------|
| Phase        | 96                                                                   |
| Date         | 2026-07-20                                                           |
| Commit       | (this commit)                                                        |
| Depends on   | Phase 9.6.3.2 (RuntimeInspectViewModel)                              |
| ADR          | (none — defensive fix)                                              |

## Bug report

User reported: **"al tocar inspect se cierra"** — when tapping
the "inspect" link on a healthy installed distro, the app closes
without an error message.

## Root cause

`RuntimeInspectViewModel.loadAll()` runs in `viewModelScope.launch`
on `Dispatchers.IO`. It calls `manager.introspect(distroId)` which
in turn calls `RootfsIntrospector.entries(maxDepth = 3)`. The
entries walker can throw on:

- **Partial installs** — a distro with a corrupted `/var/lib/dpkg/status`
  or no `/etc/os-release`. The introspector's `installedPackages()`
  catches `Exception` internally, but the `entries` walker does
  `node.listFiles()` which can return `null` on permission errors
  (handled) but can also throw on a `SecurityException` raised by
  SELinux or the storage permission system on certain Android
  versions.
- **Symlink loops** — `NioFiles.isSymbolicLink` + `child.isDirectory`
  on a cyclic symlink can produce unexpected file system state.
- **A distro whose install health check passed at one point but
  has since been removed** — the cached `DistroInstallation` says
  `isHealthy = true` but the `rootfsDir` no longer exists.

In all of these cases, the uncaught exception propagates up to
the `viewModelScope` default handler, which logs + kills the
coroutine. The Compose runtime sees the VM go into a "permanently
loading" state — the snapshot is null, the installation is set,
and the screen renders the "Inspect · <name>" + "loading…"
header. But the screen's `collectAsState` calls all become stale,
and the `LaunchedEffect`s no longer recompose. The user perceives
this as "the app closed".

## Fix

Wrap the introspector + snapshot calls in defensive
`try { ... } catch (_: Exception) { ... }` blocks. On any
exception, the VM sets the state to a safe empty value and
continues. The user sees the installation header + a "no
snapshot available" placeholder, **never a black screen**.

```kotlin
init {
    viewModelScope.launch {
        withContext(Dispatchers.IO) {
            try {
                loadAll()
            } catch (e: Exception) {
                installation.value = null
                snapshot.value = null
                snapshots.value = emptyList()
            }
        }
    }
}

private fun loadAll() {
    val install = manager.findInstalled(distroId)
    installation.value = install
    if (install != null && install.isHealthy) {
        try {
            manager.introspect(distroId) { snap ->
                snapshot.value = snap
            }
        } catch (_: Exception) {
            snapshot.value = null
        }
        try {
            refreshSnapshots()
        } catch (_: Exception) {
            snapshots.value = emptyList()
        }
    } else {
        snapshot.value = null
        snapshots.value = emptyList()
    }
}
```

The same defensive try/catch was added to `captureSnapshot()` +
`removeSnapshot()` so a failed snapshot operation never closes
the screen.

## Why this approach (and not "log + show error toast")

The user explicitly said "al tocar inspect **se cierra**" —
the app closes. We don't have a logger path that shows a toast
without significant refactoring. The defensive try/catch is
the minimal change that:

1. **Doesn't close the screen** — the VM stays in a known
   state (installation set, snapshot = null).
2. **Doesn't lose the user's data** — `installation.value` is
   set before the introspector runs; even if introspect throws,
   the user still sees the distro name + a "loading…" header
   that updates to the actual snapshot when (if) the user
   navigates back + forward.
3. **Doesn't add a new test surface** — the introspector is
   read-only against a real rootfs; a test that simulates a
   partial install would require setting up a fixture
   rootfs, which is overkill for a defensive guard.

## What this phase does NOT do

- **Root cause fix** in the introspector. The introspector
  is now Phase 97+ work: defensive file walks that catch
  every individual `IOException` / `SecurityException` at
  the point of failure, with structured logging.
- **Add a `Snackbar` to show the error to the user**. The
  current behavior is "silently show empty state"; the
  Phase 96 user can navigate back to the Runtime screen
  + re-tap inspect to retry. The snackbar would require
  a `SnackbarHostState` + a `LaunchedEffect` on
  `install.lastError` — Phase 97+ work.

## Build verification

- `./gradlew testDebugUnitTest` — 3468/3468 green (no
  test changes; the fix is in the VM init block)
- `./gradlew assembleDebug` — APK built
- `adb install -r ...` — installed
- `adb shell am start -n com.elysium.vanguard/.MainActivity` —
  launches + navigates without crash (screencap verified)

## Related

- **Phase 9.6.3.1** (`RootfsIntrospector`) — the file walker
  that can throw. The next phase will add per-call defensive
  guards + structured logging.
- **Phase 9.6.3.2** (`RootfsHealth`) — the health check that
  gates `introspect`. Already does a thorough check (rootfs
  is dir + has os-release + has a shell); the gap is when
  the check passes but the actual read fails (e.g., concurrent
  deletion).
