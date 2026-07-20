package com.elysium.vanguard.features.fileactions

import com.elysium.vanguard.core.fileactions.DiskImageFormat
import com.elysium.vanguard.core.fileactions.FileAction
import com.elysium.vanguard.core.fileactions.FileActionEnvironment
import com.elysium.vanguard.core.fileactions.handlers.DiskImageBackend
import com.elysium.vanguard.core.fileactions.handlers.DiskImageResult
import com.elysium.vanguard.core.fileactions.handlers.GitCloneHandler
import com.elysium.vanguard.core.fileactions.handlers.GitCloneResult
import com.elysium.vanguard.core.fileactions.handlers.GitCloneRunner
import com.elysium.vanguard.core.fileactions.handlers.InstallPackageHandler
import com.elysium.vanguard.core.fileactions.handlers.InstallPackageResult
import com.elysium.vanguard.core.fileactions.handlers.NetworkShareHandler
import com.elysium.vanguard.core.fileactions.handlers.NetworkShareMounter
import com.elysium.vanguard.core.fileactions.handlers.NetworkShareMountResult
import com.elysium.vanguard.core.fileactions.handlers.PackageInstaller
import com.elysium.vanguard.core.fileactions.handlers.UsbDeviceSummary
import com.elysium.vanguard.core.fileactions.handlers.UsbOtgHandler
import com.elysium.vanguard.core.fileactions.handlers.UsbOtgInspectResult
import com.elysium.vanguard.core.fileactions.handlers.UsbOtgInspector
import com.elysium.vanguard.core.fileactions.handlers.UsbPartition
import com.elysium.vanguard.core.runtime.distros.Distro
import com.elysium.vanguard.core.runtime.distros.DistroFamily
import com.elysium.vanguard.core.runtime.distros.DistroInstallation
import com.elysium.vanguard.core.runtime.windows.WindowsVmSpec
import com.elysium.vanguard.core.runtime.windows.WindowsVmState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Phase 94 — the test suite for the
 * [FileActionViewModel]. The view model
 * reads from a [FileActionEnvironment]
 * interface (no need to extend the
 * concrete `DistroManager` /
 * `WindowsVmManager` classes).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileActionViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `openActionSheet with a deb file offers InstallDebPackage`() {
        val env = FakeEnvironment(
            installedDistros = listOf(sampleInstallation("debian-12", "Debian 12"))
        )
        val vm = buildViewModel(env)
        val deb = File("test.deb")
        vm.openActionSheet(deb)
        val state = vm.state.value
        assertTrue("sheet should be visible", state.sheetVisible)
        assertEquals(deb, state.targetFile)
        assertEquals(1, state.actions.size)
        assertTrue(state.actions.first() is FileAction.InstallDebPackage)
    }

    @Test
    fun `openActionSheet with a txt file offers nothing`() {
        val env = FakeEnvironment()
        val vm = buildViewModel(env)
        vm.openActionSheet(File("readme.txt"))
        assertTrue(vm.state.value.actions.isEmpty())
    }

    @Test
    fun `openActionSheet with no installed distros offers nothing for deb`() {
        val env = FakeEnvironment(installedDistros = emptyList())
        val vm = buildViewModel(env)
        vm.openActionSheet(File("test.deb"))
        assertTrue(vm.state.value.actions.isEmpty())
    }

    @Test
    fun `closeActionSheet hides the sheet and clears the actions`() {
        val env = FakeEnvironment(
            installedDistros = listOf(sampleInstallation("debian-12", "Debian 12"))
        )
        val vm = buildViewModel(env)
        vm.openActionSheet(File("test.deb"))
        vm.closeActionSheet()
        assertTrue(!vm.state.value.sheetVisible)
        assertNull(vm.state.value.targetFile)
        assertTrue(vm.state.value.actions.isEmpty())
    }

    @Test
    fun `execute InstallDebPackage calls the PackageInstaller`() = runTest {
        val installer = RecordingPackageInstaller(
            expectedResult = InstallPackageResult.Success(
                distroId = "debian-12",
                packageName = "test.deb",
                exitCode = 0,
            )
        )
        val env = FakeEnvironment(
            installedDistros = listOf(sampleInstallation("debian-12", "Debian 12"))
        )
        val vm = buildViewModel(env, installer = installer)
        // The handler checks `packageFile.exists()`.
        // Use the working dir's TemporaryFolder equivalent
        // (a real on-disk file).
        val debFile = java.io.File.createTempFile("test", ".deb")
        debFile.deleteOnExit()
        vm.openActionSheet(debFile)
        val action = vm.state.value.actions.first() as FileAction.InstallDebPackage
        vm.execute(action)
        advanceUntilIdle()
        val outcome = vm.state.value.lastOutcome
        assertNotNull("outcome should be set after execute()", outcome)
        assertTrue("outcome should be Success, got $outcome", outcome is FileActionOutcome.Success)
        assertTrue((outcome as FileActionOutcome.Success).message.contains("test.deb"))
        assertEquals(1, installer.calls.size)
        assertEquals("debian-12", installer.calls[0].first)
    }

    @Test
    fun `execute GitClone calls the GitCloneHandler`() = runTest {
        val runner = RecordingGitCloneRunner(
            expectedResult = GitCloneResult.Success(
                url = "https://github.com/foo/bar.git",
                destination = "/tmp",
                exitCode = 0,
            )
        )
        val handler = GitCloneHandler(runner)
        val env = FakeEnvironment()
        val vm = buildViewModel(env, gitCloneHandler = handler)
        // The handler reads the URL from the file body.
        val descriptor = java.io.File.createTempFile("repo", ".git")
        descriptor.writeText("https://github.com/foo/bar.git\n")
        descriptor.deleteOnExit()
        val action = FileAction.GitClone(
            id = "test",
            repoUrl = descriptor.absolutePath,
            destinationDir = "/tmp",
        )
        vm.execute(action)
        advanceUntilIdle()
        val outcome = vm.state.value.lastOutcome
        assertNotNull("outcome should be set after execute()", outcome)
        assertTrue("outcome should be Success, got $outcome", outcome is FileActionOutcome.Success)
    }

    @Test
    fun `execute MountDiskImage returns Success when the backend reports Mounted`() = runTest {
        val backend = RecordingDiskImageBackend(
            mountResult = DiskImageResult.Mounted(
                mountPoint = "/mnt/win10",
                format = DiskImageFormat.ISO,
            )
        )
        val env = FakeEnvironment()
        val vm = buildViewModel(env, diskImageBackend = backend)
        val action = FileAction.MountDiskImage(
            id = "test",
            imagePath = "/path/to/win10.iso",
            imageFormat = DiskImageFormat.ISO,
        )
        vm.execute(action)
        advanceUntilIdle()
        val outcome = vm.state.value.lastOutcome
        assertTrue("outcome should be Success, got $outcome", outcome is FileActionOutcome.Success)
        assertTrue((outcome as FileActionOutcome.Success).message.contains("/mnt/win10"))
    }

    // --- helpers ---

    private fun buildViewModel(
        env: FileActionEnvironment,
        installer: PackageInstaller = RecordingPackageInstaller(),
        gitCloneRunner: GitCloneRunner = RecordingGitCloneRunner(),
        diskImageBackend: DiskImageBackend = RecordingDiskImageBackend(),
        gitCloneHandler: GitCloneHandler = GitCloneHandler(gitCloneRunner),
        networkShareHandler: NetworkShareHandler = NetworkShareHandler(RecordingNetworkShareMounter()),
        usbOtgHandler: UsbOtgHandler = UsbOtgHandler(RecordingUsbOtgInspector()),
    ): FileActionViewModel = FileActionViewModel(
        env = env,
        installPackageHandler = InstallPackageHandler(installer),
        gitCloneHandler = gitCloneHandler,
        diskImageBackend = diskImageBackend,
        networkShareHandler = networkShareHandler,
        usbOtgHandler = usbOtgHandler,
    )

    private fun sampleInstallation(id: String, name: String) = DistroInstallation(
        distro = Distro(
            id = id,
            displayName = name,
            family = DistroFamily.DEBIAN,
            version = "1.0",
            approxSizeBytes = 1_000_000_000L,
            minAndroidVersion = 26,
            rootfsUrl = "https://example.invalid/$id.tar.gz",
            rootfsKind = com.elysium.vanguard.core.runtime.distros.RootfsKind.TarGz,
            bootstrapCommand = null,
            packageManager = "apt",
            homepage = "https://example.invalid/$id",
        ),
        rootDir = File("/workspaces/$id"),
        rootfsDir = File("/workspaces/$id/rootfs"),
        installedAtEpochMs = 0L,
        sizeOnDiskBytes = 0L,
        lastError = null,
    )
}

