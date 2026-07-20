package com.elysium.vanguard.core.orchestrator

import com.elysium.vanguard.core.linux.ElysiumRootfsPath
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import java.util.UUID

/**
 * Phase 81 (Universal Execution Engine) — the
 * **Sandbox + Mount Policy**, the typed spec
 * for the sandbox + bind mount configuration
 * for a workspace.
 *
 * Per the master vision's Universal Execution
 * Engine (section 6), the dispatch flow is:
 *
 *   Runtime Selection (Phase 76 first half)
 *     ↓
 *   **Sandbox and Mount Policy**  ← this phase
 *     ↓
 *   Process Supervisor (Phase 78)
 *     ↓
 *   Telemetry and Recovery (Phase 79 + 80)
 *
 * The sandbox policy is the **typed spec**
 * for the workspace's sandbox. The policy
 * describes **WHAT** a process can access,
 * not **HOW** (the actual SELinux + bind
 * mount enforcement is in the OS).
 *
 * The policy is **pure-domain** (no I/O, no
 * Android dependencies). The test impl is
 * the `InMemorySandboxPolicyValidator`. The
 * production impl may be the same
 * (the validator is stateless + pure; the
 * same impl is used in production).
 *
 * The policy is **8 primitives**:
 *
 *   - **`WorkspaceId`** — the typed id of
 *     a workspace.
 *   - **`MountMode`** — the mount mode
 *     (READ_ONLY / READ_WRITE /
 *     READ_ONLY_NO_EXEC).
 *   - **`MountPurpose`** — the typed
 *     purpose of a mount (SystemLibraries /
 *     WorkspaceData / etc.).
 *   - **`MountEntry`** — a single bind
 *     mount.
 *   - **`NetworkPolicy`** — the network
 *     policy (Denied / LocalOnly /
 *     Allowlisted / Full).
 *   - **`SecurityProfile`** — the SELinux
 *     profile (Permissive / Standard /
 *     Strict / Custom).
 *   - **`SandboxLimits`** — the resource
 *     limits (memory, CPU, fds, processes,
 *     disk write).
 *   - **`SandboxPolicy`** — the full
 *     sandbox policy (the composition of
 *     the above).
 */
sealed class SandboxPolicyValidator {

    /**
     * Validate a [SandboxPolicy]. Returns
     * a list of [SandboxPolicyError]s (the
     * list is empty if the policy is
     * valid). The list is **append-only**;
     * the validator does not reorder
     * errors.
     */
    abstract fun validate(policy: SandboxPolicy): List<SandboxPolicyError>

    /**
     * Check whether a [SandboxPolicy] is
     * valid. The policy is valid if
     * [validate] returns an empty list.
     */
    fun isValid(policy: SandboxPolicy): Boolean =
        validate(policy).isEmpty()
}

/**
 * The typed id of a workspace. The id is
 * a UUID (per the Foundry id convention).
 *
 * The id is the join key the orchestrator
 * uses to find the workspace's sandbox
 * policy.
 */
@JvmInline
value class WorkspaceId(val value: UUID) {
    companion object {
        fun random(): WorkspaceId = WorkspaceId(UUID.randomUUID())
        fun from(raw: String): Result<WorkspaceId> = try {
            Result.success(WorkspaceId(UUID.fromString(raw)))
        } catch (e: IllegalArgumentException) {
            Result.failure(
                SandboxPolicyError.InvalidWorkspaceIdFormat(raw, e),
            )
        }
    }
}

/**
 * The mount mode. The mode determines
 * whether the mounted path is read-only,
 * read-write, or read-only without exec.
 */
enum class MountMode(val displayLabel: String) {
    /** Read-only mount. The process can
     *  read the path but not write to
     *  it. */
    READ_ONLY("Read-Only"),

    /** Read-write mount. The process can
     *  read + write the path. */
    READ_WRITE("Read-Write"),

    /** Read-only mount with no-exec. The
     *  process can read the path but not
     *  write to it AND not execute
     *  binaries from it. */
    READ_ONLY_NO_EXEC("Read-Only, No-Exec"),
}

/**
 * The typed purpose of a mount. The
 * purpose is a **typed classification**
 * of why the mount is included in the
 * policy; a `when` on the purpose is
 * **exhaustive**.
 *
 * The purpose is used by the validator to
 * enforce invariants (e.g. a
 * `WorkspaceData` mount must be
 * `READ_WRITE`; a `SystemLibraries`
 * mount must be `READ_ONLY`).
 */
sealed class MountPurpose {

    /**
     * The OS libraries (e.g. `/usr/lib`,
     * `/lib`). The mount is **always**
     * `READ_ONLY` (the libraries are
     * shared; the process must not modify
     * them).
     */
    data object SystemLibraries : MountPurpose()

