package com.elysium.vanguard.features.fileactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elysium.vanguard.core.fileactions.FileAction
import com.elysium.vanguard.core.fileactions.FileActionContext
import com.elysium.vanguard.core.fileactions.FileActionContext.LinuxDistroTarget
import com.elysium.vanguard.core.fileactions.FileActionContext.WindowsVmTarget
import com.elysium.vanguard.core.fileactions.FileActionEnvironment
import com.elysium.vanguard.core.fileactions.FileActionResolver
import com.elysium.vanguard.core.fileactions.LinuxPackageManager
import com.elysium.vanguard.core.fileactions.handlers.DiskImageBackend
import com.elysium.vanguard.core.fileactions.handlers.DiskImageResult
import com.elysium.vanguard.core.fileactions.handlers.GitCloneHandler
import com.elysium.vanguard.core.fileactions.handlers.GitCloneResult
import com.elysium.vanguard.core.fileactions.handlers.InstallPackageHandler
import com.elysium.vanguard.core.fileactions.handlers.InstallPackageResult
import com.elysium.vanguard.core.fileactions.handlers.NetworkShareHandler
import com.elysium.vanguard.core.fileactions.handlers.NetworkShareMountResult
import com.elysium.vanguard.core.fileactions.handlers.UsbOtgHandler
import com.elysium.vanguard.core.fileactions.handlers.UsbOtgInspectResult
import com.elysium.vanguard.core.fileactions.handlers.BinaryRunnerHandler
import com.elysium.vanguard.core.fileactions.handlers.BinaryRunResult
import com.elysium.vanguard.core.fileactions.handlers.MsiInstallerHandler
import com.elysium.vanguard.core.fileactions.handlers.MsiInstallResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Phase 94 — the [FileActionViewModel].
 *
 * The view model coordinates the three
 * collaborators that the [FileActionResolver]
 * needs:
 *
 * - The [FileActionEnvironment] (the list of
 *   installed Linux distros + the list of
 *   Windows VM specs + their state)
 * - The three handlers ([InstallPackageHandler],
 *   [GitCloneHandler], [DiskImageBackend])
 *
 * **JVM testability**: the primary constructor
 * takes a [FileActionEnvironment] + the three
 * handlers. The Hilt-injected constructor
 * takes the same; Hilt wires the environment
 * via the [com.elysium.vanguard.core.fileactions.FileActionModule].
 * Tests instantiate via the primary
 * constructor with a fake environment.
 */
