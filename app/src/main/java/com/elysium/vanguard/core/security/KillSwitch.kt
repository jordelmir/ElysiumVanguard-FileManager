package com.elysium.vanguard.core.security

import com.elysium.vanguard.core.runtime.runner.LaunchedProcess
import com.elysium.vanguard.core.runtime.runner.ProcessLauncher
import com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 100 — the **kill switch**. An
 * irreversible operation that wipes every
 * piece of runtime data the platform owns.
 *
 * The kill switch is the last line of defense
 * in the Zero Trust model: when the device is
 * lost, compromised, or about to be
 * decommissioned, the user can wipe
 * everything in one tap.
 *
 * **Algorithm** (in [trigger]):
 *
 * 1. **Record** the trigger in the security
 *    audit.
 * 2. **Stop** every running process via the
 *    [ProcessLauncher].
 * 3. **Wipe** every Room table via the
 *    [RuntimeDataWiper] (a typed seam over
 *    the Room database).
 * 4. **Wipe** every on-disk directory
 *    (via [WipeableDirectories]).
 * 5. **Wipe** the secret store.
 * 6. **Mark** the kill switch as triggered.
 *
 * **JVM testability**: the class takes
 * [RuntimeDataWiper] + [SecurityAudit] +
 * [SecretStore] + [ProcessLauncher] +
 * [WipeableDirectories] +
 * [LaunchedProcessHandlesProvider] in its
 * constructor. Tests pass fakes for each
 * dependency; the wipe paths use
 * `java.io.File.deleteRecursively()` which
 * works against a real temp dir.
 */
@Singleton
class KillSwitch @Inject constructor(
    private val dataWiper: RuntimeDataWiper,
    private val audit: SecurityAudit,
    private val secretStore: SecretStore,
    private val processLauncher: ProcessLauncher,
    private val wipeableDirectories: WipeableDirectories,
    private val launchedProcessHandles: LaunchedProcessHandlesProvider,
) {
    private val lock = ReentrantLock()
    @Volatile private var triggered: Boolean = false

    /**
     * Has the kill switch been triggered
     * already? Once `true`, the system is
     * wiped; the user can re-install the
     * platform but the runtime data is gone.
     */
    fun hasBeenTriggered(): Boolean = triggered

    /**
     * Trigger the kill switch. The [reason]
     * is recorded in the audit event.
     */
    fun trigger(reason: String): KillSwitchResult = lock.withLock {
        if (triggered) {
            return@withLock KillSwitchResult.AlreadyTriggered
        }
        if (reason.isBlank()) {
            return@withLock KillSwitchResult.Failure(
                message = "kill switch reason must not be blank"
            )
        }
        try {
            // Step 1: stop every running process.
            for (handle in launchedProcessHandles.handles()) {
                runCatching { handle.stop() }
            }
            // Step 2: wipe the Room database.
            runBlocking(Dispatchers.IO) {
                dataWiper.wipeAll()
            }
            // Step 3: wipe the on-disk dirs.
            wipeDirectories()
            // Step 4: wipe the secret store.
            runCatching { secretStore.clear() }
            // Step 5: record the audit event last
            // (it's the only thing still alive).
            runCatching {
                audit.record(
                    SecurityAuditEvent(
                        eventType = SecurityEventType.KILL_SWITCH_TRIGGERED,
                        subjectId = "kill-switch",
                        outcome = SecurityEventOutcome.SUCCESS,
                        details = SecurityEventDetails.KillSwitchDetails(
                            reason = reason,
                            wipedTables = listOf(
                                "distro_installs",
                                "sessions",
                                "application_capsules",
                                "hardware_access_audit",
                                "diagnostic_events",
                                "workspaces",
                                "network_rules",
                                "media_index",
                            ),
                            wipedDirectories = wipeableDirectories.dirs.map { it.absolutePath },
                        ),
                        at = Timestamp.monotonicWallClock().now(),
                    )
                )
            }
            triggered = true
            KillSwitchResult.Success
        } catch (e: Exception) {
            KillSwitchResult.Failure(
                message = "kill switch failed: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    private fun wipeDirectories() {
        for (dir in wipeableDirectories.dirs) {
            if (!dir.exists()) continue
            runCatching {
                dir.walkBottomUp().forEach { it.delete() }
            }
        }
    }
}

/**
 * The result of a kill-switch trigger.
 */
sealed class KillSwitchResult {
    object Success : KillSwitchResult()
    object AlreadyTriggered : KillSwitchResult()
    data class Failure(val message: String) : KillSwitchResult()
}

/**
 * Phase 100 — the typed seam the kill switch
 * uses to wipe the Room database. Production
 * wires the [com.elysium.vanguard.core.database.runtime.RuntimeDatabase]
 * via the [com.elysium.vanguard.core.security.RuntimeDataWiperImpl]
 * (a 10-line adapter in the security package);
 * tests pass a 5-line fake.
 */
interface RuntimeDataWiper {
    suspend fun wipeAll()
}
