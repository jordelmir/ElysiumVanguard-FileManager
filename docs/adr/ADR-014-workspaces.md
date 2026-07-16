# ADR-014 — Workspaces (Multi-Session State Isolation)

Status: **Accepted** (Phase 24, 2026-07-15)
Owners: Runtime
Supersedes: none
Superseded by: none

## Context

Master order §22: "The runtime groups sessions into
workspaces. A workspace is a logical container for one
or more sessions (Linux proots + Windows VMs) that
share storage mounts, network policies, and lifecycle
events. Multiple workspaces can exist; cross-workspace
isolation is a hard boundary."

Until Phase 24 the runtime had a single implicit
"workspace": a flat list of distros in `DistroCatalog`
+ a flat list of Windows VM specs in `WindowsVmCatalog`.
A user with "Work" and "Personal" distros had no way to
group them; the runtime could not enforce "Personal
sessions cannot see Work storage" or "Personal VMs
cannot talk to Work's network bridge".

The challenge: a workspace is more than a tag. The
state machine (Active / Paused / Closed), the cross-
workspace isolation, the persistence, and the per-
session lifecycle events are all new layers. The
manager has to be thread-safe (a user may add sessions
from multiple coroutines) and JVM-testable end-to-end
(without an Android device).

## Decision

We split the workspace path into four small classes:

1. **`WorkspaceSession` (sealed)** — the typed shape of
   a session. `LinuxProot(distroId, profileId)` or
   `WindowsVm(windowsSpecId)`. A future `MacosVm` or
   `RemoteSession` is a sealed-class addition.

2. **`Workspace` (data class)** — the container. Holds
   an `id`, `name`, `createdAtMs`, a list of sessions,
   and a `state` (Active / Paused / Closed). The init
   block enforces uniqueness of session ids and the
   "closed workspaces must have at least one session"
   rule.

3. **`WorkspaceStore` (interface)** — the persistence
   seam. The production impl writes JSON files under
   `<baseDir>/workspaces/<id>.json`; the
   `InMemoryWorkspaceStore` is the test impl. The store
   is the durable state; the manager's in-memory
   `byId` map is a cache.

4. **`WorkspaceManager`** — the user-facing orchestrator.
   The manager hydrates from the store on init, exposes
   `createWorkspace` / `pauseWorkspace` /
   `activateWorkspace` / `closeWorkspace` /
   `addSession` / `removeSession` / `findSession`, and
   auto-saves every state change.

The manager is the cross-workspace isolation boundary.
A session id used by another workspace is a typed
`WorkspaceError.SessionIdUsedElsewhere` — the manager
refuses to add the session, no matter which workspace
the caller names.

### Why a per-workspace lock for `addSession` / `removeSession`

The read-modify-write of `workspace.sessions + newSession`
is not atomic by default. Without a lock, two threads
adding different sessions can both read the same
`workspace.sessions`, both pass the duplicate check, both
write back, and one of the writes is lost. The Phase 24
test caught this race: 8 × 20 concurrent `addSession`
calls produced 20 sessions instead of 160. The fix: a
per-workspace lock (`locks.computeIfAbsent(workspaceId)
{ Any() }`) that serialises the read-modify-write. The
test now produces 160 sessions under contention.

### Why a sealed class for sessions

A workspace can hold Linux proots + Windows VMs +
(future) macOS VMs + (future) remote sessions. The
sealed class makes the closed set explicit; a future
session type is a deliberate code change. The runtime's
UI switches on `SessionKind` to render the right
controls (terminal for Linux, VM display for Windows).

### Why the manager auto-saves on every state change

The manager's `addSession` / `pauseWorkspace` / etc.
all call `store.save(workspace)` at the end. The
trade-off: every state change writes to disk (a small
JSON file). The benefit: the runtime never has a
"memory-only state" that the user cannot recover if
the process dies. The `flushAll` belt-and-braces hook
on shutdown is a no-op when the manager has been
auto-saving; it is the escape hatch for callers that
mutate the workspace without going through the manager
(e.g. a future debug tool).

### Why the init block enforces "closed needs at least one session"

Closing a workspace is a *logical* state, not a delete.
A closed workspace can be re-opened; a deleted
workspace cannot. The init block enforces the invariant
that a closed workspace has at least one session —
otherwise re-opening would create an empty workspace,
which is meaningless. The alternative (deleting the
sessions on close) would lose user data.

## Consequences

### Positive

- **JVM-testable end-to-end.** The 25 unit tests cover
  the workspace invariants, the session invariants,
  the state machine, the cross-workspace isolation,
  the persistence round-trip, and the manager's
  thread-safety. The in-memory store simulates the
  production file-backed store; the manager does not
  change.
- **Cross-workspace isolation is a hard error.** A
  session id used by another workspace is a typed
  `WorkspaceError.SessionIdUsedElsewhere`; the
  manager refuses the call. The runtime's UI surfaces
  the error kind; the user can rename the session.
- **State machine is small and exhaustive.** `Active`
  / `Paused` / `Closed` is a sealed class; the
  compiler enforces the closed set. A future
  `Archived` state is a deliberate code change.
- **Auto-save survives process death.** Every state
  change writes to the store; a fresh `WorkspaceManager`
  hydrates the in-memory index from the store on init.
  The user's workspaces survive across reboots.
- **Per-workspace lock fixes the addSession race.**
  The 8 × 20 concurrent test now produces 160
  sessions, not 20. The lock is the single point of
  serialisation; the manager's other methods are
  stateless and don't need locks.

### Negative

- **In-memory index is a cache, not a source of truth.**
  A manager that mutates a workspace without calling
  `store.save` would diverge from the store. The
  test suite covers the contract; a future debug
  tool that bypasses the manager is a foot-gun.
- **Cross-workspace isolation is manager-side, not
  type-side.** A typed `WorkspaceId + SessionId` pair
  would catch the "session id used elsewhere" case at
  compile time. The current shape uses `String`
  session ids; a future phase introduces a `SessionId`
  value class with the cross-workspace invariant.
- **No live-session lifecycle events.** The manager
  tracks the *logical* state (Active / Paused / Closed)
  but does not yet fire "session launched" /
  "session crashed" events. The runtime's UI polls the
  manager; a future phase adds an event bus.
- **`flushAll` is a no-op under auto-save.** The
  belt-and-braces hook exists for callers that bypass
  the manager; the manager's contract is that every
  state change is auto-saved.

## Alternatives considered

1. **Skip the manager; use the store directly.** The
   store is the persistence seam; the manager adds
   the cross-workspace isolation, the auto-save, and
   the per-workspace lock. Without the manager, every
   consumer of the store has to re-implement the
   invariants — a recipe for divergence.
2. **Use `kotlinx.coroutines` channels for the
   lifecycle events.** Out of scope for Phase 24.
   A future phase adds a `WorkspaceEventBus` that
   the manager emits to; the runtime's UI subscribes.
3. **Make the workspace an `object` or a `singleton`.**
   Rejected: the runtime has multiple workspaces
   ("Work" + "Personal"). The manager is the
   container; the workspaces are the contents.

## Revisit triggers

- The runtime needs to share state between two
  workspaces (e.g. "Work" mounts a `~/shared` dir
  that "Personal" can read). The cross-workspace
  isolation becomes a typed "shared" allow-list.
- A new session type (e.g. macOS VM, remote SSH
  session) is added. The sealed class gains a
  variant; the runtime's UI gains a switch case.
- The first on-device run reveals a persistence
  failure (e.g. the JSON file does not survive a
  reboot). The Phase 24 in-memory store hides the
  bug; a Phase 25 integration test catches it.
