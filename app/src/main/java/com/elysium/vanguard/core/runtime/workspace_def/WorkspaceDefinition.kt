package com.elysium.vanguard.core.runtime.workspace_def

import java.util.UUID

/**
 * Phase 66 — the typed orchestration schema for one
 * workspace (one reproducible app environment).
 *
 * Per the master vision (Elysium Vanguard Universal
 * Platform): every program runs inside a **reproducible
 * workspace**. A workspace is a logical container that
 * declares:
 *   - **What** is mounted (host paths → container paths).
 *   - **What** the environment looks like (env vars).
 *   - **How** the program is launched (command + args + cwd).
 *
 * The user-facing structure (per the vision doc) is:
 * ```
 * /workspaces/
 *   blender-linux/
 *     rootfs/         ← distro rootfs (Phase 24 workspaces)
 *     mounts.json     ← list of MountSpec
 *     env.json        ← list of EnvSpec (or env file)
 *     launcher.json   ← LauncherSpec (command + args)
 * ```
 *
 * In this phase, the three specs are bundled into a
 * single `WorkspaceDefinition` and persisted as a single
 * JSON file (`<baseDir>/workspaces/<id>.json`). A future
 * phase can split the file into three per the vision's
 * exact structure if a user wants to share `mounts.json`
 * across workspaces.
 *
 * The schema is **typed** (per `.ai/AGENTS.md` 24.1): every
 * field has a type. A free-form string is never the value
 * of a mount path or an env var. The consumer pattern-
 * matches on the variants.
 *
 * The schema is **versioned** (`apiVersion`): a breaking
 * change is a new version + a migration tool. A silent
 * lossy migration is a contract violation.
 */
data class WorkspaceDefinition(
    val apiVersion: ApiVersion,
    val id: String,
    val name: String,
    val description: String,
    val runtime: RuntimeKind,
    val mounts: List<MountSpec>,
    val env: List<EnvSpec>,
    val launcher: LauncherSpec,
    val resources: ResourceSpec,
    val createdAtMs: Long,
) {
    init {
        require(id.isNotBlank()) { "WorkspaceDefinition.id must not be blank" }
        require(name.isNotBlank()) { "WorkspaceDefinition.name must not be blank" }
        require(mounts.isNotEmpty() || runtime != RuntimeKind.LINUX_PROOT) {
            "a LINUX_PROOT workspace must declare at least one mount " +
                "(the rootfs mount)"
        }
        require(env.all { it.name.isNotBlank() }) {
            "every EnvSpec.name must be non-blank"
        }
        // The launcher command is the only required piece.
        require(launcher.command.isNotBlank()) {
            "LauncherSpec.command must not be blank"
        }
        require(resources.maxMemoryMb > 0) {
            "ResourceSpec.maxMemoryMb must be positive"
        }
        require(resources.cpuPriority in 0..100) {
            "ResourceSpec.cpuPriority must be in 0..100, " +
                "got ${resources.cpuPriority}"
        }
    }
}

/**
 * The workspace definition API version. A breaking change
 * is a new version + a migration tool. Per `.ai/AGENTS.md`
 * 24.1 + ADR-0006: append-only.
 */
data class ApiVersion(val value: String) {
    init {
        require(value.matches(API_VERSION_PATTERN_REGEX)) {
            "ApiVersion must match $API_VERSION_PATTERN, got: $value"
        }
    }
    companion object {
        const val API_VERSION_PATTERN: String = "^elysium\\.workspace/v\\d+(\\.\\d+)?$"

        // The regex is declared first because `V1` uses it
        // in its initializer. Declaring `V1` before the
        // regex would crash with `ExceptionInInitializerError`
        // (the regex would be null when `V1` is constructed).
        private val API_VERSION_PATTERN_REGEX = Regex(API_VERSION_PATTERN)

        val V1: ApiVersion = ApiVersion("elysium.workspace/v1")
    }
}

