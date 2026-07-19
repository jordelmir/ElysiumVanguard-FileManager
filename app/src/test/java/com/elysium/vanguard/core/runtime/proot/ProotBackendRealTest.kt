package com.elysium.vanguard.core.runtime.proot

import com.elysium.vanguard.core.runtime.distros.DesktopProfile
import com.elysium.vanguard.core.runtime.distros.Distro
import com.elysium.vanguard.core.runtime.distros.DistroFamily
import com.elysium.vanguard.core.runtime.distros.DistroInstallation
import com.elysium.vanguard.core.runtime.distros.RootfsKind
import com.elysium.vanguard.core.runtime.distros.launcher.DistroLauncher
import com.elysium.vanguard.core.runtime.distros.launcher.LauncherCapabilities
import com.elysium.vanguard.core.runtime.distros.launcher.LauncherKind
import com.elysium.vanguard.core.runtime.distros.launcher.LauncherPick
import com.elysium.vanguard.core.runtime.observability.RuntimeEventBus
import com.elysium.vanguard.core.runtime.observability.SynchronizedEventBus
import com.elysium.vanguard.core.runtime.runner.DistroSessionBackend
import com.elysium.vanguard.core.runtime.runner.LaunchedProcess
import com.elysium.vanguard.core.runtime.runner.ProcessLauncher
import com.elysium.vanguard.core.runtime.workspace_orchestrator.BindMount
import com.elysium.vanguard.core.runtime.workspaces.FileWorkspaceStore
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceManager
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Phase 71 — the JVM-side tests for
 * [ProotBackendReal].
 *
 * The [ProotBackendReal] is the production proot
 * backend; its job is to translate the orchestrator's
 * typed contract (the [BindMount] list + the
 * launch command + the environment map) into a proot
 * invocation. The translation is pure logic; it
 * doesn't need an actual proot binary. The tests
 * stand up a hand-rolled [DistroSessionBackend] + a
 * hand-rolled [ProcessLauncher] + a
 * [WorkspaceManager] and assert the translation is
 * correct.
 *
 * Why this is on the JVM (not the androidTest): the
 * `ProotBackendReal` is testable end-to-end without
 * standing up the proot binary. The androidTest
 * (`CriticalE2EInstrumentedTest`) runs the *full*
 * orchestrator against the real backend on a real
 * device. The JVM test asserts the translation
 * rules (e.g. "the orchestrator's bindMounts are
 * injected as `-b host:container` flags after the
 * `-r <rootfs>` pair") without needing a device.
 */
class ProotBackendRealTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newSession(id: String = "sess-1", distroId: String = "elysium-linux-1"): WorkspaceSession =
        WorkspaceSession.LinuxProot(
            id = id,
            displayName = "E2E Test",
            distroId = distroId,
            profileId = distroId,
        )

    // ============================================================
    // Translation: bindMounts -> proot -b flags
    // ============================================================

    @Test
    fun `launch injects bindMounts as proot -b flags after -r rootfs`() {
        val recorder = RecordingProcessLauncher()
        val setup = newSetup(recorder)
        val backend = setup.backend
        val workspaceManager = setup.workspaceManager
        val proot = ProotBackendReal(backend, recorder, workspaceManager)

        val session = newSession()
        val result = proot.launch(
            workspaceId = "ws-1",
            session = session,
            executable = "/usr/bin/e2e-test",
            args = listOf("--benchmark"),
            workingDirectory = "/workspace/projects",
            bindMounts = listOf(
                BindMount(
                    hostPath = "/sdcard/ElysiumVanguard/projects",
                    containerPath = "/workspace/projects",
                    readOnly = false,
                ),
            ),
            environment = mapOf("DISPLAY" to ":0"),
        )
        assertTrue("expected success, got $result", result.isSuccess)

        // The proot command was invoked with the
        // expected argv. The launcher's `buildShellCommand`
        // returns a specific structure; we assert the
        // bindMount was injected.
        val argv = recorder.lastCommand
        assertTrue("expected -b /sdcard/ElysiumVanguard/projects:/workspace/projects in argv, got $argv",
            argv.contains("-b") &&
                argv.contains("/sdcard/ElysiumVanguard/projects:/workspace/projects"))
        // The mount flag is positioned after the `-r <rootfs>` pair.
        val rIndex = argv.indexOf("-r")
        assertTrue("expected -r in argv", rIndex >= 0)
        val mountArgIndex = argv.indexOf("/sdcard/ElysiumVanguard/projects:/workspace/projects")
        assertTrue("expected mount arg after -r, got $argv", mountArgIndex > rIndex)
    }

    @Test
    fun `launch with no bindMounts leaves the launcher command unchanged`() {
        val recorder = RecordingProcessLauncher()
        val setup = newSetup(recorder)
        val proot = ProotBackendReal(setup.backend, recorder, setup.workspaceManager)

        val result = proot.launch(
            workspaceId = "ws-1",
            session = newSession(),
            executable = "/usr/bin/e2e-test",
            args = emptyList(),
            workingDirectory = "/workspace/projects",
            bindMounts = emptyList(),
            environment = emptyMap(),
        )
        assertTrue("expected success, got $result", result.isSuccess)

        val argv = recorder.lastCommand
        // The orchestrator didn't add any mounts, but
        // the launcher's buildShellCommand already
        // includes its standard `-b /dev /proc /sys`.
        // We assert no orchestrator mounts were
        // added (the orchestrator's mounts would be
        // `/sdcard/...` or `/workspace/...`).
        assertTrue("unexpected orchestrator mount in argv: $argv",
            argv.none { it.startsWith("/sdcard/") || it.startsWith("/workspace/") })
    }

    // ============================================================
    // Translation: executable + args + cwd -> script
    // ============================================================

    @Test
    fun `launch builds a script that cds to the working directory and runs the executable with args`() {
        val recorder = RecordingProcessLauncher()
        val setup = newSetup(recorder)
        val proot = ProotBackendReal(setup.backend, recorder, setup.workspaceManager)

        proot.launch(
            workspaceId = "ws-1",
            session = newSession(),
            executable = "/usr/bin/blender",
            args = listOf("--background", "--python /tmp/script.py"),
            workingDirectory = "/workspace/projects",
            bindMounts = emptyList(),
            environment = emptyMap(),
        )

        // The script is the last argument of the
        // launcher command (the launcher wraps it
        // in `sh -lc "<script>"`).
        val argv = recorder.lastCommand
        val script = argv.last()
        assertTrue("expected script to cd to /workspace/projects, got: $script",
            script.contains("cd /workspace/projects"))
        assertTrue("expected script to exec /usr/bin/blender, got: $script",
            script.contains("/usr/bin/blender"))
        // Args with spaces are single-quoted.
        assertTrue("expected --background in script, got: $script",
            script.contains("--background"))
        assertTrue("expected --python /tmp/script.py quoted, got: $script",
            script.contains("'--python /tmp/script.py'"))
    }

    // ============================================================
    // Translation: environment (orchestrator's env wins)
    // ============================================================

    @Test
    fun `launch merges environment with the launcher's vars and orchestrator env wins`() {
        val recorder = RecordingProcessLauncher()
        val setup = newSetup(recorder)
        val proot = ProotBackendReal(setup.backend, recorder, setup.workspaceManager)

        proot.launch(
            workspaceId = "ws-1",
            session = newSession(),
            executable = "/usr/bin/e2e-test",
            args = emptyList(),
            workingDirectory = "/workspace/projects",
            bindMounts = emptyList(),
            environment = mapOf(
                "DISPLAY" to ":0",          // orchestrator's DISPLAY overrides
                "USER_ELYSIUM" to "juan",  // orchestrator-only
            ),
        )

        val env = recorder.lastEnv
        // The orchestrator's DISPLAY wins.
        assertEquals(":0", env.firstOrNull { it.first == "DISPLAY" }?.second)
        // The orchestrator's USER_ELYSIUM is included.
        assertEquals("juan", env.firstOrNull { it.first == "USER_ELYSIUM" }?.second)
    }

    // ============================================================
    // Stop
    // ============================================================

    @Test
    fun `stop removes the handle and invokes the stop callback`() {
        val recorder = RecordingProcessLauncher()
        val setup = newSetup(recorder)
        val proot = ProotBackendReal(setup.backend, recorder, setup.workspaceManager)

        proot.launch(
            workspaceId = "ws-1",
            session = newSession(),
            executable = "/usr/bin/e2e-test",
            args = emptyList(),
            workingDirectory = "/workspace/projects",
            bindMounts = emptyList(),
            environment = emptyMap(),
        )

        val result = proot.stop("ws-1", newSession())
        assertTrue(result.isSuccess)
        // The recorder observed the stop callback.
        assertEquals(1, recorder.stopCount)
    }

    @Test
    fun `stop without a prior launch is a no-op success`() {
        val recorder = RecordingProcessLauncher()
        val setup = newSetup(recorder)
        val proot = ProotBackendReal(setup.backend, recorder, setup.workspaceManager)

        val result = proot.stop("ws-1", newSession("unknown"))
        assertTrue(result.isSuccess)
        // The recorder observed no stop callback.
        assertEquals(0, recorder.stopCount)
    }

    // ============================================================
    // Launch failure paths
    // ============================================================

    @Test
    fun `launch fails when the session is not a LinuxProot session`() {
        val recorder = RecordingProcessLauncher()
        val setup = newSetup(recorder)
        val proot = ProotBackendReal(setup.backend, recorder, setup.workspaceManager)

        val winSession = WorkspaceSession.WindowsVm(
            id = "sess-win",
            displayName = "Win",
            windowsSpecId = "win-spec",
        )
        val result = proot.launch(
            workspaceId = "ws-1",
            session = winSession,
            executable = "/usr/bin/e2e-test",
            args = emptyList(),
            workingDirectory = "/workspace/projects",
            bindMounts = emptyList(),
            environment = emptyMap(),
        )
        assertTrue("expected failure, got $result", result.isFailure)
        assertEquals(0, recorder.startCount)
    }

    @Test
    fun `launch fails when the distro is not installed`() {
        val recorder = RecordingProcessLauncher()
        val setup = newSetup(recorder)
        // The default fake returns an installation;
        // override to return null.
        val backend = object : DistroSessionBackend by setup.backend {
            override fun findInstalled(id: String): DistroInstallation? = null
        }
        val proot = ProotBackendReal(backend, recorder, setup.workspaceManager)

        val result = proot.launch(
            workspaceId = "ws-1",
            session = newSession(),
            executable = "/usr/bin/e2e-test",
            args = emptyList(),
            workingDirectory = "/workspace/projects",
            bindMounts = emptyList(),
            environment = emptyMap(),
        )
        assertTrue("expected failure, got $result", result.isFailure)
        assertEquals(0, recorder.startCount)
    }

    @Test
    fun `launch fails when the process launcher throws`() {
        val throwing = ThrowingProcessLauncher()
        val setup = newSetup(throwing)
        val proot = ProotBackendReal(setup.backend, throwing, setup.workspaceManager)

        val result = proot.launch(
            workspaceId = "ws-1",
            session = newSession(),
            executable = "/usr/bin/e2e-test",
            args = emptyList(),
            workingDirectory = "/workspace/projects",
            bindMounts = emptyList(),
            environment = emptyMap(),
        )
        assertTrue("expected failure, got $result", result.isFailure)
    }

    // ============================================================
    // Setup helpers
    // ============================================================

    private data class Setup(
        val backend: DistroSessionBackend,
        val workspaceManager: WorkspaceManager,
    )

    private fun newSetup(@Suppress("UNUSED_PARAMETER") recorder: ProcessLauncher): Setup {
        val rootDir = tempFolder.newFolder("elysium-linux-1")
        val rootfsDir = File(rootDir, "rootfs").also { it.mkdirs() }
        val distro = Distro(
            id = "elysium-linux-1",
            displayName = "Elysium Linux 1",
            family = DistroFamily.ARCH,
            version = "1.0.0",
            approxSizeBytes = 1_500_000_000L,
            minAndroidVersion = 26,
            rootfsUrl = "https://example.invalid/elysium-linux-1.tar.gz",
            rootfsKind = RootfsKind.TarGz,
            bootstrapCommand = null,
            packageManager = "pacman",
            homepage = "https://elysium.example",
            desktopProfile = DesktopProfile.TTY,
        )
        val installation = DistroInstallation(
            distro = distro,
            rootDir = rootDir,
            rootfsDir = rootfsDir,
            installedAtEpochMs = 1_700_000_000_000L,
            sizeOnDiskBytes = 1_500_000_000L,
            lastError = null,
        )
        val launcher = ScriptRecordingLauncher(
            builtShellCommandReturn = listOf(
                "/data/proot",
                "--kill-on-exit",
                "--link2symlink",
                "-0",
                "-r",
                rootfsDir.absolutePath,
                "-b",
                "/dev",
                "-b",
                "/proc",
                "-b",
                "/sys",
                "-w",
                "/root",
                "/usr/bin/env",
                "-i",
                "HOME=/root",
                "USER=root",
                "/bin/sh",
                "-lc",
                "PLACEHOLDER",
            ),
            envReturn = listOf(
                "LD_LIBRARY_PATH" to "/data/proot",
                "PROOT_LOADER" to "/data/proot/loader",
            ),
        )
        val backend = FakeDistroSessionBackend(installation, launcher)
        val store = FileWorkspaceStore(tempFolder.newFolder("ws-store"))
        val eventBus: RuntimeEventBus = SynchronizedEventBus()
        val workspaceManager = WorkspaceManager(
            store = store,
            eventBus = eventBus,
        )
        return Setup(backend, workspaceManager)
    }
}

