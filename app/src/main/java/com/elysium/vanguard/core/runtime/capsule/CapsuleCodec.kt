package com.elysium.vanguard.core.runtime.capsule

import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import com.google.gson.Gson
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
 * Phase 68 — the JSON serializer + deserializer for
 * [Capsule].
 *
 * The codec is the **only path** between the on-disk
 * JSON representation (per the vision doc's literal
 * example) and the in-memory typed value. A malformed
 * input is rejected with a typed
 * [CapsuleCodecException].
 *
 * The JSON layout matches the vision doc exactly:
 * ```json
 * {
 *   "id": "com.elysium.blender.arm64",
 *   "runtime": "linux",
 *   "architecture": "arm64",
 *   "distribution": "elysium-linux-1",
 *   "entrypoint": "/usr/bin/blender",
 *   "gpu": { "api": "vulkan", "driver": "turnip" },
 *   "permissions": { "network": false, "storage": ["user-selected"] }
 * }
 * ```
 *
 * The codec adds the platform-required fields that
 * the vision doc's example doesn't enumerate
 * (`apiVersion`, `name`, `version`, `description`,
 * `signature`, `contentHash`).
 */
object CapsuleCodec {

    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .serializeNulls()
        .registerTypeAdapter(Capsule::class.java, CapsuleAdapter)
        .registerTypeAdapter(CapsuleApiVersion::class.java, CapsuleApiVersionAdapter)
        .registerTypeAdapter(CapsuleId::class.java, CapsuleIdAdapter)
        .registerTypeAdapter(Distribution::class.java, DistributionAdapter)
        .registerTypeAdapter(EntryPoint::class.java, EntryPointAdapter)
        .registerTypeAdapter(GpuConfig::class.java, GpuConfigAdapter)
        .registerTypeAdapter(Permissions::class.java, PermissionsAdapter)
        .create()

    /**
     * Serialize the [Capsule] to a JSON string. The
     * output is pretty-printed for human inspection.
     */
    fun encode(capsule: Capsule): String =
        gson.toJson(capsule, Capsule::class.java)

    /**
     * Decode a JSON string into a [Capsule]. A
     * malformed input is rejected with a typed
     * [CapsuleCodecException].
     */
    fun decode(text: String): Capsule = try {
        gson.fromJson(text, Capsule::class.java)
    } catch (e: JsonParseException) {
        throw CapsuleCodecException(
            message = "malformed JSON: ${e.message}",
        )
    } catch (e: IllegalArgumentException) {
        throw CapsuleCodecException(
            message = "validation failed: ${e.message}",
            cause = e,
        )
    }
}

/**
 * The typed codec exception. The codec is the boundary
 * between the on-disk format and the typed value; any
 * failure at the boundary is a typed error.
 */
class CapsuleCodecException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

// ============================================================
// Gson type adapters
// ============================================================

private object CapsuleAdapter : JsonSerializer<Capsule>, JsonDeserializer<Capsule> {
    override fun serialize(
        src: Capsule,
        typeOfSrc: Type,
        context: JsonSerializationContext,
    ): JsonElement {
        val obj = JsonObject()
        obj.addProperty("apiVersion", src.apiVersion.value)
        obj.addProperty("id", src.id.value)
        obj.addProperty("name", src.name)
        obj.addProperty("version", src.version)
        obj.addProperty("description", src.description)
        obj.addProperty("runtime", src.runtime.name)
        obj.addProperty("architecture", src.architecture.name)
        obj.addProperty("distribution", src.distribution.id)
        obj.add("entrypoint", context.serialize(src.entrypoint))
        obj.add("gpu", context.serialize(src.gpu))
        obj.add("permissions", context.serialize(src.permissions))
        obj.addProperty("signature", src.signature.value)
        obj.addProperty("contentHash", src.contentHash.value)
        return obj
    }

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): Capsule {
        val obj = json.asJsonObject
        val apiVersion = CapsuleApiVersion(obj.get("apiVersion").asString)
        val id = CapsuleId(obj.get("id").asString)
        val name = obj.get("name").asString
        val version = obj.get("version").asString
        val description = if (obj.has("description")) obj.get("description").asString else ""
        val runtime = Runtime.valueOf(obj.get("runtime").asString)
        val architecture = Architecture.valueOf(obj.get("architecture").asString)
        val distribution = Distribution(obj.get("distribution").asString)
        val entrypoint: EntryPoint = context.deserialize(obj.get("entrypoint"), EntryPoint::class.java)
        val gpu: GpuConfig = context.deserialize(obj.get("gpu"), GpuConfig::class.java)
        val permissions: Permissions = context.deserialize(obj.get("permissions"), Permissions::class.java)
        val signature = Signature(obj.get("signature").asString)
        val contentHash = ContentHash(obj.get("contentHash").asString)
        return Capsule(
            apiVersion = apiVersion,
            id = id,
            name = name,
            version = version,
            description = description,
            runtime = runtime,
            architecture = architecture,
            distribution = distribution,
            entrypoint = entrypoint,
            gpu = gpu,
            permissions = permissions,
            signature = signature,
            contentHash = contentHash,
        )
    }
}

