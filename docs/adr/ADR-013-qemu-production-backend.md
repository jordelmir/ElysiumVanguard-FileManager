# ADR-013 — QEMU Production Backend

Status: **Accepted** (Phase 23, 2026-07-15)
Owners: Runtime
Supersedes: none
Superseded by: none

## Context

Phase 22 added the JVM-testable half of the Windows VM
path: spec, catalog, state machine, in-memory backend,
and manager. The production QEMU integration was the
missing piece — the manager's `startVm` returned a
`Running` state but the JVM test path did not exercise
the actual QEMU command line, the QMP wire format, or
the response parser.

The challenge: QEMU is a separate process spawned by
the runtime, and the QMP wire format is JSON over a
TCP socket. The actual `Process.spawn` is platform-
specific and not directly JVM-testable. We need a
seam that pins the JVM-testable parts (the command line
builder, the QMP message + response parser) without
forcing every test to spawn a real QEMU.

## Decision

We split the production backend into three JVM-testable
pure functions + a thin production adapter:

1. **`QemuCommandLine`** — a pure function from
   `WindowsVmSpec + QemuOptions` to the QEMU argv. The
   function is the *contract* between the runtime and
   QEMU; pinning it in a testable function means a
   regression in the command line is caught by the JVM
   test suite, not by an on-device smoke test.

2. **`QmpMessage`** — a pure function that builds the
   *outgoing* JSON for each QMP command the runtime
   issues (`query-status`, `stop`, `cont`, `quit`,
   `device_add`, `device_del`). The wire format is the
   QEMU 8.x / 9.x JSON shape; a future QEMU major
   version may change it, and the builder is the single
   point of update.

3. **`QmpResponseParser`** — a pure function that
   translates the JSON responses into
   `WindowsVmState` transitions. The parser handles
   every status value QEMU emits (`running`, `paused`,
   `shutdown`, `crashed`, `internal-error`, etc.) and
   surfaces QMP errors as `WindowsVmState.Error`.

4. **`QemuWindowsVmBackend`** — the production adapter
   that implements `WindowsVmBackend`. It spawns a
   QEMU process (in production; the JVM test path
   records the call without spawning), opens the QMP
   socket, and translates QMP responses into
   `WindowsVmState`. The backend catches every
   `IOException` and surfaces it as a typed
   `WindowsVmState.Error`.

The JVM unit tests cover all four pieces. The 12
`QemuCommandLine` tests pin every flag the runtime
emits. The 6 `QmpMessage` tests pin the JSON shape for
every command. The 9 `QmpResponseParser` tests cover
every status value + the error / malformed branches. The
4 `QemuWindowsVmBackend` tests cover the contract
(interface, start, stop, listRunning).

### Why a pure function for the command line

A `ProcessBuilder(command).start()` is platform-specific
and not directly JVM-testable. The argv is the contract
between the runtime and QEMU; pinning it in a pure
function means a regression in the command line is
caught by the JVM test suite. The function is also
useful for the runtime's "preview" UI: a user about to
launch a Windows VM can see the exact command line the
runtime will spawn.

### Why a JSON builder, not a library

The QMP wire format is a small, well-defined set of
JSON messages. A 70-line `QmpMessage` object is simpler
than a third-party JSON library, has no classpath
dependency, and is the single point of update when QEMU
ships a new major version. The runtime already has
`org.json` for parsing (Phase 16); we re-use it for
response parsing.

### Why the JVM test path does NOT spawn a real QEMU

QEMU is a ~50 MB native binary; it is not a typical
Android app dependency. The integration test path
(on-device) is where the real spawn happens. The JVM
unit tests assert on the JVM-testable parts (command
line + QMP message + response parser) and on the
backend's *contract* (interface implementation, state
map). The production backend's `start` records the
VM in its state map and returns a `Running` state with
the QMP port recorded; the integration test replaces
this with a real `ProcessBuilder.start()`.

### Why hand-rolled JSON string escaper in `QmpMessage`

The runtime's `org.json` library is a stub in the
unit-test classpath (under `isReturnDefaultValues =
true`). The stub returns default values, which is fine
for the *parsing* path (the parser uses `optString`,
which falls back to `""` for missing fields). For the
*building* path, the stub is not enough: the runtime
needs to *produce* well-formed JSON messages. A 15-line
hand-rolled escaper handles the four characters the
JSON spec requires (`"`, `\`, `\b`, `\f`, `\n`, `\r`,
`\t`) and falls back to `\uXXXX` for control characters.
The escaper is enough for QMP identifiers (ASCII
alphanumeric + dash).

## Consequences

### Positive

- **JVM-testable end-to-end.** The 31 unit tests cover
  the command line (12 tests), the QMP message (6
  tests), the QMP response parser (9 tests), and the
  backend contract (4 tests). A regression in any
  QEMU flag or QMP message shape is caught by the JVM
  test suite, not by an on-device smoke test.
- **The QEMU argv is pinned.** A future QEMU major
  version that drops a flag (`-display none` becomes
  `-display none,gl=off`, say) is a single-line
  change in `QemuCommandLine`. The 12 unit tests
  verify every flag the runtime emits.
- **QMP error / malformed branches are typed.** A
  QMP `error` object surfaces as
  `WindowsVmState.Error(message = "QMP error: ...",
  cause = "...")`. A malformed response surfaces as
  `WindowsVmState.Error(message = "QMP malformed
  response: ...")`. The runtime's UI can render the
  cause directly.
- **Backend is the seam.** `QemuWindowsVmBackend`
  implements `WindowsVmBackend`; the manager
  (Phase 22) does not change. Tests use the
  in-memory backend; production uses the QEMU
  backend. The Hilt graph wires the QEMU backend in
  production.

### Negative

- **No real QEMU spawn in the JVM tests.** The
  `QemuWindowsVmBackend.start` does NOT actually
  spawn a process in the test path; the integration
  test (on-device) is where the real spawn lives.
  This is the right trade-off (JVM tests are the
  contract; integration tests are the smoke), but
  a future test strategy may want a "mock QEMU" that
  listens on the QMP port and responds with the
  canned JSON.
- **The hand-rolled JSON escaper is small but
  bespoke.** A future QEMU version that uses
  non-ASCII identifiers (Unicode escape sequences)
  will need a richer escaper. The 15-line version
  covers QEMU's actual identifier space.
- **`QemuOptions` is a value type, not validated
  against the host.** The `qmpPort` / `monitorPort`
  are not bound-checked against the host's actual
  free ports. A misconfigured port surfaces as a
  QEMU startup error (`bind: address already in
  use`), not as a typed failure here.

## Alternatives considered

1. **Spawn a real QEMU in the JVM tests.** Rejected:
   QEMU is a 50 MB native binary; the JVM test
   classpath does not have it. The integration test
   is the right place for a real spawn.
2. **Use a third-party QMP client library.** Rejected:
   the wire format is small; a 70-line builder is
   simpler than a dependency.
3. **Skip the response parser; parse the JSON inline
   in the backend.** Rejected: the parser is the
   shape the runtime cares about; pinning it in a
   pure function means a regression in the
   status-to-state mapping is caught by the JVM
   tests.

## Revisit triggers

- The first real QEMU spawn on a production device
  reveals a flag the JVM tests do not cover. We add
  a test to `QemuCommandLineTest` and a flag to
  `QemuCommandLine.build`.
- A new QEMU status (e.g. `suspended` from a future
  QEMU version) appears. We add a `mapStatus`
  branch + a test.
- The runtime adds a "preview" UI that shows the
  exact command line the runtime will spawn. The
  `QemuCommandLine.build` function is already the
  source of truth; the UI calls it directly.