/**
 * Records the last command + env that the launcher
 * was asked to spawn. Also records the count of
 * `start` + `stop` invocations.
 */
private class RecordingProcessLauncher : ProcessLauncher {
    var startCount: Int = 0
        private set
    var stopCount: Int = 0
        private set
    var lastCommand: List<String> = emptyList()
        private set
    var lastEnv: List<Pair<String, String>> = emptyList()
        private set
    var lastCwd: File? = null
        private set

    override fun start(command: List<String>, env: List<Pair<String, String>>, cwd: File): LaunchedProcess {
        startCount += 1
        lastCommand = command
        lastEnv = env
        lastCwd = cwd
        return LaunchedProcess(pid = 99999) { stopCount += 1 }
    }
}

/**
 * Always throws on start. Used to assert the
 * `ProotBackendReal` surfaces IOExceptions as a
 * `Result.failure`.
 */
private class ThrowingProcessLauncher : ProcessLauncher {
    override fun start(command: List<String>, env: List<Pair<String, String>>, cwd: File): LaunchedProcess {
        throw java.io.IOException("simulated fork failure")
    }
}

/**
 * A minimal [DistroLauncher] that returns a canned
 * command list. Used to verify the
 * `ProotBackendReal`'s translation (the test asserts
 * the bindMounts are injected at the right place in
 * the canned argv).
 */
