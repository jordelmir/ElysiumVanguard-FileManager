# ADR-029 — Proot write-capture (the audit half of the master vision's Definition of Done)

Status: **Accepted** (Phase 72, 2026-07-19)
Owners: Runtime + Proot
Supersedes: the docstring of `ProotBackendReal.launch` (Phase 71, 2026-07-18) that said "Phase 72 wires a `FileObserver`"
Superseded by: none

## Context

The master vision doc (the order that converted the file manager into a universal computing platform) names a **Prueba de integración crítica** — the Definition of Done of the platform. The test runs eight steps; the eighth is:

> "Confirme que no hubo escrituras fuera del workspace autorizado."

Until Phase 72 this check was a **silent no-op on a real device**. The `ProotBackendReal` (Phase 71) returned `LaunchResult.writes = emptyList()`. The `CriticalE2EOrchestrator` recorded those (empty) writes to the audit log in step 6 and read them back in step 9. The audit passed because there were no writes to check — not because the platform actually constrained the writes.

The unit-side test (`CriticalE2ETest`) used the `InMemoryProotBackend` (Phase 71) with a pre-canned `nextWrites` list; the test asserted the orchestrator's audit logic. The real-device path was untested. A real proot process could write anywhere the bind-mounts allowed and the platform would not notice.

This was a real gap against the vision. The platform's most important security claim — "writes are constrained to the workspace" — was not enforced at the write-audit layer. The mount policy enforcer (Phase 50) constrains the **mounts** but does not observe the writes.

## Decision

Introduce a `WriteCapture` interface in `core/runtime/proot/` with two implementations:

- **`InMemoryWriteCapture`** (in main; reusable in tests). A 5-line hand-rolled recorder that filters writes against a "watched" path set. The test seam — tests pre-seed or pre-populate to assert the orchestrator's audit logic without standing up a real `FileObserver`.
- **`AndroidFileObserverWriteCapture`** (in main; production). One `android.os.FileObserver` per watched host path; events `CREATE | MODIFY | MOVED_TO | CLOSE_WRITE` are recorded as `<hostPath>/<file>`. Non-recursive (the orchestrator's bindMounts are user-selected directories, not deep trees). `FileObserver` is deprecated in API 29+ but still works on every supported API level (compileSdk=34, minSdk=26); a follow-up can swap in the AndroidX wrapper or `StorageManager`.

Wire the capture into the `ProotBackend` lifecycle:

- `launch(...)` calls `writeCapture.start(watchedHostPaths)` **before** spawning the process. The `LaunchResult.writes` field is a snapshot at spawn time (typically empty; the proot process hasn't done any I/O yet).
- `stop(...)` does **not** stop the capture. The orchestrator reads the final writes after `stop` and before `restoreSnapshot`.
- `restoreSnapshot(...)` stops the capture (the session is fully over).
- New `ProotBackend.writes(workspaceId, session): List<String>` method returns the capture's snapshot. Called by the orchestrator between `stop` and `restoreSnapshot`.

The orchestrator's flow updates accordingly:

```
launch → start(capture) → spawn
stop → kill process (capture still running)
writes → read capture (NEW step 7.5)
restore → stop(capture)
audit → check every write is within an authorized mount
```

The `InMemoryProotBackend` (the test stub) gets a `writes(...)` method that returns its pre-canned `nextWrites` list. The existing test scenarios (unauthorized `/etc/passwd`, authorized `/workspace/projects/output.json`) keep working without changes.

The `ProotBackendReal` constructor gains a `writeCapture: WriteCapture = InMemoryWriteCapture()` parameter. Hilt wires the production impl; tests inject fakes. The default makes the Phase 71 tests backward-compatible.

## Consequences

**Positive**

- The Definition of Done's step 8 now has real meaning on a real device. A proot process that writes to a path outside the authorized bind-mounts is detected.
- The audit log (Phase 25) gets a real `write` event for every `CREATE / MODIFY / MOVED_TO / CLOSE_WRITE` on a watched path. The Phase 64 instrumented tests can assert the audit is populated.
- The seam is the `WriteCapture` interface; future swaps (e.g. `androidx.core.content.FileObserver`, `StorageManager`, eBPF, fsnotify) are non-breaking.
- The capture is a pure observer — it never blocks the proot process. A misbehaving capture cannot break execution.
- The `InMemoryWriteCapture` doubles as a test seam + dev-build recorder; tests + dev builds get the same audit semantics as production.

**Negative / risks**

- `android.os.FileObserver` is deprecated in API 29+; the deprecation warning is logged at compile time. A follow-up (Phase 73+) swaps in the modern API.
- Non-recursive. A process that creates a sub-directory and writes to a file in the sub-directory is not captured. The orchestrator's bindMounts are typically single user-selected directories, so the common case is fine. A follow-up adds a recursive walker.
- One `WriteCapture` per `ProotBackendReal` instance (shared across sequential sessions). Concurrent sessions on the same backend would clobber each other's writes; the orchestrator currently runs one session at a time, so this is not a regression. A follow-up keys the capture by `(workspaceId, sessionId)` for full multi-session support.
- The capture is best-effort: events can be lost if the observer is not yet started when the first write fires (a small race window between `start` and `processLauncher.start`). The race is closed by the start ordering: `writeCapture.start` runs before the spawn.
- The production `AndroidFileObserverWriteCapture` is not directly unit-testable (the `FileObserver` is Android-only). The contract is asserted through the in-memory impl + the `ProotBackendReal` integration test (which uses the in-memory capture via dependency injection).

## Alternatives considered

- **eBPF / fsnotify**. More accurate, lower-level, no per-mount inotify watch. Heavyweight; out of scope for Phase 72.
- **Poll the bind-mounted directories on `stop`**. Simple, no native deps, but the orchestrator's `stop` would block on filesystem I/O and the captured "writes" would be a state diff (hard to attribute to a specific event type).
- **Wrap the proot binary in a trampoline that records every `write(2)` syscall**. The most accurate, but requires ptrace / LD_PRELOAD / a custom proot build. Phase 72 keeps the `FileObserver` for the bind-mounted host paths (the user's data, not the rootfs).
- **Have `launch` block until the process exits, then return all writes**. The simplest API change, but it changes the orchestrator's flow: it would have to wait for the proot process to finish before continuing to `stop`. The current model is "launch + return; the process runs in the background; the orchestrator decides when to stop." Keeping the model.

## Notes for follow-ups

- **Recursive watching**: a Phase 73 walker starts one `FileObserver` per sub-directory of each watched path. The capture's API doesn't change; the impl just emits more events.
- **Modern API**: swap `android.os.FileObserver` for `androidx.core.content.FileObserver` (the AndroidX wrapper, which is the recommended replacement). The interface is identical; only the import changes.
- **Multi-session**: key the capture by `(workspaceId, sessionId)`. The interface gets a `sessionId: String` parameter on `start(...)`. The orchestrator already has the `(workspaceId, sessionId)` pair.
- **Proot kernel-side audit**: a future phase may record the proot-internal syscalls (the proot binary already has audit hooks). The `WriteCapture` interface is the platform's surface for "every write the proot session made"; the kernel-side audit would be a richer data source.
