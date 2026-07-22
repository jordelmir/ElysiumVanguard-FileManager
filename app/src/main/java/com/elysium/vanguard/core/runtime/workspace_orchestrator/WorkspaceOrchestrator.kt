package com.elysium.vanguard.core.runtime.workspace_orchestrator

import com.elysium.vanguard.core.runtime.network.policy.NetworkPolicy
import com.elysium.vanguard.core.runtime.network.policy.NetworkPolicySpecBridge
import com.elysium.vanguard.core.runtime.workspace_def.EnvSpec
import com.elysium.vanguard.core.runtime.workspace_def.RuntimeKind
import com.elysium.vanguard.core.runtime.workspace_def.WorkspaceDefinition
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceSession
import com.elysium.vanguard.core.security.SecretResolver
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

        // 2. Translate the env. The orchestrator emits
        //    two artifacts:
        //
        //    - `environment` — a Map<String, String>
        //      with the **literal values** (the
        //      `value` field of every EnvSpec). The
        //      secret-id entries show up here as the
        //      secret id (the orchestrator does NOT
        //      resolve secrets — that's a separate
        //      step in [resolveSecrets]).
        //    - `secretEnvRefs` — a Map<envName,
        //      secretId> with the **secret-id refs**.
        //      The runtime hook reads this map to
        //      know which env names need resolution
        //      at session start.
        //
        //    Duplicate names are not allowed (the
        //    WorkspaceDefinition's init check rejects
        //    them). A workspace cannot have both a
        //    literal `FOO` and a secret `FOO` — the
        //    init check on [EnvSpec] rejects a
        //    duplicate name.
        val environment = definition.env.associate { spec ->
            spec.name to spec.value
        }
        val secretEnvRefs = definition.env
            .filter { it.secret }
            .associate { spec -> spec.name to spec.value }

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

        // 5. PHASE 109 — translate the network
        //    policy. The workspace spec carries a
        //    [com.elysium.vanguard.core.runtime.workspace_def.NetworkPolicySpec]
        //    (DENY_ALL / ALLOW_LIST / ALLOW_ALL).
        //    The orchestrator bridges it to a
        //    session-level [NetworkPolicy] via
        //    [NetworkPolicySpecBridge]. The default
        //    (LOOPBACK_ONLY) is the safe direction
        //    — a workspace that doesn't declare a
        //    network policy gets the deny-by-default
        //    posture.
        val networkPolicy = NetworkPolicySpecBridge.toSessionPolicy(definition.network)

        // 6. Build the session. The session id is a fresh
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
            secretEnvRefs = secretEnvRefs,
            networkPolicy = networkPolicy,
        )
    }

    /**
     * PHASE 111 — resolve the secret env vars
     * in [plan] via [resolver]. The function
     * returns a new [OrchestratedWorkspace]
     * with the secret values populated in
     * [OrchestratedWorkspace.environment] +
     * the [OrchestratedWorkspace.secretEnvRefs]
     * cleared (the secrets are now resolved).
     *
     * **Algorithm**:
     *
     *  1. Iterate [plan].secretEnvRefs.
     *  2. For each (envName, secretId) pair,
     *     call `resolver.resolve(secretId)`.
     *  3. On success, store the resolved
     *     value in the new `environment`
     *     map (overwriting the placeholder
     *     secret id that [orchestrate]
     *     emitted).
     *  4. On failure, return the typed
     *     [SecretResolutionError] — the
     *     runtime hook refuses to start the
     *     session.
     *
     * **Why fail-closed (a missing secret
     * aborts the resolution)**: the user's
     * intent is "this workspace needs
     * secret FOO at session start". A
     * missing secret is a misconfiguration
     * (the user forgot to set up the secret,
     * or the secret was deleted). Silently
     * passing an empty string would mask
     * the misconfiguration + the workspace
     * would fail later with a confusing
     * "auth failed" error from the app.
     *
     * **Why a pure function (not a member
     * of the resolver)**: the orchestrator
     * owns the typed plan; the resolver is
     * just an I/O seam. The orchestrator
     * decides what to do with the resolver's
     * output (emit a new plan, surface an
     * error, etc.).
     */
    fun resolveSecrets(
        plan: OrchestratedWorkspace,
        resolver: SecretResolver,
    ): SecretResolutionResult {
        if (plan.secretEnvRefs.isEmpty()) {
            // No secrets to resolve. The plan
            // is already in its resolved form.
            return SecretResolutionResult.Success(plan)
        }
        val resolved = plan.environment.toMutableMap()
        for ((envName, secretId) in plan.secretEnvRefs) {
            val resolvedValue = resolver.resolve(secretId)
            val applied: SecretResolutionResult? = resolvedValue.fold(
                onSuccess = { value ->
                    resolved[envName] = value
                    null
                },
                onFailure = { error ->
                    SecretResolutionResult.MissingSecret(
                        envName = envName,
                        secretId = secretId,
                        cause = error.message ?: error::class.java.simpleName,
                    )
                },
            )
            if (applied != null) {
                // A non-null result is an error
                // (the success branch returns
                // null to signal "continue"). The
                // resolution is fail-closed: the
                // first missing secret aborts the
                // resolution; the plan's secret
                // refs are NOT partially resolved.
                return applied
            }
        }
        return SecretResolutionResult.Success(
            plan.copy(
                environment = resolved.toMap(),
                secretEnvRefs = emptyMap(),
            )
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