    /**
     * The user's data (the user-selected
     * folder). The mount is **always**
     * `READ_WRITE` (the process must be
     * able to read + write the user's
     * data).
     */
    data class WorkspaceData(
        val workspaceId: WorkspaceId,
    ) : MountPurpose()

    /**
     * The workspace config. The mount is
     * `READ_WRITE` (the process must be
     * able to write its config).
     */
    data class WorkspaceConfig(
        val workspaceId: WorkspaceId,
    ) : MountPurpose()

    /**
     * The device nodes (e.g. `/dev/null`,
     * `/dev/zero`). The mount is
     * `READ_WRITE` (the process must be
     * able to write to `/dev/null`).
     */
    data object DeviceNodes : MountPurpose()

    /**
     * A tmpfs mount (an in-memory
     * filesystem). The mount is
     * `READ_WRITE` (the process must be
     * able to read + write the tmpfs).
     */
    data class Tmpfs(
        val sizeMb: Long,
    ) : MountPurpose() {
        init {
            require(sizeMb > 0) {
                "MountPurpose.Tmpfs.sizeMb must be > 0, " +
                    "got $sizeMb"
            }
        }
    }

    /**
     * A custom purpose (a purpose that
     * does not fit the standard
     * categories). The mount mode is
     * unconstrained (the validator
     * accepts any mode).
     */
    data class Custom(
        val name: String,
    ) : MountPurpose() {
        init {
            require(name.isNotBlank()) {
                "MountPurpose.Custom.name must not be blank"
            }
        }
    }
}

/**
 * A single bind mount. The mount is
 * **immutable** (a data class; no
 * setters). A new mount is a new value.
 *
 * The mount has:
 *   - **`source`** — the path on the
 *     host (the path that is bind-mounted
 *     into the sandbox).
 *   - **`target`** — the path in the
 *     sandbox (the path the process sees
 *     inside the sandbox).
 *   - **`mode`** — the mount mode
 *     (READ_ONLY / READ_WRITE /
 *     READ_ONLY_NO_EXEC).
 *   - **`purpose`** — the typed purpose
 *     of the mount.
 *
 * Note: `source` and `target` MAY be
 * equal (e.g. a system library mount
 * `source = /usr/lib, target = /usr/lib`
 * is a common pattern; the mount system
 * re-applies the path inside the
 * sandbox's rootfs). The check is
 * intentionally NOT enforced at the type
 * level.
 */
data class MountEntry(
    val source: ElysiumRootfsPath,
    val target: ElysiumRootfsPath,
    val mode: MountMode,
    val purpose: MountPurpose,
)

/**
 * The network policy. The policy
 * determines the network access for the
 * process. The policy is a sealed class
 * with 4 cases:
 *   - **`Denied`** — no network access.
 *   - **`LocalOnly`** — only loopback
 *     (127.0.0.1 + ::1).
 *   - **`Allowlisted(allowlist)`** — only
 *     allowlisted hosts (by hostname).
 *   - **`Full`** — full network access.
 */
sealed class NetworkPolicy {

    /**
     * No network access. The process is
     * **completely isolated** from the
     * network (no loopback, no internet).
     */
    data object Denied : NetworkPolicy()

    /**
     * Local-only network access. The
     * process can access loopback
     * (127.0.0.1 + ::1) but no external
     * hosts.
     */
    data object LocalOnly : NetworkPolicy()

    /**
     * Allowlisted network access. The
     * process can access only the
     * allowlisted hosts (by hostname).
     */
    data class Allowlisted(
        val allowlist: Set<String>,
    ) : NetworkPolicy() {
        init {
            require(allowlist.isNotEmpty()) {
                "NetworkPolicy.Allowlisted.allowlist must " +
                    "not be empty"
            }
            require(allowlist.all { it.isNotBlank() }) {
                "NetworkPolicy.Allowlisted.allowlist must " +
                    "not contain blank hostnames"
            }
        }
    }

    /**
     * Full network access. The process
     * can access any host.
     */
    data object Full : NetworkPolicy()
}

/**
 * The SELinux security profile. The
 * profile determines the SELinux policy
 * for the process.
 *
 * Per the master vision (section 9):
 * "Prohibición de ejecutar como root
 * salvo necesidad comprobada." The
 * platform defaults to `Strict` for new
 * workspaces; `Permissive` is for
 * debugging.
 */
sealed class SecurityProfile {

    /**
     * No SELinux enforcement. The
     * process is **not** restricted by
     * SELinux. Use only for debugging.
     */
    data object Permissive : SecurityProfile()

    /**
     * Standard SELinux policy. The
     * process is restricted by the
     * platform's standard SELinux
     * policy (allowlist-based).
     */
    data object Standard : SecurityProfile()

