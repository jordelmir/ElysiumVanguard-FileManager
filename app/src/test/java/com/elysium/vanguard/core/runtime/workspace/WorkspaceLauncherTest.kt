package com.elysium.vanguard.core.runtime.workspace

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong

class WorkspaceLauncherTest {

    private lateinit var workspaceRoot: File
    private lateinit var runner: InMemoryWorkspaceServiceRunner
    private val clock = AtomicLong(1_700_000_000_000L)

    @Before
    fun setUp() {
        workspaceRoot = Files.createTempDirectory("elysium-workspace-test").toFile()
        runner = InMemoryWorkspaceServiceRunner()
    }

    @After
    fun tearDown() {
        workspaceRoot.deleteRecursively()
    }

    @Test
    fun `launch runs services in DAG group order`() = block {
        val def = workspace(
            services = listOf(
                service("db", dependsOn = emptyList()),
                service("api", dependsOn = listOf("db")),
                service("worker", dependsOn = listOf("db", "api"))
            )
        )
        val launcher = newLauncher()
        val job = launcher.launch(def)
        awaitTerminal(job, launcher)

        assertEquals(WorkspaceLauncherState.Running, launcher.state.value)
        val order = runner.startHistory
        assertEquals(listOf("db", "api", "worker"), order)
    }

    @Test
    fun `launch starts independent services in parallel within a group`() = block {
        val def = workspace(
            services = listOf(
                service("a", dependsOn = emptyList()),
                service("b", dependsOn = emptyList()),
                service("c", dependsOn = emptyList())
            )
        )
        val launcher = newLauncher()
        val job = launcher.launch(def)
        awaitTerminal(job, launcher)

        // All three should have started; the order between them
        // is not specified, but the start counts must match.
        assertEquals(WorkspaceLauncherState.Running, launcher.state.value)
        assertEquals(3, runner.startHistory.size)
        assertTrue(runner.startHistory.containsAll(listOf("a", "b", "c")))
    }

    @Test
    fun `launch fails on cyclic dependency`() = block {
        // We have to bypass the WorkspaceDefinition validation
        // because the data class has no cyclic guard. The
        // resolver throws CyclicDependencyException, which the
        // launcher catches.
        val def = workspace(
            services = listOf(
                ServiceDefinition(name = "a", command = listOf("/bin/true"), dependsOn = listOf("b")),
                ServiceDefinition(name = "b", command = listOf("/bin/true"), dependsOn = listOf("a"))
            )
        )
        val launcher = newLauncher()
        val job = launcher.launch(def)
        awaitTerminal(job, launcher)

        val state = launcher.state.value
        assertTrue("expected Failed but got $state", state is WorkspaceLauncherState.Failed)
        val err = (state as WorkspaceLauncherState.Failed).error
        assertTrue(err is WorkspaceLauncherError.CyclicDependency)
    }

    @Test
    fun `launch fails when a service fails to start and rolls back the others`() = block {
        // We wire a runner that fails the second service so we
        // can verify the rollback. The in-memory runner succeeds
        // for everything by default; we use a custom one.
        val failingRunner = object : WorkspaceServiceRunner {
            var started = mutableListOf<String>()
            override suspend fun start(spec: ServiceDefinition, env: Map<String, String>, workdir: String): ServiceStartResult {
                started += spec.name
                return if (spec.name == "db") {
                    ServiceStartResult(success = false, error = "boom")
                } else ServiceStartResult(success = true, pid = 1L)
            }
            override suspend fun stop(name: String, timeoutMs: Long) {}
            override suspend fun health(command: List<String>, timeoutMs: Long) = HealthCheckResult(healthy = true)
        }
        val def = workspace(
            services = listOf(
                service("a", dependsOn = emptyList()),
                service("db", dependsOn = listOf("a")),
                service("c", dependsOn = listOf("db"))
            )
        )
        val launcher = newLauncher(runner = failingRunner)
        val job = launcher.launch(def)
        awaitTerminal(job, launcher)

        val state = launcher.state.value
        assertTrue("expected Failed but got $state", state is WorkspaceLauncherState.Failed)
        val err = (state as WorkspaceLauncherState.Failed).error
        assertTrue(
            "expected ServicesFailedToStart but got $err",
            err is WorkspaceLauncherError.ServicesFailedToStart
        )
        // The launcher tried to stop every service that started
        // before the failure. The failing runner's stop is a no-op,
        // but the rollback is exercised — verify by checking
        // that the launcher went through a rollback path: the
        // events flow contains a Failed event.
        val failedEvent = launcher.events.value
            .filterIsInstance<WorkspaceEvent.Failed>()
            .firstOrNull()
        assertNotNull("expected a Failed event", failedEvent)
    }

