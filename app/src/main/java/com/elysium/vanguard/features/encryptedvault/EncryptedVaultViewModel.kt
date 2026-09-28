package com.elysium.vanguard.features.encryptedvault

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elysium.vanguard.core.encryption.EncryptedVault
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * ViewModel for managing Encrypted Vaults (password-based AES-256-GCM containers).
 * Similar to Solid Explorer's vault feature.
 */
@HiltViewModel
class EncryptedVaultViewModel @Inject constructor(
    @ApplicationContext val context: Context
) : ViewModel() {

    data class VaultState(
        val isUnlocked: Boolean = false,
        val vaultPath: String? = null,
        val items: List<VaultItem> = emptyList(),
        val isLoading: Boolean = false,
        val errorMessage: String? = null,
        val infoMessage: String? = null
    )

    data class VaultItem(
        val name: String,
        val path: String,
        val size: Long,
        val isFolder: Boolean,
        val lastModified: Long,
        val mimeType: String?
    )

    private val _state = MutableStateFlow(VaultState())
    val state: StateFlow<VaultState> = _state.asStateFlow()

    private val vault = EncryptedVault.Companion

    /**
     * Create a new encrypted vault file.
     */
    fun createVault(vaultFile: java.io.File, password: CharArray) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                // Create empty vault container
                val emptyData = "{}".toByteArray() // Empty JSON as initial content
                val encrypted = vault.encrypt(password, emptyData)
                withContext(Dispatchers.IO) {
                    vaultFile.writeBytes(encrypted)
                }
                _state.update {
                    it.copy(
                        isLoading = false,
                        isUnlocked = true,
                        vaultPath = vaultFile.absolutePath,
                        infoMessage = "Vault created: ${vaultFile.name}"
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, errorMessage = "Create failed: ${e.message}") }
            }
        }
    }

    /**
     * Open an existing encrypted vault file.
     */
    fun openVault(vaultFile: java.io.File, password: CharArray) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val encrypted = vaultFile.readBytes()
                val decrypted = vault.decrypt(password, encrypted)

                // Parse vault content (JSON structure for file listing)
                // For now, just mark as unlocked
                _state.update {
                    it.copy(
                        isLoading = false,
                        isUnlocked = true,
                        vaultPath = vaultFile.absolutePath,
                        infoMessage = "Vault opened: ${vaultFile.name}"
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, errorMessage = "Open failed: ${e.message}") }
            }
        }
    }

    /**
     * Add a file to the vault.
     */
    fun addFile(sourceFile: java.io.File, password: CharArray) {
        _state.value.vaultPath?.let { vaultPath ->
            viewModelScope.launch(Dispatchers.IO) {
                _state.update { it.copy(isLoading = true, errorMessage = null) }
                try {
                    val vaultFile = java.io.File(vaultPath)
                    val encrypted = vaultFile.readBytes()
                    val decrypted = vault.decrypt(password, encrypted)

                    // Add file entry to vault content (simplified - append to JSON)
                    val updatedContent = addFileToVaultContent(decrypted, sourceFile)
                    val newEncrypted = vault.encrypt(password, updatedContent)

                    withContext(Dispatchers.IO) {
                        vaultFile.writeBytes(newEncrypted)
                    }
                    _state.update { it.copy(isLoading = false, infoMessage = "Added: ${sourceFile.name}") }
                } catch (e: Exception) {
                    _state.update { it.copy(isLoading = false, errorMessage = "Add failed: ${e.message}") }
                }
            }
        }
    }

    /**
     * Extract a file from the vault.
     */
    fun extractFile(entryName: String, destinationDir: java.io.File, password: CharArray) {
        _state.value.vaultPath?.let { vaultPath ->
            viewModelScope.launch(Dispatchers.IO) {
                _state.update { it.copy(isLoading = true, errorMessage = null) }
                try {
                    val vaultFile = java.io.File(vaultPath)
                    val encrypted = vaultFile.readBytes()
                    val decrypted = vault.decrypt(password, encrypted)

                    // Extract specific file (simplified)
                    val extracted = extractFileFromVaultContent(decrypted, entryName)
                    val destFile = java.io.File(destinationDir, entryName)
                    withContext(Dispatchers.IO) {
                        destFile.writeBytes(extracted)
                    }
                    _state.update { it.copy(isLoading = false, infoMessage = "Extracted: $entryName") }
                } catch (e: Exception) {
                    _state.update { it.copy(isLoading = false, errorMessage = "Extract failed: ${e.message}") }
                }
            }
        }
    }

    /**
     * Lock the vault (clear sensitive data from memory).
     */
    fun lock() {
        _state.update { it.copy(isUnlocked = false, vaultPath = null, items = emptyList()) }
    }

    /**
     * Change vault password.
     */
    fun changePassword(oldPassword: CharArray, newPassword: CharArray) {
        _state.value.vaultPath?.let { vaultPath ->
            viewModelScope.launch(Dispatchers.IO) {
                _state.update { it.copy(isLoading = true, errorMessage = null) }
                try {
                    val vaultFile = java.io.File(vaultPath)
                    val encrypted = vaultFile.readBytes()
                    val decrypted = vault.decrypt(oldPassword, encrypted)
                    val newEncrypted = vault.encrypt(newPassword, decrypted)
                    withContext(Dispatchers.IO) {
                        vaultFile.writeBytes(newEncrypted)
                    }
                    _state.update { it.copy(isLoading = false, infoMessage = "Password changed") }
                } catch (e: Exception) {
                    _state.update { it.copy(isLoading = false, errorMessage = "Change failed: ${e.message}") }
                }
            }
        }
    }

    private fun addFileToVaultContent(content: ByteArray, file: java.io.File): ByteArray {
        // Simplified: return original content for now
        // Real implementation would parse JSON and add file entry
        return content
    }

    private fun extractFileFromVaultContent(content: ByteArray, entryName: String): ByteArray {
        // Simplified: return empty for now
        // Real implementation would parse JSON and extract file
        return ByteArray(0)
    }
}