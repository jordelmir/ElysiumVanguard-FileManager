package com.elysium.vanguard.core.runtime.windows

import java.io.File

/**
 * Phase 22 — the user-facing orchestrator for Windows VMs.
 *
 * The manager is the runtime's single public surface for
 * the Windows VM path. It composes:
 *
 *   - a [WindowsVmCatalog] (the registry of supported
 *     Windows templates),
 *   - a [WindowsVmBackend] (the QEMU integration in
 *     production, the in-memory backend in tests),
 *   - a per-VM state map ([states]) that mirrors the
 *     backend's view and survives across calls.
 *
 * The manager's job is translation: it looks up the spec
 * by id, hands the spec to the backend, and persists the
 * backend's returned state. The backend is the source of
 * truth for "is the VM running?"; the manager's state map
 * is a cached view the runtime UI reads.
 *
 * Lifecycle:
 *
 *   - [startVm] is the only public entry that takes a
 *     spec id. The runtime's UI looks up the spec in
 *     the catalog and calls startVm.
 *   - [stopVm] is idempotent: stopping a stopped VM is a
 *     no-op (returns success).
 *   - [attachUsb] / [detachUsb] require the VM to be in
 *     a [WindowsVmState.Running] state; otherwise the
 *     call returns a typed failure.
 *   - The manager is stateless across calls; the
 *     catalog + backend + state map are the durable
 *     state.
 *
 * Concurrency: the manager is thread-safe. The
 * [WindowsVmBackend] is also thread-safe (per its
 * contract); the manager's `states` map uses a
 * `ConcurrentHashMap`.
 */
