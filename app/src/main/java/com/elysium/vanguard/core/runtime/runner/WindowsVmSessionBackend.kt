package com.elysium.vanguard.core.runtime.runner

import com.elysium.vanguard.core.runtime.windows.WindowsVmState

/**
 * Phase 31 — the narrow seam the
 * [WindowsVmSessionRunner] needs from the Windows VM
 * layer.
 *
 * Mirrors [DistroSessionBackend] (Phase 30): the
 * runtime's full [com.elysium.vanguard.core.runtime.windows.WindowsVmManager]
 * owns a wide surface (start / stop / pause / resume /
 * attach USB / detach USB). The runner only needs three
 * questions:
 *
 *   - start the VM for this spec id,
 *   - stop the VM for this spec id,
 *   - read the current VM state for this spec id.
 *
 * The runner does not care about pause / resume /
 * attach / detach. Those are follow-up actions the
 * runtime's VM screen invokes directly on the
 * manager; the runner is the orchestrator that turns
 * a "session started" into a "VM running" and back.
 *
 * Production wires the real
 * [com.elysium.vanguard.core.runtime.windows.WindowsVmManager]
 * (which implements the interface); tests pass a
 * hand-rolled fake.
 */
interface WindowsVmSessionBackend {
    fun startVm(specId: String): Result<WindowsVmState>
    fun stopVm(specId: String): Result<WindowsVmState>
    fun getState(specId: String): WindowsVmState
}
