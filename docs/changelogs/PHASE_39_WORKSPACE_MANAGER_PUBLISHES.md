# Phase 39 — `WorkspaceManager` publishes its own events (architectural cleanup)

Date: 2026-07-17
Status: **Shipped** — `assembleDebug` green, 1359 tests, 0 failures, 2 skipped.

## What landed

`WorkspaceManager` is now the **single source of
truth** for "what just happened to a workspace".
The manager gained a `RuntimeEventBus` collaborator
and a `clock` qualifier (`@WallClock`); every state
change (`createWorkspace`, `pauseWorkspace`,
`activateWorkspace`, `closeWorkspace`, `addSession`,
`removeSession`) publishes a `RuntimeEvent` on the
bus directly. The
`WorkspacesViewModel` no longer publishes events on
its own — it just calls the manager, and the bus
subscriber re-reads state.

This closes a real architectural hole. Before
Phase 39, the flow was:
- `WorkspacesViewModel.createWorkspace(name)` →
  `WorkspaceManager.createWorkspace(name)` →
  `WorkspaceViewModel.eventBus.publish(...)` →
  `WorkspaceViewModel.refresh()`.

The double-hop (manager → publish) meant a
future service or background job that mutated the
manager directly (e.g. a future "workspace
migration" tool, or a "boot-time auto-create
default workspace" path) would skip the
observability path entirely. Phase 39 makes the
manager the only place events are published; the
ViewModel becomes a pure consumer.

### Files

**Production (3 modified):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/workspaces/WorkspaceManager.kt` —
  gained `eventBus: RuntimeEventBus` and
  `clock: () -> Long = Companion::systemClock` as
  required constructor params. Every state
  change now publishes the corresponding event.
  The default `clock` lives in a private companion
  object's `systemClock()` method (Kotlin
  reserves `::` in default-value position for
  future use, so the named reference is the
  smallest workaround).
- `app/src/main/java/com/elysium/vanguard/core/runtime/ui/WorkspacesViewModel.kt` —
  removed the `eventBus.publish(...)` calls from
  `createWorkspace`, `addSession`,
  `removeSession`, and `transitionAndPublish`.
  The renamed `transitionAndRecord` only
  records failures on `lastActionResult`. The
  ViewModel's bus subscriber still receives the
  manager's events (the bus is the same instance
  in production via the Hilt graph) and refreshes
  state on the workspace-level events.
- `app/src/main/java/com/elysium/vanguard/core/runtime/RuntimeModule.kt` —
  `provideWorkspaceManager` now injects the
  `RuntimeEventBus` + `@WallClock` and passes both
  to the manager. The bus instance is the
  shared one (production; the tests use
  `RecordingEventBus`).

**Tests (1 modified):**

- `app/src/test/java/com/elysium/vanguard/core/runtime/workspaces/WorkspaceManagerTest.kt` —
  the existing tests' constructions now pass a
  `RecordingEventBus` + a `clock`. 8 new tests
  pin the event-publishing contract:
  - `createWorkspace` publishes a
    `WorkspaceStateChangedEvent` with `fromState =
    "(none)"`, `toState = "Active"`.
  - `createWorkspace` with a blank name does NOT
    publish (failure paths are silent).
  - `pauseWorkspace` publishes Active → Paused.
  - `activateWorkspace` publishes Paused → Active.
  - `closeWorkspace` publishes Active → Closed.
  - `addSession` publishes a `SessionAddedEvent`
    with the session's `kind` discriminator.
  - `addSession` with a duplicate id does NOT
    publish.
  - `removeSession` publishes a
    `SessionRemovedEvent`.
- `app/src/test/java/com/elysium/vanguard/core/runtime/ui/WorkspacesViewModelTest.kt` —
  the manager and the ViewModel now share the
  same `RecordingEventBus` + `clock` (the only
  way the existing "1 event published after
  createWorkspace" + "atMs = 1234" assertions
  stay valid post-refactor).
- `app/src/test/java/com/elysium/vanguard/core/runtime/ui/MainScreenViewModelTest.kt` —
  its `WorkspaceManager` constructions now pass
  a `RecordingEventBus` (the ViewModel does not
  need to share the bus; it only needs the
  manager to compile).

## Why this matters

Phase 39 is the **architectural ground truth** for
the runtime. After Phase 39, every workspace
mutation flows through the manager, every event
flows through the bus, and every UI subscriber
sees a consistent view. A future writer of the
manager (a service, a tool, a background job) is
guaranteed to fire the same events as the
ViewModel does — the bus subscriber does not need
to know who wrote the workspace, only that the
write happened.

The refactor also caught a real test gap: before
Phase 39, the manager's event-publishing
contract was implicit (the ViewModel's contract).
After Phase 39, the manager's contract is
explicit and pinned by 8 dedicated tests.

## Design notes

### Why the manager takes the bus as a constructor arg

A `WorkspaceManager` that takes a bus is the
*natural* shape: the manager is a stateful
object that mutates a persistent store; the bus
is the observability seam. The two collaborators
live together. Splitting them (the pre-Phase-39
shape) meant the ViewModel had to remember to
publish on every action — a fragile contract
that any future caller would forget.

### Why a default `clock` in a companion

Kotlin reserves `::` in default-value position
for future use:

```kotlin
class WorkspaceManager(
    private val clock: () -> Long = System::currentTimeMillis()  // compile error
)
```

The smallest workaround is a named reference
inside a companion object:

```kotlin
class WorkspaceManager(
    clock: () -> Long = Companion::systemClock
) {
    private val clock: () -> Long = clock
    private companion object {
        fun systemClock(): Long = System.currentTimeMillis()
    }
}
```

The constructor stays callable with a single
`WorkspaceManager(store, bus)`, the default is
a real function call (not a method reference),
and the test can pass its own `clock::get`
without the reserved-syntax gotcha.

### Why the test bus and clock are shared

Before Phase 39, the `WorkspacesViewModelTest`
asserted that `createWorkspace` published an
event with `atMs = 1234`. The test set the
ViewModel's clock to `1234L` and the ViewModel
passed `nowMs` to the manager's
`createWorkspace(nowMs = ...)`. Post-Phase-39,
the manager owns the event publication; the
test now shares the clock between the manager
and the ViewModel so the event's `atMs` matches
the test's expectation. The bus is shared for
the same reason: the manager publishes on the
test bus, the ViewModel subscribes to the same
bus, and the existing "1 event in the bus"
assertion stays valid.

## Test count

| Suite | Tests | Failures |
|---|---|---|
| `WorkspaceManagerTest` | 36 (was 28) | 0 |
| **Project total** | **1359** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Next phase

The next phase is **Phase 40 — the
`androidTest/` end-to-end coverage for the
`MainScreen`**. The Hilt-instrumented test will
exercise the full Phase 36 → Phase 38 stack
(`RuntimeModule` → `MainScreenViewModel` →
`WorkspacesViewModel` → Compose UI). Phase 40
is the only piece of the runtime still missing
instrumented coverage; everything else is
unit-testable end-to-end on the JVM.

A second follow-up (Phase 41) adds the
"Create workspace" + "Add session" + Pause /
Activate / Close UI flows the `WorkspacesViewModel`
already supports.
