package com.elysium.vanguard.core.security

/**
 * PHASE 111 — the **secret resolver** facade.
 *
 * The resolver is the seam between the
 * [com.elysium.vanguard.core.runtime.workspace_orchestrator.WorkspaceOrchestrator]
 * (pure-domain, no I/O) and the
 * [SecretStore] (I/O, audited). The orchestrator
 * produces an [com.elysium.vanguard.core.runtime.workspace_orchestrator.OrchestratedWorkspace]
 * with a list of `secretEnvRefs` (env name →
 * secret id). The runtime hook calls
 * [com.elysium.vanguard.core.runtime.workspace_orchestrator.WorkspaceOrchestrator.resolveSecrets]
 * with a [SecretResolver] + the plan + the
 * resolver returns a new plan with the secret
 * values resolved.
 *
 * **Why a facade (not calling SecretStore
 * directly)**: the resolver abstracts the
 * secret backend. A future phase adds a
 * "remote vault" impl (e.g. a Vault server
 * reachable over the local network) that
 * implements the same interface. The
 * orchestrator + the runtime hook do not
 * change.
 *
 * **Why `suspend` (in a future phase)**: the
 * secret backend may be remote (a network
 * round-trip). Marking the function `suspend`
 * is forward-compatible. The current
 * [SecretStoreResolver] is CPU-only (in-memory
 * map) but the signature is forward-compatible.
 *
 * **Why `Result<String>` (not `String?`)**: a
 * missing secret is a typed failure (the plan
 * cannot be started; the user's intent is
 * "resolve this secret at session start" +
 * the secret must exist). The `Result` makes
 * the failure mode explicit + the orchestrator
 * can map it to a typed
 * [com.elysium.vanguard.core.runtime.workspace_orchestrator.SecretResolutionError].
 */
interface SecretResolver {
    /**
     * Resolve [secretId] to its value. The
     * returned `String` is the UTF-8
     * decoding of the secret bytes.
     *
     * The function is **audited**: every
     * successful + failed resolution
     * produces a [SecurityAudit] event.
     *
     * On failure (missing secret, IO error,
     * integrity check failure), the result is
     * [Result.failure] with the typed error
     * envelope (the
     * [com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError]
     * the [SecretStore] returned).
     */
    fun resolve(secretId: String): Result<String>
}
