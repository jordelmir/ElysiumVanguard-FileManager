# ADR-002 — PTY Terminal Renderer (Rust-Owned Native PTY)

Status: **Accepted** (originally Phase 9.6.x; documented
in Phase 27, 2026-07-15)
Owners: Runtime
Supersedes: none
Superseded by: none

## Context

The runtime's Linux terminal needs a real PTY
(pseudo-terminal), not a pipe. Many programs refuse
to run on a pipe because they check `isatty(stdout)`:
`vi` enters raw mode only when stdout is a TTY, `htop`
refuses to draw its UI when stdout is a pipe, `less`
paginates only when stdout is a TTY, `top` switches to
an interactive mode only when stdout is a TTY, `vim`
emits error messages when stdout is a pipe. A user
running `apt-get install vim` in a pipe-only terminal
would get a degraded experience.

The PTY is also the OS-level mechanism that ties the
runtime to the child process: the PTY's slave side is
the child's stdin/stdout/stderr; the master side is the
parent's read/write interface; closing the master
signals `SIGHUP` to the child (the child can clean up
gracefully). A pipe does not give the parent the
`SIGHUP-on-close` semantics; a PTY does.

The challenge: a PTY is OS-specific. Android's bionic
libc has `posix_openpt(O_RDWR | O_NOCTTY)` but the
runtime also needs `grantpt` + `unlockpt` + the
`ioctl(TIOCSWINSZ)` for window size + the
`ioctl(TIOCSCTTY)` for controlling terminal + a
`waitpid(-1, ..., WUNTRACED)` for foreground process
group signalling. Doing this in Kotlin via the
Android NDK is the platform's native path; doing it
in pure JVM requires reflection on private APIs
(forbidden on modern Android).

## Decision

The runtime uses **Elysium's own Rust PTY runtime**
(loaded via JNI) as the production terminal backend.
The Kotlin side owns an opaque handle (a `Long`); the
Rust side owns the file descriptor, the child PID, the
process group, and the wait/reap lifecycle. The
contract is:

```kotlin
internal class NativePty private constructor(
    private val handle: Long,
    val pid: Long
) : AutoCloseable {
    fun read(destination: ByteArray, timeoutMs: Int): Int
    fun write(source: ByteArray): Int
    fun resize(columns: Int, rows: Int)
    override fun close()
}
```

The Kotlin layer never uses reflection or hidden APIs
to access a raw descriptor. The Rust layer is the
single source of truth for the POSIX PTY dance
(`posix_openpt` / `grantpt` / `unlockpt` /
`ioctl(TIOCSWINSZ)` / `forkpty` / `waitpid`).

For JVM unit tests, the runtime ships a `PtyPipe`
interface — a regular Java pipe harness that models
the read/write semantics without an actual PTY. The
`PtyPipe` is **never** selected by a production
terminal session; the comment on the interface
explicitly says: *"Production terminal sessions use
[NativePty], backed by Elysium's Rust runtime. This
file models ordinary Java pipes only for deterministic
tests; it must not be selected by a production
terminal session."*

The two backends share a `PtyPipe` interface (read /
write / resize / close), so the terminal session can
be tested end-to-end without spawning a real PTY.

### Why a Rust-owned PTY, not pure JVM

A pure-JVM PTY would require:
- `ProcessBuilder` with a hand-crafted `/system/bin/sh
  -i < /dev/ptmx > /dev/ptmx 2>&1` invocation (no
  built-in PTY support in `ProcessBuilder`).
- Reflection on `android.os.ParcelFileDescriptor` or
  `libc.ptsname` to read the slave path. Forbidden on
  modern Android (private API restrictions).
- Manual `ioctl` calls via JNI for window size and
  controlling terminal. A re-implementation of libc.

The Rust runtime does the POSIX dance once, in
maintainable Rust, with the platform's native
syscalls. The Kotlin side is a thin facade over an
opaque handle. The tradeoff: the runtime ships a
`librustpty.so` (~ 200 KB) per ABI. The cost is
acceptable; the alternative (reflection on private
APIs) is fragile and breaks on every Android version.

### Why a `PtyPipe` test impl, not a mock

