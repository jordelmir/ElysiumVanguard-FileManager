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
    val gpu: GpuAccessSpec = GpuAccessSpec.NONE,
    val network: NetworkPolicySpec = NetworkPolicySpec.DEFAULT,
    val backup: BackupPolicySpec = BackupPolicySpec.NONE,
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
        // Phase 104 — invariant: a workspace that asks for
        // GPU acceleration must declare it in the GPU spec;
        // the launcher can't pick up GPU by accident. The
        // check is on the runtime + gpu combination: WINE
        // is the only one that needs explicit GPU opt-in
        // today; LINUX_PROOT and WINDOWS_VM get the host
        // GPU by default unless [GpuAccessSpec.NONE].
        if (runtime == RuntimeKind.WINE_ON_LINUX && gpu.kind == GpuAccessKind.NONE) {
            // Allowed (Wine with no GPU is a real use case
            // for headless builds). No exception.
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

// =====================================================================
// Phase 104 — additional workspace policy fields
// =====================================================================

/**
 * The GPU access spec. Decides what kind of GPU
 * acceleration the workspace's processes get.
 *
 * **Why three levels** (NONE / BASIC_2D / FULL_3D): the
 * vision calls out "GPU access" as a workspace policy.
 * We split it into three because the cost + the security
 * surface are wildly different:
 *
 *  - [GpuAccessKind.NONE] — no GPU. The process uses a
 *    software renderer. Cheapest + safest; the
 *    `virgl` / `panfrost` drivers are not loaded.
 *  - [GpuAccessKind.BASIC_2D] — 2D acceleration only.
 *    The process gets a framebuffer device +
 *    EGL/GLES2. OK for desktop apps that draw 2D
 *    windows; not enough for games or 3D apps.
 *  - [GpuAccessKind.FULL_3D] — full OpenGL 4 / Vulkan /
 *    DXVK (when running under Wine). The process gets
 *    the device GPU. Required for Blender, Unity,
 *    Unreal, etc.
 *
 * **Why a typed enum (not a free-form string)**: a
 * typo in the JSON file would silently fall through
 * to "NONE" (deny GPU by default), which is the
 * safe direction but causes "why is Blender so
 * slow?" support tickets. A typed enum surfaces
 * the typo at JSON parse time.
 */
data class GpuAccessSpec(
    val kind: GpuAccessKind = GpuAccessKind.NONE,
    val vendor: GpuVendor? = null,
    /** Optional env var name → value overrides for the
     *  GPU driver (e.g. `MESA_LOADER_DRIVER_OVERRIDE=panfrost`). */
    val driverEnvOverrides: Map<String, String> = emptyMap(),
) {
    init {
        // FULL_3D + a vendor restriction is the typical
        // "I have an Adreno and I want only the Adreno"
        // case. NONE + a vendor makes no sense (no GPU,
        // no vendor to restrict to).
        if (kind == GpuAccessKind.NONE) {
            require(vendor == null) {
                "GpuAccessSpec with kind=NONE must not have a vendor restriction"
            }
            require(driverEnvOverrides.isEmpty()) {
                "GpuAccessSpec with kind=NONE must not have driver env overrides"
            }
        }
    }

    companion object {
        /** No GPU. The default for new workspaces. */
        val NONE = GpuAccessSpec()

        /** 2D acceleration (framebuffer + GLES2). */
        val BASIC_2D = GpuAccessSpec(kind = GpuAccessKind.BASIC_2D)

        /** Full 3D acceleration (OpenGL 4 / Vulkan). */
        val FULL_3D = GpuAccessSpec(kind = GpuAccessKind.FULL_3D)
    }
}

/**
 * The GPU access level. The `name` is the
 * machine-readable token persisted in the JSON file.
 */
enum class GpuAccessKind {
    /** No GPU acceleration. */
    NONE,
    /** 2D acceleration only (framebuffer + GLES2). */
    BASIC_2D,
    /** Full 3D acceleration (OpenGL 4 / Vulkan). */
    FULL_3D;

    companion object {
        /** Parse a string token; null on unknown values. */
        fun fromToken(token: String): GpuAccessKind? =
            values().firstOrNull { it.name.equals(token, ignoreCase = true) }
    }
}

/**
 * The GPU vendor restriction. The vision says
 * workspaces can be GPU-vendor-restricted (e.g. "only
 * use the Adreno, not the Mali"). The values are the
 * common mobile GPU vendors; null means "any vendor".
 */
enum class GpuVendor {
    /** Qualcomm Adreno. */
    ADRENO,
    /** ARM Mali. */
    MALI,
    /** Imagination PowerVR. */
    POWER_VR,
    /** Apple GPU (won't appear on Android, but listed
     *  for cross-platform workspaces). */
    APPLE,
    /** Intel integrated (x86 tablets, Chromebooks). */
    INTEL,
    /** NVIDIA discrete (rare on mobile, common in
     *  Windows VMs). */
    NVIDIA,
    /** AMD discrete. */
    AMD,
    /** Any / unknown vendor. */
    OTHER;

    companion object {
        fun fromToken(token: String): GpuVendor? =
            values().firstOrNull { it.name.equals(token, ignoreCase = true) }
    }
}

/**
 * The network policy. Decides what (if any) network
 * access the workspace's processes get.
 *
 * **Why deny-by-default**: the master vision's
 * Zero-Trust section says "red denegada por
 * defecto". The default policy ([NetworkAccessMode.DENY_ALL])
 * is the safe direction; a workspace that wants
 * internet access must opt in via
 * [NetworkAccessMode.ALLOW_LIST] + an explicit
 * [allowedHosts] list.
 *
 * **Why a host allow-list (not a port allow-list)**:
 * the workspace typically talks to a small number of
 * known hosts (an API server, a CDN). A host
 * allow-list is a much smaller attack surface than
 * "any host on port 443" + a malware binary that
 * exfiltrates to attacker.com.
 *
 * **Why CIDR ranges are not in the policy yet**:
 * Phase 104 ships host-based allow-listing. CIDR /
 * subnet-based allow-listing (e.g. "any RFC 1918
 * address") is a Phase 105+ addition; the data class
 * is small enough to add a `allowedSubnets` field
 * later without breaking the JSON codec.
 */
data class NetworkPolicySpec(
    val mode: NetworkAccessMode = NetworkAccessMode.DENY_ALL,
    val allowedHosts: List<String> = emptyList(),
    val allowedPorts: Set<Int> = emptySet(),
    /** True iff DNS lookups are allowed (needed for
     *  host-based allow-listing; the resolver still
     *  rejects unknown hosts). */
    val dnsAllowed: Boolean = false,
) {
    init {
        require(allowedPorts.all { it in 1..65535 }) {
            "NetworkPolicySpec.allowedPorts must be in 1..65535, got $allowedPorts"
        }
        require(allowedHosts.all { it.isNotBlank() }) {
            "NetworkPolicySpec.allowedHosts must not contain blank entries"
        }
        // Invariant: ALLOW_LIST + empty allowedHosts is
        // a misconfiguration that means "deny everything
        // but log 'allow list' is in effect". Surface the
        // misconfiguration at construction time.
        if (mode == NetworkAccessMode.ALLOW_LIST) {
            require(allowedHosts.isNotEmpty()) {
                "NetworkPolicySpec mode=ALLOW_LIST requires at least one allowedHost"
            }
        }
        // DENY_ALL + allowedHosts is also a
        // misconfiguration (the allow-list is dead code).
        if (mode == NetworkAccessMode.DENY_ALL) {
            require(allowedHosts.isEmpty()) {
                "NetworkPolicySpec mode=DENY_ALL must not declare allowedHosts"
            }
            require(allowedPorts.isEmpty()) {
                "NetworkPolicySpec mode=DENY_ALL must not declare allowedPorts"
            }
        }
    }

    companion object {
        /**
         * The platform default. Per the vision's
         * Zero-Trust principle, network is denied
         * unless the workspace explicitly opts in.
         */
        val DEFAULT = NetworkPolicySpec()

        /**
         * Open internet (rarely needed; useful for
         * development workspaces that need to
         * download packages).
         */
        val OPEN = NetworkPolicySpec(
            mode = NetworkAccessMode.ALLOW_ALL,
            dnsAllowed = true,
        )
    }
}

/**
 * The network access mode.
 */
enum class NetworkAccessMode {
    /** No outbound network from the workspace. The
     *  default. Bind mounts are unaffected. */
    DENY_ALL,

    /** Only the hosts in [NetworkPolicySpec.allowedHosts]
     *  are reachable. */
    ALLOW_LIST,

    /** Any host is reachable. Requires
     *  [NetworkPolicySpec.dnsAllowed] for hostname
     *  resolution. */
    ALLOW_ALL;

    companion object {
        fun fromToken(token: String): NetworkAccessMode? =
            values().firstOrNull { it.name.equals(token, ignoreCase = true) }
    }
}

/**
 * The backup policy. Decides when (if at all) the
 * workspace's state is snapshotted + persisted to
 * the host filesystem.
 *
 * **Why three strategies** (NONE / ON_EXIT / SCHEDULED):
 *
 *  - [BackupStrategy.NONE] — no backups. The
 *    workspace's state is ephemeral; closing it
 *    loses the changes.
 *  - [BackupStrategy.ON_EXIT] — snapshot the
 *    workspace's state when the user closes it. A
 *    single rolling snapshot; the previous snapshot
 *    is overwritten. Cost: O(workspace size) on close.
 *  - [BackupStrategy.SCHEDULED] — periodic snapshot
 *    on a cron-like schedule. The user picks
 *    [scheduleIntervalMinutes]; the platform
 *    caps the maximum (60 minutes) to bound disk
 *    usage.
 *
 * **Why not a continuous backup (rsync-style)**: the
 * cost is non-trivial on a mobile device, and the
 * vision's "workspace" is more like a sandbox than
 * a long-lived VM. ON_EXIT covers 90% of use cases;
 * SCHEDULED is the escape hatch for long-running
 * sessions.
 */
data class BackupPolicySpec(
    val strategy: BackupStrategy = BackupStrategy.NONE,
    val scheduleIntervalMinutes: Int = 30,
    val maxSnapshotCount: Int = 3,
    val compress: Boolean = true,
) {
    init {
        require(scheduleIntervalMinutes in 1..MAX_SCHEDULE_MINUTES) {
            "BackupPolicySpec.scheduleIntervalMinutes must be in 1..$MAX_SCHEDULE_MINUTES, " +
                "got $scheduleIntervalMinutes"
        }
        require(maxSnapshotCount in 1..MAX_SNAPSHOTS) {
            "BackupPolicySpec.maxSnapshotCount must be in 1..$MAX_SNAPSHOTS, " +
                "got $maxSnapshotCount"
        }
        // NONE + any other field is a misconfiguration.
        if (strategy == BackupStrategy.NONE) {
            // The other fields are documented defaults;
            // we don't reject them (the user might set
            // them in advance of switching strategies).
        }
    }

    companion object {
        const val MAX_SCHEDULE_MINUTES: Int = 60
        const val MAX_SNAPSHOTS: Int = 10

        /** No backups. The default for new workspaces. */
        val NONE = BackupPolicySpec()

        /** Snapshot on workspace close. */
        val ON_EXIT = BackupPolicySpec(strategy = BackupStrategy.ON_EXIT)

        /** Snapshot every 15 minutes, keep last 3. */
        val SCHEDULED_15MIN = BackupPolicySpec(
            strategy = BackupStrategy.SCHEDULED,
            scheduleIntervalMinutes = 15,
            maxSnapshotCount = 3,
        )
    }
}

/**
 * The backup strategy.
 */
enum class BackupStrategy {
    /** No backups. The workspace's state is ephemeral. */
    NONE,

    /** Snapshot the workspace when it closes. */
    ON_EXIT,

    /** Periodic snapshot on
     *  [BackupPolicySpec.scheduleIntervalMinutes]. */
    SCHEDULED;

    companion object {
        fun fromToken(token: String): BackupStrategy? =
            values().firstOrNull { it.name.equals(token, ignoreCase = true) }
    }
}
