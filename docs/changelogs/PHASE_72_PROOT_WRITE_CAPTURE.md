# Phase 72 — Proot write-capture (the audit half of the Definition of Done)

The master vision's eighth step — "Confirme que no hubo escrituras
fuera del workspace autorizado" — was a silent no-op on a real device.
The `ProotBackendReal.launch` returned `LaunchResult.writes =
emptyList()`. The audit passed because there were no writes to
check, not because the platform constrained them. Phase 72 wires
`android.os.FileObserver` into the proot lifecycle so the audit
sees real writes.

## The gap

Phase 71 shipped `ProotBackendReal` with the docstring:

> "The `writes` list on the returned `ProotBackend.LaunchResult`
>  is empty for now. Phase 72 wires a `FileObserver` so the real
>  device captures the actual writes."

The unit-side test (`CriticalE2ETest`) used the
`InMemoryProotBackend` with a pre-canned `nextWrites` list and
asserted the orchestrator's audit logic. The real-device path
(`CriticalE2EInstrumentedTest`) ran on a real device but the
`ProotBackendReal.writes` was empty, so step 9 of the E2E was
meaningless on-device.

## What shipped

### 1. The `WriteCapture` seam

`app/src/main/java/com/elysium/vanguard/core/runtime/proot/WriteCapture.kt`

The interface. One `start(watching: Set<String>)`, one `stop()`,
one `writes(): List<String>`. The seam makes the production
impl (`AndroidFileObserverWriteCapture`) swappable without
touching the orchestrator or the backend. The in-memory impl
(`InMemoryWriteCapture`) doubles as a test seam + dev recorder.

### 2. The production impl

`AndroidFileObserverWriteCapture.kt`

One `android.os.FileObserver` per watched host path. Listens for
`CREATE | MODIFY | MOVED_TO | CLOSE_WRITE`. The events are
recorded as `<hostPath>/<file>` strings. Non-recursive
(documented limitation; a follow-up adds a walker). The
`FileObserver` API is deprecated in API 29+ but still works on
every supported level (compileSdk=34, minSdk=26).

### 3. The proot lifecycle

`ProotBackendReal.kt` now:

- Takes a `writeCapture: WriteCapture = InMemoryWriteCapture()`
  parameter (the default makes the Phase 71 tests
  backward-compatible; Hilt wires the production impl).
- Calls `writeCapture.start(bindMounts.map { it.hostPath }.toSet())`
  **before** spawning the proot process.
- Populates `LaunchResult.writes` with the capture's snapshot
  at spawn time (typically empty; the proot process hasn't
  done I/O yet).
- Does **not** stop the capture in `stop()` — the orchestrator
  reads the final writes after `stop` and before
  `restoreSnapshot`.
- Stops the capture in `restoreSnapshot()` (session is fully
  over).

### 4. The new `ProotBackend.writes(workspaceId, session)` method

`ProotBackend.kt` gains a `writes` method. The interface
contract:

> Returns the host paths the process wrote to during the
> session. The orchestrator calls this **after** [stop] and
> **before** [restoreSnapshot] to populate the audit log;
> step 9 of the E2E then asserts every write is within the
> authorized mount list.

`ProotBackendReal.writes(...)` returns `writeCapture.writes()`.
`InMemoryProotBackend.writes(...)` returns its pre-canned
`nextWrites` (backward-compat: the existing tests
`step 9 fails when the process writes outside the authorized
mounts` + `step 9 passes when the process writes within the
authorized mounts` keep working without changes).

### 5. The orchestrator's flow update

`CriticalE2EOrchestrator.kt` now reads writes via
`prootBackend.writes(workspaceId, session)` in a new step 7.5
(between `stop` and `restoreSnapshot`):

```kotlin
// Step 6: launch
val launchResult = prootBackend.launch(...)

// Step 7: stop
val stopResult = prootBackend.stop(...)

// Step 7.5 (NEW): read the writes captured during the session
val capturedWrites = prootBackend.writes(workspace.id, session)
for (write in capturedWrites) {
    auditLog.record("proot:${session.id}", "write", write)
}

// Step 8: restore
val restoreResult = prootBackend.restoreSnapshot(...)
```

Step 9 (the audit) is unchanged: it reads the audit log and
asserts every write is within an authorized mount.

## Tests