A mock would be a `NativePty` that returns canned
data. The runtime's tests want to assert on the
*bytes* the child writes to stdout — `cat` a file,
pipe through `grep`, verify the result. A `PtyPipe`
is regular Java pipes; the test runs `cat
/rootfs/etc/os-release | grep ID=` and asserts on the
captured stdout. The interface is small enough that
a fake is straightforward; mocking would be overkill.

### Why the `Long` handle, not a `FileDescriptor`

The Kotlin side does not own the descriptor; the Rust
side does. The `Long` is an opaque token. The Kotlin
side never `close(fd)`s; the Rust side does. This
prevents a class of bugs where the Kotlin side closes
the descriptor twice (once in `close()` and once in
`finalize()`) and the second close corrupts a
different file descriptor that the process re-opened
in the meantime.

## Consequences

### Positive

- **Real PTY semantics.** The runtime's terminal
  behaves like a real terminal: `vi` enters raw mode,
  `htop` renders its UI, `less` paginates, `top` is
  interactive. Programs that check `isatty(stdout)`
  see `true` and behave correctly.
- **`SIGHUP`-on-close.** Closing the PTY master
  signals the child, which can clean up gracefully
  (write a "disconnected" line to the log, close its
  own file handles, exit). A pipe does not give the
  parent this semantics.
- **JVM-testable end-to-end.** The `PtyPipe` test
  interface is regular Java pipes; tests run real
  `cat` / `grep` / `sed` invocations against it. The
  test fixture is `cat /rootfs/etc/os-release | grep
  ID=`, asserted on the captured bytes.
- **Stable Kotlin contract.** The `NativePty` class
  is a thin facade. A future port (e.g. to a
  Crostini-style custom backend) replaces the Rust
  side; the Kotlin side does not change.

### Negative

- **Rust dependency.** The runtime ships a
  `librustpty.so` per ABI (~ 200 KB × 4 ABIs ≈
  800 KB). The dependency is unavoidable for native
  PTY semantics; the alternative is reflection on
  private APIs.
- **The Rust layer is integration-only.** The JVM
  unit tests assert on the Kotlin contract; the
  `librustpty.so` itself is tested via
  `androidTest/` instrumentation tests on a real
  device. A unit test cannot exercise the
  `posix_openpt` / `grantpt` / `unlockpt` dance
  without a real Linux PTY.
- **The `Long` handle leaks across JNI boundaries.**
  A bug in the Kotlin side (e.g. passing a stale
  handle after `close()`) would manifest as a
  segfault, not a typed exception. The tests pin the
  contract; the integration tests on a real device
  catch the segfault.
- **`PtyPipe` is a fixture, not a fallback.** A
  future contributor who wires `PtyPipe` into
  production would ship a terminal that does not
  behave like a real terminal. The comment on the
  interface is the only guard; a runtime config flag
  would be more explicit.

## Alternatives considered

1. **Use `ProcessBuilder` with a `/system/bin/sh -i`
   shim.** Rejected: `ProcessBuilder` does not give
   a real PTY; the child's stdout is a pipe. Programs
   that check `isatty(stdout)` see `false`.
2. **Use the Android NDK directly from Kotlin via
   JNI.** Equivalent to the Rust runtime but
   Kotlin/JNI is harder to maintain than Rust/JNI.
   The Rust runtime is the right place for the
   POSIX PTY dance.
3. **Use `java.util.concurrent` channels instead of a
   PTY.** Rejected: a channel is a pipe; programs
   that check `isatty(stdout)` see `false`. The
   whole point of a PTY is the `isatty` semantics.
4. **Use Termux's `libptsocket.so`.** Rejected: it
   is a third-party native lib; the runtime already
   has its own native layer for the launcher
   backends (Phase 9.6.x). One native lib to
   maintain, not two.

## Revisit triggers

- The Rust runtime is ported to a new platform
  (Crostini, WSL2, a research OS). The Kotlin side
  does not change; the Rust side is rebuilt.
- A user wants a real terminal on a device without
  a native lib (e.g. a WebAssembly build). The
  Kotlin side gains a `WebPty` impl that uses a
  browser-side PTY emulator. The interface does not
  change.
- The runtime adds `ioctl(TIOCSTI)` or
  `ioctl(TIOCSPTLCK)` support (terminal injection,
  tab lock). The Rust side gains the new
  `ioctl` calls; the Kotlin side gains a
  corresponding `PtyPipe` test impl.
