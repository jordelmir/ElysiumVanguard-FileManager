package com.elysium.vanguard.core.runtime.workspace_orchestrator

import com.elysium.vanguard.core.runtime.workspace_def.RuntimeKind
import com.elysium.vanguard.core.runtime.workspace_def.WorkspaceDefinition
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceSession
import java.util.UUID

/**
 * Phase 67 — the [WorkspaceOrchestrator] is the
 * **translator** between the typed
 * [WorkspaceDefinition] (Phase 66) and the runtime-ready
 * [OrchestratedWorkspace] that the existing runtime
 * hooks (Phase 24's `WorkspaceManager` +
 * `LinuxProotSessionRunner` + `WindowsVmSessionRunner`)
 * consume.
 *
 * Per the master vision (sección 6 — Motor universal de
 * ejecución): the orchestrator is the typed seam between
 * the user's intent (the spec) and the runtime's
 * execution (the backend). The orchestrator does NOT
 * execute anything — it produces a plan. The runtime
 * hooks consume the plan and execute.
 *
 * The orchestrator is **pure-domain**: no I/O, no
 * Android dependencies. The orchestrator is JVM-testable
 * end-to-end with a hand-rolled fixture. The runtime
 * hooks (which depend on Android Context + Hilt) are
 * tested separately.
 *
 * The orchestrator is **deterministic**: the same
 * [WorkspaceDefinition] produces the same
 * [OrchestratedWorkspace] on every call. The integration
 * test asserts this.
 */
class WorkspaceOrchestrator {

    /**
     * Translate the [definition] into an [OrchestratedWorkspace].
     * The orchestrator allocates a fresh session id
     * (a `UUID.randomUUID()`) — the runtime hook may
     * override this if it has a session-state recovery
     * path. The mounts, environment, launch command, and
     * resource limits are derived from the definition.
     */
    fun orchestrate(definition: WorkspaceDefinition): OrchestratedWorkspace {
        // 1. Translate the mounts. The orchestrator emits
        //    one BindMount per WorkspaceDefinition.MountSpec.
        val bindMounts = definition.mounts.map { spec ->
            BindMount(
                hostPath = spec.hostPath,
                containerPath = spec.containerPath,
                readOnly = spec.readOnly,
            )
        }

        // 2. Translate the env. The orchestrator emits a
        //    Map<String, String> from the EnvSpec list.
        //    Duplicate names are not allowed (the
        //    WorkspaceDefinition's init check rejects them).
        val environment = definition.env.associate { spec ->
            spec.name to spec.value
        }

        // 3. Translate the launcher. The orchestrator emits
        //    a LaunchCommand from the LauncherSpec. The
        //    command + args are concatenated into a single
        //    args list (the runtime hook splits argv).
        val launchCommand = LaunchCommand(
            executable = definition.launcher.command,
            args = definition.launcher.args,
            workingDirectory = definition.launcher.workingDirectory,
        )

        // 4. Translate the resource limits.
        val resourceLimits = ResourceLimits(
            maxMemoryMb = definition.resources.maxMemoryMb,
            cpuPriority = definition.resources.cpuPriority,
        )

        // 5. Build the session. The session id is a fresh
        //    UUID; the session kind is derived from the
        //    definition's runtime. The display name is
        //    the definition's name.
        val session = buildSession(
            sessionId = UUID.randomUUID().toString(),
            displayName = definition.name,
            runtimeKind = definition.runtime,
        )

        return OrchestratedWorkspace(
            session = session,
            bindMounts = bindMounts,
            environment = environment,
            launchCommand = launchCommand,
            resourceLimits = resourceLimits,
        )
    }

    /**
     * Build a [WorkspaceSession] from a runtime kind.
     * The session is the **typed slot** in the
     * `WorkspaceManager` (Phase 24). The runtime kind
     * determines the session variant.
     */
    private fun buildSession(
        sessionId: String,
        displayName: String,
        runtimeKind: RuntimeKind,
    ): WorkspaceSession = when (runtimeKind) {
        RuntimeKind.LINUX_PROOT -> WorkspaceSession.LinuxProot(
            id = sessionId,
            displayName = displayName,
            // The orchestrator doesn't pick a distro /
            // profile id — the runtime hook (or the
            // WorkspaceManager) decides. For now, the
            // orchestrator emits a placeholder; the
            // integration step in Phase 68 wires the
            // actual selector.
            distroId = "__pending__",
            profileId = "__pending__",
        )
        RuntimeKind.WINDOWS_VM -> WorkspaceSession.WindowsVm(
            id = sessionId,
            displayName = displayName,
            windowsSpecId = "__pending__",
        )
        RuntimeKind.WINE_ON_LINUX -> WorkspaceSession.LinuxProot(
            // A WINE_ON_LINUX workspace is a Linux proot
            // session with a Wine prefix. The runtime
            // hook interprets the launcher + env to
            // decide the Wine invocation.
            id = sessionId,
            displayName = displayName,
            distroId = "__wine__",
            profileId = "__wine__",
        )
    }
}
