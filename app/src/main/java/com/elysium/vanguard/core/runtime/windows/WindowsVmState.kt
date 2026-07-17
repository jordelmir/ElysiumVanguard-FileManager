package com.elysium.vanguard.core.runtime.windows

/**
 * Phase 22 — the runtime state of a Windows VM.
 *
 * The state machine is:
 *
 * ```
 *   Stopped ──start──> Booting ──backend ready──> Running
 *      ^                  │                          │
 *      │                  ▼                          ├──pause──> Paused
 *      │                Error                        │
 *      │                                           resume──┘
 *      │              │
 *      └─────stop──────┘
 *                  ▲
 *                  │
 *              Stopping
 *                  │
 *   Running / Paused ──stop──> Stopping ──backend idle──> Stopped
 * ```
 *
 * The states are exhaustive (sealed class) so the runtime
 * UI can `when` over them without a default branch. The
 * `Running` state carries the QEMU PID and the QMP socket
 * port so the runtime can talk to the guest via QMP
 * (Phase 23 wires the actual QMP integration).
 */
sealed class WindowsVmState {
    /** The VM is not running. The runtime can call [WindowsVmManager.startVm]. */
    object Stopped : WindowsVmState()

    /** The VM is starting up. The runtime waits for the
     *  backend to report `Running` (the QEMU process has
     *  responded to a QMP `query-status` with `running`). */
    object Booting : WindowsVmState()

    /**
     * The VM is running. Carries the QEMU PID + the QMP
     * socket port so the runtime can talk to the guest
     * via the QEMU machine protocol.
     *
     * Phase 47 — `vncPort` is the QEMU VNC display
     * port (the `5900 + displayNumber` QEMU
     * convention; display 0 = port 5900). The
     * `WindowsVmVncScreen` uses this to connect
     * via RFB and stream the guest framebuffer.
     * `null` for backends that do not expose a
     * VNC port (e.g. tests using the in-memory
     * backend).
     */
    data class Running(
        val pid: Int,
        val qmpPort: Int,
        val monitorPort: Int? = null,
        val vncPort: Int? = null
    ) : WindowsVmState()

    /** The VM is paused (QEMU `stop` command). The runtime
     *  can call resume to continue. */
    object Paused : WindowsVmState()

    /** The VM is in the process of shutting down. */
    object Stopping : WindowsVmState()

    /**
     * The VM failed to boot or crashed. The runtime
     * surfaces the message to the user; the manager
     * transitions to Stopped after a follow-up start.
     */
    data class Error(val message: String, val cause: String? = null) : WindowsVmState()
}
