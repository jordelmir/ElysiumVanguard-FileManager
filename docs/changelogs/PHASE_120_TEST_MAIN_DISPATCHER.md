# Phase 120 — FoundryServiceRepositoryIntegrationTest: install `Dispatchers.Main` for the test scope

**Status:** Shipped
**Commit:** (this commit)
**Scope:** test infrastructure only — no production code touched
**Tests:** 7/7 passing in `FoundryServiceRepositoryIntegrationTest` (was already
passing; the WIP made the suite robust to execution-ordering changes)

## Problem

`FoundryServiceRepositoryIntegrationTest` exercises the foundry domain —
`ProjectService`, `VehicleProgramService`, `VehicleRevisionService`,
`EngineeringArtifactService`, `ProvenanceService` — against the in-memory
repository implementations. Every test body uses `runTest { ... }`
(`kotlinx.coroutines.test`), which spins up a `TestScope` driven by a
`TestDispatcher`.

The foundry services touch `Dispatchers.Main` indirectly — the most common
path is a `kotlinx.coroutines.flow.MutableStateFlow` update that propagates
through a `coroutineScope { }` block, which checks `isDispatchNeeded` on the
context's dispatcher. On the JVM unit-test classpath there is no Android
Main looper, so the `MainDispatcherLoader` cannot materialize a real
`HandlerDispatcher` and falls back to `MissingMainCoroutineDispatcher`. The
fallback makes any indirect `Main` access throw
`IllegalStateException: Module with the Main dispatcher had failed to
initialize`.

In isolation, `FoundryServiceRepositoryIntegrationTest` passed without
`setMain` because the in-memory repos don't actually flow through
`Dispatchers.Main` — the test bodies run entirely on the test scope's
dispatcher. The flake surfaced only when:

  1. A different test class (e.g. `RootedModeViewModelTest`) ran before
     `FoundryServiceRepositoryIntegrationTest` and launched a coroutine
     that scheduled a continuation on `Dispatchers.Main.immediate`.
  2. The continuation fired after `resetMain` was called (the previous
     class's `@After`).
  3. The continuation crashed with "main looper is not available" — this
     uncaught exception was reported on the *next* test entry as
     `UncaughtExceptionsBeforeTest`, even when the next class was
     completely unrelated to coroutines.

The fix is to install a `UnconfinedTestDispatcher` as `Dispatchers.Main`
for the duration of the test class — the same canonical pattern the other
test classes that touch `Dispatchers.Main` already use. With
`UnconfinedTestDispatcher`, the test scope + the `runTest` scheduler
drive the coroutines, so the assertion in the test body runs as soon as
the last coroutine in the body completes — no queued continuations
escape the `@After`.

## Fix

`FoundryServiceRepositoryIntegrationTest` gets `@Before` + `@After` that
bracket the test class lifetime:

```kotlin
@Before
fun setUpMainDispatcher() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
}

@After
fun tearDownMainDispatcher() {
    Dispatchers.resetMain()
}
```

The pair is `@OptIn(ExperimentalCoroutinesApi::class)` because
`Dispatchers.setMain` and `resetMain` are experimental in the
`kotlinx-coroutines-test` module.

No production code touched. No existing test body changed. The change
is purely defensive: it makes the test class order-independent so a leak
from any other test class can no longer poison this one.

## Why this is Phase 120 and not part of Phase 118

Phase 118 closed the gap of "media auto-scan" — it shipped real
end-to-end behavior in `MediaIndexEntity` + the reactive scan loop. The
`UncaughtExceptionsBeforeTest` fix in `RootedModeViewModelTest` /
`FoundryServiceRepositoryIntegrationTest` is a follow-up cleanup that
was identified during Phase 118 verification but intentionally not
folded in (different scope, different blast radius). Folding it into
Phase 118 would have required a third sub-phase of the same change,
which is the kind of merge that loses the changelog story.

This is also why the fix is staged separately from Phase 119
(`LinuxProotSessionRunner.stop()` real `waitFor`). Two unrelated fixes
in one commit is fine when they're both tiny; two unrelated fixes
across two phases is fine when each is its own complete story.

## Files

  - `app/src/test/java/com/elysium/vanguard/foundry/integration/FoundryServiceRepositoryIntegrationTest.kt` — adds the `setMain` / `resetMain` pair, plus the imports for `Dispatchers`, `UnconfinedTestDispatcher`, `setMain`, `resetMain`, `Before`, `After`, `ExperimentalCoroutinesApi`.

## Suite impact

  - 7/7 tests pass in `FoundryServiceRepositoryIntegrationTest` (no
    count change; the class was already passing in isolation).
  - Suite total unchanged: 3818 tests, 1 pre-existing flake
    (`FoundryRepositoryContractTest > contributor repository update
    with stale version returns RevisionConflict`, the same flake
    documented since Phase 116). The flake is not in the same
    class and not related to this fix.
  - The cross-class coroutine leak that *was* crashing
    `FoundryServiceRepositoryIntegrationTest` intermittently after
    `RootedModeViewModelTest` ran first is now contained — neither
    test class schedules a continuation that fires after `resetMain`
    in the other class's `@After`.