/**
 * The fake [FileActionEnvironment] the tests
 * use. The tests pass a fixed list of
 * distros / specs; the view model reads
 * the values via the interface methods.
 */
private class FakeEnvironment(
    private val installedDistros: List<DistroInstallation> = emptyList(),
    private val windowsVmSpecs: List<WindowsVmSpec> = emptyList(),
    private val windowsVmStates: Map<String, WindowsVmState> = emptyMap(),
) : FileActionEnvironment {
    override fun installedDistros(): List<DistroInstallation> = installedDistros
    override fun windowsVmSpecs(): List<WindowsVmSpec> = windowsVmSpecs
    override fun windowsVmState(vmId: String): WindowsVmState =
        windowsVmStates[vmId] ?: WindowsVmState.Stopped
}

private class RecordingPackageInstaller(
    private val expectedResult: InstallPackageResult = InstallPackageResult.Failure(
        message = "no test result configured"
    ),
) : PackageInstaller {
    val calls: MutableList<Pair<String, File>> = mutableListOf()

    override suspend fun installApt(distroId: String, packageFile: File): InstallPackageResult {
        calls.add(distroId to packageFile)
        return expectedResult
    }
    override suspend fun installDnf(distroId: String, packageFile: File) = expectedResult
    override suspend fun installPacman(distroId: String, packageFile: File) = expectedResult
}