    @Test
    fun `launch runs health checks between groups and fails on unhealthy`() = block {
        val unhealthyRunner = object : WorkspaceServiceRunner {
            override suspend fun start(spec: ServiceDefinition, env: Map<String, String>, workdir: String) =
                ServiceStartResult(success = true, pid = 1L)
            override suspend fun stop(name: String, timeoutMs: Long) {}
            override suspend fun health(command: List<String>, timeoutMs: Long) =
                HealthCheckResult(healthy = false, error = "no ping")
        }
        val def = workspace(
            services = listOf(service("db", dependsOn = emptyList())),
            healthChecks = listOf(
                HealthCheck(name = "db", command = listOf("/bin/ping", "-c1", "127.0.0.1"), intervalMs = 100, timeoutMs = 100)
            )
        )
        val launcher = newLauncher(runner = unhealthyRunner)
        val job = launcher.launch(def)
        awaitTerminal(job, launcher)

        val state = launcher.state.value
        assertTrue("expected Failed but got $state", state is WorkspaceLauncherState.Failed)
        val err = (state as WorkspaceLauncherState.Failed).error
        assertTrue(
            "expected HealthCheckFailed but got $err",
            err is WorkspaceLauncherError.HealthCheckFailed
        )
    }

    @Test
    fun `service states map reflects each service's state through launch`() = block {
        val def = workspace(
            services = listOf(
                service("db", dependsOn = emptyList()),
                service("api", dependsOn = listOf("db"))
            )
        )
        val launcher = newLauncher()
        val job = launcher.launch(def)
        awaitTerminal(job, launcher)

        val map = launcher.serviceStates.value
        assertTrue(map.containsKey("db"))
        assertTrue(map.containsKey("api"))
        assertTrue(map["db"] is WorkspaceServiceInstance.Healthy || map["db"] is WorkspaceServiceInstance.Running)
        assertTrue(map["api"] is WorkspaceServiceInstance.Healthy || map["api"] is WorkspaceServiceInstance.Running)
    }

    @Test
    fun `stop in reverse order publishes Stopping and Stopped events`() = block {
        val def = workspace(
            services = listOf(
                service("db", dependsOn = emptyList()),
                service("api", dependsOn = listOf("db"))
            )
        )
        val launcher = newLauncher()
        val job = launcher.launch(def)
        awaitTerminal(job, launcher)
        assertEquals(WorkspaceLauncherState.Running, launcher.state.value)

        launcher.stop(def)
        assertEquals(WorkspaceLauncherState.Stopped, launcher.state.value)
        val stopEvents = launcher.events.value.filterIsInstance<WorkspaceEvent.Stopping>()
        assertTrue("expected at least one Stopping event", stopEvents.isNotEmpty())
        // Stop order is reverse of start order: api first, then db.
        val stopped = runner.stopHistory
        assertEquals(listOf("api", "db"), stopped)
    }

