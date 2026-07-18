package com.elysium.vanguard.core.runtime.domain

import java.io.File
import java.time.Instant

/**
 * Phase 2 — ApplicationCapsule manifest format.
 *
 * A capsule is the standard packaging format for applications running
 * under the Elysium Vanguard Universal Computing Fabric. It encapsulates
 * an application's metadata, dependencies, capabilities, and launch
 * configuration in a single, verifiable manifest.
 *
 * Capsule types:
 *  - LINUX_NATIVE: ARM64 Linux binary with shared libraries
 *  - LINUX_PROOT: Application requiring PRoot for filesystem isolation
 *  - WINDOWS_WINE: Windows application running under Wine+Box64
 *  - REMOTE: Application accessible via network (SSH, VNC, HTTP)
 *  - VM: Application running in a virtual machine
 */
data class ApplicationCapsule(
    val manifestVersion: Int = MANIFEST_VERSION,
    val capsuleId: String,
    val name: String,
    val displayName: String,
    val version: String,
    val description: String,
    val type: CapsuleType,
    val icon: IconReference? = null,
    val metadata: CapsuleMetadata,
    val runtime: RuntimeRequirements,
    val capabilities: Set<RequiredCapability>,
    val launch: LaunchConfiguration,
    val permissions: Set<Permission>,
    val integrity: IntegrityManifest? = null
) {
    init {
        require(capsuleId.isNotBlank()) { "capsuleId must not be blank" }
        require(name.isNotBlank()) { "name must not be blank" }
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require(version.isNotBlank()) { "version must not be blank" }
        require(capsuleId.matches(CAPSULE_ID_REGEX)) { "capsuleId contains invalid characters" }
    }

    fun toJson(): String = buildString {
        append("{\"manifestVersion\":").append(manifestVersion).append(',')
        append("\"capsuleId\":\"").append(esc(capsuleId)).append("\",")
        append("\"name\":\"").append(esc(name)).append("\",")
        append("\"displayName\":\"").append(esc(displayName)).append("\",")
        append("\"version\":\"").append(esc(version)).append("\",")
        append("\"description\":\"").append(esc(description)).append("\",")
        append("\"type\":\"").append(type.name).append("\",")
        if (icon != null) {
            append("\"icon\":{\"kind\":\"${icon.kind}\",\"path\":\"${esc(icon.path)}\"},")
        }
        append("\"metadata\":{")
        append("\"author\":\"").append(esc(metadata.author)).append("\",")
        append("\"license\":\"").append(esc(metadata.license)).append("\",")
        append("\"homepage\":\"").append(esc(metadata.homepage)).append("\",")
        append("\"category\":\"").append(esc(metadata.category)).append("\",")
        append("\"tags\":[")
        append(metadata.tags.joinToString(",") { "\"${esc(it)}\"" })
        append("]},")
        append("\"runtime\":{")
        append("\"backend\":\"${runtime.backend.name}\",")
        append("\"distroId\":${runtime.distroId?.let { "\"$it\"" } ?: "null"},")
        append("\"minAndroidSdk\":${runtime.minAndroidSdk},")
        append("\"requiredBinaries\":[")
        append(runtime.requiredBinaries.joinToString(",") { "\"${esc(it)}\"" })
        append("],")
        append("\"envOverrides\":{")
        append(runtime.envOverrides.entries.joinToString(",") { "\"${esc(it.key)}\":\"${esc(it.value)}\"" })
        append("}},")
        append("\"capabilities\":[")
        append(capabilities.joinToString(",") { "\"${it.name}\"" })
        append("],")
        append("\"launch\":{")
        append("\"command\":[")
        append(launch.command.joinToString(",") { "\"${esc(it)}\"" })
        append("],")
        append("\"workingDirectory\":\"").append(esc(launch.workingDirectory)).append("\",")
        append("\"autoStart\":").append(launch.autoStart).append(',')
        append("\"foregroundService\":").append(launch.foregroundService).append(',')
        append("\"networkExposed\":").append(launch.networkExposed)
        append("},")
        append("\"permissions\":[")
        append(permissions.joinToString(",") { "\"${it.name}\"" })
        append("]")
        if (integrity != null) {
            append(",\"integrity\":{")
            append("\"sha256\":\"${integrity.sha256}\",")
            append("\"sizeBytes\":${integrity.sizeBytes},")
            append("\"signedAtMs\":${integrity.signedAtMs}")
            append("}")
        }
        append("}")
    }

    companion object {
        const val MANIFEST_VERSION = 1
        private val CAPSULE_ID_REGEX = Regex("^[a-z][a-z0-9._-]{0,63}$")

        private fun esc(s: String): String = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

        fun parse(json: String): ApplicationCapsule? {
            try {
                val obj = parseObject(json) ?: return null
                return ApplicationCapsule(
                    manifestVersion = obj["manifestVersion"]?.toIntOrNull() ?: 1,
                    capsuleId = obj["capsuleId"] ?: return null,
                    name = obj["name"] ?: return null,
                    displayName = obj["displayName"] ?: return null,
                    version = obj["version"] ?: return null,
                    description = obj["description"] ?: "",
                    type = try { CapsuleType.valueOf(obj["type"] ?: "LINUX_NATIVE") } catch (_: Exception) { CapsuleType.LINUX_NATIVE },
                    icon = obj["icon.kind"]?.let { IconReference(kind = it, path = obj["icon.path"] ?: "") },
                    metadata = CapsuleMetadata(
                        author = obj["metadata.author"] ?: "",
                        license = obj["metadata.license"] ?: "",
                        homepage = obj["metadata.homepage"] ?: "",
                        category = obj["metadata.category"] ?: "",
                        tags = obj["metadata.tags"]?.split(",")?.map { it.trim() } ?: emptyList()
                    ),
                    runtime = RuntimeRequirements(
                        backend = try { BackendKind.valueOf(obj["runtime.backend"] ?: "PROOT_LINUX") } catch (_: Exception) { BackendKind.PROOT_LINUX },
                        distroId = obj["runtime.distroId"],
                        minAndroidSdk = obj["runtime.minAndroidSdk"]?.toIntOrNull() ?: 26,
                        requiredBinaries = obj["runtime.requiredBinaries"]?.split(",")?.map { it.trim() } ?: emptyList(),
                        envOverrides = obj["runtime.envOverrides"]?.split(",")?.mapNotNull { entry ->
                            val parts = entry.split("=", limit = 2)
                            if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
                        }?.toMap() ?: emptyMap()
                    ),
                    capabilities = obj["capabilities"]?.split(",")?.mapNotNull {
                        try { RequiredCapability.valueOf(it.trim()) } catch (_: Exception) { null }
                    }?.toSet() ?: emptySet(),
                    launch = LaunchConfiguration(
                        command = obj["launch.command"]?.split(",")?.map { it.trim() } ?: listOf("/bin/sh"),
                        workingDirectory = obj["launch.workingDirectory"] ?: "/",
                        autoStart = obj["launch.autoStart"]?.toBooleanStrictOrNull() ?: false,
                        foregroundService = obj["launch.foregroundService"]?.toBooleanStrictOrNull() ?: true,
                        networkExposed = obj["launch.networkExposed"]?.toBooleanStrictOrNull() ?: false
                    ),
                    permissions = obj["permissions"]?.split(",")?.mapNotNull {
                        try { Permission.valueOf(it.trim()) } catch (_: Exception) { null }
                    }?.toSet() ?: emptySet(),
                    integrity = obj["integrity.sha256"]?.let {
                        IntegrityManifest(
                            sha256 = it,
                            sizeBytes = obj["integrity.sizeBytes"]?.toLongOrNull() ?: 0L,
                            signedAtMs = obj["integrity.signedAtMs"]?.toLongOrNull() ?: 0L
                        )
                    }
                )
            } catch (_: Exception) {
                return null
            }
        }

        private fun parseObject(json: String): Map<String, String>? {
            val result = mutableMapOf<String, String>()
            val cleaned = json.trim().removePrefix("{").removeSuffix("}")
            var depth = 0
            var inString = false
            var escape = false
            var currentKey = StringBuilder()
            var currentValue = StringBuilder()
            var collectingKey = true

            for (c in cleaned) {
                when {
                    escape -> { currentValue.append(c); escape = false }
                    c == '\\' && inString -> escape = true
                    c == '"' -> inString = !inString
                    c == ':' && !inString && depth == 0 -> collectingKey = false
                    c == ',' && !inString && depth == 0 -> {
                        val key = currentKey.toString().trim().trim('"')
                        val value = currentValue.toString().trim().trim('"')
                        if (key.isNotEmpty()) result[key] = value
                        currentKey = StringBuilder()
                        currentValue = StringBuilder()
                        collectingKey = true
                    }
                    c == '{' && !inString -> { depth++; if (!collectingKey) currentValue.append(c) }
                    c == '}' && !inString -> { depth--; if (depth >= 0 && !collectingKey) currentValue.append(c) }
                    else -> {
                        if (collectingKey) currentKey.append(c) else currentValue.append(c)
                    }
                }
            }
            if (collectingKey) return null
            val key = currentKey.toString().trim().trim('"')
            val value = currentValue.toString().trim().trim('"')
            if (key.isNotEmpty()) result[key] = value
            return result
        }
    }
}