class WindowsVmManager(
    private val baseDir: File,
    private val backend: WindowsVmBackend,
    private val catalog: WindowsVmCatalog = WindowsVmCatalog.official()
) : com.elysium.vanguard.core.runtime.runner.WindowsVmSessionBackend {
    private val states: java.util.concurrent.ConcurrentHashMap<String, WindowsVmState> =
        java.util.concurrent.ConcurrentHashMap()

    /**
     * Start the VM for the given catalog id. Returns a
     * typed result: success carries the initial
     * [WindowsVmState], failure carries a typed
     * [WindowsVmError].
     */
    override fun startVm(specId: String): Result<WindowsVmState> {
        val spec = catalog.find(specId)
            ?: return Result.failure(WindowsVmError.UnknownSpec(specId))
        // The manager persists the initial state. The
        // backend's `start` may return Booting (the
        // typical case) or Running (a fast-boot image).
        val initial = backend.start(spec)
        states[spec.id] = initial
        return Result.success(initial)
    }

    /**
     * Stop the VM. Idempotent: stopping a stopped VM is
     * a no-op success.
     */
    override fun stopVm(specId: String): Result<WindowsVmState> {
        val spec = catalog.find(specId)
            ?: return Result.failure(WindowsVmError.UnknownSpec(specId))
        val current = states[specId] ?: WindowsVmState.Stopped
        if (current == WindowsVmState.Stopped) {
            return Result.success(WindowsVmState.Stopped)
        }
        val stopped = backend.stop(specId)
        if (stopped) {
            // The backend transitioned to Stopping;
            // the manager's view follows. A follow-up
            // queryState (or a background poller in
            // production) flips to Stopped when the
            // QEMU process exits.
            states[specId] = WindowsVmState.Stopping
            return Result.success(WindowsVmState.Stopping)
        }
        return Result.failure(WindowsVmError.BackendRefused("stop", specId))
    }

    /**
     * Pause the VM. The VM must be in
     * [WindowsVmState.Running] for the call to succeed.
     */
    fun pauseVm(specId: String): Result<WindowsVmState> {
        val spec = catalog.find(specId)
            ?: return Result.failure(WindowsVmError.UnknownSpec(specId))
        val current = states[specId]
        if (current !is WindowsVmState.Running) {
            return Result.failure(WindowsVmError.InvalidTransition(
                from = current?.javaClass?.simpleName ?: "null",
                to = "Paused",
                vmId = specId
            ))
        }
        return if (backend.pause(specId)) {
            states[specId] = WindowsVmState.Paused
            Result.success(WindowsVmState.Paused)
        } else {
            Result.failure(WindowsVmError.BackendRefused("pause", specId))
        }
    }

    /**
     * Resume a paused VM.
     */
    fun resumeVm(specId: String): Result<WindowsVmState> {
        val spec = catalog.find(specId)
            ?: return Result.failure(WindowsVmError.UnknownSpec(specId))
        val current = states[specId]
        if (current != WindowsVmState.Paused) {
            return Result.failure(WindowsVmError.InvalidTransition(
                from = current?.javaClass?.simpleName ?: "null",
                to = "Running",
                vmId = specId
            ))
        }
        return if (backend.resume(specId)) {
            // Resume brings the VM back to Running;
            // the backend returns the new state.
            val fresh = backend.queryState(specId)
            states[specId] = fresh
            Result.success(fresh)
        } else {
            Result.failure(WindowsVmError.BackendRefused("resume", specId))
        }
    }

    /**
     * Attach a USB device to a running VM. The VM must
     * be in [WindowsVmState.Running] for the call to
     * succeed. The hardware broker (Phase 18) gates
     * the call; the manager is the dispatch.
     */
    fun attachUsb(
        specId: String,
        vendorId: Int,
        productId: Int
    ): Result<Unit> {
        val spec = catalog.find(specId)
            ?: return Result.failure(WindowsVmError.UnknownSpec(specId))
        val current = states[specId]
        if (current !is WindowsVmState.Running) {
            return Result.failure(WindowsVmError.InvalidTransition(
                from = current?.javaClass?.simpleName ?: "null",
                to = "Running (for USB attach)",
                vmId = specId
            ))
        }
        return if (backend.attachUsb(specId, vendorId, productId)) {
            Result.success(Unit)
        } else {
            Result.failure(WindowsVmError.BackendRefused("attachUsb", specId))
        }
    }

    /**
     * Detach a USB device from a running VM. Idempotent:
     * detaching a non-attached device is a no-op success.
     */
    fun detachUsb(
        specId: String,
        vendorId: Int,
        productId: Int
    ): Result<Unit> {
        val spec = catalog.find(specId)
            ?: return Result.failure(WindowsVmError.UnknownSpec(specId))
        if (states[specId] !is WindowsVmState.Running) {
            return Result.failure(WindowsVmError.InvalidTransition(
                from = states[specId]?.javaClass?.simpleName ?: "null",
                to = "Running (for USB detach)",
                vmId = specId
            ))
        }
        return if (backend.detachUsb(specId, vendorId, productId)) {
            Result.success(Unit)
        } else {
            // detachUsb returning false may simply mean
            // the device was not attached. Treat as a
            // typed warning rather than a hard failure.
            Result.success(Unit)
        }
    }

    /** Look up the manager's view of a VM's state. */
    override fun getState(specId: String): WindowsVmState = states[specId] ?: WindowsVmState.Stopped

    /** List the catalog ids the runtime ships. */
    fun listSpecIds(): List<String> = catalog.all.map { it.id }

    /** List the VMs the manager currently tracks as running. */
    fun listRunning(): List<String> = states.entries
        .filter { (_, s) -> s is WindowsVmState.Running }
        .map { (k, _) -> k }

    /**
     * Phase 48 — the VNC port for a running VM. Returns
     * `null` if the VM is not running, or if the
     * running state does not carry a VNC port
     * (the in-memory test backend does not). The
     * [com.elysium.vanguard.core.runtime.windows.WindowsVmVncScreen]
     * uses this to look up the VNC port the user
     * tapped on, given a [WorkspaceSession.WindowsVm]'s
     * [WorkspaceSession.WindowsVm.windowsSpecId].
     */
    fun vncPortFor(specId: String): Int? {
        val state = states[specId] ?: return null
        return (state as? WindowsVmState.Running)?.vncPort
    }

    /** Refresh the manager's view of a VM by asking the backend. */
    fun refreshState(specId: String): WindowsVmState {
        val fresh = backend.queryState(specId)
        states[specId] = fresh
        return fresh
    }
}

/**
 * Typed errors the manager returns. The caller branches
 * on the kind rather than parsing free-form strings.
 */
sealed class WindowsVmError(message: String) : RuntimeException(message) {
    /** The catalog has no spec with this id. */
    data class UnknownSpec(val specId: String) : WindowsVmError("Unknown Windows VM spec: $specId")

    /** The state machine refused the transition (e.g.
     *  pause on a non-Running VM). */
    data class InvalidTransition(
        val from: String,
        val to: String,
        val vmId: String
    ) : WindowsVmError("Cannot transition $vmId from $from to $to")

    /** The backend's call returned `false`; the
     *  platform refused the operation. */
    data class BackendRefused(
        val operation: String,
        val vmId: String
    ) : WindowsVmError("Windows VM backend refused $operation for $vmId")
}
