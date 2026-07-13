package com.elysium.vanguard.features.runtime.desktop

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.elysium.vanguard.core.runtime.distros.DistroManager
import com.elysium.vanguard.core.runtime.distros.RootfsIntrospectorSnapshot
import com.elysium.vanguard.core.runtime.distros.gui.LinuxAppCatalog
import com.elysium.vanguard.core.runtime.distros.gui.LinuxAppEntry
import com.elysium.vanguard.core.runtime.distros.gui.BoundedDiagnosticLog
import com.elysium.vanguard.core.runtime.distros.gui.GraphicalDesktopCapability
import com.elysium.vanguard.core.runtime.distros.gui.GraphicalDesktopCapabilityDetector
import com.elysium.vanguard.core.runtime.distros.gui.LinuxDesktopGeometry
import com.elysium.vanguard.core.runtime.distros.gui.LinuxDesktopLaunchPlan
import com.elysium.vanguard.core.runtime.distros.gui.VncSessionMaterial
import com.elysium.vanguard.core.runtime.distros.launcher.LauncherKind
import com.elysium.vanguard.core.runtime.distros.gui.rfb.RfbSession
import com.elysium.vanguard.core.runtime.terminal.session.TerminalSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * PHASE 9.6.5 — Linux desktop view-model.
 *
 * Reads a distro's desktop entries and runs a real local Xvnc framebuffer
 * when the rootfs has the verified graphical runtime. It never manufactures
 * a bitmap: the renderer only consumes the authenticated local RFB stream.
 */
