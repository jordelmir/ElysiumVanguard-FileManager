package com.elysium.vanguard.core.runtime.workspace_def

import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.lang.reflect.Type

/**
 * Phase 66 — the JSON serializer + deserializer for
 * [WorkspaceDefinition].
 *
 * The codec is the **only path** between the on-disk
 * JSON representation and the in-memory typed value.
 * A malformed JSON file is rejected with a typed
 * [WorkspaceDefinitionCodecException] (no free-form
 * strings, per `.ai/AGENTS.md` 24.1).
 *
 * The JSON layout mirrors the vision doc's user-facing
 * structure:
 * ```json
 * {
 *   "apiVersion": "elysium.workspace/v1",
 *   "id": "blender-linux",
 *   "name": "Blender on Linux",
 *   "description": "Blender 3D on Elysium Vanguard Linux",
 *   "runtime": "LINUX_PROOT",
 *   "mounts": [
 *     { "hostPath": "/sdcard/.../blender-projects",
 *       "containerPath": "/workspace/projects",
 *       "readOnly": false,
 *       "description": "User's Blender project files" }
 *   ],
 *   "env": [
 *     { "name": "DISPLAY", "value": ":0" }
 *   ],
 *   "launcher": {
 *     "command": "/usr/bin/blender",
 *     "args": ["--background"],
 *     "workingDirectory": "/workspace",
 *     "environmentPassthrough": false
 *   },
 *   "resources": {
 *     "maxMemoryMb": 4096,
 *     "cpuPriority": 75
 *   },
 *   "createdAtMs": 1700000000000
 * }
 * ```
 */
object WorkspaceDefinitionCodec {

    private val gson = GsonBuilder()
        .setPrettyPrinting()
        .serializeNulls()
        .registerTypeAdapter(WorkspaceDefinition::class.java, WorkspaceDefinitionAdapter)
        .registerTypeAdapter(ApiVersion::class.java, ApiVersionAdapter)
        .registerTypeAdapter(RuntimeKind::class.java, RuntimeKindAdapter)
        .create()

    /**
     * Serialize the [WorkspaceDefinition] to a JSON
     * string. The output is pretty-printed for human
     * inspection.
     */
    fun encode(definition: WorkspaceDefinition): String =
        gson.toJson(definition, WorkspaceDefinition::class.java)

    /**
     * Decode a JSON string into a [WorkspaceDefinition].
     * A malformed input is rejected with a typed
     * [WorkspaceDefinitionCodecException].
     */
    fun decode(text: String): WorkspaceDefinition = try {
        gson.fromJson(text, WorkspaceDefinition::class.java)
    } catch (e: JsonParseException) {
        throw WorkspaceDefinitionCodecException(
            message = "malformed JSON: ${e.message}",
        )
    } catch (e: IllegalArgumentException) {
        // The data class `init` blocks throw IAE on
        // invalid input. The codec wraps the IAE in a
        // typed codec exception.
        throw WorkspaceDefinitionCodecException(
            message = "validation failed: ${e.message}",
            cause = e,
        )
    }

    /**
     * Read + decode from a file path. Convenience for
     * the file-backed store.
     */
    fun decodeFromFile(path: java.io.File): WorkspaceDefinition = try {
        decode(path.readText(Charsets.UTF_8))
    } catch (e: java.io.FileNotFoundException) {
        throw WorkspaceDefinitionCodecException(
            message = "file not found: ${path.absolutePath}",
        )
    } catch (e: java.io.IOException) {
        throw WorkspaceDefinitionCodecException(
            message = "I/O error reading ${path.absolutePath}: ${e.message}",
            cause = e,
        )
    }

    /**
     * Encode + write to a file path atomically (via a
     * temp file + rename).
     */
    fun encodeToFile(definition: WorkspaceDefinition, path: java.io.File) {
        val tmp = java.io.File(path.parentFile, path.name + ".tmp")
        try {
            tmp.writeText(encode(definition), Charsets.UTF_8)
            if (!tmp.renameTo(path)) {
                // Fallback: copy + delete
                path.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: java.io.IOException) {
            tmp.delete()
            throw WorkspaceDefinitionCodecException(
                message = "I/O error writing ${path.absolutePath}: ${e.message}",
                cause = e,
            )
        }
    }
}

/**
 * The typed codec exception. The codec is the boundary
 * between the on-disk format and the typed value; any
 * failure at the boundary is a typed error.
 */
class WorkspaceDefinitionCodecException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

// ============================================================
// Gson type adapters
// ============================================================

private object WorkspaceDefinitionAdapter : JsonSerializer<WorkspaceDefinition>,
    JsonDeserializer<WorkspaceDefinition> {

    override fun serialize(
        src: WorkspaceDefinition,
        typeOfSrc: Type,
        context: JsonSerializationContext,
    ): JsonElement {
        val obj = JsonObject()
        obj.addProperty("apiVersion", src.apiVersion.value)
        obj.addProperty("id", src.id)
        obj.addProperty("name", src.name)
        obj.addProperty("description", src.description)
        obj.addProperty("runtime", src.runtime.name)
        obj.add("mounts", context.serialize(src.mounts))
        obj.add("env", context.serialize(src.env))
        obj.add("launcher", context.serialize(src.launcher))
        obj.add("resources", context.serialize(src.resources))
        obj.addProperty("createdAtMs", src.createdAtMs)
        return obj
    }

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): WorkspaceDefinition {
        val obj = json.asJsonObject
        val apiVersion = ApiVersion(obj.get("apiVersion").asString)
        val id = obj.get("id").asString
        val name = obj.get("name").asString
        val description = if (obj.has("description")) obj.get("description").asString else ""
        val runtime = RuntimeKind.valueOf(obj.get("runtime").asString)
        val mounts: List<MountSpec> = context.deserialize(
            obj.get("mounts"),
            object : com.google.gson.reflect.TypeToken<List<MountSpec>>() {}.type,
        )
        val env: List<EnvSpec> = context.deserialize(
            obj.get("env"),
            object : com.google.gson.reflect.TypeToken<List<EnvSpec>>() {}.type,
        )
        val launcher: LauncherSpec = context.deserialize(obj.get("launcher"), LauncherSpec::class.java)
        val resources: ResourceSpec = context.deserialize(obj.get("resources"), ResourceSpec::class.java)
        val createdAtMs = obj.get("createdAtMs").asLong
        return WorkspaceDefinition(
            apiVersion = apiVersion,
            id = id,
            name = name,
            description = description,
            runtime = runtime,
            mounts = mounts,
            env = env,
            launcher = launcher,
            resources = resources,
            createdAtMs = createdAtMs,
        )
    }
}

private object ApiVersionAdapter : JsonSerializer<ApiVersion>, JsonDeserializer<ApiVersion> {
    override fun serialize(
        src: ApiVersion,
        typeOfSrc: Type,
        context: JsonSerializationContext,
    ): JsonElement = JsonObject().apply { addProperty("value", src.value) }

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): ApiVersion = ApiVersion(json.asJsonObject.get("value").asString)
}

private object RuntimeKindAdapter : JsonSerializer<RuntimeKind>, JsonDeserializer<RuntimeKind> {
    override fun serialize(
        src: RuntimeKind,
        typeOfSrc: Type,
        context: JsonSerializationContext,
    ): JsonElement = JsonObject().apply { addProperty("name", src.name) }

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): RuntimeKind = RuntimeKind.valueOf(json.asJsonObject.get("name").asString)
}