/**
 * The runtime kind. The master vision describes three
 * universal runtimes (Android, Linux, Windows); the
 * WorkspaceDefinition supports the two that have
 * orchestration (the Android runtime is the host —
 * the workspace IS an Android-side concept).
 */
enum class RuntimeKind {
    /** A Linux proot session backed by a distro + profile. */
    LINUX_PROOT,

    /** A Windows VM session backed by a QEMU image. */
    WINDOWS_VM,

    /** A Wine prefix on a Linux proot host. */
    WINE_ON_LINUX,
}

/**
 * A mount spec — declares a host path that's made
 * available inside the workspace at a container path.
 *
 * The vision says: "Directorios montados". A mount is
 * read-only by default; a `readWrite = true` flag is
 * the opt-in.
 */
data class MountSpec(
    val hostPath: String,
    val containerPath: String,
    val readOnly: Boolean = true,
    val description: String = "",
) {
    init {
        require(hostPath.isNotBlank()) { "MountSpec.hostPath must not be blank" }
        require(containerPath.isNotBlank()) { "MountSpec.containerPath must not be blank" }
        require(containerPath.startsWith("/")) {
            "MountSpec.containerPath must be absolute, got: $containerPath"
        }
    }
}

/**
 * An environment variable spec — declares one env var
 * inside the workspace.
 *
 * The vision says: "Variables de entorno". An env var
 * has a name + a value. The `secret` flag marks a
 * value that should be loaded from the `SecretStore`
 * (Phase 63) rather than stored in the file.
 */
data class EnvSpec(
    val name: String,
    val value: String,
    val secret: Boolean = false,
) {
    init {
        require(name.isNotBlank()) { "EnvSpec.name must not be blank" }
        // A non-secret env value can be empty (e.g. `DEBUG=`).
        // A secret env value must NOT be empty (a "secret"
        // with no value is a smell — it should be a missing
        // env var, not a literal empty string).
        if (secret) {
            require(value.isNotBlank()) {
                "EnvSpec.secret=true requires a non-blank value " +
                    "(use a different mechanism for missing secrets)"
            }
        }
    }
}

/**
 * A launcher spec — declares how to start the program
 * inside the workspace.
 *
 * The vision says: "Runtime. Arquitectura. Variables de
 * entorno. Directorios montados. Límites de memoria.
 * Prioridad de CPU." The launcher is the entry point.
 */
data class LauncherSpec(
    val command: String,
    val args: List<String> = emptyList(),
    val workingDirectory: String = "/",
    val environmentPassthrough: Boolean = false,
) {
    init {
        require(command.isNotBlank()) { "LauncherSpec.command must not be blank" }
        require(workingDirectory.startsWith("/")) {
            "LauncherSpec.workingDirectory must be absolute, " +
                "got: $workingDirectory"
        }
    }
}

/**
 * A resource spec — declares the memory + CPU bounds
 * for the workspace.
 *
 * The vision says: "Límites de memoria. Prioridad de CPU."
 */
data class ResourceSpec(
    val maxMemoryMb: Int,
    val cpuPriority: Int = 50, // 0 (lowest) .. 100 (highest); default 50 = normal
) {
    init {
        require(maxMemoryMb > 0) {
            "ResourceSpec.maxMemoryMb must be positive, got $maxMemoryMb"
        }
        require(cpuPriority in 0..100) {
            "ResourceSpec.cpuPriority must be in 0..100, got $cpuPriority"
        }
    }

    companion object {
        /**
         * A reasonable default: 2 GB of memory, normal CPU
         * priority. Workspaces that need more memory or a
         * higher priority override the spec.
         */
        val DEFAULT: ResourceSpec = ResourceSpec(maxMemoryMb = 2048, cpuPriority = 50)
    }
}

/**
 * Random ID generator. Wraps `UUID.randomUUID()` so the
 * test suite can inject a deterministic source.
 */
fun randomWorkspaceId(): String = UUID.randomUUID().toString()
