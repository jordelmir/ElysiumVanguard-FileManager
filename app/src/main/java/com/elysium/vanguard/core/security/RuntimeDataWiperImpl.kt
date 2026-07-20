package com.elysium.vanguard.core.security

import com.elysium.vanguard.core.database.runtime.RuntimeDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 100 — the production
 * [RuntimeDataWiper]. The class is a
 * 10-line adapter from the [RuntimeDatabase]
 * Room class to the narrow [RuntimeDataWiper]
 * interface the [KillSwitch] consumes. The
 * adapter calls `clear()` on every DAO; Room
 * is thread-safe + the call is idempotent.
 *
 * **JVM testability**: the [KillSwitch] takes
 * the [RuntimeDataWiper] interface; production
 * wires this implementation; tests pass a
 * 5-line fake.
 */
@Singleton
class RuntimeDataWiperImpl @Inject constructor(
    private val database: RuntimeDatabase,
) : RuntimeDataWiper {
    override suspend fun wipeAll() {
        runCatching { database.distroInstallDao().clear() }
        runCatching { database.sessionDao().clear() }
        runCatching { database.applicationCapsuleDao().clear() }
        runCatching { database.hardwareAccessAuditDao().clear() }
        runCatching { database.diagnosticEventDao().clear() }
        runCatching { database.workspaceDao().clear() }
        runCatching { database.networkRuleDao().clear() }
    }
}
