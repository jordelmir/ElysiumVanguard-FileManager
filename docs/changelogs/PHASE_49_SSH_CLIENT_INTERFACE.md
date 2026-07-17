# Phase 49 — `SshClient` interface + `InMemorySshClient` (Phase 9.6.6 first slice)

Date: 2026-07-17
Status: **Shipped** — `assembleDebug` green, 1380 tests, 0 failures, 2 skipped.

## What landed

The Phase 9.6.6 SSH client stack now has its
**type-safe seam**. Phase 49 adds:

- [SshClient] — the runtime's interface for
  "connect to a remote host over SSH". A
  narrow, JVM-testable surface that the
  terminal screen consumes.
- [SshSession] — the connected session.
  Carries the [host] for observability;
  allocates a PTY + spawns a shell on
  [start]; exchanges input / output via
  [sendLine] / [readAvailable]; releases
  the channel on [close].
- [SshError] — a sealed hierarchy of typed
  failures (ConnectionRefused /
  HandshakeFailed / AuthenticationFailed /
  HostDisconnected / Other). The caller
  branches on the kind rather than parsing
  free-form strings.
- [InMemorySshClient] + [InMemorySshSession]
  — the in-memory impl. The test
  fixture the terminal unit tests use; also
  useful in previews + dev builds where the
  MINA SSHD backend is not yet wired.
  Thread-safe (the output buffer is a
  [CopyOnWriteArrayList] of chunks; the
  [sendLine] / [readAvailable] methods are
  safe against concurrent callers).

The production impl — Apache MINA SSHD over
a real TCP socket — is **Phase 50**. The
[InMemorySshClient] is the seam the unit
tests use today, and the runtime can wire
either the in-memory client (dev mode) or
the MINA SSHD client (production) without
touching the terminal layer.

### Files

**Production (2 new):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/distros/ssh/SshClient.kt` —
  defines the [SshClient], [SshSession], and
  [SshError] interfaces. The class is the
  runtime's seam for the SSH transport;
  nothing else in the runtime knows about
  MINA SSHD or any specific SSH library.
- `app/src/main/java/com/elysium/vanguard/core/runtime/distros/ssh/InMemorySshClient.kt` —
  defines the [InMemorySshClient] +
  [InMemorySshSession] impls. The session
  is backed by an in-memory buffer; the
  responder is a `(String) -> String`
  hook the test / dev code can override to
  simulate a remote shell.

**Tests (1 modified):**

- `app/src/test/java/com/elysium/vanguard/core/runtime/distros/ssh/SshConnectionTest.kt` —
  8 new tests pin the contract:
  - `InMemorySshClient connect returns a
    session on success` — the basic happy
    path.
  - `InMemorySshClient start succeeds and
    unlocks sendLine` — the start / send /
    read round-trip.
  - `InMemorySshClient sendLine echoes via
    the responder` — the responder hook
    produces a custom response.
  - `InMemorySshClient pushOutput injects
    response without a sendLine` — the
    [InMemorySshClient.pushOutput] test
    seam.
  - `InMemorySshClient readAvailable returns
    empty when no output` — the no-output
    edge case.
  - `InMemorySshClient close is idempotent` —
    a second `close()` is a no-op (the
    [AutoCloseable] contract).
  - `InMemorySshClient sendLine after close
    throws IOException` — the contract is
    "closed sessions reject I/O", not
    "closed sessions silently drop I/O".
  - `InMemorySshClient readAvailable drains
    the buffer (single read consumes all)`
    — a single [readAvailable] returns
    every chunk the buffer accumulated, then
    the next read returns empty.

## What the runtime now knows

| Capability | Source | Status |
|---|---|---|
| Connect to a remote SSH host | [SshClient.connect] (Phase 49 interface) | ✅ Type-safe seam |
| Open a PTY + spawn a shell | [SshSession.start] (Phase 49) | ✅ Test impl |
| Send a line of input | [SshSession.sendLine] (Phase 49) | ✅ Test impl |
| Read the remote's output | [SshSession.readAvailable] (Phase 49) | ✅ Test impl |
| Close the channel | [SshSession.close] (Phase 49) | ✅ Test impl |
| **Real MINA SSHD transport** | (Phase 50, follow-up) | ⏳ Next slice |
| **X11 forwarding** | [X11Forwarder] (existing) + [SshSession] integration | ⏳ Phase 51 |
| **SshClient in the Hilt graph** | (Phase 52) | ⏳ Wiring |

The seam is the contract. The unit tests
above prove the contract is implementable
end-to-end (the in-memory impl satisfies
every line of the interface contract).
Phase 50's MINA SSHD impl can land
without touching the terminal layer at
all.

## Why this matters

The terminal layer is the runtime's
**most reused** user-facing surface:
- Phase 9.6.1 — the local terminal (existing)
- Phase 45 — the "Open" affordance for
  Running Linux sessions
- Phase 9.6.6 (this phase + Phase 50) — the
  remote SSH terminal
- Phase 9.6.8 — the tmate / tmux integration
  (a remote session, also SSH-shaped)

All four share the same architectural shape:
"open a channel, send a line, read the
output, close the channel". The [SshClient]
interface captures that shape once. The
terminal's [TerminalSessionManager] can
build on it (Phase 50) without re-learning
the SSH lifecycle on every consumer.

## Design notes

### Why a [Result<SshSession>] return, not a throw

The [SshClient.connect] returns
[Result.success] with the [SshSession], or
[Result.failure] with a typed [SshError].
The terminal layer (and any other consumer)
branches on the [SshError] kind rather than
catching exceptions — error handling is
data, not control flow.

### Why the session is two-phase (connect / start)

The seam splits [connect] (SSH handshake +
channel reservation) from [start] (PTY
allocation + shell spawn). This lets a test
or a future "preview" path hold a connected
session without consuming a remote PTY
until the user explicitly requests it. The
trade-off is one extra method call per
session open; the benefit is a clear
"connected but not yet ready" state the
UI can render as a "Connecting..." spinner.

### Why [InMemorySshClient] ships in `main`, not `test`

Two reasons:
1. **Dev mode**: the runtime can wire the
   in-memory client instead of the MINA
   SSHD client and still have a fully
   functional terminal UX. The user sees
   the in-memory buffer echoed back, which
   is the right behaviour for a "no host
   available" demo mode.
2. **Previews**: Compose previews need a
   concrete [SshClient] to drive the
   terminal composable. The in-memory
   client is the canonical preview impl.

The [InMemorySshClient] is a real, shipped
production class — the in-memory
implementation IS one of the runtime's
[installIn(SingletonComponent)] candidates.
Phase 52's Hilt module will wire it (or the
Phase 50 MINA SSHD impl) based on a build
flag.

## Test count

| Suite | Tests | Failures |
|---|---|---|
| `SshConnectionTest` | 19 (was 11) | 0 |
| **Project total** | **1380** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Next phase

The follow-up after Phase 49 is **Phase 50 —
the MINA SSHD-backed [SshClient] impl**. The
impl wires the existing Apache MINA SSHD
library (already a project dep) to the
[SshClient] interface. The impl is
production-only; the unit tests continue
to use [InMemorySshClient]. Phase 50 also
adds the [SshClient] to the Hilt graph so
the terminal can inject it.

After Phase 50, the runtime has a
**complete SSH transport** — the user can
configure an SSH host, connect, run a
remote shell, and disconnect. Phase 51
adds X11 forwarding (the second half of
Phase 9.6.6).
