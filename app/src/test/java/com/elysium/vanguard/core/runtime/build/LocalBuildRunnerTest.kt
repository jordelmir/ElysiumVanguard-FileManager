package com.elysium.vanguard.core.runtime.build

import com.elysium.vanguard.core.runtime.runner.LaunchedProcess
import com.elysium.vanguard.core.runtime.runner.ProcessLauncher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Phase 56 — tests for [LocalBuildRunner].
 *
 * The runner consumes a [BuildRequest] + a
 * [ToolchainRegistry] and returns a
 * [BuildResult]. The tests use a
 * [FakeProcessLauncher] that records every
 * call; the runner's orchestration is
 * asserted without actually running the
 * build.
 *
 * The tests pin:
 *
 *   - The runner rejects a request with
 *     `forceRemote = true` (no remote
 *     client in Phase 56).
 *   - The runner rejects a request for a
 *     toolchain that is not installed.
 *   - The runner delegates to the launcher
 *     on a valid request.
 *   - The command line is
 *     `<toolchain binary> <user command>`.
 *   - The working directory is the
 *     project's path.
 *   - The environment variables are
 *     threaded through to the launcher.
 *   - The runner surfaces a spawn failure
 *     as a typed [LocalBuildError.SpawnFailed].
 */
class LocalBuildRunnerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var projectPath: File
    private lateinit var launcher: FakeProcessLauncher
    private lateinit var runner: LocalBuildRunner

    @Before
    fun setUp() {
        projectPath = tempFolder.newFolder("project")
        launcher = FakeProcessLauncher()
        runner = LocalBuildRunner(processLauncher = launcher)
    }

    @Test
    fun `build rejects a request with forceRemote set`() {
        val registry = makeRegistry(
            mapOf(ToolchainKind.RUST to File("/usr/bin/cargo"))
        )
        val request = BuildRequest(
            projectPath = projectPath,
            kind = ToolchainKind.RUST,
            command = listOf("build", "--release"),
            forceRemote = true
        )
        val result = runner.build(request, registry)
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is LocalBuildError.ForceRemoteWithoutRemoteClient)
    }

    @Test
    fun `build rejects a request for an un-installed toolchain`() {
        val registry = makeRegistry(emptyMap())
        val request = BuildRequest(
            projectPath = projectPath,
            kind = ToolchainKind.RUST,
            command = listOf("build", "--release")
        )
        val result = runner.build(request, registry)
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is LocalBuildError.ToolchainNotInstalled)
        assertEquals(ToolchainKind.RUST, (error as LocalBuildError.ToolchainNotInstalled).kind)
    }

    @Test
    fun `build delegates to the launcher on a valid request`() {
        val cargo = File("/usr/bin/cargo")
        val registry = makeRegistry(mapOf(ToolchainKind.RUST to cargo))
        val request = BuildRequest(
            projectPath = projectPath,
            kind = ToolchainKind.RUST,
            command = listOf("build", "--release")
        )
        val result = runner.build(request, registry)
        assertTrue(result.isSuccess)
        // The launcher was called once.
        assertEquals(1, launcher.calls.size)
        val call = launcher.calls[0]
        // The command line is
        // "<cargo> build --release".
        assertEquals(
            listOf("/usr/bin/cargo", "build", "--release"),
            call.command
        )
        // The working directory is the
        // project's path.
        assertEquals(projectPath, call.cwd)
    }

    @Test
    fun `build threads the request's environment variables through to the launcher`() {
        val cargo = File("/usr/bin/cargo")
        val registry = makeRegistry(mapOf(ToolchainKind.RUST to cargo))
        val request = BuildRequest(
            projectPath = projectPath,
            kind = ToolchainKind.RUST,
            command = listOf("build"),
            environmentVariables = mapOf(
                "CARGO_TARGET_DIR" to "/tmp/target",
                "RUSTFLAGS" to "-C opt-level=3"
            )
        )
        val result = runner.build(request, registry)
        assertTrue(result.isSuccess)
        val envMap = launcher.calls[0].env.toMap()
        assertEquals("/tmp/target", envMap["CARGO_TARGET_DIR"])
        assertEquals("-C opt-level=3", envMap["RUSTFLAGS"])
    }

    @Test
    fun `build surfaces a spawn failure as a typed SpawnFailed error`() {
        val cargo = File("/usr/bin/cargo")
        val registry = makeRegistry(mapOf(ToolchainKind.RUST to cargo))
        launcher.nextException = RuntimeException("permission denied")
        val request = BuildRequest(
            projectPath = projectPath,
            kind = ToolchainKind.RUST,
            command = listOf("build")
        )
        val result = runner.build(request, registry)
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is LocalBuildError.SpawnFailed)
        assertTrue(
            "error message should mention the cause: ${error?.message}",
            (error?.message ?: "").contains("permission denied", ignoreCase = true)
        )
    }

    @Test
    fun `BuildRequest rejects a blank project path`() {
        try {
            BuildRequest(
                projectPath = File(""),
                kind = ToolchainKind.RUST,
                command = listOf("build")
            )
            assert(false) { "expected IllegalArgumentException" }
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    @Test
    fun `BuildRequest rejects an empty command`() {
        try {
            BuildRequest(
                projectPath = projectPath,
                kind = ToolchainKind.RUST,
                command = emptyList()
            )
            assert(false) { "expected IllegalArgumentException" }
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    // --- helpers ---

    private fun makeRegistry(
        tools: Map<ToolchainKind, File>
    ): ToolchainRegistry {
        val installs = tools.mapValues { (_, file) ->
            ToolchainInstall(binaryPath = file)
        }
        return ToolchainRegistry(installed = installs)
    }
}

/**
 * Hand-rolled [ProcessLauncher] for unit
 * tests. Records every call in a
 * thread-safe list; the runner's
 * orchestration is asserted without
 * actually running the build.
 */
internal class FakeProcessLauncher(
    private var nextPid: Int = 99999
) : ProcessLauncher {

    data class Call(
        val command: List<String>,
        val env: List<Pair<String, String>>,
        val cwd: File
    )

    val calls = java.util.Collections.synchronizedList(mutableListOf<Call>())
    var nextException: Throwable? = null

    override fun start(
        command: List<String>,
        env: List<Pair<String, String>>,
        cwd: File
    ): LaunchedProcess {
        val ex = nextException
        if (ex != null) {
            nextException = null
            throw ex
        }
        calls += Call(command = command, env = env, cwd = cwd)
        return LaunchedProcess(pid = nextPid++, stop = {})
    }
}
