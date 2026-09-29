package com.elysium.vanguard.features.encfs

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elysium.vanguard.core.encryption.EncFsVolume
import com.elysium.vanguard.core.encryption.MountedVolumeRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Browser for a single EncFS volume (route `encfs_volume/{volumePath}`).
 *
 * The volume path arrives URL-encoded in the nav argument; Navigation
 * percent-decodes it before it lands in [SavedStateHandle]. If the session
 * already holds an unlocked volume at that path (the mount action put it in
 * [MountedVolumeRegistry]), the screen opens straight into the listing —
 * otherwise it asks for the password and unlocks it.
 */
@HiltViewModel
class EncFsVolumeViewModel @Inject constructor(
    private val registry: MountedVolumeRegistry,
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val volumePath: String = savedStateHandle.get<String>("volumePath").orEmpty()

    data class Entry(
        val encryptedName: String,
        val plainName: String,
        val sizeBytes: Long,
    )

    sealed interface VolumeState {
        data object Loading : VolumeState
        data class Locked(
            val volumePath: String,
            val error: String? = null,
            val checking: Boolean = false,
        ) : VolumeState

        data class Unlocked(
            val volumePath: String,
            val entries: List<Entry>,
            val busy: Boolean = false,
            val message: String? = null,
        ) : VolumeState
    }

    private val _state = MutableStateFlow<VolumeState>(VolumeState.Loading)
    val state: StateFlow<VolumeState> = _state.asStateFlow()

    init {
        val mounted = registry.get(volumePath)
        _state.value = if (mounted != null) {
            VolumeState.Unlocked(volumePath, entriesOf(mounted))
        } else {
            VolumeState.Locked(volumePath)
        }
    }

    fun unlock(password: String) {
        if (volumePath.isEmpty()) {
            _state.value = VolumeState.Locked("", error = "Missing volume path")
            return
        }
        if (password.isEmpty()) {
            _state.value = VolumeState.Locked(volumePath, error = "Password required")
            return
        }
        _state.value = VolumeState.Locked(volumePath, checking = true)
        viewModelScope.launch {
            val volume = withContext(Dispatchers.IO) {
                EncFsVolume.open(File(volumePath), password.toCharArray())
            }
            _state.value = if (volume != null) {
                registry.mount(volumePath, volume)
                VolumeState.Unlocked(volumePath, entriesOf(volume))
            } else {
                VolumeState.Locked(
                    volumePath,
                    error = "Wrong password or corrupt volume (.encfs6.xml)",
                )
            }
        }
    }

    fun refresh() {
        val current = _state.value
        if (current !is VolumeState.Unlocked) return
        val volume = registry.get(volumePath) ?: return
        _state.value = current.copy(entries = entriesOf(volume))
    }

    fun clearMessage() {
        val current = _state.value
        if (current is VolumeState.Unlocked && current.message != null) {
            _state.value = current.copy(message = null)
        }
    }

    /** Decrypt one entry and write it to `Downloads/Elysium`. */
    fun exportEntry(encryptedName: String) {
        val current = _state.value as? VolumeState.Unlocked ?: return
        val entry = current.entries.find { it.encryptedName == encryptedName } ?: return
        _state.value = current.copy(busy = true, message = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { doExport(entry) }
            _state.value = ( _state.value as? VolumeState.Unlocked )
                ?.copy(busy = false, message = result)
                ?: VolumeState.Locked(volumePath, error = "Volume was locked")
        }
    }

    /** Import files picked via SAF: copy to cache (keep name) → encrypt into the volume. */
    fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val current = _state.value as? VolumeState.Unlocked ?: return
        _state.value = current.copy(busy = true, message = null)
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) {
                val volume = registry.get(volumePath) ?: return@withContext -1
                val importDir = File(context.cacheDir, "encfs_import").apply { mkdirs() }
                var ok = 0
                for (uri in uris) {
                    try {
                        val name = displayNameOf(uri) ?: "import_$ok.bin"
                        val tmp = File(importDir, name)
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            tmp.outputStream().use { output -> input.copyTo(output) }
                        } ?: continue
                        volume.encryptFile(tmp)
                        tmp.delete()
                        ok++
                    } catch (_: Exception) {
                        // count only successes; caller reports totals
                    }
                }
                importDir.deleteRecursively()
                ok
            }
            val state = _state.value as? VolumeState.Unlocked
                ?: return@launch
            val volume = registry.get(volumePath)
            _state.value = state.copy(
                busy = false,
                entries = volume?.let { entriesOf(it) } ?: state.entries,
                message = when {
                    added < 0 -> "Volume is locked"
                    added == uris.size -> "Added $added file(s) to the volume"
                    else -> "Added $added of ${uris.size} file(s)"
                },
            )
        }
    }

    fun deleteEntry(encryptedName: String) {
        val current = _state.value as? VolumeState.Unlocked ?: return
        _state.value = current.copy(busy = true, message = null)
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) {
                registry.get(volumePath)?.deleteEntry(encryptedName) ?: false
            }
            val state = _state.value as? VolumeState.Unlocked ?: return@launch
            val volume = registry.get(volumePath)
            _state.value = state.copy(
                busy = false,
                entries = volume?.let { entriesOf(it) } ?: emptyList(),
                message = if (removed) "Deleted entry" else "Delete failed",
            )
        }
    }

    /** Lock the volume and drop it from the registry (screen navigates back). */
    fun unmount() {
        registry.unmount(volumePath)
        _state.value = VolumeState.Locked(volumePath)
    }

    private fun entriesOf(volume: EncFsVolume): List<Entry> =
        volume.listFiles()
            .map { file ->
                Entry(
                    encryptedName = file.name,
                    plainName = volume.getDecryptedName(file.name),
                    sizeBytes = file.length(),
                )
            }
            .sortedBy { it.plainName.lowercase() }

    private fun doExport(entry: Entry): String {
        val volume = registry.get(volumePath)
            ?: return "Volume is locked"
        val tmp = File.createTempFile("encfs-export", ".tmp", context.cacheDir)
        try {
            val stored = File(volumePath, entry.encryptedName)
            if (!volume.decryptFile(stored, tmp)) {
                return "Decryption failed for ${entry.plainName}"
            }
            val bytes = tmp.readBytes()
            val mime = mimeOf(entry.plainName)
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, entry.plainName)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Elysium")
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values,
                ) ?: return "Could not create download entry"
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: return "Could not write download entry"
                "Exported to ${Environment.DIRECTORY_DOWNLOADS}/Elysium"
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "Elysium",
                )
                if (!dir.exists() && !dir.mkdirs()) {
                    return "No permission to write Downloads (grant storage access)"
                }
                var target = File(dir, entry.plainName)
                if (target.exists()) {
                    val stem = entry.plainName.substringBeforeLast('.', entry.plainName)
                    val ext = entry.plainName.substringAfterLast('.', "")
                    var i = 1
                    while (target.exists()) {
                        target = File(dir, if (ext.isEmpty()) "$stem ($i)" else "$stem ($i).$ext")
                        i++
                    }
                }
                target.writeBytes(bytes)
                "Exported to ${Environment.DIRECTORY_DOWNLOADS}/Elysium"
            }
        } catch (e: Exception) {
            return "Export failed: ${e.message}"
        } finally {
            tmp.delete()
        }
    }

    private fun displayNameOf(uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                return cursor.getString(idx)
            }
        }
        return uri.lastPathSegment
    }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}
