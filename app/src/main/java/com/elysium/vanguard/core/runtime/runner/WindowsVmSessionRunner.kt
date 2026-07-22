package com.elysium.vanguard.core.runtime.runner

import com.elysium.vanguard.core.runtime.observability.RuntimeEvent
import com.elysium.vanguard.core.runtime.observability.RuntimeEventBus
import com.elysium.vanguard.core.runtime.windows.WindowsVmState
import com.elysium.vanguard.core.runtime.workspaces.Workspace
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceSession
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 31 — the [SessionRunner] implementation for
 * [WorkspaceSession.WindowsVm].
 *
 * The runner is the parallel of
 * [LinuxProotSessionRunner] (Phase 30), but the
 * "process" is a QEMU VM rather than a forked shell.
 * The runner:
 *
 *   - looks up the spec id from the
 *     [WorkspaceSession.WindowsVm.windowsSpecId]
 *     field,
 *   - delegates start / stop to the
 *     [WindowsVmSessionBackend] (the
 *     [com.elysium.vanguard.core.runtime.windows.WindowsVmManager]
 *     in production),
 *   - maps the backend's [WindowsVmState] to a
 *     [SessionState] the rest of the runtime can
 *     consume uniformly:
 *
 *       WindowsVmState.Stopped     -> SessionState.Idle
 *       WindowsVmState.Booting     -> SessionState.Starting
 *       WindowsVmState.Running     -> SessionState.Running(pid, host=null, port=qmpPort)
 *       WindowsVmState.Paused      -> SessionState.Running(pid, host=null, port=qmpPort)
 *                                     (the VM is suspended, not stopped)
 *       WindowsVmState.Stopping    -> SessionState.Stopping
 *       WindowsVmState.Error       -> SessionState.Error
 *
 *   - publishes a [RuntimeEvent.SessionStartedEvent] /
 *     `SessionStoppedEvent` on every successful state
 *     transition.
 *
 * The runner is `Context`-free and JVM-testable
 * end-to-end. The QEMU + QMP integration is the
 * backend's job; the runner treats the VM as a black
 * box with `state` and `start` / `stop`.
 */
