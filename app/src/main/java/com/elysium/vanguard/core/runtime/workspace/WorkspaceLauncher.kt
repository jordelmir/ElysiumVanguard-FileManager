package com.elysium.vanguard.core.runtime.workspace

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Section 22 — Workspace launcher.
 *
 * The launcher executes the [WorkspaceDefinition] in the order the
 * master order prescribes:
 *
 *   1. validar capacidades;
 *   2. validar almacenamiento;
 *   3. iniciar runtime;
 *   4. montar workspace;
 *   5. configurar red;
 *   6. iniciar servicios por dependencia (DAG, parallel within group);
 *   7. ejecutar health checks;
 *   8. abrir aplicaciones;
 *   9. mostrar logs;
 *  10. revertir parcialmente ante fallo.
 *
 * Steps 1..5 and 8 are pre-conditions and post-conditions handled
 * by the orchestrator; this launcher owns steps 6, 7, 9, 10. The
 * other steps hook into this launcher through the typed events
 * it emits (so the diagnostic surface can show the user 'we are
 * now in step 6' / 'step 7' / 'step 10 — rolling back service X').
 *
 * DAG semantics: services within the same [ServiceDagResolver]
 * group start in parallel; the next group starts only after every
 * service in the current group reports success. A failure in any
 * service of the current group triggers a rollback of the
 * already-started services in reverse group order.
 *
 * Health checks: after each group finishes starting, the
 * launcher runs the [WorkspaceDefinition.healthChecks] whose
 * [HealthCheck.name] matches a service in the group. (The order's
 * section 22 says 'ejecutar health checks' as a separate step; we
 * treat it as a per-group gate rather than a one-shot after
 * all services are up, so a misconfigured service is caught
 * before the next group starts.)
 *
 * The launcher is intentionally pure with respect to the
 * [WorkspaceServiceRunner]: it never calls ProcessBuilder, never
 * touches the network, never opens a socket. All those concerns
 * are behind the runner. This makes the launcher fully testable
 * on the JVM with an in-memory runner.
 */
class WorkspaceLauncher(
    private val runner: WorkspaceServiceRunner,
    private val workspaceRoot: File,
    private val scopeFactory: () -> CoroutineScope = { CoroutineScope(SupervisorJob() + Dispatchers.IO) },
    private val clock: () -> Long = System::currentTimeMillis
) : AutoCloseable {

    private val _state = MutableStateFlow<WorkspaceLauncherState>(WorkspaceLauncherState.Idle)
    val state: StateFlow<WorkspaceLauncherState> = _state.asStateFlow()

    private val _serviceStates = MutableStateFlow<Map<String, WorkspaceServiceInstance>>(emptyMap())
    val serviceStates: StateFlow<Map<String, WorkspaceServiceInstance>> = _serviceStates.asStateFlow()

    private val _events = MutableStateFlow<List<WorkspaceEvent>>(emptyList())
    val events: StateFlow<List<WorkspaceEvent>> = _events.asStateFlow()

    private val runningScopes = ConcurrentHashMap<String, Job>()

    /**
     * Start the workspace. The returned [WorkspaceLauncherState]
     * is published through [state]. The function does not block:
     * it returns when the launch coroutine has been scheduled.
     *
     * Errors during launch are reported through [state] and the
     * [events] flow, never thrown. The caller can observe the
     * state from a coroutine.
     */
    fun launch(definition: WorkspaceDefinition): Job {
        return scopeFactory().launch {
            runLaunch(definition)
        }
    }

    /**
     * Internal: the actual launch sequence. Public callers go
     * through [launch].
     */
    private suspend fun runLaunch(definition: WorkspaceDefinition) = withContext(Dispatchers.IO) {
        // Step 0: validation
        _state.value = WorkspaceLauncherState.Validating
        emit(WorkspaceEvent.ValidationStarted(definition.id, clock()))
        val validationError = validatePreconditions(definition)
        if (validationError != null) {
            val err = validationError
            _state.value = WorkspaceLauncherState.Failed(err)
            emit(WorkspaceEvent.ValidationFailed(definition.id, err.message, clock()))
            return@withContext
        }
        emit(WorkspaceEvent.ValidationCompleted(definition.id, clock()))

        // Step 1: services DAG
        val groups = try {
            ServiceDagResolver.resolveStartupOrder(definition.services)
        } catch (e: CyclicDependencyException) {
            val err = WorkspaceLauncherError.CyclicDependency(definition.id, e.message ?: "")
            _state.value = WorkspaceLauncherState.Failed(err)
            emit(WorkspaceEvent.Failed(definition.id, err, clock()))
            return@withContext
        }

        _state.value = WorkspaceLauncherState.Starting(groupsStarted = 0, totalGroups = groups.size)
        val startedSoFar = mutableListOf<String>()

        for ((groupIndex, group) in groups.withIndex()) {
            _state.value = WorkspaceLauncherState.Starting(
                groupsStarted = groupIndex,
                totalGroups = groups.size
            )
            emit(WorkspaceEvent.GroupStarted(definition.id, groupIndex, group, clock()))

            val groupServices = definition.services.filter { it.name in group }
            val workdir = workspaceRoot.absolutePath

            val results = try {
                coroutineScope {
                    groupServices.map { service ->
                        async {
                            val env = mergeEnvironment(definition.environment, service.environment)
                            markService(service.name, WorkspaceServiceInstance.Starting)
                            val result = try {
                                runner.start(service, env, workdir)
                            } catch (e: Exception) {
                                ServiceStartResult(success = false, error = e.message ?: e::class.simpleName.orEmpty())
                            }
                            if (result.success) {
                                markService(service.name, WorkspaceServiceInstance.Running(result.pid ?: -1L))
                                startedSoFar += service.name
                            } else {
                                markService(service.name, WorkspaceServiceInstance.FailedToStart(result.error ?: "unknown"))
                            }
                            service to result
                        }
                    }.awaitAll()
                }
            } catch (e: Exception) {
                val err = WorkspaceLauncherError.GroupStartFailed(definition.id, groupIndex, e.message ?: "")
                _state.value = WorkspaceLauncherState.Failed(err)
                emit(WorkspaceEvent.Failed(definition.id, err, clock()))
                rollback(definition, startedSoFar)
                return@withContext
            }

            val failedInGroup = results.filter { !it.second.success }
            if (failedInGroup.isNotEmpty()) {
                val err = WorkspaceLauncherError.ServicesFailedToStart(
                    definition.id,
                    failedInGroup.map { it.first.name to (it.second.error ?: "unknown") }
                )
                _state.value = WorkspaceLauncherState.Failed(err)
                emit(WorkspaceEvent.Failed(definition.id, err, clock()))
                rollback(definition, startedSoFar)
                return@withContext
            }

            // Step 2: health checks for the group
            val healthChecksForGroup = definition.healthChecks.filter { hc ->
                group.any { it == hc.name } || groupServices.any { it.name == hc.name }
            }
            for (hc in healthChecksForGroup) {
                _state.value = WorkspaceLauncherState.HealthChecking(groupIndex, hc.name)
                val result = try {
                    withTimeout(hc.timeoutMs) {
                        runner.health(hc.command, hc.timeoutMs)
                    }
                } catch (e: Exception) {
                    HealthCheckResult(healthy = false, error = e.message ?: e::class.simpleName.orEmpty())
                }
                if (!result.healthy) {
                    val err = WorkspaceLauncherError.HealthCheckFailed(
                        definition.id,
                        hc.name,
                        result.error ?: "unhealthy"
                    )
                    _state.value = WorkspaceLauncherState.Failed(err)
                    emit(WorkspaceEvent.Failed(definition.id, err, clock()))
                    rollback(definition, startedSoFar)
                    return@withContext
                }
                markService(hc.name, WorkspaceServiceInstance.Healthy)
            }
            emit(WorkspaceEvent.GroupCompleted(definition.id, groupIndex, clock()))
        }

        _state.value = WorkspaceLauncherState.Running
        emit(WorkspaceEvent.Running(definition.id, clock()))
    }

    /**
     * Stop every service the launcher started, in reverse group
     * order. Errors during stop are logged via the events flow
     * but do not propagate; the caller already knows the workspace
     * is in a Stopping state and a partial failure here is
     * reported through the events stream.
     */
    suspend fun stop(definition: WorkspaceDefinition) = withContext(Dispatchers.IO) {
        _state.value = WorkspaceLauncherState.Stopping
        emit(WorkspaceEvent.Stopping(definition.id, clock()))
        rollback(definition, definition.services.map { it.name })
        _state.value = WorkspaceLauncherState.Stopped
        emit(WorkspaceEvent.Stopped(definition.id, clock()))
    }

    private suspend fun rollback(definition: WorkspaceDefinition, names: List<String>) {
        // Reverse the order: services that started last stop first.
        // This mirrors the order the master order prescribes ('shutdown
        // order' is the reverse of 'startup order').
        for (name in names.reversed()) {
            val job = runningScopes.remove(name)
            job?.cancel()
            try {
                runner.stop(name, timeoutMs = 5_000L)
                markService(name, WorkspaceServiceInstance.Stopped)
            } catch (e: Exception) {
                emit(WorkspaceEvent.ServiceStopFailed(definition.id, name, e.message ?: "", clock()))
            }
        }
    }

    private fun markService(name: String, instance: WorkspaceServiceInstance) {
        _serviceStates.value = _serviceStates.value + (name to instance)
    }

    private fun mergeEnvironment(
        workspace: Map<String, String>,
        service: Map<String, String>
    ): Map<String, String> {
        // Service env wins over workspace env on collision. This is
        // the same precedence rule the RuntimeOrchestrator uses for
        // the per-session env.
        val merged = LinkedHashMap<String, String>(workspace.size + service.size)
        merged.putAll(workspace)
        merged.putAll(service)
        return merged
    }

    private fun validatePreconditions(definition: WorkspaceDefinition): WorkspaceLauncherError? {
        if (definition.services.isEmpty()) {
            return WorkspaceLauncherError.NoServices(definition.id)
        }
        // The actual capability probe is the orchestrator's job; the
        // launcher only checks what it can check locally.
        if (!workspaceRoot.exists() && !workspaceRoot.mkdirs()) {
            return WorkspaceLauncherError.StorageUnavailable(definition.id, workspaceRoot.absolutePath)
        }
        return null
    }

    private fun emit(event: WorkspaceEvent) {
        _events.value = _events.value + event
    }

    override fun close() {
        runningScopes.values.forEach { it.cancel() }
        runningScopes.clear()
    }
}

/**
 * State machine for the workspace launcher. The states are
 * deliberately enumerated (not boolean isRunning) per section 7.2
 * of the master order.
 */
sealed class WorkspaceLauncherState {

    data object Idle : WorkspaceLauncherState()

    data object Validating : WorkspaceLauncherState()

    data class Starting(
        val groupsStarted: Int,
        val totalGroups: Int
    ) : WorkspaceLauncherState()

    data class HealthChecking(
        val groupIndex: Int,
        val checkName: String
    ) : WorkspaceLauncherState()

    data object Running : WorkspaceLauncherState()

    data object Stopping : WorkspaceLauncherState()

    data object Stopped : WorkspaceLauncherState()

    data class Failed(
        val error: WorkspaceLauncherError
    ) : WorkspaceLauncherState()
}

/**
 * Per-service state. The map is published through [serviceStates].
 */
sealed class WorkspaceServiceInstance {
    data object Starting : WorkspaceServiceInstance()
    data class Running(val pid: Long) : WorkspaceServiceInstance()
    data object Healthy : WorkspaceServiceInstance()
    data class FailedToStart(val error: String) : WorkspaceServiceInstance()
    data object Stopped : WorkspaceServiceInstance()
}

/**
 * Typed errors from the launcher. Per section 7.3 of the master
 * order, every error carries a stable code, a cause, and a
 * suggested action. The actions here are advisory; the UI reads
 * them to suggest 'retry', 'reinstall', 'check storage', etc.
 */
sealed class WorkspaceLauncherError(val code: String, val message: String, val suggestedAction: String) {
    data class NoServices(val workspaceId: String) : WorkspaceLauncherError(
        code = "WORKSPACE_NO_SERVICES",
        message = "Workspace $workspaceId has no services to start",
        suggestedAction = "Add at least one service to the workspace"
    )
    data class StorageUnavailable(val workspaceId: String, val path: String) : WorkspaceLauncherError(
        code = "WORKSPACE_STORAGE_UNAVAILABLE",
        message = "Storage path $path is not available for workspace $workspaceId",
        suggestedAction = "Free space or grant the storage permission, then retry"
    )
    data class CyclicDependency(val workspaceId: String, val detail: String) : WorkspaceLauncherError(
        code = "WORKSPACE_CYCLIC_DEPENDENCY",
        message = "Workspace $workspaceId has a cyclic service dependency: $detail",
        suggestedAction = "Remove one of the cycle edges and retry"
    )
    data class GroupStartFailed(val workspaceId: String, val groupIndex: Int, val detail: String) : WorkspaceLauncherError(
        code = "WORKSPACE_GROUP_FAILED",
        message = "Group $groupIndex of workspace $workspaceId failed to start: $detail",
        suggestedAction = "Inspect the per-service log; the runner reported a start failure"
    )
    data class ServicesFailedToStart(
        val workspaceId: String,
        val failures: List<Pair<String, String>>
    ) : WorkspaceLauncherError(
        code = "WORKSPACE_SERVICES_FAILED",
        message = "Workspace $workspaceId: services ${failures.map { it.first }} failed to start",
        suggestedAction = "Inspect the per-service log; fix the failing service, then retry"
    )
    data class HealthCheckFailed(val workspaceId: String, val checkName: String, val detail: String) : WorkspaceLauncherError(
        code = "WORKSPACE_HEALTHCHECK_FAILED",
        message = "Health check '$checkName' for workspace $workspaceId reported unhealthy: $detail",
        suggestedAction = "Inspect the service that owns the health check; it may need a longer warm-up"
    )
}

/**
 * Events emitted by the launcher. The events flow is append-only;
 * consumers can compute a tail to see the most recent activity.
 */
sealed class WorkspaceEvent {
    abstract val workspaceId: String
    abstract val timestampMs: Long

    data class ValidationStarted(override val workspaceId: String, override val timestampMs: Long) : WorkspaceEvent()
    data class ValidationCompleted(override val workspaceId: String, override val timestampMs: Long) : WorkspaceEvent()
    data class ValidationFailed(override val workspaceId: String, val error: String, override val timestampMs: Long) : WorkspaceEvent()
    data class GroupStarted(override val workspaceId: String, val groupIndex: Int, val group: List<String>, override val timestampMs: Long) : WorkspaceEvent()
    data class GroupCompleted(override val workspaceId: String, val groupIndex: Int, override val timestampMs: Long) : WorkspaceEvent()
    data class Running(override val workspaceId: String, override val timestampMs: Long) : WorkspaceEvent()
    data class Stopping(override val workspaceId: String, override val timestampMs: Long) : WorkspaceEvent()
    data class Stopped(override val workspaceId: String, override val timestampMs: Long) : WorkspaceEvent()
    data class Failed(override val workspaceId: String, val error: WorkspaceLauncherError, override val timestampMs: Long) : WorkspaceEvent()
    data class ServiceStopFailed(override val workspaceId: String, val serviceName: String, val detail: String, override val timestampMs: Long) : WorkspaceEvent()
}

/**
 * Convenience for callers that want to await a terminal state.
 */
suspend fun WorkspaceLauncher.awaitTerminalState(timeoutMs: Long = 30_000L): WorkspaceLauncherState {
    return withTimeout(timeoutMs) {
        var s = state.value
        while (s !is WorkspaceLauncherState.Running &&
               s !is WorkspaceLauncherState.Failed &&
               s !is WorkspaceLauncherState.Stopped) {
            delay(20)
            s = state.value
        }
        s
    }
}
