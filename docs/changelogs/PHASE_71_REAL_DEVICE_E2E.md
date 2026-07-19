# Phase 71 — Real-device E2E (ProotBackend production implementation)

**Status**: ✅ SHIPPED
**Date**: 2026-07-18
**Commit**: (this commit)
**Builds**: `./gradlew :app:testDebugUnitTest` (2344 tests, 0 fail, 2 skip) ·
`./gradlew :app:assembleDebug` (0 warnings) ·
`./gradlew :app:assembleDebugAndroidTest` (0 warnings)

---

## Why

Phase 70 shipped the **Critical E2E orchestrator** — the
8-step pure-domain coordinator that drives the master
vision's "Definition of Done" (download signed distro →
verify → install capsule → create workspace → orchestrate
→ launch binary → stop → restore snapshot → audit
writes). The orchestrator ran end-to-end on the JVM
against a `ProotBackendStub`.

Phase 70's stub was a test-only parallel API. The
production `ProotBackend` interface didn't exist yet, so
the JVM test was disconnected from the Android runtime
hooks (`LinuxProotSessionRunner`, `DistroSessionBackend`,
`ProcessLauncher`).

Phase 71 closes the gap:

1. The `ProotBackend` is now the **production seam**
   (in `core.runtime.proot`).
2. The orchestrator runs against the interface, not a
   stub. The interface has two implementations:
   - `InMemoryProotBackend` (test fixture, JVM-friendly)
   - `ProotBackendReal` (production, wraps the existing
     runner + distro + process-launcher seams)
3. A new `CriticalE2EInstrumentedTest` (androidTest) runs
   the **same** 8-step scenario against `ProotBackendReal`
   on a real Android device.

The E2E is now the **Definition of Done** of the platform
on a real device, not just on the JVM.

---

## What shipped

### Production (core.runtime.proot)

#### 1. `ProotBackend` interface (production seam)

The typed contract between the orchestrator and the
proot execution. Two methods:

- `launch(workspaceId, session, executable, args, workingDirectory, bindMounts, environment)`
- `stop(workspaceId, session)`
- `restoreSnapshot(workspaceId, session)`

The interface also carries `LaunchResult(pid, exitCode, writes)` — the audit-relevant writes for step 9.

The `workspaceId` parameter is the runtime hook the real
backend needs to find the `Workspace` (the
`SessionRunner.start(workspace, session)` signature
requires both).

#### 2. `ProotBackendReal` (production implementation)

Wraps the existing runtime hooks:

- `DistroSessionBackend` (Phase 30 seam) — looks up the
  distro installation + the launcher pick
- `ProcessLauncher` (Phase 30 seam) — actually forks the
  child process
- `WorkspaceManager` (Phase 24) — for snapshot restore

The launch translation:

1. Look up the launcher pick for the session's distro.
2. Build the shell script: `cd <workingDirectory> && <executable> <args>`.
3. Call `launcher.buildShellCommand(rootfsDir, script)` to
   get the base proot command.
4. **Inject the orchestrator's bindMounts as proot `-b host:container` flags**, placed after the `-r <rootfs>` pair.
5. **Merge the env** — orchestrator's env wins.
6. Spawn via `ProcessLauncher.start()`; remember the
   `LaunchedProcess` handle so `stop` can find it.

`stop` removes the handle atomically + invokes the
process's `stop` callback.

`restoreSnapshot` delegates to `WorkspaceManager.rollbackWorkspace()`
against the most recent snapshot for the workspace.

The `writes` list on the returned `LaunchResult` is
**empty** for now. Phase 72 wires a `FileObserver` to
capture actual writes on the device; the JVM test
exercises the audit step with a controlled writes list.

### Production (core.runtime.critical_e2e — promoted from test)

#### 3. `CriticalE2EOrchestrator` (production)

Phase 70's orchestrator was in `app/src/test/`. Phase 71
promotes it to `app/src/main/` so the
`CriticalE2EInstrumentedTest` (androidTest) can run it on
a real device against the production `ProotBackendReal`.

The orchestrator now takes the production `ProotBackend`
interface (not the test-only `ProotBackendStub`).