private class ScriptRecordingLauncher(
    private val builtShellCommandReturn: List<String>,
    private val envReturn: List<Pair<String, String>>,
) : DistroLauncher {
    override val kind: LauncherKind = LauncherKind.NATIVE_PROOT
    override val capabilities: LauncherCapabilities = LauncherCapabilities(
        canRunElfBinaries = true,
        exposesPty = true,
        supportsBindMounts = true,
        requiresRoot = false,
        abiSupport = setOf("arm64-v8a"),
    )

    override fun buildShellCommand(rootfsDir: File, script: String): List<String> {
        return builtShellCommandReturn.map { if (it == "PLACEHOLDER") script else it }
    }
    override fun buildProbeCommand(rootfsDir: File, args: List<String>): List<String> =
        builtShellCommandReturn + args
    override fun environmentVariables(rootfsDir: File): List<Pair<String, String>> = envReturn
    override fun isAvailable(rootfsDir: File): Boolean = true
}

private class FakeDistroSessionBackend(
    private val installation: DistroInstallation,
    private val launcher: DistroLauncher,
) : DistroSessionBackend {
    override fun findInstalled(id: String): DistroInstallation? =
        if (id == installation.distro.id) installation else null
    override fun launcherFor(id: String): LauncherPick? =
        if (id == installation.distro.id) LauncherPick(launcher, "test") else null
}