private class RecordingGitCloneRunner(
    private val expectedResult: GitCloneResult = GitCloneResult.Failure(
        message = "no test result configured"
    ),
) : GitCloneRunner {
    override suspend fun clone(url: String, destination: File): GitCloneResult = expectedResult
}

private class RecordingDiskImageBackend(
    private val mountResult: DiskImageResult = DiskImageResult.Failure(message = "no test"),
) : DiskImageBackend {
    override suspend fun mountReadOnly(image: File, format: DiskImageFormat) = mountResult
    override suspend fun bootVm(
        image: File,
        format: DiskImageFormat,
        preferredVmId: String?,
    ) = DiskImageResult.VmBooted(vmId = "test-vm", format = format)
}

private class RecordingNetworkShareMounter(
    private val expectedResult: NetworkShareMountResult = NetworkShareMountResult.Failure(
        message = "no test result configured"
    ),
) : NetworkShareMounter {
    override suspend fun mount(
        url: String,
        protocol: com.elysium.vanguard.core.fileactions.NetworkProtocol,
        username: String?,
        password: String?,
        descriptorName: String,
    ): NetworkShareMountResult = expectedResult
}

private class RecordingUsbOtgInspector(
    private val expectedResult: UsbOtgInspectResult = UsbOtgInspectResult.Failure(
        message = "no test result configured"
    ),
) : UsbOtgInspector {
    override fun findFirstMassStorageDevice(): UsbDeviceSummary? = null
    override fun findByBlockPath(blockPath: String): UsbDeviceSummary? = null
    override fun firstReadablePartition(device: UsbDeviceSummary): UsbPartition? = null
    override suspend fun mountReadOnly(partition: UsbPartition): UsbOtgInspectResult = expectedResult
}