**`WriteCaptureTest.kt`** — 15 JVM tests for the in-memory impl:
- `start` clears previous state
- `start` records the watched paths
- `record` captures paths inside a watched directory
- `record` rejects paths outside watched directories
- exact-match prefix counts
- `record` is dropped after `stop`
- `stop` is idempotent
- `stop` preserves the captured writes
- `writes` returns a snapshot (not aliased)
- `seed` pre-populates (test-only)
- `writes` returns empty before `start`
- re-`start` clears the list
- insertion order is preserved
- `stop` clears the watched set
- ...

**`ProotBackendRealWriteCaptureTest.kt`** — 7 JVM tests for
the `ProotBackendReal` integration with `WriteCapture`:
- `launch` calls `start` with the bind-mounted host paths
- `launch` with no bindMounts starts the capture with the
  empty set
- `launch` with a spawn failure stops the capture to avoid
  stale state
- `stop` does **not** stop the capture
- `writes` returns the capture's snapshot
- `restoreSnapshot` stops the capture
- sequential sessions reset the capture (no write bleed)

**Existing tests**: 14 in `WriteCaptureTest` + 7 in
`ProotBackendRealWriteCaptureTest` + 9 in
`CriticalE2ETest` (all green, no changes needed for the
critical_e2e side) + 9 in `ProotBackendRealTest` (all green,
no changes needed for the translation side).

## Build / test status

- `compileDebugKotlin` — green (1 pre-existing deprecation
  warning for `FileObserver(String)`; the warning is the
  documented limitation in ADR-029)
- `testDebugUnitTest` — **2558 tests, 0 failures, 0 errors**
- `compileDebugUnitTestKotlin` — green

## Files

- `app/src/main/java/com/elysium/vanguard/core/runtime/proot/WriteCapture.kt` (NEW)
- `app/src/main/java/com/elysium/vanguard/core/runtime/proot/InMemoryWriteCapture.kt` (NEW)
- `app/src/main/java/com/elysium/vanguard/core/runtime/proot/AndroidFileObserverWriteCapture.kt` (NEW)
- `app/src/main/java/com/elysium/vanguard/core/runtime/proot/ProotBackend.kt` (UPDATED: added `writes` method)
- `app/src/main/java/com/elysium/vanguard/core/runtime/proot/ProotBackendReal.kt` (UPDATED: capture wiring)
- `app/src/main/java/com/elysium/vanguard/core/runtime/critical_e2e/CriticalE2EOrchestrator.kt` (UPDATED: new step 7.5)
- `app/src/test/java/com/elysium/vanguard/core/runtime/proot/WriteCaptureTest.kt` (NEW, 15 tests)
- `app/src/test/java/com/elysium/vanguard/core/runtime/proot/ProotBackendRealWriteCaptureTest.kt` (NEW, 7 tests)
- `app/src/test/java/com/elysium/vanguard/core/runtime/critical_e2e/InMemoryProotBackend.kt` (UPDATED: `writes` method)
- `docs/adr/ADR-029-proot-write-capture.md` (NEW)

## Lessons

- **Audit seams are the right abstraction for write-capture.** A
  `start / stop / writes` triple is the minimum surface the
  orchestrator needs. The production impl is Android-native;
  the in-memory impl is JVM-testable. Tests assert the
  contract, not the implementation.
- **The `LaunchResult.writes` field is a snapshot, not a
  stream.** Reading it after `launch` returns whatever the
  capture saw between `start` and the moment `launch`
  returned — typically empty. The orchestrator needs a
  post-`stop` read; that's why the new `writes` method
  exists.
- **Backward compat via the `InMemoryProotBackend.writes`**
  return value. The Phase 71 tests set `nextWrites` and
  expected the orchestrator to detect the unauthorized
  write. With the new flow, the same `nextWrites` flows
  through `writes()` instead of `LaunchResult.writes`, but
  the test outcome is identical.
- **Test-discovered design refinement #1**: the in-memory
  capture should clear the watched set on `stop` to match
  the Android impl's `observer.stopWatching()`. Caught by
  the test "after stop the watched set is empty".
- **Test-discovered design refinement #2**: the
  `RecordingWriteCapture` test fake must clear the simulated
  writes on `start` (matches the production behavior —
  otherwise a stale write from session A would bleed into
  session B's audit). Caught by the test "sequential
  sessions reset the capture".

## What's next

The next gap in the master vision is `RealAgentCollaborators` —
Vanguard AI's runtime integration. The `PlanExecutor` and
`NaturalLanguageParser` exist (Phase 57) with full unit test
coverage, but no production `AgentCollaborators` implementation
is wired into the Hilt graph, and no UI dispatches the agent.
Closing this gap converts Vanguard AI from a typed schema with
unit tests into a real "agent that operates the platform".
