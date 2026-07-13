package com.elysium.vanguard.core.runtime.workspace

import com.elysium.vanguard.core.database.runtime.WorkspaceDao
import com.elysium.vanguard.core.database.runtime.WorkspaceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * Section 25 / FASE 11 — Repository over the [WorkspaceEntity] /
 * [WorkspaceDao] Room table. The repository is the boundary
 * between the persistence layer and the workspace domain; the
 * domain layer never sees a [WorkspaceEntity] directly, it
 * sees a [WorkspaceDefinition] and a [WorkspaceLaunchStats].
 *
 * JSON serialization: WorkspaceDefinition is currently serialized
 * with its own hand-rolled toJson() (see WorkspaceDefinition.kt).
 * The deserializer is not yet implemented because the launcher
 * does not need a JSON-to-Definition path; the orchestrator
 * builds the definition in memory and persists it. A
 * DefinitionParser will land in a follow-up slice.
 */
class WorkspaceRepository(
    private val dao: WorkspaceDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { defaultId() }
) {

    fun observeAll(): Flow<List<WorkspaceSummary>> = dao.observeAll().map { rows ->
        rows.map { it.toSummary() }
    }

    suspend fun listAll(): List<WorkspaceSummary> = dao.listAll().map { it.toSummary() }

    suspend fun getById(id: String): WorkspaceSummary? = dao.getById(id)?.toSummary()

    suspend fun getDefinition(id: String): WorkspaceDefinition? {
        val entity = dao.getById(id) ?: return null
        return decodeDefinition(entity)
    }

    suspend fun search(query: String): List<WorkspaceSummary> =
        dao.search(query).map { it.toSummary() }

    suspend fun count(): Int = dao.count()

    /**
     * Save a workspace definition. The [WorkspaceDefinition.id]
     * is the primary key; if it is blank, the repository
     * generates a UUID.
     */
    suspend fun save(definition: WorkspaceDefinition): Result<WorkspaceDefinition> {
        return try {
            val id = if (definition.id.isBlank()) idGenerator() else definition.id
            val resolved = if (definition.id.isBlank()) {
                definition.copy(id = id)
            } else definition
            val now = clock()
            val json = resolved.toJson()
            val existing = dao.getById(id)
            val entity = WorkspaceEntity(
                id = id,
                name = resolved.name,
                configJson = json,
                createdAtMs = existing?.createdAtMs ?: now,
                lastLaunchedAtMs = existing?.lastLaunchedAtMs ?: 0L,
                launchCount = existing?.launchCount ?: 0
            )
            dao.upsert(entity)
            Result.success(resolved)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Mark a workspace as launched. Bumps the launch count and
     * the last-launched timestamp. The actual launch lifecycle
     * is owned by [WorkspaceLauncher]; the repository only
     * tracks the telemetry.
     */
    suspend fun recordLaunch(id: String): Result<Unit> {
        return try {
            val existing = dao.getById(id)
                ?: return Result.failure(IllegalArgumentException("workspace $id not found"))
            dao.update(
                existing.copy(
                    lastLaunchedAtMs = clock(),
                    launchCount = existing.launchCount + 1
                )
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun delete(id: String): Result<Unit> {
        return try {
            dao.deleteById(id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun decodeDefinition(entity: WorkspaceEntity): WorkspaceDefinition? {
        return try {
            parseWorkspaceJson(entity.configJson)
        } catch (_: Exception) {
            null
        }
    }

    private fun WorkspaceEntity.toSummary(): WorkspaceSummary = WorkspaceSummary(
        id = id,
        name = name,
        createdAtMs = createdAtMs,
        lastLaunchedAtMs = lastLaunchedAtMs,
        launchCount = launchCount
    )

    companion object {
        private fun defaultId(): String = java.util.UUID.randomUUID().toString()

        /**
         * Decode a [WorkspaceDefinition] from the JSON produced by
         * [WorkspaceDefinition.toJson]. Uses the org.json library
         * for parsing (bundled with Android at runtime; added as
         * a testImplementation for the JVM unit tests).
         *
         * The earlier hand-rolled recursive-descent parser was
         * retired after the test suite caught a real roundtrip
         * bug (services with nested objects truncated the value
         * when a closing `}` at depth 1 collided with the depth-0
         * emit logic). org.json is small, well-tested, and the
         * Android system already ships it; using it here keeps
         * the parser honest.
         */
        fun parseWorkspaceJson(json: String): WorkspaceDefinition {
            val root = JSONObject(json)
            val id = root.getString("id")
            val name = root.getString("name")
            val description = root.optString("description", "")
            val rootfsId = root.getString("rootfsId")
            val runtime = root.optString("runtime", "proot-linux")
            val services = parseServiceArray(root.optJSONArray("services"))
            val tools = parseToolArray(root.optJSONArray("tools"))
            val env = parseEnvObject(root.optJSONObject("environment"))
            val ports = parsePortArray(root.optJSONArray("ports"))
            val mounts = parseMountArray(root.optJSONArray("storageMounts"))
            val health = parseHealthArray(root.optJSONArray("healthChecks"))
            return WorkspaceDefinition(
                id = id,
                name = name,
                description = description,
                rootfsId = rootfsId,
                runtime = runtime,
                services = services,
                tools = tools,
                environment = env,
                ports = ports,
                storageMounts = mounts,
                healthChecks = health
            )
        }

        private fun parseServiceArray(array: JSONArray?): List<ServiceDefinition> {
            if (array == null) return emptyList()
            return (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                ServiceDefinition(
                    name = obj.getString("name"),
                    command = obj.optJSONArray("command")?.toStringList() ?: emptyList(),
                    dependsOn = obj.optJSONArray("dependsOn")?.toStringList() ?: emptyList(),
                    autoStart = obj.optBoolean("autoStart", true),
                    restartOnFailure = obj.optBoolean("restartOnFailure", false),
                    maxRestarts = obj.optInt("maxRestarts", 3),
                    startupTimeoutMs = obj.optLong("startupTimeoutMs", 30_000L),
                    environment = parseEnvObject(obj.optJSONObject("environment"))
                )
            }
        }

        private fun parseToolArray(array: JSONArray?): List<ToolDefinition> {
            if (array == null) return emptyList()
            return (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                ToolDefinition(
                    name = obj.getString("name"),
                    command = obj.optJSONArray("command")?.toStringList() ?: emptyList(),
                    description = obj.optString("description", "")
                )
            }
        }

        private fun parseEnvObject(obj: JSONObject?): Map<String, String> {
            if (obj == null) return emptyMap()
            val map = LinkedHashMap<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = obj.optString(k, "")
            }
            return map
        }

        private fun parsePortArray(array: JSONArray?): List<PortMapping> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                val obj = array.getJSONObject(i)
                val guest = obj.optInt("guest", -1).takeIf { it > 0 } ?: return@mapNotNull null
                val host = obj.optInt("host", -1).takeIf { it > 0 }
                val protocol = obj.optString("protocol", "tcp")
                PortMapping(guestPort = guest, hostPort = host, protocol = protocol)
            }
        }

        private fun parseMountArray(array: JSONArray?): List<StorageMount> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                val obj = array.getJSONObject(i)
                val host = obj.optString("hostPath", "")
                val guest = obj.optString("guestPath", "")
                if (host.isBlank() || guest.isBlank()) return@mapNotNull null
                val ro = obj.optBoolean("readOnly", true)
                StorageMount(hostPath = host, guestPath = guest, readOnly = ro)
            }
        }

        private fun parseHealthArray(array: JSONArray?): List<HealthCheck> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                val obj = array.getJSONObject(i)
                val name = obj.optString("name", "")
                if (name.isBlank()) return@mapNotNull null
                HealthCheck(
                    name = name,
                    command = obj.optJSONArray("command")?.toStringList() ?: emptyList(),
                    intervalMs = obj.optLong("intervalMs", 10_000L),
                    timeoutMs = obj.optLong("timeoutMs", 5_000L),
                    failureThreshold = obj.optInt("failureThreshold", 3)
                )
            }
        }

        private fun JSONArray.toStringList(): List<String> =
            (0 until length()).map { getString(it) }
    }
}

/**
 * Lightweight view-model for the workspace list. Carries the
 * telemetry fields (last launched, launch count) but not the
 * service / tool / port graph, which is loaded on demand.
 */
data class WorkspaceSummary(
    val id: String,
    val name: String,
    val createdAtMs: Long,
    val lastLaunchedAtMs: Long,
    val launchCount: Int
)