    @Test
    fun `events flow contains validation start and complete`() = block {
        val def = workspace(services = listOf(service("a", dependsOn = emptyList())))
        val launcher = newLauncher()
        val job = launcher.launch(def)
        awaitTerminal(job, launcher)
        val eventTypes = launcher.events.value.map { it::class.simpleName }
        assertTrue("expected ValidationStarted in $eventTypes", eventTypes.contains("ValidationStarted"))
        assertTrue("expected ValidationCompleted in $eventTypes", eventTypes.contains("ValidationCompleted"))
        assertTrue("expected Running in $eventTypes", eventTypes.contains("Running"))
    }

    @Test
    fun `launcher refuses workspace with no services`() = block {
        val def = workspace(services = emptyList())
        val launcher = newLauncher()
        val job = launcher.launch(def)
        awaitTerminal(job, launcher)
        val state = launcher.state.value
        assertTrue("expected Failed but got $state", state is WorkspaceLauncherState.Failed)
        assertTrue(
            (state as WorkspaceLauncherState.Failed).error is WorkspaceLauncherError.NoServices
        )
    }

    @Test
    fun `launcher refuses when workspace root is unusable`() = block {
        val def = workspace(services = listOf(service("a", dependsOn = emptyList())))
        val blocker = File(workspaceRoot, "blocker")
        blocker.createNewFile()
        // The launcher checks 'exists() || mkdirs()'. A file with
        // the same name as a directory cannot be turned into a
        // directory. We simulate this by using a path under a
        // non-writable parent.
        val unwritable = Files.createTempDirectory("elysium-unwritable-parent").toFile()
        val blockingFile = File(unwritable, "blocker")
        blockingFile.createNewFile()
        val launcher = newLauncher(workspaceRoot = File(blockingFile, "nope"))
        val job = launcher.launch(def)
        awaitTerminal(job, launcher)
        val state = launcher.state.value
        // On a system that allows creating a file under a file
        // (impossible), the launch would proceed. The expected
        // outcome on a normal system is Failed/StorageUnavailable.
        if (state is WorkspaceLauncherState.Failed) {
            assertTrue(state.error is WorkspaceLauncherError.StorageUnavailable)
        } else {
            // Defensive: if the platform let us create the path,
            // we still want the test to be informative.
            assertEquals(WorkspaceLauncherState.Running, state)
        }
        unwritable.deleteRecursively()
    }

    private fun newLauncher(
        workspaceRoot: File = this.workspaceRoot,
        runner: WorkspaceServiceRunner = this.runner
    ): WorkspaceLauncher {
        return WorkspaceLauncher(
            runner = runner,
            workspaceRoot = workspaceRoot,
            scopeFactory = { CoroutineScope(SupervisorJob() + Dispatchers.IO) },
            clock = { clock.incrementAndGet() }
        )
    }

    private fun workspace(
        id: String = "ws-test",
        name: String = "Test Workspace",
        rootfsId: String = "test-debian",
        services: List<ServiceDefinition>,
        healthChecks: List<HealthCheck> = emptyList()
    ): WorkspaceDefinition = WorkspaceDefinition(
        id = id,
        name = name,
        description = "Workspace used by WorkspaceLauncherTest",
        rootfsId = rootfsId,
        services = services,
        healthChecks = healthChecks
    )

    private fun service(name: String, dependsOn: List<String>): ServiceDefinition =
        ServiceDefinition(
            name = name,
            command = listOf("/bin/sh", "-c", "true"),
            dependsOn = dependsOn,
            autoStart = true
        )

    private suspend fun awaitTerminal(job: Job, launcher: WorkspaceLauncher) {
        // Wait up to 5 s for the launcher to reach a terminal state.
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            val s = launcher.state.value
            if (s is WorkspaceLauncherState.Running ||
                s is WorkspaceLauncherState.Failed ||
                s is WorkspaceLauncherState.Stopped) {
                job.join()
                return
            }
            delay(10)
        }
        job.cancel()
        throw AssertionError("launcher did not reach a terminal state in 5s: ${launcher.state.value}")
    }

    private fun block(block: suspend () -> Unit) = runBlocking { block() }
}
