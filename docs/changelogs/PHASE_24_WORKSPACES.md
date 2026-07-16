# Phase 24 — Workspaces (Multi-Session State Isolation)

Date: 2026-07-15
Status: **Shipped** — `assembleDebug` green, 1242 tests, 0 failures, 2 skipped.

## What landed

The runtime now has a typed workspace layer. A workspace
is a logical container for one or more sessions (Linux
proots + Windows VMs); the manager enforces cross-
workspace isolation and persists every state change.

### Files

**Production (4 new):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/workspaces/WorkspaceSession.kt`
  — the sealed `WorkspaceSession` class. `LinuxProot(distroId,
  profileId)` or `WindowsVm(windowsSpecId)`. The sealed
  class makes the closed set explicit; a future
  `MacosVm` is a sealed-class addition.
- `app/src/main/java/com/elysium/vanguard/core/runtime/workspaces/Workspace.kt`
  — the `Workspace` data class + the `WorkspaceState`
  sealed class (`Active` / `Paused` / `Closed`).
  Init block enforces uniqueness of session ids and
  the "closed needs at least one session" rule.
- `app/src/main/java/com/elysium/vanguard/core/runtime/workspaces/WorkspaceStore.kt`
  — the persistence seam. `WorkspaceStore` interface
  + `InMemoryWorkspaceStore` test impl (5-line
  hand-rolled, thread-safe via `synchronized`).
- `app/src/main/java/com/elysium/vanguard/core/runtime/workspaces/WorkspaceManager.kt`
  — the user-facing orchestrator. Hydrates from the
  store on init, exposes `createWorkspace` /
  `pauseWorkspace` / `activateWorkspace` /
  `closeWorkspace` / `addSession` / `removeSession`
  / `findSession`, and auto-saves every state change.
  Returns typed `Result<...>` with a
  `WorkspaceError` sealed class for failures
  (`NotFound` / `InvalidName` /
  `DuplicateSessionId` / `SessionIdUsedElsewhere` /
  `SessionNotFound` / `CannotCloseEmpty`).

**Tests (1 new):**

- `app/src/test/java/com/elysium/vanguard/core/runtime/workspaces/WorkspaceManagerTest.kt`
  — 25 unit tests covering the workspace invariants,
  session invariants, state machine
  (Active/Paused/Closed + re-activation), session
  management (add/remove), cross-workspace isolation,
  the persistence round-trip, and the manager's
  thread-safety under 8 × 20 concurrent `addSession`
  calls.

**ADR (1 new):**

- `docs/adr/ADR-014-workspaces.md` — context, decision,
  the per-workspace lock rationale, the auto-save
  policy, consequences, alternatives, revisit triggers.

### Why this matters

Master order §22: "The runtime groups sessions into
workspaces. Cross-workspace isolation is a hard
boundary." Until Phase 24 the runtime had a single
implicit workspace — a flat list of distros + VMs.
A user with "Work" and "Personal" had no way to group
them, and the runtime had no way to enforce
"Personal sessions cannot see Work storage".

Phase 24 closes the gap. A workspace is the
isolation boundary. The manager's `addSession`
refuses to add a session whose id is used by another
workspace — a typed `SessionIdUsedElsewhere` error.
The runtime's UI surfaces the error kind; the user
renames the session.

The state machine (`Active` / `Paused` / `Closed`) is
the lifecycle anchor. A closed workspace can be
re-opened (sessions preserved); a paused workspace
resumes the sessions. The manager auto-saves every
state change, so a process death does not lose data.

### The test that caught a real race

The 8 × 20 concurrent `addSession` test originally
failed: only 20 sessions ended up in the workspace
instead of 160. The root cause was a classic
check-then-act race: two threads read the same
`workspace.sessions`, both passed the duplicate check,
both wrote back, and one of the writes was lost. The
fix: a per-workspace lock
(`locks.computeIfAbsent(workspaceId) { Any() }`) that
serialises the read-modify-write. The test now
produces 160 sessions under contention.

This is exactly the kind of regression the user
contract calls out as "good news": the test caught a
real race; the fix is a 3-line per-workspace lock;
the test's invariant (160 sessions after 8 × 20 adds)
is what protects future contributors from re-
introducing the bug.

## Test count

| Suite | Tests | Failures |
|---|---|---|
| `WorkspaceManagerTest` | 25 | 0 |
| **Project total** | **1242** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Bug fix during this phase

`addSession` and `removeSession` had a check-then-act
race that the 8 × 20 concurrent test caught. Fixed
with a per-workspace lock
(`synchronized(lockFor(workspaceId))`). The test now
passes with 160 sessions, not 20.

## Next phase

**§25 Observability** (master order) — the runtime's
audit log + event bus. The Phase 19 hardware audit
log + the Phase 13 network audit log are the seeds; a
single `WorkspaceEventBus` is the production seam.
Also candidate: **§36 remaining ADRs** (the master
order lists 001, 002, 014, 015, 016 in addition to
005–013 — 4–5 to go).