enum class CapsuleType {
    LINUX_NATIVE,
    LINUX_PROOT,
    WINDOWS_WINE,
    REMOTE,
    VM
}

data class IconReference(val kind: String, val path: String)

data class CapsuleMetadata(
    val author: String,
    val license: String,
    val homepage: String,
    val category: String,
    val tags: List<String> = emptyList()
)

data class RuntimeRequirements(
    val backend: BackendKind,
    val distroId: String? = null,
    val minAndroidSdk: Int = 26,
    val requiredBinaries: List<String> = emptyList(),
    val envOverrides: Map<String, String> = emptyMap()
)

enum class RequiredCapability {
    PTY,
    DISPLAY,
    AUDIO,
    CLIPBOARD,
    NETWORK,
    FILESYSTEM,
    CAMERA,
    LOCATION,
    SENSOR
}

data class LaunchConfiguration(
    val command: List<String>,
    val workingDirectory: String = "/",
    val autoStart: Boolean = false,
    val foregroundService: Boolean = true,
    val networkExposed: Boolean = false
)

enum class Permission {
    INTERNET,
    ACCESS_NETWORK_STATE,
    READ_EXTERNAL_STORAGE,
    WRITE_EXTERNAL_STORAGE,
    FOREGROUND_SERVICE,
    WAKE_LOCK,
    VIBRATE,
    CAMERA,
    RECORD_AUDIO,
    ACCESS_FINE_LOCATION,
    ACCESS_COARSE_LOCATION,
    READ_CONTACTS,
    READ_CALENDAR,
    WRITE_CALENDAR,
    SEND_SMS,
    READ_PHONE_STATE,
    USE_BIOMETRIC,
    SYSTEM_ALERT_WINDOW
}

data class IntegrityManifest(
    val sha256: String,
    val sizeBytes: Long,
    val signedAtMs: Long
)
