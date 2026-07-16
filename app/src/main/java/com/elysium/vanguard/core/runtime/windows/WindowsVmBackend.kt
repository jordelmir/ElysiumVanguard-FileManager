package com.elysium.vanguard.core.runtime.windows

/**
 * Phase 22 — the platform seam for Windows VMs.
 *
 * The [WindowsVmBackend] is the interface the production
 * QEMU integration satisfies. It receives a
 * [WindowsVmSpec] (already decided by the manager) and
 * returns a typed [WindowsVmState]. The backend owns the
 * QEMU process lifecycle: it spawns the process, watches
 * the QMP socket, and reports state transitions back to
 * the manager.
 *
 * Splitting the backend from the manager keeps the policy
 * logic JVM-testable end-to-end. The
 * [InMemoryWindowsVmBackend] is the test impl; the
 * production QEMU integration is a follow-up phase
 * (Phase 23, the QMP wire format and the QEMU command
 * line builder).
 *
 * Implementations MUST be thread-safe. The manager may
 * call [start] / [stop] from multiple coroutines
 * concurrently.
 */
interface WindowsVmBackend {
    fun start(spec: WindowsVmSpec): WindowsVmState
    fun stop(vmId: String): Boolean
    fun pause(vmId: String): Boolean
    fun resume(vmId: String): Boolean
    fun queryState(vmId: String): WindowsVmState
    fun listRunning(): List<String>
    fun attachUsb(vmId: String, vendorId: Int, productId: Int): Boolean
    fun detachUsb(vmId: String, vendorId: Int, productId: Int): Boolean
}

/**
 * 5-line hand-rolled backend for tests. Records every
 * call and returns canned results; tests override the
 * fields they care about.
 */
class InMemoryWindowsVmBackend : WindowsVmBackend {
    private val lock = Any()
    private val states = mutableMapOf<String, WindowsVmState>()
    private val recorded = mutableListOf<RecordedCall>()
    private val usbDevices = mutableMapOf<String, MutableSet<Pair<Int, Int>>>()
    private var defaultStartState: WindowsVmState = WindowsVmState.Running(
        pid = 1000, qmpPort = 4444, monitorPort = 4445
    )

    sealed class RecordedCall {
        data class Start(val spec: WindowsVmSpec) : RecordedCall()
        data class Stop(val vmId: String) : RecordedCall()
        data class Pause(val vmId: String) : RecordedCall()
        data class Resume(val vmId: String) : RecordedCall()
        data class Query(val vmId: String) : RecordedCall()
        data class AttachUsb(val vmId: String, val vendorId: Int, val productId: Int) : RecordedCall()
        data class DetachUsb(val vmId: String, val vendorId: Int, val productId: Int) : RecordedCall()
    }

    fun respondWithStartState(state: WindowsVmState) {
        synchronized(lock) { defaultStartState = state }
    }

    fun recordStateForVm(vmId: String, state: WindowsVmState) {
        synchronized(lock) { states[vmId] = state }
    }

    fun calls(): List<RecordedCall> = synchronized(lock) { recorded.toList() }
    fun size(): Int = synchronized(lock) { recorded.size }
    fun clear() = synchronized(lock) { recorded.clear() }

    override fun start(spec: WindowsVmSpec): WindowsVmState {
        synchronized(lock) {
            recorded += RecordedCall.Start(spec)
            states[spec.id] = defaultStartState
            return defaultStartState
        }
    }

    override fun stop(vmId: String): Boolean {
        synchronized(lock) {
            recorded += RecordedCall.Stop(vmId)
            return if (vmId in states && states[vmId] !is WindowsVmState.Stopped) {
                states[vmId] = WindowsVmState.Stopping
                // Stopping -> Stopped is a follow-up call.
                // For tests, the manager does the follow-up.
                true
            } else false
        }
    }

    override fun pause(vmId: String): Boolean {
        synchronized(lock) {
            recorded += RecordedCall.Pause(vmId)
            val current = states[vmId] as? WindowsVmState.Running ?: return false
            states[vmId] = WindowsVmState.Paused
            return true
        }
    }

    override fun resume(vmId: String): Boolean {
        synchronized(lock) {
            recorded += RecordedCall.Resume(vmId)
            if (states[vmId] == WindowsVmState.Paused) {
                states[vmId] = defaultStartState
                return true
            }
            return false
        }
    }

    override fun queryState(vmId: String): WindowsVmState {
        synchronized(lock) {
            recorded += RecordedCall.Query(vmId)
            return states[vmId] ?: WindowsVmState.Stopped
        }
    }

    override fun listRunning(): List<String> = synchronized(lock) {
        states.entries
            .filter { (_, s) -> s is WindowsVmState.Running }
            .map { (k, _) -> k }
    }

    override fun attachUsb(vmId: String, vendorId: Int, productId: Int): Boolean {
        synchronized(lock) {
            recorded += RecordedCall.AttachUsb(vmId, vendorId, productId)
            val current = states[vmId] as? WindowsVmState.Running ?: return false
            val devices = usbDevices.getOrPut(vmId) { mutableSetOf() }
            return devices.add(vendorId to productId)
        }
    }

    override fun detachUsb(vmId: String, vendorId: Int, productId: Int): Boolean {
        synchronized(lock) {
            recorded += RecordedCall.DetachUsb(vmId, vendorId, productId)
            return usbDevices[vmId]?.remove(vendorId to productId) == true
        }
    }
}
