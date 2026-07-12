# ADR-002: Native PTY and terminal architecture

- Status: Accepted
- Date: 2026-07-12
- Owners: Elysium Vanguard terminal/runtime
- Depends on: ADR-001

## Context

The current terminal connects `ProcessBuilder` pipes to a partial character
parser. Pipes do not provide terminal line discipline, controlling-terminal
behavior, process-group signals, window size or reliable full-screen TUI
support. The screen dimensions are immutable and resize clears content. The
renderer repaints the complete centered grid. This cannot correctly run `vim`,
`htop`, `tmux`, `less` or interactive package managers.

## Decision

Implement the first real terminal as this strict pipeline:

```text
Compose host
  → SessionManager
  → JNI bridge with opaque handles
  → Rust PTY owner + minimal C spawn shim where post-fork safety requires it
  → PRoot argv/env + distro shell
  → raw byte stream
  → incremental Kotlin VT parser
  → mutable main/alternate screen buffer + bounded scrollback
  → damage-tracked Android Surface renderer
```

### Native ownership

The native runtime owns the PTY master FD, child PID, process group, nonblocking
I/O registration and exit status. It uses `posix_openpt`, `grantpt`, `unlockpt`,
`ptsname`, `setsid`, controlling-terminal setup, `dup2`, `execve`,
`TIOCSWINSZ`, `SIGWINCH` and `epoll` where supported by Android's stable NDK
surface.

Rust owns state, synchronization, validation and error conversion. A minimal C
shim may perform the child-side post-`fork` sequence so only
async-signal-safe operations run before `execve`. No panic or C++ exception may
cross JNI.

JNI accepts bounded arrays and byte buffers, returns typed result codes and
opaque 64-bit handles, and never stores an Activity. Reads, writes, wait and
shutdown execute off the main thread.

### Parser and screen

The parser consumes arbitrary byte fragments and retains UTF-8 and escape state
across calls. It emits typed terminal operations rather than mutating Android
views. Required behavior includes CSI/OSC/SGR, 16/256/truecolor, cursor modes,
tabs, erase, insert/delete, scroll regions, alternate screen, bracketed paste,
mouse modes, titles, OSC 8, combining marks and wide cells.

The screen owns mutable rows/columns, main and alternate buffers, bounded
scrollback, cursor and style tables. Resize defines reflow and invalidates only
affected rows. Malformed input has bounded recovery and cannot allocate without
limits.

### Renderer and input

The renderer draws a single coherent grid on an Android surface. Pure black is
`#000000`; ANSI/neon colors are uniform cell fills rather than overlaid black
rectangles. Glyph metrics, cursor and hit testing use the same geometry.
Changed rows/regions are batched once per frame.

IME, hardware keyboard, touch and mouse feed one key translator. Enter,
backspace, Ctrl-C, function/navigation keys, modifiers, bracketed paste and
mouse reporting are encoded according to terminal modes, not pipe-specific
special cases.

## Invariants

1. No Compose state update occurs per byte, character or cell.
2. All native handles are single-owner and close exactly once.
3. Stop closes input, signals the process group, waits with a deadline,
   escalates, reaps, unregisters I/O and closes all FDs in order.
4. Window-size state agrees across UI grid, buffer and kernel PTY.
5. Parser output is independent of input fragmentation.
6. Scrollback and escape payloads have hard memory limits.
7. Pipe mode is labelled legacy/non-PTY and cannot claim TUI compatibility.

## Alternatives considered

### Continue with Java process pipes

Rejected because the missing behavior is kernel PTY behavior, not a formatting
bug.

### Render terminal cells directly as Compose text nodes

Rejected because high-volume output would cause excessive recomposition and
cannot provide predictable grid timing.

### Import a complete terminal application wholesale

Rejected for the first vertical slice because ownership, licensing, UI
integration and lifecycle would remain opaque. Small audited libraries may be
adopted later through a separate ADR.

## Verification

- Native tests: spawn, read/write, resize, signal, wait, close races and process
  group cleanup.
- Parser fixtures: every fragmentation boundary, malformed UTF-8/escape input,
  alternate screen and wide/combining text.
- Screen tests: resize/reflow, scroll regions, bounded history and dirty rows.
- Device matrix: `stty size`, Ctrl-C, rotation/fold, `vim`, `htop`, `tmux`,
  `less`, `nano`, `top` and output stress without ANR.

## Rollback

The native backend is capability-gated. A failed probe returns a stable error
and leaves the legacy shell available only for commands that do not require PTY
semantics. No graphical or TUI capability is advertised during rollback.

## Revisit triggers

- parser profiling proves the managed implementation cannot meet throughput;
- Android surface rendering cannot meet frame pacing with dirty regions;
- a maintained terminal core can be adopted with compatible licensing and
  demonstrably lower risk.
