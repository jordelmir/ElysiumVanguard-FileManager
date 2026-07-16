# Phase 26 — ADR-001 (Runtime Backend Abstraction)

Date: 2026-07-15
Status: **Shipped** — `assembleDebug` green, 1258 tests, 0 failures, 2 skipped.

## What landed

A new ADR captures the runtime backend abstraction that
the codebase has shipped since Phase 9.6.3. The
decision: the runtime uses a strategy pattern
(`LauncherResolver` + `DistroLauncher`) instead of
hardcoding a single launcher backend.

### Files

**ADR (1 new):**

- `docs/adr/ADR-001-runtime-backend-abstraction.md` —
  context, decision (strategy pattern with
  `LauncherResolver` + `DistroLauncher`), the four
  concrete launchers (NATIVE_PROOT / DIRECT_EXEC /
  JAILED_SHELL / NAMESPACE_UNSHARE), why a strategy
  pattern (not a config file, not hardcoded), why a
  `LauncherKind` enum (not the class name), the testable
  contract, consequences, alternatives, revisit triggers.

### Why this matters

The master order lists ADR-001 as one of the
foundational ADRs. Without it, a reader of the codebase
sees the `LauncherResolver` interface and wonders
"why a strategy pattern? why not just hardcode the
launcher?" The ADR answers: the launcher backend is a
property of the *device*, not the *distributor*; the
right backend varies per device (Pixel 7 with proot vs
unbranded tablet without proot); a strategy pattern
keeps the choice testable end-to-end.

Phase 26 is a documentation phase — no code change.
The decision was already made (in Phase 9.6.3) and
shipped. The ADR is the rationale; future contributors
who add a new backend have a single document to read.

### What the ADR captures

| Concern | Where it lives |
|---|---|
| The four launchers | `NativeProotLauncher`, `DirectExecDistroLauncher`, `JailedDistroLauncher`, (future) `NamespaceUnshareLauncher` |
| The tag for logs + UI | `LauncherKind` enum |
| The strategy picker | `LauncherResolver` interface + `LauncherResolutionResolver.DEFAULT` |
| The capability surface | `LauncherCapabilities` (can run ELFs, can run apt, has namespace isolation) |
| The contract test | `DistroLauncher` interface; tests inject a fake resolver |

The ADR also captures the alternatives considered
(hardcoded launcher, config file, one-per-distributor)
and the revisit triggers (new backend, slow probe,
config-file override).

## Test count

No new tests. Phase 26 is a documentation phase.

| Suite | Tests | Failures |
|---|---|---|
| **Project total** | **1258** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Next phase

**ADR-002 (PTY terminal renderer)** — the decision
that powers the Linux terminal in the runtime. The
runtime needs a PTY (not a pipe) because some programs
(`vi`, `htop`, `less`) refuse to run on a pipe — they
check `isatty(stdout)`. The ADR captures the choice of
the PTY backend (Termux's `libptsocket.so` vs the
`pty` syscall on Linux desktops vs a custom Java
implementation).

After ADR-002, **§24 UX** (master order) — the
runtime's main screen + workspace list + settings.
The `RuntimeEventBus` (Phase 25) is the seam the UI
subscribes to; the workspaces list is
`WorkspaceManager.listWorkspaces()`.