@HiltViewModel
class LinuxDesktopViewModel @Inject constructor(
    application: Application,
    savedStateHandle: SavedStateHandle,
    private val manager: DistroManager
) : AndroidViewModel(application) {

    private val distroId: String =
        savedStateHandle.get<String>(DISTRO_ID_ARG)?.takeIf { it.isNotEmpty() } ?: ""

    val apps = MutableStateFlow<List<LinuxAppEntry>>(emptyList())
    val snapshot = MutableStateFlow<RootfsIntrospectorSnapshot?>(null)

    private val _capability = MutableStateFlow(
        GraphicalDesktopCapability(
            state = GraphicalDesktopCapability.State.ROOTFS_UNAVAILABLE,
            detail = "Checking installed runtime…"
        )
    )
    val capability: StateFlow<GraphicalDesktopCapability> = _capability.asStateFlow()

    private val _rfbSession = MutableStateFlow<RfbSession?>(null)
    internal val rfbSession: StateFlow<RfbSession?> = _rfbSession.asStateFlow()
    private val _desktopError = MutableStateFlow<String?>(null)
    val desktopError: StateFlow<String?> = _desktopError.asStateFlow()
    private val desktopLock = Any()
    private var desktopProcess: TerminalSession? = null
    private var desktopMaterial: VncSessionMaterial? = null
    private var desktopOutputCollector: Job? = null
    private var desktopLog: BoundedDiagnosticLog? = null

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { loadAll() }
        }
    }

    private fun loadAll() {
        val install = manager.findInstalled(distroId)
        if (install == null || !install.isHealthy) {
            apps.value = emptyList()
            snapshot.value = null
            _capability.value = GraphicalDesktopCapabilityDetector.inspect(null, null)
            return
        }
        var captured: RootfsIntrospectorSnapshot? = null
        manager.introspect(distroId) { snap -> captured = snap }
        snapshot.value = captured
        val catalog = LinuxAppCatalog(install.rootfsDir)
        apps.value = catalog.listApps()
        _capability.value = GraphicalDesktopCapabilityDetector.inspect(
            rootfsDir = install.rootfsDir,
            launcherKind = manager.launcherFor(distroId)?.launcher?.kind
        )
    }

    /** Starts a real, authenticated Xvnc/Openbox/xterm desktop inside PRoot. */
    fun connectLocalVnc() {
        if (_capability.value.state != GraphicalDesktopCapability.State.SERVER_DETECTED_RENDERER_AVAILABLE) return
        if (_rfbSession.value != null) return
        viewModelScope.launch(Dispatchers.IO) {
            startDesktop()
        }
    }

    fun disconnectLocalVnc() = releaseDesktop()

    override fun onCleared() {
        disconnectLocalVnc()
        super.onCleared()
    }

    private fun startDesktop() {
        var material: VncSessionMaterial? = null
        var process: TerminalSession? = null
        var outputCollector: Job? = null
        var outputLog: BoundedDiagnosticLog? = null
        try {
            releaseDesktop()
            val install = manager.findInstalled(distroId) ?: error("Linux rootfs is unavailable")
            check(install.isHealthy) { "Linux rootfs is unhealthy" }
            val pick = manager.launcherFor(distroId) ?: error("Linux launcher is unavailable")
            check(pick.launcher.kind == LauncherKind.NATIVE_PROOT) { "A native PRoot runtime is required for the graphical workspace" }
            requireDesktopBinaries(install.rootfsDir)

            val metrics = getApplication<Application>().resources.displayMetrics
            val plan = LinuxDesktopLaunchPlan(
                guestPasswordPath = VncSessionMaterial.create(install.rootfsDir).let { created ->
                    material = created
                    created.guestPath
                },
                geometry = LinuxDesktopGeometry.fromDisplayPixels(metrics.widthPixels, metrics.heightPixels)
            )
            val launchedProcess = TerminalSession.forDistroScript(install.rootfsDir, pick, plan.script)
            process = launchedProcess
            val transcript = BoundedDiagnosticLog(MAX_SERVER_LOG_CHARS)
            outputLog = transcript
            outputCollector = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                launchedProcess.output.collect(transcript::append)
            }
            launchedProcess.start()
            val startError = launchedProcess.state.value as? TerminalSession.State.Error
            check(startError == null) { startError?.message ?: "could not start the Linux desktop" }

            val materialValue = requireNotNull(material) {
                "VNC session material was not produced by the desktop installer"
            }
            val session = RfbSession(
                config = RfbSession.Config(passwordProvider = materialValue.passwordProvider)
            )
            synchronized(desktopLock) {
                desktopProcess = process
                desktopMaterial = materialValue
                desktopOutputCollector = outputCollector
                desktopLog = outputLog
                _rfbSession.value = session
                _desktopError.value = null
            }
            process = null
            material = null
            outputCollector = null
            outputLog = null
            session.start()
            viewModelScope.launch {
                session.state.collect { state ->
                    if (state is RfbSession.State.Failed) releaseDesktop(session, state.detail)
                }
            }
        } catch (error: Exception) {
            outputCollector?.cancel()
            process?.stop()
            material?.close()
            _desktopError.value = desktopError(
                error.message ?: "could not start the graphical workspace",
                outputLog
            )
        }
    }

    private fun releaseDesktop(expectedSession: RfbSession? = null, error: String? = null) {
        val resources = synchronized(desktopLock) {
            val current = _rfbSession.value
            if (expectedSession != null && current !== expectedSession) return
            _rfbSession.value = null
            val resources = DesktopResources(
                session = current,
                process = desktopProcess,
                material = desktopMaterial,
                outputCollector = desktopOutputCollector,
                log = desktopLog
            )
            if (error != null) _desktopError.value = desktopError(error, resources.log)
            resources.also {
                desktopProcess = null
                desktopMaterial = null
                desktopOutputCollector = null
                desktopLog = null
            }
        }
        resources.session?.stop()
        resources.outputCollector?.cancel()
        resources.process?.stop()
        resources.material?.close()
    }

    private fun requireDesktopBinaries(rootfsDir: java.io.File) {
        val server = listOf("usr/bin/Xvnc", "usr/bin/Xtigervnc").any { java.io.File(rootfsDir, it).canExecute() }
        check(server) { "TigerVNC is not installed in this rootfs" }
        listOf("usr/bin/openbox", "usr/bin/xterm").firstOrNull { !java.io.File(rootfsDir, it).canExecute() }?.let {
            error("required graphical program is missing: /$it")
        }
    }

    private data class DesktopResources(
        val session: RfbSession?,
        val process: TerminalSession?,
        val material: VncSessionMaterial?,
        val outputCollector: Job?,
        val log: BoundedDiagnosticLog?
    )

    private fun desktopError(error: String, log: BoundedDiagnosticLog?): String {
        val serverOutput = log?.snapshot().orEmpty()
        return if (serverOutput.isBlank()) {
            error.take(MAX_ERROR_CHARS)
        } else {
            "$error · server: $serverOutput".take(MAX_ERROR_CHARS)
        }
    }

    companion object {
        const val DISTRO_ID_ARG = "distroId"
        const val MAX_ERROR_CHARS = 240
        private const val MAX_SERVER_LOG_CHARS = 160
    }
}
