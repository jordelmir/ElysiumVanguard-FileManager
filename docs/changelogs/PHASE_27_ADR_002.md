# Phase 27 — ADR-002 (PTY Terminal Renderer)

Date: 2026-07-15
Status: **Shipped** — `assembleDebug` green, 1258 tests, 0 failures, 2 skipped.

## What landed

A new ADR captures the PTY terminal renderer decision
the codebase has shipped since Phase 9.6.x. The
decision: the runtime uses Elysium's own Rust PTY
runtime (loaded via JNI) as the production terminal
backend; the Kotlin side owns an opaque handle; tests
use a `PtyPipe` fixture (regular Java pipes).

### Files

**ADR (1 new):**

- `docs/adr/ADR-002-pty-terminal-renderer.md` — context,
  decision (Rust-owned native PTY + opaque `Long`
  handle), why not pure JVM (reflection on private
  APIs is fragile), why a `PtyPipe` test impl (real
  pipes are the right fixture for terminal tests),
  the Kotlin contract (`read` / `write` / `resize` /
  `close`), consequences, alternatives, revisit
  triggers.

### Why this matters

The master order lists ADR-002 as one of the
foundational ADRs. Without it, a reader of the codebase
sees the `NativePty` class with a `Long` handle and
wonders "why a Rust PTY, not a Java pipe?" The ADR
answers: many programs (`vi`, `htop`, `less`, `top`,
`vim`) refuse to run on a pipe because they check
`isatty(stdout)`; a real PTY is the only way to give
the user's terminal the full interactive semantics.

Phase 27 is a documentation phase — no code change.
The decision was already made (in Phase 9.6.x) and
shipped. The ADR is the rationale; future contributors
who add a new terminal feature (window resize,
mouse input, alternate screen buffer) have a single
document to read.

### What the ADR captures

| Concern | Where it lives |
|---|---|
| The native PTY runtime | Elysium's Rust crate, JNI-loaded |
| The Kotlin facade | `NativePty` class with `Long` handle |
| The test fixture | `PtyPipe` interface + pipe harness |
| The Kotlin contract | `read` / `write` / `resize` / `close` |
| The integration test | `androidTest/` (real device) |
| The JVM unit test | `PipePtyTest` + `NativePtyContractTest` |

The ADR also documents the alternatives considered
(`ProcessBuilder` shim, NDK directly, channels, Termux
`libptsocket.so`) and the revisit triggers (Rust
runtime port, WebAssembly build, new `ioctl` features).

## Test count

No new tests. Phase 27 is a documentation phase.

| Suite | Tests | Failures |
|---|---|---|
| **Project total** | **1258** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Next phase

**ADR-016** (the master order lists 001, 002, 005–015,
and 016). I need to look at the master order to find
what 016 is. If the list is exhausted, the next big
step is **§24 UX** (master order) — the runtime's main
screen + workspace list + settings. The
`RuntimeEventBus` (Phase 25) is the seam the UI
subscribes to; the workspaces list is
`WorkspaceManager.listWorkspaces()`.
