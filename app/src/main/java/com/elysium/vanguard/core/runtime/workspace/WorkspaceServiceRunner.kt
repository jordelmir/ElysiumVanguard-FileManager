package com.elysium.vanguard.core.runtime.workspace

/**
 * Section 22 — Service runner abstraction for a [WorkspaceDefinition].
 *
 * The launcher does not know how to start a Postgres or a Redis; it
 * only knows the dependency graph. The actual binary invocation
 * (proot + service command) lives behind this interface. Production
 * wires a runner that shells out to proot with the workspace's
 * rootfs; tests wire a no-op or a scripted fake.
 *
 * The runner is also responsible for health checks, which the
 * launcher invokes between DAG groups. A health check is a single
 * command (argv) executed inside the same context as the service.
 */
interface WorkspaceServiceRunner {

    /**
     * Start a service. The [env] map contains the workspace's
     * environment plus any per-service overrides from
     * [ServiceDefinition.environment]. The [workdir] is the
     * resolved working directory.
     *
     * The runner must be idempotent: if the service is already
     * running, return success with the existing pid. The launcher
     * does not track this state itself; the runner owns the
     * process lifecycle.
     */
    suspend fun start(
        spec: ServiceDefinition,
        env: Map<String, String>,
        workdir: String
    ): ServiceStartResult

    /**
     * Stop a previously-started service. The launcher waits up to
     * [timeoutMs] for graceful exit before asking the runner to
     * force-stop (which the runner does via process group signal).
     */
    suspend fun stop(name: String, timeoutMs: Long)

    /**
     * Execute a health check command. The command is a structured
     * argv (not a shell string). The runner is expected to run
     * the command with the same context as a service start.
     */
    suspend fun health(command: List<String>, timeoutMs: Long): HealthCheckResult
}

/**
 * Outcome of [WorkspaceServiceRunner.start]. [pid] is non-null
 * when [success] is true; [error] is non-null when [success] is
 * false. The two fields are mutually exclusive.
 */
data class ServiceStartResult(
    val success: Boolean,
    val pid: Long? = null,
    val error: String? = null
)

/**
 * Outcome of a health check. The launcher uses [healthy] to decide
 * whether the workspace is Running or Degraded; [output] is
 * surfaced in the diagnostic log; [durationMs] feeds the metrics.
 */
data class HealthCheckResult(
    val healthy: Boolean,
    val output: String = "",
    val error: String? = null,
    val durationMs: Long = 0
)

/**
 * Section 22 — In-memory runner used in tests and dev. Every
 * service is recorded as 'started' (success=true, pid=hash) and
 * every health check is recorded as healthy. The real binary
 * runner is wired in the runtime orchestrator slice; this one
 * exists so the DAG semantics and the state machine can be
 * exercised end-to-end on the JVM.
 */
class InMemoryWorkspaceServiceRunner : WorkspaceServiceRunner {

    private data class ServiceHandle(
        val spec: ServiceDefinition,
        val pid: Long,
        val stopped: Boolean = false
    )

    private val services = mutableMapOf<String, ServiceHandle>()
    private val healthLog = mutableListOf<Pair<List<String>, HealthCheckResult>>()
    private val startLog = mutableListOf<String>()
    private val stopLog = mutableListOf<String>()
    private val nextPid = java.util.concurrent.atomic.AtomicLong(10_000L)

    val startHistory: List<String> get() = startLog.toList()
    val stopHistory: List<String> get() = stopLog.toList()
    val healthHistory: List<Pair<List<String>, HealthCheckResult>> get() = healthLog.toList()

    override suspend fun start(
        spec: ServiceDefinition,
        env: Map<String, String>,
        workdir: String
    ): ServiceStartResult {
        val existing = services[spec.name]
        if (existing != null && !existing.stopped) {
            return ServiceStartResult(success = true, pid = existing.pid)
        }
        val pid = nextPid.incrementAndGet()
        services[spec.name] = ServiceHandle(spec, pid)
        startLog += spec.name
        return ServiceStartResult(success = true, pid = pid)
    }

    override suspend fun stop(name: String, timeoutMs: Long) {
        val existing = services[name] ?: return
        services[name] = existing.copy(stopped = true)
        stopLog += name
    }

    override suspend fun health(command: List<String>, timeoutMs: Long): HealthCheckResult {
        val result = HealthCheckResult(healthy = true, output = "ok", durationMs = 1L)
        healthLog += command to result
        return result
    }
}
