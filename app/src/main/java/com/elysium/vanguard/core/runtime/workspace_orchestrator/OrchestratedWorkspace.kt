package com.elysium.vanguard.core.runtime.workspace_orchestrator

import com.elysium.vanguard.core.runtime.network.policy.NetworkPolicy
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceSession

/**
 * Phase 67 — the runtime-ready plan that the
 * [WorkspaceOrchestrator] produces from a
 * [com.elysium.vanguard.core.runtime.workspace_def.WorkspaceDefinition].
 *
 * The plan is **the contract** between the typed schema
 * (Phase 66) and the existing runtime hooks (Phase 24's
 * `WorkspaceManager` + `LinuxProotSessionRunner` +
 * `WindowsVmSessionRunner`). The orchestrator translates
 * the user's intent (mounts + env + launcher) into the
 * concrete operations the runtime executes:
 *   - `session` — the [WorkspaceSession] to create.
 *   - `bindMounts` — the bind mounts to apply on the
 *     proot / VM (host path -> container path,
 *     read-only flag).
 *   - `environment` — the resolved environment variables.
 *   - `launchCommand` — the concrete command + args + cwd.
 *
 * The plan is **typed** (per `.ai/AGENTS.md` 24.1): no
 * free-form strings, no `Map<String, Any>`. Every field
 * is a typed value the runtime hooks can consume without
 * re-validation.
 *
 * The plan is **immutable** (data class + no setters):
 * a plan is a frozen snapshot of "what the user wants
 * to run". Mutations create a new plan, not edit an
 * existing one. This matches the platform's
 * append-only + content-addressed invariant (per
 * `.ai/STANDARDS.md` 2.2 + ADR-0006).
 */
data class OrchestratedWorkspace(
    val session: WorkspaceSession,
    val bindMounts: List<BindMount>,
    val environment: Map<String, String>,
    val launchCommand: LaunchCommand,
    val resourceLimits: ResourceLimits,
    /**
     * PHASE 109 — the session-level network
     * policy, bridged from the workspace's
     * [com.elysium.vanguard.core.runtime.workspace_def.NetworkPolicySpec]
     * (the typed "red denegada por defecto" field
     * the spec carries). The session runner
     * consumes this when starting the session:
     * it compiles the ruleset via
     * [com.elysium.vanguard.core.runtime.network.firewall.NetworkPolicyFirewall.compileFromSpec]
     * and applies the rules.
     *
     * The default is
     * [NetworkPolicySpecBridge.toSessionPolicy]'s
     * default output (LOOPBACK_ONLY — the safe
     * direction). The orchestrator always sets
     * this explicitly; the default exists so
     * tests that construct an [OrchestratedWorkspace]
     * without specifying a policy still get a
     * safe value.
     */
    val networkPolicy: NetworkPolicy = com.elysium.vanguard.core.runtime.network.policy.NetworkPolicySpecBridge
        .toSessionPolicy(com.elysium.vanguard.core.runtime.workspace_def.NetworkPolicySpec.DEFAULT),
) {
    init {
        // The bindMounts list MAY be empty: the runtime
        // hook is responsible for injecting the rootfs
        // mount. The orchestrator's contract is to
        // translate the spec verbatim; the runtime hook
        // adds the platform-level mount.
        require(launchCommand.executable.isNotBlank()) {
            "LaunchCommand.executable must not be blank"
        }
    }
}

/**
 * A bind mount in the runtime's vocabulary. The
 * orchestrator translates a [com.elysium.vanguard.core.runtime.workspace_def.MountSpec]
 * into a [BindMount] for the proot / VM backend.
 *
 * The `readOnly` flag maps to the proot `-R` (read-only
 * bind) option + the VM's mount flag. The host path +
 * container path are the typed values the runtime
 * consumes.
 */
data class BindMount(
    val hostPath: String,
    val containerPath: String,
    val readOnly: Boolean,
) {
    init {
        require(hostPath.isNotBlank()) { "BindMount.hostPath must not be blank" }
        require(containerPath.isNotBlank()) { "BindMount.containerPath must not be blank" }
        require(containerPath.startsWith("/")) {
            "BindMount.containerPath must be absolute, got: $containerPath"
        }
    }
}

/**
 * The launch command in the runtime's vocabulary. The
 * orchestrator translates a [com.elysium.vanguard.core.runtime.workspace_def.LauncherSpec]
 * into a [LaunchCommand] for the proot / VM backend.
 *
 * The `executable` is the absolute path to the binary
 * (e.g. `/usr/bin/blender`). The `args` are the
 * command-line arguments (passed in order). The
 * `workingDirectory` is the container-side cwd.
 */
data class LaunchCommand(
    val executable: String,
    val args: List<String>,
    val workingDirectory: String,
) {
    init {
        require(executable.isNotBlank()) { "LaunchCommand.executable must not be blank" }
        require(executable.startsWith("/")) {
            "LaunchCommand.executable must be absolute, got: $executable"
        }
        require(workingDirectory.isNotBlank()) { "LaunchCommand.workingDirectory must not be blank" }
        require(workingDirectory.startsWith("/")) {
            "LaunchCommand.workingDirectory must be absolute, got: $workingDirectory"
        }
    }
}

/**
 * The resource limits in the runtime's vocabulary. The
 * orchestrator translates a [com.elysium.vanguard.core.runtime.workspace_def.ResourceSpec]
 * into a [ResourceLimits] for the proot / VM backend.
 *
 * `maxMemoryMb` maps to the cgroup memory limit (root
 * mode) or the proot `-m` flag. `cpuPriority` maps to
 * the cgroup cpu shares.
 */
data class ResourceLimits(
    val maxMemoryMb: Int,
    val cpuPriority: Int,
) {
    init {
        require(maxMemoryMb > 0) {
            "ResourceLimits.maxMemoryMb must be positive, got $maxMemoryMb"
        }
        require(cpuPriority in 0..100) {
            "ResourceLimits.cpuPriority must be in 0..100, got $cpuPriority"
        }
    }
}