private object CapsuleApiVersionAdapter : JsonSerializer<CapsuleApiVersion>,
    JsonDeserializer<CapsuleApiVersion> {
    override fun serialize(
        src: CapsuleApiVersion,
        typeOfSrc: Type,
        context: JsonSerializationContext,
    ): JsonElement = JsonObject().apply { addProperty("value", src.value) }

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): CapsuleApiVersion = CapsuleApiVersion(json.asJsonObject.get("value").asString)
}

private object CapsuleIdAdapter : JsonSerializer<CapsuleId>, JsonDeserializer<CapsuleId> {
    override fun serialize(
        src: CapsuleId,
        typeOfSrc: Type,
        context: JsonSerializationContext,
    ): JsonElement = JsonObject().apply { addProperty("value", src.value) }

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): CapsuleId = CapsuleId(json.asJsonObject.get("value").asString)
}

private object DistributionAdapter : JsonSerializer<Distribution>, JsonDeserializer<Distribution> {
    override fun serialize(
        src: Distribution,
        typeOfSrc: Type,
        context: JsonSerializationContext,
    ): JsonElement = JsonObject().apply { addProperty("id", src.id) }

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): Distribution = Distribution(json.asJsonObject.get("id").asString)
}

private object EntryPointAdapter : JsonSerializer<EntryPoint>, JsonDeserializer<EntryPoint> {
    override fun serialize(
        src: EntryPoint,
        typeOfSrc: Type,
        context: JsonSerializationContext,
    ): JsonElement {
        val obj = JsonObject()
        obj.addProperty("executable", src.executable)
        obj.add("args", context.serialize(src.args))
        obj.addProperty("workingDirectory", src.workingDirectory)
        return obj
    }

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): EntryPoint {
        val obj = json.asJsonObject
        val executable = obj.get("executable").asString
        val args: List<String> = context.deserialize(
            obj.get("args"),
            object : com.google.gson.reflect.TypeToken<List<String>>() {}.type,
        )
        val workingDirectory = obj.get("workingDirectory").asString
        return EntryPoint(executable = executable, args = args, workingDirectory = workingDirectory)
    }
}

private object GpuConfigAdapter : JsonSerializer<GpuConfig>, JsonDeserializer<GpuConfig> {
    override fun serialize(
        src: GpuConfig,
        typeOfSrc: Type,
        context: JsonSerializationContext,
    ): JsonElement {
        val obj = JsonObject()
        obj.addProperty("api", src.api.name)
        obj.addProperty("driver", src.driver.name)
        return obj
    }

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): GpuConfig {
        val obj = json.asJsonObject
        val api = GpuApi.valueOf(obj.get("api").asString)
        val driver = GpuDriver.valueOf(obj.get("driver").asString)
        return GpuConfig(api = api, driver = driver)
    }
}

private object PermissionsAdapter : JsonSerializer<Permissions>, JsonDeserializer<Permissions> {
    override fun serialize(
        src: Permissions,
        typeOfSrc: Type,
        context: JsonSerializationContext,
    ): JsonElement {
        val obj = JsonObject()
        obj.addProperty("network", src.network)
        obj.add("storage", context.serialize(src.storage.map { it.name }))
        return obj
    }

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): Permissions {
        val obj = json.asJsonObject
        val network = obj.get("network").asBoolean
        val storageNames: List<String> = context.deserialize(
            obj.get("storage"),
            object : com.google.gson.reflect.TypeToken<List<String>>() {}.type,
        )
        val storage = storageNames.map { StorageScope.valueOf(it) }
        return Permissions(network = network, storage = storage)
    }
}
