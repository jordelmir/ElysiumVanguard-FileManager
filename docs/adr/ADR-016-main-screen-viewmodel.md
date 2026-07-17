# ADR-016 — Main Screen ViewModel (UI Orchestration)

Status: **Accepted** (Phase 28, 2026-07-15)
Owners: Runtime
Supersedes: none
Superseded by: none

## Context

Master order §24: "The runtime's main screen shows
the user's workspaces, the Linux distros they have
installed, the Windows VMs they have running, and a
'what just happened' feed of recent events." Until
Phase 28, every Compose ViewModel was
`AndroidViewModel`-flavoured (Phase 9.6.3's
`RuntimeViewModel`): it inherited from
`androidx.lifecycle.AndroidViewModel`, required an
`Application`, and was not JVM-testable. A bug in the
orchestration logic was caught by an on-device
integration test, not by a fast JVM test.

The challenge: the Compose UI consumes `StateFlow`
(Phase 9.6.x). The orchestration logic that produces
the `StateFlow` is the business logic — the
ViewModel. The ViewModel must be:

- **JVM-testable**: every assertion runs in the JVM
  unit test classpath, no Android device.
- **`Context`-free**: no `Application`, no `Context`,
  no `SharedPreferences`, no `Resources`.
- **`StateFlow`-shaped**: the UI subscribes via
  `state.collect { … }`.
- **Event-driven**: the `RuntimeEventBus` (Phase 25)
  is the seam; every state change emits an event;
  the ViewModel subscribes once.

## Decision

The runtime's main-screen orchestration is a single
class — `MainScreenViewModel` — that composes the four
collaborators and exposes a single
`StateFlow<MainScreenState>`.

```kotlin
class MainScreenViewModel(
    private val workspaceManager: WorkspaceManager,
    private val distroManager: DistroManager,
    private val windowsVmManager: WindowsVmManager,
    private val eventBus: RuntimeEventBus,
    private val recentEventsCapacity: Int = 20
) : AutoCloseable {
    val state: StateFlow<MainScreenState>

    fun refresh()
    fun forceRefresh() = refresh()
    override fun close()
}
```

The `MainScreenState` is a single data class with
five fields:
- `workspaces: List<WorkspaceSummary>` — the
  workspace list (name, state, session counts).
- `linuxDistrosInstalled: Int` — the count of
  installed Linux distros.
- `linuxDistrosInstalling: Int` — the count of
  distros in the middle of an install.
- `windowsVmsRunning: Int` — the count of running
  Windows VMs.
- `recentEvents: List<RuntimeEvent>` — the "what
  just happened" feed (capped at
  `recentEventsCapacity`).

The ViewModel subscribes to the event bus on
construction; every event appends to `recentEvents`
and trims to the capacity. The `refresh()` method
rebuilds the state from the four collaborators; the
UI calls `refresh()` on `onResume` (and the bus
subscriptions handle the live updates).

### Why a single class, not one per collaborator

The four collaborators have overlapping data the
runtime's main screen renders. A separate
ViewModel per collaborator would force the UI to
combine four `StateFlow`s into a single render —
a recipe for inconsistent frames. A single
ViewModel with a single `StateFlow` is the simplest
shape the UI consumes.

### Why a `MainScreenState` data class, not a sealed class

The main-screen state is a snapshot of values, not
a tagged union. A data class with five fields is
the natural shape; the UI renders each field. A
sealed class would force a `when` over the state,
which is unnecessary for a snapshot.

### Why the bus subscription, not a polling loop

The bus (Phase 25) is the production seam; the
ViewModel uses it. A polling loop (`refresh()` every
N seconds) is the alternative; it has a latency
floor (the poll interval) and a CPU cost. The bus
pushes events; the ViewModel updates the state in
real time. The 8 × 50 concurrent test exercises the
contention path.

### Why `AndroidViewModel`-free

`AndroidViewModel` requires `Application`. The JVM
unit tests do not have an `Application`. The
`MainScreenViewModel` uses a plain `AutoCloseable`
interface; the production wiring adds the
`Application` parameter at the Hilt boundary (a
future phase's `MainScreenViewModelFactory`).

## Consequences

### Positive

- **JVM-testable end-to-end.** The 8 unit tests
  cover: empty initial state, refresh from the four
  collaborators, picking up new workspaces, the
  recent-events buffer's cap, the close lifecycle,
  thread-safety under 4 × 50 publishes + 4 × 20
  refreshes, and the per-kind session counts in
  `WorkspaceSummary`.
- **`Context`-free.** The class compiles without
  any `android.*` import; the JVM unit tests do
  not need `Context` or `Application`. A future
  Compose-side adapter wires the
  `Application`-flavoured factory.
- **Single `StateFlow`.** The UI subscribes once
  (`state.collect { … }`); the snapshot is
  consistent across the five fields. No race
  between "workspaces" and "recentEvents".
- **Bus events drive live updates.** A new
  `RuntimeEvent` published on the bus
  immediately appends to `recentEvents` and (if the
  event changes the state) re-emits a new
  `MainScreenState`. The UI re-renders.
- **`refresh()` is the explicit escape hatch.** A
  bus that missed an event (e.g. process death) is
  reconciled on the next `refresh()` call. The UI
  calls `refresh()` on `onResume`.

### Negative

- **The ViewModel does not yet emit a
  `MainScreenViewModelFactory` Hilt adapter.** A
  future phase adds the `HiltViewModel` annotation
  + the factory. The class is the JVM-testable
  contract; the Hilt adapter is a one-line wiring.
- **`recentEvents` is a `List<RuntimeEvent>`, not
  a typed sealed-class view.** The UI must
  `when (event)` to render. A future phase adds
  per-event renderers.
- **The cap is a single number, not a per-kind
  cap.** A user that wants "last 20 network events
  + last 20 workspace events" would need a
  per-kind buffer; the current shape is a single
  ring.
- **No `viewModelScope` integration.** The class
  is `AndroidViewModel`-free; a future
  Compose-side adapter wires the `StateFlow` to
  `viewModelScope.launch { state.collect { … } }`.

## Alternatives considered

1. **One ViewModel per collaborator.** Each
   collaborator's `StateFlow` is exposed
   individually; the UI combines them. Rejected:
   the UI would render inconsistent frames
   (workspaces updated at one tick, distros at
   another). A single `StateFlow` is the simplest
   consistent shape.
2. **Skip the ViewModel; let the UI subscribe
   directly to each collaborator's `StateFlow`.**
   Rejected: the Compose UI is a consumer; the
   business logic of "what does the main screen
   show" is the ViewModel's job.
3. **Use `LiveData` instead of `StateFlow`.**
   Rejected: the runtime is Compose-first;
   `StateFlow` is the natural Compose shape
   (`collectAsStateWithLifecycle`). `LiveData` is
   the legacy ViewModel shape.

## Revisit triggers

- The runtime adds a new collaborator (e.g. a
  macOS VM manager). The ViewModel's constructor
  gains a parameter; the `MainScreenState` gains a
  field; the test suite adds a test.
- The UI wants per-kind event filters (e.g. "only
  show network events"). The bus subscription
  gains a filter; the recent-events buffer splits
  per kind.
- A future phase wires a `HiltViewModel`
  annotation + `MainScreenViewModelFactory`. The
  JVM unit tests continue to construct the class
  directly; the Hilt graph is the production path.
