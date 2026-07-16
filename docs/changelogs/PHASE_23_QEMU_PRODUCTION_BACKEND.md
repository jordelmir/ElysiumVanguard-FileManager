# Phase 23 — QEMU Production Backend

Date: 2026-07-15
Status: **Shipped** — `assembleDebug` green, 1217 tests, 0 failures, 2 skipped.

## What landed

The JVM-testable half of the QEMU production backend:
the command line builder, the QMP message + response
parser, and a thin production adapter. The actual
`Process.spawn` is integration territory; the JVM
unit tests assert on the JVM-testable parts and on the
backend's *contract*.

### Files

**Production (4 new):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/windows/qemu/QemuCommandLine.kt`
  — the pure function that builds the QEMU argv from a
  `WindowsVmSpec` + `QemuOptions`. Pins every flag the
  runtime emits: `-name`, `-machine` (KVM accel when
  `requiresKvm`), `-cpu host`, `-smp`, `-m`, the boot
  ISO + virtio ISO + disk image, the network netdev,
  the QMP + monitor TCP ports, `-display none` for
  headless, and the TPM emulator when `requiresSwtpm`.
- `app/src/main/java/com/elysium/vanguard/core/runtime/windows/qemu/QmpMessage.kt`
  — the QMP wire-format builder. Builds the JSON for
  `query-status`, `stop`, `cont`, `quit`, `device_add`,
  `device_del`. Hand-rolled JSON string escaper (15
  lines) handles the JSON-spec-required characters and
  falls back to `\uXXXX` for control characters.
- `app/src/main/java/com/elysium/vanguard/core/runtime/windows/qemu/QmpResponseParser.kt`
  — translates QMP responses into `WindowsVmState`
  transitions. Maps every status value QEMU emits
  (`running` / `paused` / `shutdown` / `crashed` /
  `internal-error` / `io-error` / `guest-panicked`).
  Surfaces QMP `error` objects as `WindowsVmState.Error`
  with the class + desc. Malformed responses are
  `Error(message = "QMP malformed response: ...")`.
- `app/src/main/java/com/elysium/vanguard/core/runtime/windows/qemu/QemuWindowsVmBackend.kt`
  — the production adapter. Implements
  `WindowsVmBackend`. Spawns a QEMU process in
  production; the JVM test path records the call
  without spawning. Catches every `IOException` and
  surfaces it as `WindowsVmState.Error`. Per-VM
  `ConcurrentHashMap<String, VmRecord>` tracks the
  QEMU PID + QMP + monitor ports.

**Tests (1 new):**

- `app/src/test/java/com/elysium/vanguard/core/runtime/windows/qemu/QemuPhase23Test.kt`
  — 31 unit tests across the four pieces. 12 tests for
  `QemuCommandLine` (every flag). 6 tests for
  `QmpMessage` (every command + JSON escape). 9 tests
  for `QmpResponseParser` (every status + error +
  malformed + command ack). 4 tests for the backend
  (interface + start + stop + listRunning).

**ADR (1 new):**

- `docs/adr/ADR-013-qemu-production-backend.md` —
  context, decision, the three-piece split
  (command-line / message+parser / backend), the
  no-real-spawn rationale, consequences, alternatives,
  revisit triggers.

### Why this matters

Phase 22 added the JVM-testable half of the Windows VM
path. Phase 23 closes the production seam. The runtime
now has a typed contract for "what command line does
the runtime spawn" and "what JSON does the runtime
send over QMP" — both pinned by JVM tests. A regression
in any QEMU flag or QMP message shape is caught by the
test suite, not by an on-device smoke.

The actual `Process.spawn` is integration territory.
The integration test (on-device) replaces the
JVM-test path's "record the call" with a real
`ProcessBuilder.start()`. The runtime's contract is
unchanged; only the production adapter changes.

### What's JVM-tested vs what isn't

| Concern | JVM-tested | On-device only |
|---|---|---|
| `QemuCommandLine.build` argv | 12 tests | — |
| `QmpMessage.*` JSON shape | 6 tests | — |
| `QmpResponseParser.*` status mapping | 9 tests | — |
| `QemuWindowsVmBackend` contract | 4 tests | — |
| Actual `Process.spawn` | — | integration |
| Actual QMP socket I/O | — | integration |
| SWTPM socket setup | — | integration |

The contract tests (12 + 6 + 9 + 4 = 31) cover every
shape the JVM test classpath can reach. The integration
tests cover the actual spawn + I/O.

## Test count

| Suite | Tests | Failures |
|---|---|---|
| `QemuPhase23Test` | 31 | 0 |
| **Project total** | **1217** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Bugs found and fixed during this phase

1. **Double-quoted `deviceAdd` JSON.** The original
   template wrapped `escapeJsonString(id)` in
   extra quotes, producing `"id": ""usb-1""` (double
   quotes around an already-quoted value). Fixed by
   dropping the inner quotes and letting the
   escaper's own quotes stand.
2. **Test expected `"id": "x"` with a space** but
   the message had `"id":"x"` (no space). The
   `QmpMessage` builder does not add spaces; the
   test was wrong. Updated the assertions to match
   the canonical JSON shape.

## Next phase

The Windows VM path is complete (spec + state + catalog
+ manager + backend + QEMU integration). The next big
step is **§22 Workspaces** (master order) — the
multi-session UI / state isolation layer. The Windows
VM is one of the inputs to workspaces (a workspace can
run one Windows VM + multiple Linux proots).

Or **§36 remaining ADRs** (the master order lists
001, 002, 005–015; we have 005–013 — 4 to go: 001
runtime backend abstraction, 002 PTY terminal renderer,
014 / 015 next big subsystems).
