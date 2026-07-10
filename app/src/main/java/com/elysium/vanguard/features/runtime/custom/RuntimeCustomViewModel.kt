package com.elysium.vanguard.features.runtime.custom

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.elysium.vanguard.core.runtime.distros.Distro
import com.elysium.vanguard.core.runtime.distros.DistroFamily
import com.elysium.vanguard.core.runtime.distros.DistroManager
import com.elysium.vanguard.core.runtime.distros.RootfsKind
import com.elysium.vanguard.core.runtime.distros.custom.CustomRootfsKind
import com.elysium.vanguard.core.runtime.distros.custom.CustomRootfsPipeline
import com.elysium.vanguard.core.runtime.distros.custom.CustomRootfsValidator
import com.elysium.vanguard.core.runtime.distros.custom.UrlProbe
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * PHASE 9.6.3.2 — Custom rootfs screen view-model.
 *
 * Three states the UI drives:
 *
 *   1. `idle`           : user types the URL, no probe yet
 *   2. `probed(probe)`  : HEAD ran; we know if the URL is acceptable
 *   3. `installing`     : actual rootfs download + extraction in progress
 *   4. `done(file)`     : success; we know the on-disk rootfs path
 *   5. `error(msg)`     : probe or install failure
 *
 * State is intentionally explicit (sealed class) so the UI can render
 * distinct affordances per stage instead of guessing.
 *
 * Phase 9.6.3.2 — first build; intentionally minimal.
 */
@HiltViewModel
class RuntimeCustomViewModel @Inject constructor(
    application: Application,
    private val manager: DistroManager,
    private val validator: CustomRootfsValidator,
    private val pipeline: CustomRootfsPipeline
) : AndroidViewModel(application) {

    val baseDir: File
        get() = File(getApplication<Application>().filesDir, "distros")

    sealed class State {
        data object Idle : State()
        data class Probed(val probe: UrlProbe) : State()
        data class Installing(
            val bytesRead: Long = 0L,
            val totalBytes: Long? = null
        ) : State()
        data class Installed(val rootfsDir: File, val distroId: String) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _url = MutableStateFlow("")
    val url: StateFlow<String> = _url.asStateFlow()

    /**
     * PHASE 9.6.3.3 — Bytes-read counter; the UI uses this to draw a
     * real progress bar. Updated from the byte-progress callback on
     * the installer's IO thread.
     */
    private val _bytesRead = MutableStateFlow(0L)
    val bytesRead: StateFlow<Long> = _bytesRead.asStateFlow()

    fun onUrlChanged(value: String) {
        _url.value = value
    }

    fun probe() {
        val url = _url.value.trim()
        if (url.isBlank()) {
            _state.value = State.Error("URL is empty")
            return
        }
        viewModelScope.launch {
            val probe = withContext(Dispatchers.IO) { validator.probe(url) }
            _state.value = State.Probed(probe)
        }
    }

    fun install() {
        val probe = (_state.value as? State.Probed)?.probe
        if (probe == null) {
            _state.value = State.Error("validate the URL first")
            return
        }
        val distro = buildDistro(probe)
        val url = probe.url
        viewModelScope.launch {
            _bytesRead.value = 0L
            _state.value = State.Installing(totalBytes = probe.contentLengthBytes)
            val result = withContext(Dispatchers.IO) {
                pipeline.install(
                    distro = distro,
                    baseDir = baseDir,
                    url = url,
                    onByteProgress = { bytesRead ->
                        // Mirror state into the VM-level counter so the
                        // progress bar can read it without coupling to
                        // the sealed-class instance equality rules.
                        _bytesRead.value = bytesRead
                    }
                )
            }
            _state.value = result.fold(
                onSuccess = { file -> State.Installed(rootfsDir = file, distroId = distro.id) },
                onFailure = { e -> State.Error(e.message ?: "install failed") }
            )
            if (result.isSuccess) {
                manager.refreshInstalled()
            }
        }
    }

    fun reset() {
        _state.value = State.Idle
        _bytesRead.value = 0L
    }

    /**
     * Build a [Distro] from the URL. The id is derived from the URL
     * filename (sanitized) so the user can install multiple custom
     * rootfs into the catalog without collisions.
     */
    private fun buildDistro(probe: UrlProbe): Distro {
        val id = "custom-${probe.url.substringAfterLast('/').substringBefore('?').lowercase()}"
        return Distro(
            id = id,
            displayName = id,
            family = DistroFamily.DEBIAN, // unknown; DEBIAN is the most permissive default
            version = "custom",
            approxSizeBytes = probe.contentLengthBytes ?: 0L,
            minAndroidVersion = 26,
            rootfsUrl = probe.url,
            rootfsKind = RootfsKind.Custom,
            bootstrapCommand = null,
            packageManager = "apt",
            homepage = probe.url
        )
    }

    companion object {
        fun CustomRootfsKind.toRootfsKind(): RootfsKind = when (this) {
            CustomRootfsKind.TarGz, CustomRootfsKind.Tgz -> RootfsKind.TarGz
            CustomRootfsKind.TarXz -> RootfsKind.TarXz
            CustomRootfsKind.Tar -> RootfsKind.Custom
            CustomRootfsKind.Unknown -> RootfsKind.Custom
        }
    }
}
