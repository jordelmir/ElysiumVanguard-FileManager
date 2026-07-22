package com.elysium.vanguard.core.security

import javax.inject.Inject
import javax.inject.Singleton

/**
 * PHASE 111 — the **production** [SecretResolver]
 * implementation. Reads from the
 * [SecretStore].
 *
 * The resolver is a thin shell over the
 * [SecretStore] + UTF-8 decoding:
 *
 *  1. Read the secret via [SecretStore.get] with
 *     the [accessReason] `"workspace-session-start"`.
 *     The store audits every read.
 *  2. Decode the bytes as UTF-8. The platform
 *     stores secrets as bytes; the orchestrator
 *     expects strings.
 *  3. Surface the typed [com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError]
 *     from the store as [Result.failure].
 *
 * **Why a separate class (not just calling
 * `SecretStore.get` from the orchestrator)**:
 * the orchestrator is pure-domain. The
 * [SecretStore] is I/O. Separating the two
 * keeps the orchestrator testable + the
 * production wiring straightforward.
 *
 * **Why `@Singleton`**: the resolver holds
 * no state; one instance is enough for the
 * whole app. A future phase adds a TTL cache
 * for hot secrets (the resolver becomes a
 * `@Reusable` + the cache lives in the
 * resolver).
 */
@Singleton
class SecretStoreResolver @Inject constructor(
    private val secretStore: SecretStore,
) : SecretResolver {

    override fun resolve(secretId: String): Result<String> {
        val bytes = secretStore.get(
            secretId = secretId,
            accessReason = "workspace-session-start",
        )
        return bytes.mapCatching { raw ->
            String(raw, Charsets.UTF_8)
        }
    }
}
