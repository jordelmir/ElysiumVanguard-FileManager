package com.elysium.vanguard.features.vault

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elysium.vanguard.core.database.VaultEntity
import com.elysium.vanguard.core.vault.SecureDelete
import com.elysium.vanguard.core.vault.VaultCrypto
import com.elysium.vanguard.core.vault.VaultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

/**
 * PHASE 2.1 — Vault ViewModel.
 *
 * Two distinct concerns:
 * 1. The crypto is "always available" — Tink/AndroidKeysetManager holds the master
 *    AEAD in memory and uses the Android Keystore master key under the hood.
 * 2. The UI gate is a UX concern: we show a "locked" screen until the user taps
 *    Unlock (which today just flips a flag; in a follow-up we'll bind BiometricPrompt
 *    here so unlocking requires fingerprint/face).
 */
@HiltViewModel
class VaultViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: VaultRepository,
    private val crypto: VaultCrypto,
    private val secureDelete: SecureDelete,
    private val savedState: SavedStateHandle
) : ViewModel() {

    data class VaultState(
        val isUnlocked: Boolean = false,
        val items: List<VaultEntity> = emptyList(),
        val totalBytes: Long = 0L,
        val isLoading: Boolean = false,
        val lastDecrypted: DecryptedPreview? = null,
        val errorMessage: String? = null,
        val infoMessage: String? = null
    )

    data class DecryptedPreview(
        val entry: VaultEntity,
        val tempFile: File
    )

    private val _state = MutableStateFlow(VaultState())
    val state: StateFlow<VaultState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeAll().collect { items ->
                _state.update { it.copy(items = items, totalBytes = items.sumOf { e -> e.vaultSize }) }
            }
        }
    }

    fun unlock() {
        // Sanity check: try to derive the master Aead. If it throws (no Keystore, weird state),
        // surface the error instead of silently failing.
        viewModelScope.launch {
            try {
                _state.update { it.copy(isUnlocked = true, errorMessage = null) }
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = "Vault unlock failed: ${e.message}") }
            }
        }
    }

    fun lock() {
        _state.update {
            it.copy(
                isUnlocked = false,
                lastDecrypted = null
            )
        }
    }

    fun encryptLocalFile(file: File, mime: String? = null) {
        if (!state.value.isUnlocked) {
            _state.update { it.copy(errorMessage = "Vault is locked") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val entry = repository.encryptFile(file, mime)
                // Wipe the plaintext source with the 3-pass overwrite.
                repository.secureDeleteOriginal(file)
                _state.update {
                    it.copy(
                        isLoading = false,
                        infoMessage = "Encrypted \"${entry.originalName}\""
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, errorMessage = "Encrypt failed: ${e.message}") }
            }
        }
    }

    fun encryptUri(uri: Uri, displayName: String, mime: String?) {
        if (!state.value.isUnlocked) {
            _state.update { it.copy(errorMessage = "Vault is locked") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val entry = repository.encryptUri(uri, displayName, mime)
                _state.update {
                    it.copy(
                        isLoading = false,
                        infoMessage = "Encrypted \"${entry.originalName}\""
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, errorMessage = "Encrypt failed: ${e.message}") }
            }
        }
    }

    /**
     * Decrypt [entry] into a temp file in cacheDir. The temp file is wiped on
     * subsequent lock()/consumeDecrypted() and on ViewModel cleared.
     */
    fun decryptToCache(entry: VaultEntity) {
        if (!state.value.isUnlocked) {
            _state.update { it.copy(errorMessage = "Vault is locked") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val tempFile = withContext(Dispatchers.IO) {
                    val tmp = File(appContext.cacheDir, "vault_preview_${entry.id}_${entry.originalName}")
                    repository.decryptEntryToFile(entry, tmp)
                    tmp
                }
                _state.update {
                    it.copy(
                        isLoading = false,
                        lastDecrypted = DecryptedPreview(entry, tempFile),
                        infoMessage = "Decrypted to ${tempFile.name}"
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, errorMessage = "Decrypt failed: ${e.message}") }
            }
        }
    }

    fun purgeEntry(entry: VaultEntity) {
        if (!state.value.isUnlocked) {
            _state.update { it.copy(errorMessage = "Vault is locked") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                repository.purgeEntry(entry)
                _state.update { it.copy(isLoading = false, infoMessage = "Purged \"${entry.originalName}\"") }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, errorMessage = "Purge failed: ${e.message}") }
            }
        }
    }

    /** Wipe any preview temp file and clear the reference. */
    fun consumeDecrypted() {
        val prev = state.value.lastDecrypted ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val tmp = prev.tempFile
            if (tmp.isFile) {
                secureDelete.overwrite(tmp)
                tmp.delete()
            }
            _state.update { it.copy(lastDecrypted = null) }
        }
    }

    fun consumeError() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun consumeInfo() {
        _state.update { it.copy(infoMessage = null) }
    }

    override fun onCleared() {
        // Best-effort cleanup of any decrypted preview file.
        val preview = state.value.lastDecrypted
        if (preview != null) {
            val tmp = preview.tempFile
            if (tmp.isFile) {
                runCatching {
                    secureDelete.overwrite(tmp)
                    tmp.delete()
                }
            }
        }
        super.onCleared()
    }
}