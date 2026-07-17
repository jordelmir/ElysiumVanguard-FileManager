package com.elysium.vanguard.core.runtime.runner

/**
 * Phase 30 — the runtime state of a single [com.elysium.vanguard.core.runtime.workspaces.WorkspaceSession].
 *
 * The state machine is intentionally small and matches the lifecycle the
 * user observes in the Compose UI ("loading" spinner, "running" green
 * pill, "stopping" grey-out, "stopped" tombstone):
 *
 *   Idle ──start()──▶ Starting ──launch ok──▶ Running ──stop()──▶ Stopping ──exit──▶ Stopped
 *                       │                                          │
 *                       └──────────────────┐                       │
 *                                          ▼                       ▼
 *                                        Error                  Error
 *
 * The [com.elysium.vanguard.core.runtime.runner.SessionRunner] is the
 * only mutator of this state machine. Consumers read [SessionState] via
 * the runner's `state(workspaceId, sessionId)` lookup or via the
 * active-sessions list.
 *
 * Why a sealed class: every transition the runtime performs is a
 * `when` over this hierarchy. Adding a state is a deliberate code
 * change with a compile-time check that every consumer is updated.
 */
sealed class SessionState {

    /** The session has never been started, or was previously stopped. */
    object Idle : SessionState()

    /** The session is being launched (the [com.elysium.vanguard.core.runtime.distros.launcher.DistroLauncher]
     *  is resolving, the [com.elysium.vanguard.core.runtime.runner.ProcessLauncher]
     *  is forking). The UI renders a spinner. */
    data class Starting(val atMs: Long) : SessionState()

    /** The session is live. [pid] is the OS process id; the runner's
     *  stop function will signal it. For a Windows VM, [pid] is the
     *  QEMU process and [host]/[port] are the VNC/SPICE endpoints. */
    data class Running(
        val pid: Int,
        val startedAtMs: Long,
        val host: String? = null,
        val port: Int? = null
    ) : SessionState()

    /** The session is being torn down. The OS process has been
     *  signalled; the runner is waiting for the exit code. */
    data class Stopping(val atMs: Long) : SessionState()

    /** The session finished cleanly (exit code 0) or was stopped by
     *  the user. The runner is now ready to start the session again. */
    object Stopped : SessionState()

    /** The session could not be launched or terminated with an
     *  error. The runner is now ready to retry. */
    data class Error(val atMs: Long, val message: String) : SessionState()

    /** Convenience: true for the two "live-ish" states the UI shows
     *  as in-flight. */
    fun isLive(): Boolean = this is Starting || this is Running || this is Stopping

    /** Convenience: true if the runner can call [com.elysium.vanguard.core.runtime.runner.SessionRunner.start]. */
    fun isStartable(): Boolean = this is Idle || this is Stopped || this is Error

    /** Convenience: true if the runner can call [com.elysium.vanguard.core.runtime.runner.SessionRunner.stop]. */
    fun isStoppable(): Boolean = this is Starting || this is Running
}
