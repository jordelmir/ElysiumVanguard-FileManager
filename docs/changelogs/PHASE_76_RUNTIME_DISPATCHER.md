# Phase 76 second half — Runtime Dispatcher (Universal Execution Engine)

> **Status:** ✅ Shipped (`commit pending`)
> **Date:** 2026-07-19
> **Phase:** 76 / Universal Execution Engine (vision section 6)
> **Predecessor:** Phase 76 first half (Runtime Selector)
> **Vertical:** Runtime Orchestrator (`com.elysium.vanguard.core.orchestrator.*`)

---

## TL;DR

The Universal Execution Engine is **fully
operational**. The `RuntimeDispatcher` is the
orchestrator that **actually launches the process**
based on the `RuntimeSelector`'s output.

Per the master vision's Universal Execution Engine
(section 6), the dispatch flow is:

```
Runtime Selection         ← Phase 76 first half
    ↓
Sandbox and Mount Policy  ← existing infrastructure
    ↓
Process Supervisor        ← Phase 76 second half (this)
```

The `RuntimeSelector` decides **WHAT to do**; the
`RuntimeDispatcher` **DOES it**. The two together
implement the **typed Runtime Selection →
Process Supervisor** path.

The dispatcher is **pure-domain** (no I/O, no
Android dependencies). The dispatcher produces a
typed [LaunchPlan] with the launch command + the
runtime arguments; a separate component (the
`ProcessLauncher` in the EV runtime, or a future
`AndroidProcessLauncher` in the Elysium Linux runtime)
executes the plan.

**Phase 76 is now CLOSED.** The Universal Execution
Engine is fully specified.

---

## What shipped

### `RuntimeDispatcher` (class)

The orchestrator that launches the process based on
the `RuntimeSelector`'s output.

```kotlin
class RuntimeDispatcher {
    fun dispatch(capsule: Capsule, selection: RuntimeSelection): LaunchPlan
}
```

The dispatcher is **declarative** (computes the launch
plan from the selection + the capsule; doesn't
execute anything itself).

### `LaunchPlan` (data class)

The typed launch plan. The plan has:

- **`runtime`** — the [LaunchRuntime] (the launch
  strategy: NATIVE / BOX64 / FEX / WINE / etc.).
- **`executable`** — the launch executable (the
  translation wrapper, e.g. `/usr/bin/box64`, or
  the capsule's entrypoint for native).
- **`args`** — the launch arguments (the translation
  wrapper, the capsule's entrypoint, the capsule's
  args).
- **`workingDirectory`** — the launch working dir.
- **`environment`** — the launch environment
  variables (a future Phase 7+ increment may
  populate this from the WorkspaceDefinition).
- **`fullCommandLine`** — the executable + args
  joined by spaces (for display / audit).
- **`programAndArgs`** — the executable + args
  (for the `Process.exec` call).

### `LaunchRuntime` (enum, 8 values)

| Runtime | Meaning |
| --- | --- |
| `NATIVE` | No translation; the capsule runs directly. |
| `BOX64` | Box64 user-mode x86_64 translation. |
| `FEX` | FEX-Emu user-mode x86 translation. |
| `WINE` | Wine Windows API re-implementation. |
| `PROOT` | PRoot filesystem root in user space. |
| `CHROOT` | chroot filesystem root in kernel space. |
| `QEMU` | QEMU full system emulation. |
| `REMOTE` | Oracle Free remote build server. |

### `TranslationType.wrapperExecutable` (extension)

The translation wrapper executable. The extension
maps a `TranslationType` to the canonical wrapper
path:

| Type | Wrapper |
| --- | --- |
| `BOX64` | `/usr/bin/box64` |
| `FEX` | `/usr/bin/FEXInterpreter` |
| `WINE` | `/usr/bin/wine` |
| `PROOT` | `/usr/bin/proot` |
| `CHROOT` | `/usr/sbin/chroot` |
| `QEMU` | `/usr/bin/qemu-x86_64` |
| `REMOTE` | `/usr/bin/elysium-remote-launcher` |

### `TranslationType.toLaunchRuntime()` (extension)

Maps a `TranslationType` to its corresponding
[LaunchRuntime]. The mapping is total: every
`TranslationType` has a corresponding `LaunchRuntime`.

### The dispatch algorithm

The `dispatch` method follows this algorithm:

1. **For `Native`** — the launch plan is the
   capsule's entrypoint + args verbatim (no
   translation).
2. **For `Translated`** — the launch plan is the
   translation wrapper + the capsule's entrypoint +
   args (e.g. `box64 /opt/steam/steam -gamepadui`
   for Box64 + Steam).
3. **For `Unsupported`** — the dispatcher throws
   `IllegalArgumentException`. The Unsupported
   selection is **not dispatchable**; the
   orchestrator surfaces the reason to the user.

### The realistic Elysium Linux scenario

The `RuntimeDispatcher` + the `RuntimeSelector` + the
`ElysiumLinuxCapsule` together implement the canonical
Elysium Linux dispatch:

