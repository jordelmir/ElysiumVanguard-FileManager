package com.elysium.vanguard.core.ai

import android.app.ActivityManager
import android.content.Context
import com.elysium.vanguard.core.runtime.distros.DistroManager
import com.elysium.vanguard.core.runtime.terminal.session.TerminalSessionManager
import com.elysium.vanguard.core.server.LocalFileServer
import com.elysium.vanguard.core.server.LocalServerOrchestrator
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Executes the approved, typed Command Core tool surface locally. It has no
 * generic command runner: every supported operation is allow-listed here and
 * returns structured, bounded output for the gateway continuation.
 */
@Singleton
class AgentLocalToolExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val terminalSessions: TerminalSessionManager,
    private val distros: DistroManager,
    private val localServer: LocalServerOrchestrator
) {
    suspend fun execute(action: AgentToolCall): AgentFunctionOutput = withContext(Dispatchers.IO) {
        val output = try {
            val arguments = parseArguments(action.argumentsJson)
            when (action.name) {
                "inspect_processes" -> inspectProcesses()
                "read_terminal" -> readTerminal(arguments)
                "create_snapshot" -> createSnapshot(arguments)
                "start_service" -> startService(arguments)
                "stop_service" -> stopService(arguments)
                "publish_port" -> publishPort(arguments)
                "verify_artifact" -> verifyArtifact(arguments)
                "create_build", "install_package", "apply_patch", "rollback_snapshot", "mount_workspace" -> unavailable(
                    action.name,
                    "This local executor does not expose a safe implementation for this action yet. No change was made."
                )
                else -> unavailable(action.name, "Unknown Command Core tool")
            }
        } catch (error: Exception) {
            mapOf(
                "status" to "error",
                "message" to (error.message ?: "Local tool execution failed")
            )
        }
        AgentFunctionOutput(callId = action.callId, output = output)
    }

    private fun inspectProcesses(): Map<String, Any?> {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val appProcesses = manager.runningAppProcesses
            ?.filter { it.processName.startsWith(context.packageName) }
            ?.map {
                mapOf(
                    "process" to it.processName,
                    "pid" to it.pid,
                    "importance" to it.importance
                )
            }
            .orEmpty()
        val sessions = terminalSessions.summaries().map {
            mapOf("id" to it.id, "state" to it.state, "pid" to it.pid)
        }
        return mapOf(
            "status" to "ok",
            "appProcesses" to appProcesses,
            "terminalSessions" to sessions,
            "note" to "Command lines and terminal input are intentionally not exposed."
        )
    }

    private fun readTerminal(arguments: JsonObject): Map<String, Any?> {
        val sessionId = arguments.requiredString("session_id")
        val maxLines = arguments.optionalInt("max_lines", 120).coerceIn(1, 500)
        val tail = terminalSessions.readTail(sessionId, maxLines)
            ?: return mapOf("status" to "not_found", "message" to "Terminal session is not active")
        return mapOf("status" to "ok", "sessionId" to sessionId, "lines" to tail)
    }

    private fun createSnapshot(arguments: JsonObject): Map<String, Any?> {
        val distroId = arguments.requiredString("workspace_id")
        val snapshot = distros.captureSnapshot(distroId)
            ?: return mapOf("status" to "error", "message" to "Unable to capture a snapshot for $distroId")
        return mapOf(
            "status" to "ok",
            "snapshotId" to snapshot.id,
            "sourceId" to snapshot.sourceId,
            "bytesCopied" to snapshot.bytesCopied,
            "complete" to snapshot.isComplete
        )
    }

    private fun startService(arguments: JsonObject): Map<String, Any?> {
        val service = arguments.requiredString("service")
        if (service != FILE_SHARE_SERVICE) return unsupportedService(service)
        val started = localServer.start()
        return mapOf(
            "status" to if (started) "ok" else "error",
            "service" to FILE_SHARE_SERVICE,
            "state" to localServer.state.value.name.lowercase(),
            "port" to localServer.stats.value.boundPort,
            "message" to localServer.lastError.value
        )
    }

    private fun stopService(arguments: JsonObject): Map<String, Any?> {
        val service = arguments.requiredString("service")
        if (service != FILE_SHARE_SERVICE) return unsupportedService(service)
        localServer.stop()
        return mapOf("status" to "ok", "service" to FILE_SHARE_SERVICE, "state" to "stopped")
    }

    private fun publishPort(arguments: JsonObject): Map<String, Any?> {
        val requestedPort = arguments.requiredInt("port")
        if (requestedPort != LocalFileServer.DEFAULT_PORT) {
            return mapOf(
                "status" to "rejected",
                "message" to "Only the configured file-share port ${LocalFileServer.DEFAULT_PORT} can be published locally"
            )
        }
        if (!localServer.start()) {
            return mapOf("status" to "error", "message" to (localServer.lastError.value ?: "Local file share failed to start"))
        }
        return mapOf(
            "status" to "ok",
            "service" to FILE_SHARE_SERVICE,
            "port" to localServer.stats.value.boundPort,
            "expiresInMinutes" to arguments.optionalInt("ttl_minutes", 30).coerceIn(1, 120),
            "note" to "The current local file-share implementation remains active until explicitly stopped."
        )
    }

    private fun verifyArtifact(arguments: JsonObject): Map<String, Any?> {
        val artifact = File(arguments.requiredString("artifact_path")).canonicalFile
        require(artifact.isFile) { "Artifact does not exist or is not a regular file" }
        require(artifact.length() <= MAX_HASH_BYTES) { "Artifact exceeds the verification size limit" }
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(artifact).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return mapOf(
            "status" to "ok",
            "artifactName" to artifact.name,
            "bytes" to artifact.length(),
            "sha256" to digest.digest().joinToString("") { "%02x".format(it) }
        )
    }

    private fun unavailable(name: String, message: String): Map<String, Any?> = mapOf(
        "status" to "unavailable",
        "tool" to name,
        "message" to message
    )

    private fun unsupportedService(service: String): Map<String, Any?> = mapOf(
        "status" to "rejected",
        "message" to "Service '$service' is not in the local allow-list"
    )

    private fun parseArguments(raw: String): JsonObject = try {
        JsonParser.parseString(raw).asJsonObject
    } catch (error: Exception) {
        throw IllegalArgumentException("Tool arguments are not a JSON object", error)
    }

    private fun JsonObject.requiredString(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing required argument: $name")

    private fun JsonObject.requiredInt(name: String): Int =
        get(name)?.takeIf { it.isJsonPrimitive }?.asInt
            ?: throw IllegalArgumentException("Missing required argument: $name")

    private fun JsonObject.optionalInt(name: String, default: Int): Int =
        get(name)?.takeIf { it.isJsonPrimitive }?.asInt ?: default

    private companion object {
        const val FILE_SHARE_SERVICE = "file_share"
        const val MAX_HASH_BYTES = 2L * 1024L * 1024L * 1024L
    }
}