    /**
     * Strict SELinux policy (deny by
     * default). The process is
     * restricted by the platform's
     * strict SELinux policy; only
     * explicitly allowed operations
     * are permitted.
     */
    data object Strict : SecurityProfile()

    /**
     * A custom SELinux context. The
     * process is restricted by the
     * custom SELinux context. The
     * context is a string in SELinux
     * format (e.g.
     * "user:role:type:level").
     */
    data class Custom(
        val selinuxContext: String,
    ) : SecurityProfile() {
        init {
            require(selinuxContext.isNotBlank()) {
                "SecurityProfile.Custom.selinuxContext " +
                    "must not be blank"
            }
            require(selinuxContext.contains(":")) {
                "SecurityProfile.Custom.selinuxContext " +
                    "must be in SELinux format " +
                    "(user:role:type:level), got: " +
                    selinuxContext
            }
        }
    }
}

/**
 * The resource limits. The limits
 * determine the **resource consumption
 * cap** for the process. A limit of 0
 * means **unlimited**.
 */
data class SandboxLimits(
    /**
     * The maximum memory in megabytes.
     * 0 = unlimited.
     */
    val maxMemoryMb: Long,

    /**
     * The maximum CPU percent (0-100).
     * 0 = unlimited.
     */
    val maxCpuPercent: Int,

    /**
     * The maximum number of open file
     * descriptors. 0 = unlimited.
     */
    val maxOpenFileDescriptors: Int,

    /**
     * The maximum number of processes
     * (the process + its children).
     * 0 = unlimited.
     */
    val maxProcesses: Int,

    /**
     * The maximum disk write in
     * megabytes (the cumulative write
     * across the workspace). 0 =
     * unlimited.
     */
    val maxDiskWriteMb: Long,
) {
    init {
        require(maxMemoryMb >= 0) {
            "SandboxLimits.maxMemoryMb must be >= 0, " +
                "got $maxMemoryMb"
        }
        require(maxCpuPercent in 0..100) {
            "SandboxLimits.maxCpuPercent must be in 0..100, " +
                "got $maxCpuPercent"
        }
        require(maxOpenFileDescriptors >= 0) {
            "SandboxLimits.maxOpenFileDescriptors must be " +
                ">= 0, got $maxOpenFileDescriptors"
        }
        require(maxProcesses >= 0) {
            "SandboxLimits.maxProcesses must be >= 0, " +
                "got $maxProcesses"
        }
        require(maxDiskWriteMb >= 0) {
            "SandboxLimits.maxDiskWriteMb must be >= 0, " +
                "got $maxDiskWriteMb"
        }
    }

    companion object {
        /**
         * The default limits (memory 2GB,
         * CPU 50%, fds 1024, processes
         * 64, disk write 100MB).
         */
        val DEFAULT: SandboxLimits = SandboxLimits(
            maxMemoryMb = 2_048L,
            maxCpuPercent = 50,
            maxOpenFileDescriptors = 1_024,
            maxProcesses = 64,
            maxDiskWriteMb = 100L,
        )

        /**
         * Unlimited limits (all 0s).
         * The process can consume
         * unbounded resources.
         */
        val UNLIMITED: SandboxLimits = SandboxLimits(
            maxMemoryMb = 0L,
            maxCpuPercent = 0,
            maxOpenFileDescriptors = 0,
            maxProcesses = 0,
            maxDiskWriteMb = 0L,
        )
    }
}

/**
 * The full sandbox policy. The policy
 * is **immutable** (a data class; no
 * setters). A new policy is a new
 * value.
 *
 * The policy is the composition of:
 *   - **`workspaceId`** — the workspace
 *     the policy is for.
 *   - **`mounts`** — the list of bind
 *     mounts (the filesystem
 *     configuration).
 *   - **`limits`** — the resource
 *     limits (the consumption caps).
 *   - **`network`** — the network
 *     policy (the connectivity).
 *   - **`security`** — the SELinux
 *     security profile (the MAC).
 *   - **`signature`** — the policy's
 *     signature.
 */
data class SandboxPolicy(
    val workspaceId: WorkspaceId,
    val mounts: List<MountEntry>,
    val limits: SandboxLimits,
    val network: NetworkPolicy,
    val security: SecurityProfile,
    val signature: Signature,
) {
    init {
        require(mounts.isNotEmpty()) {
            "SandboxPolicy.mounts must not be empty"
        }
    }

    /**
     * Get a mount by target path. Returns
     * `null` if no mount has the given
     * target.
     */
    fun mountForTarget(target: ElysiumRootfsPath): MountEntry? =
        mounts.firstOrNull { it.target == target }

    /**
     * Check whether the policy allows
     * the given host. The check uses
     * the network policy:
     *   - `Denied` → `false`.
     *   - `LocalOnly` → `false` (no
     *     external hosts).
     *   - `Allowlisted(allowlist)` →
     *     `host in allowlist`.
     *   - `Full` → `true`.
     */
    fun allowsHost(host: String): Boolean = when (network) {
        is NetworkPolicy.Denied -> false
        is NetworkPolicy.LocalOnly -> false
        is NetworkPolicy.Allowlisted ->
            host in network.allowlist
        is NetworkPolicy.Full -> true
    }
}