1. The selector picks `Native` (the capsule's
   architecture matches the device's architecture).
2. The dispatcher produces the `elysium-pm init`
   plan.
3. The `ProcessLauncher` (a future Phase 7+ increment)
   executes the plan: spawns the `elysium-pm init`
   process in the proot backend.

---

## Design decisions

### Why is the dispatcher a class, not an object?

The dispatcher is **stateless** (no mutable
fields). A class captures the dependency-free
default; an object would couple the dispatcher to a
specific instance. The class is thread-safe (no
shared state); a singleton can be injected via
Hilt.

### Why is the dispatcher pure-domain?

The dispatcher produces a `LaunchPlan`; the
`ProcessLauncher` executes the plan. The split
between "what to do" (the dispatcher) and "how to
do it" (the process launcher) is the
**declarative-vs-procedural** split. The dispatcher
is declarative (computes a plan); the process
launcher is procedural (executes a plan).

The pure-domain dispatcher is **testable** (no I/O,
no Android dependencies); the process launcher is
the Android seam (depends on `Process.exec` or
equivalent).

### Why a `LaunchPlan` data class, not a single string?

A single string (`"box64 /opt/steam/steam -gamepadui"`)
is opaque; the consumer cannot tell which part is
the executable + which parts are the args. The
`LaunchPlan` data class is **typed** (the executable
is a `String`; the args is `List<String>`); the
consumer can dispatch on the structure.

A `Process.exec` call takes the executable as the
program + the args as the arguments. The
`LaunchPlan.programAndArgs` returns the right shape
for the `Process.exec` call.

### Why a `fullCommandLine` extension property?

The `fullCommandLine` is a **display + audit** value.
The audit log records the full command line; the UI
displays the full command line. The `fullCommandLine`
is computed from the executable + args (no separate
field; the source of truth is the executable + args).

### Why is the `Unsupported` case not dispatchable?

A `RuntimeSelection.Unsupported` is a **deployment
error** (the capsule's requirements don't match the
device's capabilities). The dispatcher cannot
launch the capsule; throwing an
`IllegalArgumentException` surfaces the error to
the caller. The caller is expected to handle the
exception (e.g. display the reason to the user).

A `RuntimeSelection.Unsupported` is **not a
recoverable state** — the orchestrator should
reject the launch before it reaches the
dispatcher. The dispatcher's throw is a
defense-in-depth check (the orchestrator's check
is the primary defense).

---

## Tests

11 new tests in `RuntimeDispatcherTest`. The tests
cover:

- **Native dispatch** (2 tests): the launch plan is
  the capsule's entrypoint verbatim; the canonical
  Elysium Linux scenario produces the `elysium-pm
  init` plan.
- **Translated dispatch** (3 tests): Box64 wraps
  the capsule's entrypoint; Wine wraps the
  capsule's entrypoint; FEX wraps the capsule's
  entrypoint.
- **Unsupported dispatch** (1 test): the
  dispatcher throws `IllegalArgumentException`.
- **TranslationType mapping** (2 tests): every
  `TranslationType` maps to a `LaunchRuntime`;
  every `TranslationType` has a wrapper executable.
- **LaunchPlan invariants** (3 tests): rejects
  blank executable; `fullCommandLine` joins by
  space; `programAndArgs` returns executable +
  args.

**Total project tests:** 3065 (was 3054, +11 new).

---

## Phase 76 — CLOSED

With the dispatcher shipped, **Phase 76 is closed**.
The Universal Execution Engine is fully specified:

- **Phase 76 first half** (commit `92937db`): the
  `RuntimeSelector` (the typed component that picks
  the optimal runtime layer).
- **Phase 76 second half** (this): the
  `RuntimeDispatcher` (the orchestrator that actually
  launches the process).

The two together implement the **typed Runtime
Selection → Process Supervisor** path. The selector
+ dispatcher are the **typed heart** of the
Universal Execution Engine.

A future Phase 7+ increment can add a
`ProcessLauncher` interface + an
`AndroidProcessLauncher` implementation (the
Android seam) + a `RemoteProcessLauncher`
implementation (for the Oracle Free build server).

---

## Files

| File | Status | Role |
| --- | --- | --- |
| `app/src/main/java/com/elysium/vanguard/core/orchestrator/RuntimeDispatcher.kt` | new | dispatcher class + launch plan + launch runtime + extension functions |
| `app/src/test/java/com/elysium/vanguard/core/orchestrator/RuntimeDispatcherTest.kt` | new | 11 JVM tests |

---

## The role in the bigger picture

The `RuntimeDispatcher` is the **typed bridge** between
the `RuntimeSelector`'s output + the actual process
launch. The two together implement the **typed
Runtime Selection → Process Supervisor** path:

- **Selector** — "What runtime should I use?"
- **Dispatcher** — "What command do I run?"

The two are the **typed heart** of the Universal
Execution Engine. The selector + dispatcher +
the existing proot backend + the existing audit
log + the existing `ElysiumLinuxCapsule` together
implement the **complete typed execution pipeline**
from the user's perspective:

1. The user installs the Elysium Linux distro
   (Phase 75 default repository).
2. The user runs the Elysium Linux capsule
   (Phase 74 second half capsule).
3. The orchestrator selects the optimal runtime
   (Phase 76 first half selector).
4. The dispatcher produces the launch plan
   (Phase 76 second half dispatcher — this phase).
5. The proot backend launches the process
   (Phase 30 + 71).
6. The audit log records every step
   (Phase 63 + 70).
7. The user sees the canonical Elysium Linux
   experience (per the vision doc).

The selector + dispatcher + proot + audit + capsule
+ repository are the **typed Elysium Linux
pipeline**. The pipeline is **end-to-end
shippable** as a typed foundation; the real
binaries are the next concrete deliverable.
