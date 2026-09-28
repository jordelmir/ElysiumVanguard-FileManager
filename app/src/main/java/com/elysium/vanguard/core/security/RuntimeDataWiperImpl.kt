package com.elysium.vanguard.core.security

import android.util.Log
import com.elysium.vanguard.core.database.runtime.RuntimeDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 100 — the production
 * [RuntimeDataWiper]. The class is a
 * adapter from the [RuntimeDatabase]
 * Room class to the narrow [RuntimeDataWiper]
 * interface the [KillSwitch] consumes. The
 * adapter calls `clear()` on every DAO; Room
 * is thread-safe + the call is idempotent.
 *
 * Each DAO clear is wrapped in runCatching so
 * a single DAO failure does not prevent the
 * others from being wiped. Failures are logged
 * via Android's Log.e for diagnostics.
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
        val failures = mutableListOf<String>()
        runCatching { database.distroInstallDao().clear() }
            .onFailure { failures.add("distroInstallDao: ${it.message}") }
        runCatching { database.sessionDao().clear() }
            .onFailure { failures.add("sessionDao: ${it.message}") }
        runCatching { database.applicationCapsuleDao().clear() }
            .onFailure { failures.add("applicationCapsuleDao: ${it.message}") }
        runCatching { database.hardwareAccessAuditDao().clear() }
            .onFailure { failures.add("hardwareAccessAuditDao: ${it.message}") }
        runCatching { database.diagnosticEventDao().clear() }
            .onFailure { failures.add("diagnosticEventDao: ${it.message}") }
        runCatching { database.workspaceDao().clear() }
            .onFailure { failures.add("workspaceDao: ${it.message}") }
        runCatching { database.networkRuleDao().clear() }
            .onFailure { failures.add("networkRuleDao: ${it.message}") }
        if (failures.isNotEmpty()) {
            Log.e(TAG, "RuntimeDataWiper: ${failures.size} DAO(s) failed to clear: ${failures.joinToString("; ")}")
        }
    }

    companion object {
        private const val TAG = "RuntimeDataWiper"
    }
}
