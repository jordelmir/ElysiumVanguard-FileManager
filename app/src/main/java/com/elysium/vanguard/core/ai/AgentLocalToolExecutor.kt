package com.elysium.vanguard.core.ai

import android.app.ActivityManager
import android.content.Context
import com.elysium.vanguard.core.runtime.distros.DistroManager
import com.elysium.vanguard.core.runtime.distros.launcher.LauncherKind
import com.elysium.vanguard.core.runtime.terminal.service.TerminalService
import com.elysium.vanguard.core.runtime.terminal.session.TerminalSession
import com.elysium.vanguard.core.runtime.terminal.session.TerminalSessionManager
import com.elysium.vanguard.core.server.LocalFileServer
import com.elysium.vanguard.core.server.LocalServerOrchestrator
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID
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
                "install_package" -> installPackage(arguments)
                "apply_patch" -> applyPatch(arguments)
                "create_snapshot" -> createSnapshot(arguments)
                "rollback_snapshot" -> rollbackSnapshot(arguments)
                "start_service" -> startService(arguments)
                "stop_service" -> stopService(arguments)
                "publish_port" -> publishPort(arguments)
                "verify_artifact" -> verifyArtifact(arguments)
                "create_build", "mount_workspace" -> unavailable(
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

    private fun rollbackSnapshot(arguments: JsonObject): Map<String, Any?> {
        val snapshotId = arguments.requiredString("snapshot_id")
        val restored = distros.restoreSnapshot(snapshotId)
            ?: return mapOf("status" to "error", "message" to "Unable to restore snapshot '$snapshotId'")
        return mapOf(
            "status" to "ok",
            "snapshotId" to restored.id,
            "workspaceId" to restored.sourceId,
            "bytesRestored" to restored.bytesCopied,
            "note" to "The rootfs was restored by a staged directory swap. Existing terminal sessions should be restarted."
        )
    }

    private fun installPackage(arguments: JsonObject): Map<String, Any?> {
        val distroId = arguments.requiredString("workspace_id")
        val manager = arguments.requiredString("manager")
        val packageName = arguments.requiredString("package_name")
        val script = AgentToolArgumentPolicy.installScript(manager, packageName)
        return startDistroCommand(
            distroId = distroId,
            script = script,
            action = "install_package",
            details = mapOf("manager" to manager, "package" to packageName)
        )
    }

    private fun applyPatch(arguments: JsonObject): Map<String, Any?> {
        val distroId = arguments.requiredString("workspace_id")
        val patch = AgentToolArgumentPolicy.validateUnifiedPatch(arguments.requiredString("patch"))
        val installation = distros.findInstalled(distroId)
            ?: return mapOf("status" to "not_found", "message" to "Distro '$distroId' is not installed")
        val patchDirectory = File(installation.rootfsDir, "tmp/elysium-agent-patches")
        if (!patchDirectory.isDirectory && !patchDirectory.mkdirs()) {
            return mapOf("status" to "error", "message" to "Unable to prepare patch staging directory")
        }
        val patchFile = File(patchDirectory, "${UUID.randomUUID()}.diff")
        patchFile.writeText(patch, Charsets.UTF_8)
        // Direct-Exec sees host paths, whereas PRoot sees the rootfs under
        // `/`. Select the same staged file through the launcher's namespace.
        val patchPathForLauncher = if (distros.launcherFor(distroId)?.launcher?.kind == LauncherKind.NATIVE_PROOT) {
            "/tmp/elysium-agent-patches/${patchFile.name}"
        } else {
            patchFile.absolutePath
        }
        return startDistroCommand(
            distroId = distroId,
            script = "patch --batch --forward --reject-file=- -p1 -i ${AgentToolArgumentPolicy.shellQuote(patchPathForLauncher)}",
            action = "apply_patch",
            details = mapOf("patchBytes" to patchFile.length())
        )
    }

    private fun startDistroCommand(
        distroId: String,
        script: String,
        action: String,
        details: Map<String, Any?>
    ): Map<String, Any?> {
        val installation = distros.findInstalled(distroId)
            ?: return mapOf("status" to "not_found", "message" to "Distro '$distroId' is not installed")
        if (!installation.isHealthy) {
            return mapOf("status" to "error", "message" to "Distro '$distroId' is not healthy")
        }
        val pick = distros.launcherFor(distroId)
            ?: return mapOf("status" to "error", "message" to "No executable launcher is available for '$distroId'")
        val session = terminalSessions.create(
            TerminalSession.Config(
                command = pick.launcher.buildShellCommand(installation.rootfsDir, script),
                workingDirectory = installation.rootfsDir,
                environmentVariables = pick.launcher.environmentVariables(installation.rootfsDir),
                cols = 120,
                rows = 40
            )
        )
        // Package installs and patching can take longer than an Activity. The
        // existing unexported foreground service owns the same session safely.
        TerminalService.promote(context, session.id)
        return mapOf(
            "status" to "started",
            "action" to action,
            "workspaceId" to distroId,
            "terminalSessionId" to session.id,
            "launcher" to pick.launcher.kind.name.lowercase(),
            "details" to details,
            "nextStep" to "Use read_terminal with terminalSessionId to inspect the bounded transcript."
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