class WindowsVmSessionRunner(
    private val backend: WindowsVmSessionBackend,
    private val eventBus: RuntimeEventBus,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * PHASE 109 — the firewall that compiles
     * the bridged [NetworkPolicy] into
     * iptables-style rules. The default is a
     * fresh [NetworkPolicyFirewall] (no state,
     * no rules) so existing tests that don't
     * care about the network policy still pass.
     * Production wires the real
     * [com.elysium.vanguard.core.runtime.network.firewall.NetworkPolicyFirewall].
     */
    private val networkPolicyFirewall: com.elysium.vanguard.core.runtime.network.firewall.NetworkPolicyFirewall =
        com.elysium.vanguard.core.runtime.network.firewall.NetworkPolicyFirewall(),
    /**
     * PHASE 109 — the backend that applies the
     * compiled firewall state. The default is
     * an [InMemoryFirewallBackend] (records
     * every state in memory) so existing tests
     * that don't care about the firewall still
     * pass. Production wires the real
     * [com.elysium.vanguard.core.runtime.network.firewall.IptablesFirewallRuleBackend]
     * (Phase 109+ production wiring).
     */
    private val firewallBackend: com.elysium.vanguard.core.runtime.network.firewall.FirewallRuleBackend =
        com.elysium.vanguard.core.runtime.network.firewall.InMemoryFirewallBackend(),
) : SessionRunner {

    private val states = ConcurrentHashMap<SessionKey, SessionState>()

    private data class SessionKey(val workspaceId: String, val sessionId: String)

    override fun start(
        workspace: Workspace,
        session: WorkspaceSession,
        networkPolicy: com.elysium.vanguard.core.runtime.network.policy.NetworkPolicy,
    ): Result<SessionState> {
        if (session !is WorkspaceSession.WindowsVm) {
            return Result.failure(
                SessionRunnerError.UnsupportedKind(
                    workspaceId = workspace.id,
                    sessionId = session.id,
                    kind = session.kind
                )
            )
        }
        val key = SessionKey(workspace.id, session.id)
        val current = states[key] ?: SessionState.Idle

        // PHASE 109 — apply the session's network
        // policy. The same logic as
        // [LinuxProotSessionRunner.start]: compile
        // the bridged [NetworkPolicy] into a
        // [FirewallState] + apply the state. A
        // failure to apply is a typed
        // [SessionRunnerError.StartFailed].
        val firewallState = try {
            networkPolicyFirewall.compile(sessionId = session.id, policy = networkPolicy)
        } catch (e: IllegalArgumentException) {
            return Result.failure(
                SessionRunnerError.StartFailed(
                    workspaceId = workspace.id,
                    sessionId = session.id,
                    causeMessage = "network policy rejected: ${e.message ?: e::class.java.simpleName}"
                )
            )
        }
        firewallBackend.apply(firewallState)

        if (!current.isStartable()) {
            return Result.failure(
                SessionRunnerError.SessionAlreadyRunning(
                    workspaceId = workspace.id,
                    sessionId = session.id,
                    currentState = current
                )
            )
        }
        val nowMs = clock()
        states[key] = SessionState.Starting(nowMs)

        val result = backend.startVm(session.windowsSpecId)
        if (result.isFailure) {
            val error = result.exceptionOrNull()
            val message = error?.message ?: "Windows VM start failed"
            states[key] = SessionState.Error(nowMs, message)
            eventBus.publish(
                RuntimeEvent.SessionStartFailedEvent(
                    atMs = nowMs,
                    workspaceId = workspace.id,
                    sessionId = session.id,
                    kind = session.kind.toString(),
                    error = message
                )
            )
            return Result.failure(
                SessionRunnerError.StartFailed(
                    workspaceId = workspace.id,
                    sessionId = session.id,
                    causeMessage = message
                )
            )
        }

        val vmState = result.getOrThrow()
        val mapped = mapVmState(vmState, startedAtMs = nowMs)
        states[key] = mapped

        // The runner only publishes a SessionStartedEvent
        // when the VM has actually reached a "live-ish"
        // state. Booting (the typical QEMU start) is
        // still "starting" from the runner's perspective;
        // a follow-up backend.queryState in a future
        // phase would transition Booting -> Running.
        if (mapped is SessionState.Running) {
            eventBus.publish(
                RuntimeEvent.SessionStartedEvent(
                    atMs = nowMs,
                    workspaceId = workspace.id,
                    sessionId = session.id,
                    kind = session.kind.toString(),
                    launcherKind = "QEMU",
                    pid = mapped.pid
                )
            )
        }
        return Result.success(mapped)
    }

    override fun stop(workspace: Workspace, session: WorkspaceSession): Result<SessionState> {
        if (session !is WorkspaceSession.WindowsVm) {
            return Result.failure(
                SessionRunnerError.UnsupportedKind(
                    workspaceId = workspace.id,
                    sessionId = session.id,
                    kind = session.kind
                )
            )
        }
        val key = SessionKey(workspace.id, session.id)
        val current = states[key] ?: SessionState.Idle
        if (!current.isStoppable()) {
            return Result.failure(
                SessionRunnerError.SessionNotRunning(
                    workspaceId = workspace.id,
                    sessionId = session.id,
                    currentState = current
                )
            )
        }
        val nowMs = clock()
        states[key] = SessionState.Stopping(nowMs)

        val result = backend.stopVm(session.windowsSpecId)
        if (result.isFailure) {
            val error = result.exceptionOrNull()
            val message = error?.message ?: "Windows VM stop failed"
            states[key] = SessionState.Error(nowMs, message)
            return Result.failure(
                SessionRunnerError.StartFailed(
                    workspaceId = workspace.id,
                    sessionId = session.id,
                    causeMessage = message
                )
            )
        }
        val stopped = SessionState.Stopped
        states[key] = stopped
        eventBus.publish(
            RuntimeEvent.SessionStoppedEvent(
                atMs = nowMs,
                workspaceId = workspace.id,
                sessionId = session.id,
                exitCode = 0
            )
        )
        return Result.success(stopped)
    }

    override fun state(workspaceId: String, sessionId: String): SessionState =
        states[SessionKey(workspaceId, sessionId)] ?: SessionState.Idle

    override fun listActive(): List<ActiveSession> {
        return states.entries
            .asSequence()
            .filter { it.value.isLive() }
            .map { (key, state) ->
                ActiveSession(
                    workspaceId = key.workspaceId,
                    sessionId = key.sessionId,
                    kind = WorkspaceSession.SessionKind.WINDOWS_VM,
                    state = state,
                    launcherKind = "QEMU"
                )
            }
            .sortedBy { (it.state as? SessionState.Running)?.startedAtMs ?: 0L }
            .toList()
    }

    private fun mapVmState(vmState: WindowsVmState, startedAtMs: Long): SessionState = when (vmState) {
        is WindowsVmState.Stopped -> SessionState.Stopped
        is WindowsVmState.Booting -> SessionState.Starting(startedAtMs)
        is WindowsVmState.Running -> SessionState.Running(
            pid = vmState.pid,
            startedAtMs = startedAtMs,
            host = null,
            port = vmState.qmpPort
        )
        is WindowsVmState.Paused -> SessionState.Running(
            pid = 0,  // We don't have the pid at this point; Running is the closest match.
            startedAtMs = startedAtMs,
            host = null,
            port = null
        )
        is WindowsVmState.Stopping -> SessionState.Stopping(startedAtMs)
        is WindowsVmState.Error -> SessionState.Error(startedAtMs, vmState.message)
    }
}
