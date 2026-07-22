package com.elysium.vanguard.core.runtime.workspace_orchestrator

/**
 * PHASE 111 — the typed result of
 * [WorkspaceOrchestrator.resolveSecrets].
 *
 * The sealed class has two variants:
 *
 *  - [Success] — every secret was resolved.
 *    The [plan] is the new
 *    [OrchestratedWorkspace] with the
 *    secret values populated in
 *    [OrchestratedWorkspace.environment].
 *  - [MissingSecret] — at least one secret
 *    failed to resolve. The error carries
 *    the env name + the secret id + the
 *    cause so the runtime hook can surface
 *    a typed error to the user.
 *
 * The variants are typed (sealed class, not
 * a free-form `Result<T>`) so the runtime
 * hook pattern-matches without unwrapping
 * a generic envelope.
 */
sealed class SecretResolutionResult {
    /**
     * Every secret was resolved. The [plan]
     * has the secret values populated in
     * `environment` + the `secretEnvRefs`
     * map cleared.
     */
    data class Success(
        val plan: OrchestratedWorkspace,
    ) : SecretResolutionResult()

    /**
     * A secret failed to resolve. The
     * [envName] is the env var name the
     * workspace declared; the [secretId]
     * is the secret id in the
     * [com.elysium.vanguard.core.security.SecretStore];
     * the [cause] is the human-readable
     * reason (a missing secret, an IO
     * error, an integrity check failure).
     *
     * The runtime hook refuses to start
     * the session + surfaces the cause
     * to the user. The plan's secret refs
     * are NOT partially resolved (the
     * resolution is all-or-nothing).
     */
    data class MissingSecret(
        val envName: String,
        val secretId: String,
        val cause: String,
    ) : SecretResolutionResult()
}