@HiltViewModel
class FileActionViewModel @Inject constructor(
    private val env: FileActionEnvironment,
    private val installPackageHandler: InstallPackageHandler,
    private val gitCloneHandler: GitCloneHandler,
    private val diskImageBackend: DiskImageBackend,
    private val networkShareHandler: NetworkShareHandler,
    private val usbOtgHandler: UsbOtgHandler,
    private val binaryRunnerHandler: BinaryRunnerHandler,
    private val msiInstallerHandler: MsiInstallerHandler,
) : ViewModel() {

    private val _state = MutableStateFlow(FileActionUiState())
    val state: StateFlow<FileActionUiState> = _state.asStateFlow()

    fun openActionSheet(file: File) {
        val context = buildContext()
        val actions = FileActionResolver.resolve(file, context)
        _state.update { current ->
            current.copy(
                sheetVisible = true,
                targetFile = file,
                actions = actions,
            )
        }
    }

    fun closeActionSheet() {
        _state.update { current ->
            current.copy(
                sheetVisible = false,
                targetFile = null,
                actions = emptyList(),
            )
        }
    }

    fun execute(action: FileAction) {
        viewModelScope.launch {
            val outcome = dispatchAction(action)
            _state.update { it.copy(lastOutcome = outcome) }
        }
    }

    private suspend fun dispatchAction(action: FileAction): FileActionOutcome =
        when (action) {
            is FileAction.InstallDebPackage,
            is FileAction.InstallRpmPackage,
            is FileAction.InstallPacmanPackage -> {
                val result = installPackageHandler.install(action)
                when (result) {
                    is InstallPackageResult.Success -> FileActionOutcome.Success(
                        message = "Installed ${result.packageName} (exit=${result.exitCode})"
                    )
                    is InstallPackageResult.Failure -> FileActionOutcome.Failure(
                        message = result.message
                    )
                    is InstallPackageResult.MissingDistro -> FileActionOutcome.Failure(
                        message = "distro ${result.distroId} is not installed"
                    )
                }
            }
            is FileAction.RunAppImage -> {
                val result = binaryRunnerHandler.run(action)
                when (result) {
                    is BinaryRunResult.Launched -> FileActionOutcome.Success(
                        message = "Launched ${action.appImagePath} in ${action.targetDistroName}"
                    )
                    is BinaryRunResult.Failure -> FileActionOutcome.Failure(
                        message = result.message
                    )
                }
            }
            is FileAction.RunWindowsBinary -> {
                val result = binaryRunnerHandler.run(action)
                when (result) {
                    is BinaryRunResult.Launched -> FileActionOutcome.Success(
                        message = "Launched ${action.binaryPath} in ${action.targetVmName}"
                    )
                    is BinaryRunResult.Failure -> FileActionOutcome.Failure(
                        message = result.message
                    )
                }
            }
            is FileAction.InstallWindowsMsi -> {
                // Phase 103 — `.msi` install.
                // Distinct from RunWindowsBinary
                // because the command path is
                // `msiexec /i ... /qn` (Windows
                // Installer service), not `wine`.
                val result = msiInstallerHandler.install(action)
                when (result) {
                    is MsiInstallResult.Completed -> FileActionOutcome.Success(
                        // The exit code is 0 on
                        // success, 3010 on
                        // success + reboot
                        // required, 1603 on
                        // fatal install error.
                        // We surface the code so
                        // the toast is accurate.
                        message = "Installed ${action.msiPath} in ${action.targetVmName} (msiexec exit=${result.exitCode})"
                    )
                    is MsiInstallResult.Failure -> FileActionOutcome.Failure(
                        message = result.message
                    )
                }
            }
            is FileAction.GitClone -> {
                val result = gitCloneHandler.clone(action)
                when (result) {
                    is GitCloneResult.Success -> FileActionOutcome.Success(
                        message = "Cloned ${result.url} to ${result.destination}"
                    )
                    is GitCloneResult.Failure -> FileActionOutcome.Failure(
                        message = result.message
                    )
                    is GitCloneResult.InvalidDescriptor -> FileActionOutcome.Failure(
                        message = result.message
                    )
                }
            }
            is FileAction.MountDiskImage -> {
                val result = diskImageBackend.mountReadOnly(
                    image = File(action.imagePath),
                    format = action.imageFormat,
                )
                when (result) {
                    is DiskImageResult.Mounted -> FileActionOutcome.Success(
                        message = "Mounted ${result.format} at ${result.mountPoint}"
                    )
                    is DiskImageResult.VmBooted -> FileActionOutcome.Failure(
                        message = "unexpected: got VmBooted for MountDiskImage"
                    )
                    is DiskImageResult.Failure -> FileActionOutcome.Failure(
                        message = result.message
                    )
                }
            }
            is FileAction.BootVmFromImage -> {
                val result = diskImageBackend.bootVm(
                    image = File(action.imagePath),
                    format = action.imageFormat,
                    preferredVmId = action.preferredVmId,
                )
                when (result) {
                    is DiskImageResult.VmBooted -> FileActionOutcome.Success(
                        message = "Booted VM ${result.vmId}"
                    )
                    is DiskImageResult.Mounted -> FileActionOutcome.Failure(
                        message = "unexpected: got Mounted for BootVmFromImage"
                    )
                    is DiskImageResult.Failure -> FileActionOutcome.Failure(
                        message = result.message
                    )
                }
            }
            is FileAction.MountNetworkShare -> {
                val result = networkShareHandler.mount(action)
                when (result) {
                    is NetworkShareMountResult.Mounted -> FileActionOutcome.Success(
                        message = "Mounted ${result.protocol.name} at ${result.mountPoint}"
                    )
                    is NetworkShareMountResult.Failure -> FileActionOutcome.Failure(
                        message = result.message
                    )
                }
            }
            is FileAction.InspectUsbOtgDevice -> {
                val result = usbOtgHandler.inspect(action)
                when (result) {
                    is UsbOtgInspectResult.Mounted -> FileActionOutcome.Success(
                        message = "Mounted ${result.partition.blockPath} at ${result.mountPoint}"
                    )
                    is UsbOtgInspectResult.Unmounted -> FileActionOutcome.Success(
                        message = "Found ${result.partitions.size} partition(s) on ${result.device.productName} (none auto-mounted)"
                    )
                    is UsbOtgInspectResult.Failure -> FileActionOutcome.Failure(
                        message = result.message
                    )
                }
            }
        }

    private fun buildContext(): FileActionContext {
        val distros = env.installedDistros().map { installation ->
            LinuxDistroTarget(
                id = installation.distro.id,
                name = installation.distro.displayName,
                packageManager = mapToPackageManager(installation.distro.id),
            )
        }
        val vms = env.windowsVmSpecs().map { spec ->
            WindowsVmTarget(
                id = spec.id,
                name = spec.displayName,
                isRunning = env.windowsVmState(spec.id)
                    is com.elysium.vanguard.core.runtime.windows.WindowsVmState.Running,
            )
        }
        return FileActionContext(
            linuxDistros = distros,
            windowsVms = vms,
            preferredLinuxDistroId = distros.firstOrNull()?.id,
            preferredWindowsVmId = vms.firstOrNull()?.id,
        )
    }

    private fun mapToPackageManager(distroId: String): LinuxPackageManager = when {
        distroId.startsWith("debian", ignoreCase = true) -> LinuxPackageManager.APT
        distroId.startsWith("ubuntu", ignoreCase = true) -> LinuxPackageManager.APT
        distroId.startsWith("fedora", ignoreCase = true) -> LinuxPackageManager.DNF
        distroId.startsWith("opensuse", ignoreCase = true) -> LinuxPackageManager.DNF
        distroId.startsWith("arch", ignoreCase = true) -> LinuxPackageManager.PACMAN
        distroId.startsWith("alpine", ignoreCase = true) -> LinuxPackageManager.APK
        distroId.startsWith("elysium", ignoreCase = true) -> LinuxPackageManager.ELEVATOR
        else -> LinuxPackageManager.APT
    }
}

/**
 * The UI state the [FileActionViewModel]
 * exposes. The [FileActionSheet] reads
 * [sheetVisible] + [targetFile] + [actions]
 * to render itself; the [FileManagerScreen]
 * reads [lastOutcome] to render a Snackbar.
 */
data class FileActionUiState(
    val sheetVisible: Boolean = false,
    val targetFile: File? = null,
    val actions: List<FileAction> = emptyList(),
    val lastOutcome: FileActionOutcome? = null,
)

/**
 * The result of a file action execution. A
 * sealed class so the UI can pattern-match
 * (render a success Snackbar or a failure
 * dialog).
 */
sealed class FileActionOutcome {
    data class Success(val message: String) : FileActionOutcome()
    data class Failure(val message: String) : FileActionOutcome()
}