/**
 * The in-memory [SandboxPolicyValidator]
 * for testing + production. The
 * validator is the stateless composition
 * of:
 *   - The validation rules (per
 *     [SandboxPolicy] invariants).
 *
 * The validator is **thread-safe** (no
 * mutable fields).
 */
class InMemorySandboxPolicyValidator : SandboxPolicyValidator() {

    override fun validate(
        policy: SandboxPolicy,
    ): List<SandboxPolicyError> {
        val errors = mutableListOf<SandboxPolicyError>()

        // Rule 1: No duplicate mount targets.
        val targets = policy.mounts.map { it.target }
        val uniqueTargets = targets.toSet()
        if (targets.size != uniqueTargets.size) {
            val duplicates = targets
                .groupingBy { it }
                .eachCount()
                .filter { it.value > 1 }
                .keys
            duplicates.forEach { duplicate ->
                errors.add(
                    SandboxPolicyError.DuplicateMountTarget(duplicate),
                )
            }
        }

        // Rule 2: SystemLibraries mounts must
        // be READ_ONLY.
        policy.mounts
            .filter { it.purpose is MountPurpose.SystemLibraries }
            .forEach { mount ->
                if (mount.mode != MountMode.READ_ONLY) {
                    errors.add(
                        SandboxPolicyError.ReadWriteMountOnReadOnlyPurpose(
                            mount.target,
                            mount.purpose,
                        ),
                    )
                }
            }

        // Rule 3: WorkspaceData mounts must
        // be READ_WRITE.
        policy.mounts
            .filter { it.purpose is MountPurpose.WorkspaceData }
            .forEach { mount ->
                if (mount.mode != MountMode.READ_WRITE) {
                    errors.add(
                        SandboxPolicyError.ReadOnlyMountOnReadWritePurpose(
                            mount.target,
                            mount.purpose,
                        ),
                    )
                }
            }

        return errors
    }
}

/**
 * The typed error envelope for the
 * sandbox policy. The error extends
 * `RuntimeException` (mirrors the
 * `FoundryError` contract with `code` +
 * `message`, but lives in the
 * `orchestrator` package because Kotlin
 * sealed classes only permit subclassing
 * in the same package where the base
 * class is declared).
 */
sealed class SandboxPolicyError(
    message: String,
    val code: String,
) : RuntimeException(message) {

    /**
     * The workspace id string was not a
     * valid UUID. Raised at the boundary
     * (per `.ai/AGENTS.md` 24.1) — never
     * inside the domain.
     */
    data class InvalidWorkspaceIdFormat(
        val rawInput: String,
        val parseFailure: Throwable,
    ) : SandboxPolicyError(
        message = "Invalid UUID format for WorkspaceId: $rawInput",
        code = "INVALID_WORKSPACE_ID_FORMAT",
    )

    /**
     * Two mounts in the policy have the
     * same target path. The policy must
     * have unique targets (the order of
     * mounts is significant; the same
     * target twice is ambiguous).
     */
    data class DuplicateMountTarget(
        val target: ElysiumRootfsPath,
    ) : SandboxPolicyError(
        message = "Duplicate mount target: ${target.value}",
        code = "DUPLICATE_MOUNT_TARGET",
    )

    /**
     * A mount with a `READ_ONLY`-required
     * purpose (e.g. `SystemLibraries`)
     * is not `READ_ONLY`. The mount
     * violates the purpose's
     * mount-mode invariant.
     */
    data class ReadWriteMountOnReadOnlyPurpose(
        val target: ElysiumRootfsPath,
        val purpose: MountPurpose,
    ) : SandboxPolicyError(
        message = "Read-write mount on a read-only " +
            "purpose at ${target.value}: " +
            "${purpose::class.simpleName}",
        code = "READ_WRITE_MOUNT_ON_READ_ONLY_PURPOSE",
    )

    /**
     * A mount with a `READ_WRITE`-required
     * purpose (e.g. `WorkspaceData`) is
     * not `READ_WRITE`. The mount
     * violates the purpose's
     * mount-mode invariant.
     */
    data class ReadOnlyMountOnReadWritePurpose(
        val target: ElysiumRootfsPath,
        val purpose: MountPurpose,
    ) : SandboxPolicyError(
        message = "Read-only mount on a read-write " +
            "purpose at ${target.value}: " +
            "${purpose::class.simpleName}",
        code = "READ_ONLY_MOUNT_ON_READ_WRITE_PURPOSE",
    )
}
