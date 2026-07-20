package com.elysium.vanguard.core.security

import com.elysium.vanguard.core.runtime.runner.LaunchedProcess
import com.elysium.vanguard.core.runtime.runner.ProcessLauncher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 100 — the test suite for the
 * [KillSwitch]. The kill switch wipes the
 * Room database, the on-disk wipeable
 * directories, the secret store, and stops
 * every running process. The tests use
 * in-memory fakes for the data wiper + audit +
 * secret store; the wipe paths use a real
 * [org.junit.rules.TemporaryFolder] so the
 * deletion is real.
 */
class KillSwitchTest {

    @get:Rule
    val tmp = org.junit.rules.TemporaryFolder()

    private lateinit var fakeDataWiper: FakeDataWiper
    private lateinit var audit: SecurityAudit
    private lateinit var secretStore: SecretStore
    private lateinit var fakeLauncher: FakeProcessLauncher
    private lateinit var wipeableDirs: List<File>
    private lateinit var killSwitch: KillSwitch

    @Before
    fun setUp() {
        fakeDataWiper = FakeDataWiper()
        audit = SecurityAudit()
        secretStore = SecretStore(audit = audit)
        fakeLauncher = FakeProcessLauncher()
        wipeableDirs = listOf(
            File(tmp.root, "workspaces").also { it.mkdirs() },
            File(tmp.root, "distros").also { it.mkdirs() },
        )
        killSwitch = KillSwitch(
            dataWiper = fakeDataWiper,
            audit = audit,
            secretStore = secretStore,
            processLauncher = fakeLauncher,
            wipeableDirectories = WipeableDirectories(wipeableDirs),
            launchedProcessHandles = LaunchedProcessHandlesProvider { fakeLauncher.handles },
        )
    }

    @After
    fun tearDown() {
        tmp.root.deleteRecursively()
    }

    @Test
    fun `trigger wipes the database, directories, and secrets`() = runTest {
        // Populate the on-disk dirs.
        File(wipeableDirs[0], "workspace-1.json").writeText("data")
        File(wipeableDirs[1], "debian-12/rootfs/etc/os-release").also {
            it.parentFile?.mkdirs()
            it.writeText("NAME=debian")
        }
        // Populate the secret store.
        secretStore.put(
            secretId = "vault-pass",
            secretType = SecretType.VAULT_PASSPHRASE,
            value = byteArrayOf(1, 2, 3),
            accessReason = "test"
        )
        assertEquals(1, secretStore.count())

        val result = killSwitch.trigger(reason = "device-lost")

        assertEquals(KillSwitchResult.Success, result)
        assertEquals(1, fakeDataWiper.wipeCount)
        assertEquals(0, secretStore.count())
        // The on-disk dirs are wiped.
        assertTrue(
            "wipeable dir should be empty after kill switch: ${wipeableDirs[0]}",
            (wipeableDirs[0].listFiles() ?: emptyArray()).isEmpty()
        )
        // The audit log records the trigger.
        val events = audit.all()
        val killEvents = events.filter { it.eventType == SecurityEventType.KILL_SWITCH_TRIGGERED }
        assertEquals(1, killEvents.size)
        assertEquals(SecurityEventOutcome.SUCCESS, killEvents[0].outcome)
        val details = killEvents[0].details as SecurityEventDetails.KillSwitchDetails
        assertEquals("device-lost", details.reason)
        assertTrue(
            "wipedTables must include 'distro_installs': ${details.wipedTables}",
            details.wipedTables.contains("distro_installs")
        )
    }

    @Test
    fun `trigger stops every running process before wiping`() = runTest {
        val stoppedCount = AtomicInteger(0)
        fakeLauncher.handles += LaunchedProcess(pid = 1, stop = { stoppedCount.incrementAndGet() })
        fakeLauncher.handles += LaunchedProcess(pid = 2, stop = { stoppedCount.incrementAndGet() })
        fakeLauncher.handles += LaunchedProcess(pid = 3, stop = { stoppedCount.incrementAndGet() })

        val result = killSwitch.trigger(reason = "device-decommissioned")

        assertEquals(KillSwitchResult.Success, result)
        assertEquals(3, stoppedCount.get())
    }

    @Test
    fun `trigger rejects a blank reason`() = runTest {
        val result = killSwitch.trigger(reason = "")
        assertTrue(result is KillSwitchResult.Failure)
        // The database is untouched.
        assertEquals(0, fakeDataWiper.wipeCount)
    }

    @Test
    fun `trigger is idempotent — second call returns AlreadyTriggered`() = runTest {
        val first = killSwitch.trigger(reason = "first")
        val second = killSwitch.trigger(reason = "second")
        assertEquals(KillSwitchResult.Success, first)
        assertEquals(KillSwitchResult.AlreadyTriggered, second)
    }

    @Test
    fun `hasBeenTriggered returns true after a successful trigger`() = runTest {
        assertEquals(false, killSwitch.hasBeenTriggered())
        killSwitch.trigger(reason = "test")
        assertEquals(true, killSwitch.hasBeenTriggered())
    }

    @Test
    fun `trigger continues wiping even when one directory fails`() = runTest {
        // Make one of the dirs read-only to
        // simulate a permission failure; the
        // kill switch should still wipe the
        // other dir + the database.
        val readOnlyDir = File(wipeableDirs[1], "ro")
        readOnlyDir.mkdirs()
        readOnlyDir.setWritable(false)
        File(wipeableDirs[0], "data.json").writeText("keep me wiped")

        val result = killSwitch.trigger(reason = "test")

        assertEquals(KillSwitchResult.Success, result)
        // The first dir is wiped.
        assertTrue(
            "wipeable dir should be empty: ${wipeableDirs[0]}",
            (wipeableDirs[0].listFiles() ?: emptyArray()).isEmpty()
        )
        // The audit log was recorded (the kill
        // switch is robust to per-directory
        // failures).
        val events = audit.all()
        val killEvents = events.filter { it.eventType == SecurityEventType.KILL_SWITCH_TRIGGERED }
        assertEquals(1, killEvents.size)
    }
}

/**
 * A 5-line fake for the [RuntimeDataWiper]
 * seam. The test surface is the `wipeCount`
 * property + the `wipeAll()` method.
 */
private class FakeDataWiper : RuntimeDataWiper {
    var wipeCount: Int = 0
    override suspend fun wipeAll() { wipeCount++ }
}

/**
 * A fake [ProcessLauncher] that records the
 * handles the kill switch should stop. The
 * `start()` method is never called by the
 * kill switch.
 */
private class FakeProcessLauncher : ProcessLauncher {
    val handles: MutableList<LaunchedProcess> = mutableListOf()
    override fun start(command: List<String>, env: List<Pair<String, String>>, cwd: File): LaunchedProcess {
        throw UnsupportedOperationException("not used in kill switch tests")
    }
}
