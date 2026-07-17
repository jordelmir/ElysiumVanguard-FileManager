# Phase 47 — QEMU VNC display + `WindowsVmState.Running.vncPort` (Phase 9.6.5 first slice)

Date: 2026-07-17
Status: **Shipped** — `assembleDebug` green, 1369 tests, 0 failures, 2 skipped.

## What landed

The QEMU command line now uses a real VNC
display (`-vnc 127.0.0.1:N`) instead of the
legacy `-display none` headless mode. Every
VM gets its own VNC display number (0, 1, 2,
...) so two running VMs do not collide. The
[WindowsVmState.Running] data class gained a
[vncPort] field that carries `5900 + display`
(QEMU's VNC port convention).

This is the **first slice of the Phase 9.6.5
VNC viewer** (the Worldwide Vision doc). The
foundational piece — "QEMU exposes a VNC port
the runtime can connect to" — is now true.
The Compose VNC viewer is a follow-up phase
(Phase 48) that consumes the
`vncPort` field via the existing
[RfbSession] client.

### Files

**Production (3 modified):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/windows/qemu/QemuCommandLine.kt` —
  the legacy `-display none` is replaced by
  `-vnc 127.0.0.1:<vncDisplay>`. The
  [QemuOptions] data class gained a
  [vncDisplay: Int] field (default 0) + a
  computed [vncPort()] function
  (`5900 + vncDisplay`). The init block
  validates that the VNC port does not
  collide with the QMP / monitor ports.
- `app/src/main/java/com/elysium/vanguard/core/runtime/windows/qemu/QemuWindowsVmBackend.kt` —
  the backend gained a `nextVncDisplay`
  `AtomicInteger` that allocates a unique
  display per VM. The `VmRecord` data class
  gained a [vncDisplay] field; the `start` /
  `queryState` methods return
  [WindowsVmState.Running] with the
  [vncPort] field populated.
- `app/src/main/java/com/elysium/vanguard/core/runtime/windows/WindowsVmState.kt` —
  the [WindowsVmState.Running] data class
  gained a [vncPort: Int? = null] field.
  Null is for backends that do not expose a
  VNC port (e.g. the in-memory test backend).

**Tests (1 modified):**

- `app/src/test/java/com/elysium/vanguard/core/runtime/windows/qemu/QemuPhase23Test.kt` —
  the legacy `command line uses a headless
  display` test was updated to assert the
  new VNC display contract: the command line
  includes `-vnc 127.0.0.1:0` (display 0
  for the default QemuOptions). 2 new tests
  pin the VNC port allocation:
  - `QemuWindowsVmBackend start returns a
    Running state with a VNC port` — the first
    VM gets `vncPort = 5900`.
  - `QemuWindowsVmBackend allocates a unique
    VNC port per VM` — the first VM gets 5900,
    the second gets 5901.

## Design notes

### Why a unique display per VM

QEMU's VNC port is `5900 + displayNumber`
(display 0 → 5900, display 1 → 5901, ...).
Allocating a unique display per VM is the
only way two running VMs can both be
reachable via VNC without a port collision.
The backend's `nextVncDisplay` is a
process-wide `AtomicInteger` (same pattern
as the existing `nextPort` for QMP / monitor
ports); the first VM gets 0, the second gets
1, etc.

### Why a `vncPort: Int?` nullable field

The [WindowsVmState.Running.vncPort] is
nullable to keep the test backends
([InMemoryWindowsVmBackend]) compatible.
The production backend populates it; the
in-memory backend leaves it null. A
`is VncCapable` extension could check for
non-null, but the field is informational
enough that a null-check at the call site
is enough.

### Why the `QemuOptions.vncPort()` function

`vncPort` is a derived field
(`5900 + vncDisplay`). The
[QemuOptions] data class exposes it as a
function rather than a stored field so the
init block can validate it against the
QMP / monitor ports. A function call is
zero-cost (the JVM inlines it) and keeps
the data class immutable.

## What the runtime now knows

| Field | Source | Used by |
|---|---|---|
| `pid` | QemuWindowsVmBackend | OS process kill on `stop` |
| `qmpPort` | QemuWindowsVmBackend | QMP wire format (Phase 23) |
| `monitorPort` | QemuWindowsVmBackend | QEMU human monitor (debug) |
| `vncPort` (Phase 47) | QemuWindowsVmBackend | Phase 48 VNC viewer |

The runtime's VNC viewer is now possible
end-to-end: the backend knows the port, the
state exposes it, the RfbSession client
connects to it, the Compose viewer renders
the framebuffer. Phase 48 closes the
visible half of the loop.

## Test count

| Suite | Tests | Failures |
|---|---|---|
| `QemuPhase23Test` | 22 (was 20) | 0 |
| **Project total** | **1369** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Next phase

The follow-up after Phase 47 is **Phase 48 —
the Compose VNC viewer + NavHost route**:
- A new `WindowsVmVncScreen` composable that
  takes a `vncPort: Int` and uses the
  existing [RfbSession] to connect to
  `127.0.0.1:<vncPort>`, render the
  framebuffer via [RfbSurfaceView], and
  forward click / keyboard input back to
  the guest.
- A new `vnc_windows_vm/{vmId}` route in the
  NavHost. The Phase 45 "Open" snackbar for
  WindowsVm sessions now navigates to
  this route instead of showing the
  "VNC viewer not yet implemented"
  snackbar.
- A new HiltViewModel for the screen that
  holds the RfbSession + the LiveState
  (Idle / Connecting / Connected /
  Streaming).