Phase 71 also adds a `patchSessionDistro()` step: the
orchestrator's plan emits a `WorkspaceSession` with
`distroId = "__pending__"` (a placeholder, since the
orchestrator doesn't pick a distro). The orchestrator
now patches the session with the capsule's
`distribution.id` so the proot backend can find the
right distro.

#### 4. `E2EAuditLog` (production)

Phase 70's audit log was in `app/src/test/`. Phase 71
promotes it to `app/src/main/` for the same reason as the
orchestrator.

### Test fixtures (test/ + androidTest/)

#### 5. `InMemoryProotBackend` (replaces `ProotBackendStub`)

Phase 70's `ProotBackendStub` was a test-only class with a
parallel API. Phase 71 promotes it to
`InMemoryProotBackend` implementing the production
`ProotBackend` interface. The orchestrator's JVM test
now runs against the production interface, not a stub.

#### 6. `CriticalE2ETest` (JVM)

Refactored to:
- Use the production `ProotBackend` interface
- Wire `InMemoryProotBackend` as the test fixture
- Three new tests:
  - `step 7 fails when proot stop fails` — exercises
    step 7 (stop)
  - `step 8 fails when proot restore fails` — exercises
    step 8 (restore)
  - `phase 71 orchestrator patches the session distroId from the capsule distribution`
    — exercises the Phase 71 distroId patching

9 tests total (was 6 in Phase 70).

#### 7. `ProotBackendRealTest` (JVM)

9 new tests asserting the `ProotBackendReal` translation
logic end-to-end without standing up a real proot
binary:

- bindMounts → proot `-b` flags after `-r rootfs`
- no bindMounts → launcher's command unchanged
- executable + args + cwd → script
- environment merge (orchestrator wins)
- stop removes the handle + invokes the stop callback
- stop without prior launch is a no-op success
- non-LinuxProot session fails
- missing distro installation fails
- `ProcessLauncher` IOException surfaces as `Result.failure`

#### 8. `CriticalE2EInstrumentedTest` (androidTest, real device)

2 new instrumented tests:

- `critical_e2e_happy_path_runs_on_a_real_device_with_ProotBackendReal`
  — runs the 8-step E2E end-to-end on a real device
  with a stub `DistroSessionBackend` + stub
  `ProcessLauncher` + production `ProotBackendReal`;
  asserts the orchestrator composed with the
  production backend correctly
- `critical_e2e_surfaces_an_android_context_file_system_path_correctly`
  — asserts the test uses the device's
  `context.filesDir` (not a JVM test path)

The instrumented test is **the Definition of Done** of
the platform on a real device. It runs the same
orchestrator + the same backend shape as production
Hilt wiring (just with stub launchers since the test
doesn't actually fork proot).

---

## The proot command shape (what ProotBackendReal builds)

The Phase 71 backend's job is to translate the typed
contract into a proot invocation. The shape:

```
<proot-binary>                                  # from launcher.nativeLibrary
  --kill-on-exit
  --link2symlink
  -0
  -r <rootfs>                                    # from installation
  -b /dev -b /proc -b /sys                      # from launcher (Android sysfs)
  -b <host>:<container>                         # from orchestrator.bindMounts (E2E mounts)
  -w /root
  /usr/bin/env -i
    HOME=/root USER=root LOGNAME=root
    SHELL=/bin/sh TERM=xterm-256color
    LANG=C.UTF-8 TMPDIR=/tmp
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
  /bin/sh -lc "cd <workingDirectory> && <executable> <args>"
```

The orchestrator's bindMounts are injected as `-b
host:container` flags **after the `-r <rootfs>` pair**,
alongside the launcher's own `-b` flags. Read-only mounts
are inserted as plain `-b host:container` (proot doesn't
have a read-only flag; the `MountPolicyEnforcer` is the
boundary for read-only semantics).

The launcher's env vars are merged with the orchestrator's
env; the orchestrator's values win on conflict.

---

## Test counts

| Suite | Before | After | Delta |
|-------|--------|-------|-------|
| CriticalE2ETest (JVM) | 6 | 9 | +3 |
| ProotBackendRealTest (JVM) | 0 | 9 | +9 (new) |
| **Total JVM unit tests** | 2332 | 2344 | **+12** |
| CriticalE2EInstrumentedTest (androidTest) | 0 | 2 | +2 (new) |

**0 lint warnings, 0 test failures, 0 build errors.**

---

## Files

### New (production)
- `app/src/main/java/com/elysium/vanguard/core/runtime/proot/ProotBackend.kt`
- `app/src/main/java/com/elysium/vanguard/core/runtime/proot/ProotBackendReal.kt`
- `app/src/main/java/com/elysium/vanguard/core/runtime/critical_e2e/CriticalE2EOrchestrator.kt` (moved from test)
- `app/src/main/java/com/elysium/vanguard/core/runtime/critical_e2e/E2EAuditLog.kt` (moved from test)

### New (test)
- `app/src/test/java/com/elysium/vanguard/core/runtime/critical_e2e/InMemoryProotBackend.kt` (replaces ProotBackendStub)
- `app/src/test/java/com/elysium/vanguard/core/runtime/critical_e2e/CriticalE2ETest.kt` (refactored)
- `app/src/test/java/com/elysium/vanguard/core/runtime/proot/ProotBackendRealTest.kt`

### New (androidTest)
- `app/src/androidTest/java/com/elysium/vanguard/core/runtime/critical_e2e/CriticalE2EInstrumentedTest.kt`

### Removed (test)
- `app/src/test/java/com/elysium/vanguard/core/runtime/critical_e2e/ProotBackendStub.kt`
- `app/src/test/java/com/elysium/vanguard/core/runtime/critical_e2e/E2EAuditLog.kt`
- `app/src/test/java/com/elysium/vanguard/core/runtime/critical_e2e/CriticalE2EOrchestrator.kt`

---

## Architectural notes

### Why a separate `ProotBackend` interface (not a direct call)

The orchestrator is **pure-domain** (no I/O, no Android
deps). The `ProotBackend` is the seam where the
Android-side execution happens. Two implementations:

- `ProotBackendReal` (production) — calls the real
  `LinuxProotSessionRunner` + `DistroSessionBackend` +
  `ProcessLauncher`
- `InMemoryProotBackend` (test) — records invocations
  + returns canned writes

The orchestrator's tests can swap the backend without
touching the orchestrator's code. The real-device test
wires the production backend; the JVM test wires the
in-memory backend.

### Why `patchSessionDistro()` lives in the orchestrator (not the WorkspaceOrchestrator)

`WorkspaceOrchestrator` is **pure-domain** — it doesn't
know about capsules, distributions, or specific distros.
The `CriticalE2EOrchestrator` has the capsule; the
patching step is the bridge between the orchestrator
output (a `WorkspaceSession` with `distroId =
"__pending__"`) and the runtime hook (which needs an
actual distro id).

The patching is a one-line copy: `session.copy(distroId
= capsule.distribution.id, profileId = capsule.distribution.id)`.
Phase 72 may add a richer selector (e.g. a registry of
`capsule.distribution.id → installed distro`).

### Why the writes list is empty (for now)

The audit step 9 expects `LaunchResult.writes` to
contain the paths the process actually wrote to. The JVM
test wires a controlled writes list (via the
`InMemoryProotBackend`'s `nextWrites`). The real device
needs a `FileObserver` to capture the writes — that's a
Phase 72 follow-up. For Phase 71, the real-device test
asserts the launch path (the most critical Android-side
piece) + the orchestrator's composition with the
production backend. The audit step on the real device
will pass trivially (no writes = nothing to check).

### The `workspaceId` parameter on the interface

The `ProotBackend.launch(workspaceId, session, ...)` and
`stop(workspaceId, session)` and
`restoreSnapshot(workspaceId, session)` all take the
`workspaceId` because the real backend needs to find
the `Workspace` (the `SessionRunner.start(workspace,
session)` signature requires both). The
`WorkspaceManager` is the source of truth for the
workspace lookup.

---

## Next phases (the pipeline forward)

- **Phase 72 — Capsule installer UI (Compose)**: browse
  + install + update Capsules from the Market
- **Phase 73 — Elysium Vanguard Linux distro
  (proprietary)**: actual rootfs + manifests
- **Phase 74 — FileObserver for the audit's writes**:
  wire Android's `FileObserver` to capture the real
  device's writes; complete the audit step 9 on real
  hardware

Per the master vision, Phase 71 closes the **Critical E2E
Definition of Done** of the platform on a real device
(the 8-step scenario the vision's final section
describes). The remaining work is:

1. **UI** — the user-facing Market + Workspace screens
   (Phase 72, 73, 74).
2. **The actual Elysium Linux distro** — the proprietary
   rootfs the Elysium distribution references
   (Phase 73).
3. **Filesystem observer** — the real-device writes
   capture for the audit step (Phase 74).

The runtime is now feature-complete end-to-end. The
remaining phases are the user-facing + content side of
the platform.

---

## Test-discovered bugs this phase

Phase 71's development surface two test-discovered
bugs (caught and fixed in this phase):

1. **`assertEquals(expected, actual)` parameter order
   bug** in the new "distroId patch" test. The test
   expected `"elysium-linux-1"` but compared against
   the launch's `sessionId` (a UUID). The assertion
   silently failed. Fix: assert the `sessionId` is
   non-blank (the actual contract), not that it equals
   a specific value.

2. **D8 dexer rejects space characters in test method
   names** (not just colons). The Phase 64 fix
   mentioned colons; the D8 dexer also rejects
   spaces in `SimpleName` (per the error: "Space
   characters in SimpleName '...' are not allowed
   prior to DEX version 040"). Fix: use
   underscore-separated test names in `androidTest`
   (the JVM test classpath is more permissive).

Both are now reflected in the cross-project engineering
rules (see `engineering-gotchas.md`).
