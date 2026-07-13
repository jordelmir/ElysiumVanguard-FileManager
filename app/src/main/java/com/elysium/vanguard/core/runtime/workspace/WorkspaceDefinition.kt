package com.elysium.vanguard.core.runtime.workspace

import java.io.File

/**
 * Phase 11 — Workspace definition format.
 *
 * A workspace is a reproducible development environment that bundles
 * a rootfs, services, tools, and configuration into a single unit.
 * Services are orchestrated via a DAG (Directed Acyclic Graph) to
 * support dependency-based startup ordering.
 */
data class WorkspaceDefinition(
    val id: String,
    val name: String,
    val description: String,
    val rootfsId: String,
    val runtime: String = "proot-linux",
    val services: List<ServiceDefinition> = emptyList(),
    val tools: List<ToolDefinition> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    val ports: List<PortMapping> = emptyList(),
    val storageMounts: List<StorageMount> = emptyList(),
    val healthChecks: List<HealthCheck> = emptyList(),
    val startupOrder: List<String> = emptyList(),
    val shutdownOrder: List<String> = emptyList()
) {
    init {
        require(id.isNotBlank()) { "workspace id must not be blank" }
        require(name.isNotBlank()) { "workspace name must not be blank" }
        require(rootfsId.isNotBlank()) { "rootfsId must not be blank" }
    }

    fun toJson(): String = buildString {
        append("{\"id\":\"").append(esc(id)).append("\",")
        append("\"name\":\"").append(esc(name)).append("\",")
        append("\"description\":\"").append(esc(description)).append("\",")
        append("\"rootfsId\":\"").append(esc(rootfsId)).append("\",")
        append("\"runtime\":\"").append(esc(runtime)).append("\",")
        append("\"services\":[")
        services.forEachIndexed { i, svc ->
            if (i > 0) append(",")
            append("{\"name\":\"").append(esc(svc.name)).append("\",")
            append("\"command\":[").append(svc.command.joinToString(",") { "\"${esc(it)}\"" }).append("],")
            append("\"dependsOn\":[").append(svc.dependsOn.joinToString(",") { "\"${esc(it)}\"" }).append("],")
            append("\"autoStart\":").append(svc.autoStart).append("}")
        }
        append("],")
        append("\"environment\":{")
        append(environment.entries.joinToString(",") { "\"${esc(it.key)}\":\"${esc(it.value)}\"" })
        append("},")
        append("\"ports\":[")
        ports.forEachIndexed { i, p ->
            if (i > 0) append(",")
            append("{\"guest\":").append(p.guestPort).append(",\"host\":").append(p.hostPort ?: -1)
            append(",\"protocol\":\"${p.protocol}\"}")
        }
        append("],")
        append("\"healthChecks\":[")
        healthChecks.forEachIndexed { i, hc ->
            if (i > 0) append(",")
            append("{\"name\":\"").append(esc(hc.name)).append("\",")
            append("\"command\":[").append(hc.command.joinToString(",") { "\"${esc(it)}\"" }).append("],")
            append("\"intervalMs\":").append(hc.intervalMs).append(",")
            append("\"timeoutMs\":").append(hc.timeoutMs).append("}")
        }
        append("]}")
    }

    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
}

data class ServiceDefinition(
    val name: String,
    val command: List<String>,
    val dependsOn: List<String> = emptyList(),
    val autoStart: Boolean = true,
    val restartOnFailure: Boolean = false,
    val maxRestarts: Int = 3,
    val startupTimeoutMs: Long = 30_000,
    val environment: Map<String, String> = emptyMap()
)

data class ToolDefinition(
    val name: String,
    val command: List<String>,
    val description: String = ""
)

data class PortMapping(
    val guestPort: Int,
    val hostPort: Int? = null,
    val protocol: String = "tcp"
) {
    init {
        require(guestPort in 1..65535) { "invalid guest port" }
    }
}

data class StorageMount(
    val hostPath: String,
    val guestPath: String,
    val readOnly: Boolean = false
)

data class HealthCheck(
    val name: String,
    val command: List<String>,
    val intervalMs: Long = 10_000,
    val timeoutMs: Long = 5_000,
    val failureThreshold: Int = 3
)

/**
 * Phase 11 — Service startup DAG resolver.
 *
 * Resolves the dependency graph for workspace services and returns
 * an ordered list of startup groups. Services within the same group
 * can start in parallel.
 */
object ServiceDagResolver {

    /**
     * Resolve the startup order. Returns a list of groups where each
     * group contains services that can start in parallel. Groups are
     * ordered by dependency depth.
     *
     * @throws IllegalArgumentException if a cycle is detected.
     */
    fun resolveStartupOrder(services: List<ServiceDefinition>): List<List<String>> {
        val graph = services.associate { it.name to it.dependsOn }
        return topologicalSort(graph)
    }

    /**
     * Resolve the shutdown order (reverse of startup).
     */
    fun resolveShutdownOrder(services: List<ServiceDefinition>): List<List<String>> {
        return resolveStartupOrder(services).reversed()
    }

    /**
     * Validate that the dependency graph has no cycles.
     */
    fun hasCycle(services: List<ServiceDefinition>): Boolean {
        return try {
            resolveStartupOrder(services)
            false
        } catch (_: CyclicDependencyException) {
            true
        }
    }

    private fun topologicalSort(graph: Map<String, List<String>>): List<List<String>> {
        val inDegree = mutableMapOf<String, Int>()
        val adjacency = mutableMapOf<String, MutableList<String>>()

        graph.forEach { (node, deps) ->
            inDegree.putIfAbsent(node, 0)
            adjacency.putIfAbsent(node, mutableListOf())
            deps.forEach { dep ->
                adjacency.computeIfAbsent(dep) { mutableListOf() }.add(node)
                inDegree[node] = (inDegree[node] ?: 0) + 1
            }
        }

        val result = mutableListOf<List<String>>()
        val queue = ArrayDeque<String>()
        inDegree.forEach { (node, degree) ->
            if (degree == 0) queue.add(node)
        }

        while (queue.isNotEmpty()) {
            val group = queue.toList()
            queue.clear()
            result.add(group)
            group.forEach { node ->
                adjacency[node]?.forEach { neighbor ->
                    val current = inDegree[neighbor] ?: 1
                    val next = current - 1
                    inDegree[neighbor] = next
                    if (next == 0) queue.add(neighbor)
                }
            }
        }

        if (result.sumOf { it.size } != graph.size) {
            throw CyclicDependencyException("cyclic dependency detected in service graph")
        }

        return result
    }
}

class CyclicDependencyException(message: String) : RuntimeException(message)
