package com.elysium.vanguard.core.cloud

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elysium.vanguard.core.database.CloudConnectionEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * ViewModel for managing cloud storage connections and operations.
 * Provides reactive state for cloud accounts, file browsing, and transfers.
 */
@HiltViewModel
class CloudStorageViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val factory: CloudServiceFactory,
    private val repository: CloudConnectionRepository
) : ViewModel() {

    // ─────────────────────────────────────────────────────────────
    // State
    // ─────────────────────────────────────────────────────────────

    private val _connections = MutableStateFlow<List<CloudConnection>>(emptyList())
    val connections: StateFlow<List<CloudConnection>> = _connections

    private val _currentConnection = MutableStateFlow<CloudConnection?>(null)
    val currentConnection: StateFlow<CloudConnection?> = _currentConnection

    private val _currentFiles = MutableStateFlow<List<CloudFile>>(emptyList())
    val currentFiles: StateFlow<List<CloudFile>> = _currentFiles

    private val _currentFolderId = MutableStateFlow<String>("root")
    val currentFolderId: StateFlow<String> = _currentFolderId

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _transferProgress = MutableStateFlow<CloudTransferProgress?>(null)
    val transferProgress: StateFlow<CloudTransferProgress?> = _transferProgress

    private val _quota = MutableStateFlow<StorageQuota?>(null)
    val quota: StateFlow<StorageQuota?> = _quota

    // ─────────────────────────────────────────────────────────────
    // Connection Management
    // ─────────────────────────────────────────────────────────────

    init {
        loadConnections()
    }

    private fun loadConnections() {
        viewModelScope.launch(Dispatchers.IO) {
            val saved = repository.getAllConnections()
            _connections.value = saved
        }
    }

    /** Add a new cloud connection */
    fun addConnection(config: CloudConnectionConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val service = factory.createService(config.provider)
                val authResult = service.authenticate(config)

                if (authResult.isSuccess) {
                    val connection = CloudConnection(
                        id = java.util.UUID.randomUUID().toString(),
                        provider = config.provider,
                        credentials = authResult.data!!,
                        config = config,
                        createdAt = System.currentTimeMillis(),
                        lastUsedAt = System.currentTimeMillis()
                    )
                    repository.saveConnection(connection)
                    _connections.value = _connections.value + connection
                    selectConnection(connection)
                } else {
                    _error.value = authResult.error
                }
            } catch (e: Exception) {
                _error.value = "Failed to add connection: ${e.message}"
            }
        }
    }

    /** Select a connection for browsing */
    fun selectConnection(connection: CloudConnection) {
        _currentConnection.value = connection
        _currentFolderId.value = connection.config.rootFolderId
        browseFolder(connection.config.rootFolderId)
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateLastUsed(connection.id)
        }
    }

    /** Remove a cloud connection */
    fun removeConnection(connectionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteConnection(connectionId)
            _connections.value = _connections.value.filter { it.id != connectionId }
            if (_currentConnection.value?.id == connectionId) {
                _currentConnection.value = null
                _currentFiles.value = emptyList()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // File Browsing
    // ─────────────────────────────────────────────────────────────

    /** Browse a folder */
    fun browseFolder(folderId: String) {
        _currentConnection.value?.let { connection ->
            _isLoading.value = true
            _error.value = null
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val service = factory.createService(connection.provider)
                    val result = service.listFiles(folderId)

                    if (result.isSuccess) {
                        _currentFiles.value = result.data!!.files
                        _currentFolderId.value = folderId
                        // Update quota
                        val quotaResult = service.getQuota()
                        if (quotaResult.isSuccess) {
                            _quota.value = quotaResult.data
                        }
                    } else {
                        _error.value = result.error
                    }
                } catch (e: Exception) {
                    _error.value = "Failed to browse: ${e.message}"
                } finally {
                    _isLoading.value = false
                }
            }
        }
    }

    /** Search files */
    fun searchFiles(query: String) {
        _currentConnection.value?.let { connection ->
            _isLoading.value = true
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val service = factory.createService(connection.provider)
                    val result = service.searchFiles(query, _currentFolderId.value)

                    if (result.isSuccess) {
                        _currentFiles.value = result.data!!.files
                    } else {
                        _error.value = result.error
                    }
                } catch (e: Exception) {
                    _error.value = "Search failed: ${e.message}"
                } finally {
                    _isLoading.value = false
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // File Operations
    // ─────────────────────────────────────────────────────────────

    /** Upload files */
    fun uploadFiles(localFiles: List<java.io.File>) {
        _currentConnection.value?.let { connection ->
            _transferProgress.value = CloudTransferProgress(
                totalFiles = localFiles.size,
                completedFiles = 0,
                currentFile = localFiles.firstOrNull()?.name ?: "",
                bytesTransferred = 0,
                totalBytes = localFiles.sumOf { it.length() },
                isUpload = true
            )
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val service = factory.createService(connection.provider)
                    val result = service.uploadFiles(
                        _currentFolderId.value,
                        localFiles,
                        object : CloudProgressListener {
                            override fun onProgress(bytesTransferred: Long, totalBytes: Long, currentFile: String) {
                                _transferProgress.value = _transferProgress.value?.copy(
                                    bytesTransferred = bytesTransferred,
                                    totalBytes = totalBytes,
                                    currentFile = currentFile
                                )
                            }

                            override fun onComplete(success: Boolean, error: String?) {
                                if (success) {
                                    browseFolder(_currentFolderId.value)
                                } else {
                                    _error.value = error
                                }
                                _transferProgress.value = null
                            }
                        }
                    )

                    if (!result.isSuccess) {
                        _error.value = result.error
                    }
                } catch (e: Exception) {
                    _error.value = "Upload failed: ${e.message}"
                    _transferProgress.value = null
                }
            }
        }
    }

    /** Download files */
    fun downloadFiles(fileIds: List<String>, destinationDir: java.io.File) {
        _currentConnection.value?.let { connection ->
            _transferProgress.value = CloudTransferProgress(
                totalFiles = fileIds.size,
                completedFiles = 0,
                currentFile = "",
                bytesTransferred = 0,
                totalBytes = 0,
                isUpload = false
            )
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val service = factory.createService(connection.provider)
                    val result = service.downloadFiles(fileIds, destinationDir, object : CloudProgressListener {
                        override fun onProgress(bytesTransferred: Long, totalBytes: Long, currentFile: String) {
                            _transferProgress.value = _transferProgress.value?.copy(
                                bytesTransferred = bytesTransferred,
                                totalBytes = totalBytes,
                                currentFile = currentFile
                            )
                        }

                        override fun onComplete(success: Boolean, error: String?) {
                            if (!success) {
                                _error.value = error
                            }
                            _transferProgress.value = null
                        }
                    })

                    if (!result.isSuccess) {
                        _error.value = result.error
                    }
                } catch (e: Exception) {
                    _error.value = "Download failed: ${e.message}"
                    _transferProgress.value = null
                }
            }
        }
    }

    /** Delete files */
    fun deleteFiles(fileIds: List<String>, permanent: Boolean = false) {
        _currentConnection.value?.let { connection ->
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val service = factory.createService(connection.provider)
                    val result = service.deleteFiles(fileIds, permanent)

                    if (result.isSuccess) {
                        browseFolder(_currentFolderId.value)
                    } else {
                        _error.value = result.error
                    }
                } catch (e: Exception) {
                    _error.value = "Delete failed: ${e.message}"
                }
            }
        }
    }

    /** Create folder */
    fun createFolder(name: String) {
        _currentConnection.value?.let { connection ->
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val service = factory.createService(connection.provider)
                    val result = service.createFolder(_currentFolderId.value, name)

                    if (result.isSuccess) {
                        browseFolder(_currentFolderId.value)
                    } else {
                        _error.value = result.error
                    }
                } catch (e: Exception) {
                    _error.value = "Create folder failed: ${e.message}"
                }
            }
        }
    }

    /** Rename file */
    fun renameFile(fileId: String, newName: String) {
        _currentConnection.value?.let { connection ->
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val service = factory.createService(connection.provider)
                    val result = service.renameFile(fileId, newName)

                    if (result.isSuccess) {
                        browseFolder(_currentFolderId.value)
                    } else {
                        _error.value = result.error
                    }
                } catch (e: Exception) {
                    _error.value = "Rename failed: ${e.message}"
                }
            }
        }
    }

    /** Move files */
    fun moveFiles(fileIds: List<String>, destinationFolderId: String) {
        _currentConnection.value?.let { connection ->
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val service = factory.createService(connection.provider)
                    // Move one by one for now
                    for (fileId in fileIds) {
                        val result = service.moveFile(fileId, destinationFolderId)
                        if (!result.isSuccess) {
                            _error.value = result.error
                            return@launch
                        }
                    }
                    browseFolder(_currentFolderId.value)
                } catch (e: Exception) {
                    _error.value = "Move failed: ${e.message}"
                }
            }
        }
    }

    /** Copy files */
    fun copyFiles(fileIds: List<String>, destinationFolderId: String) {
        _currentConnection.value?.let { connection ->
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val service = factory.createService(connection.provider)
                    for (fileId in fileIds) {
                        val result = service.copyFile(fileId, destinationFolderId)
                        if (!result.isSuccess) {
                            _error.value = result.error
                            return@launch
                        }
                    }
                    browseFolder(_currentFolderId.value)
                } catch (e: Exception) {
                    _error.value = "Copy failed: ${e.message}"
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Sharing
    // ─────────────────────────────────────────────────────────────

    /** Create share link */
    fun createShareLink(fileId: String, role: ShareRole = ShareRole.VIEWER, expirationDays: Int? = null): CloudResult<String> {
        _currentConnection.value?.let { connection ->
            return runBlocking {
                try {
                    val service = factory.createService(connection.provider)
                    service.createShareLink(fileId, role, expirationDays)
                } catch (e: Exception) {
                    CloudResult.failure("Share failed: ${e.message}")
                }
            }
        }
        return CloudResult.failure("No connection selected")
    }

    // ─────────────────────────────────────────────────────────────
    // Navigation Helpers
    // ─────────────────────────────────────────────────────────────

    /** Go to parent folder */
    fun goToParent() {
        _currentFiles.value.firstOrNull { it.id == _currentFolderId.value }?.parentId?.let {
            browseFolder(it)
        } ?: _currentConnection.value?.let { connection ->
            browseFolder(connection.config.rootFolderId)
        }
    }

    /** Navigate into a folder */
    fun navigateInto(folder: CloudFile) {
        if (folder.isFolder) {
            browseFolder(folder.id)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Sync
    // ─────────────────────────────────────────────────────────────

    /** Sync a connection */
    fun syncConnection(connectionId: String) {
        _connections.value.firstOrNull { it.id == connectionId }?.let { connection ->
            if (connection.config.autoSync) {
                // Trigger background sync
                // TODO: Implement sync logic
            }
        }
    }
}

/**
 * Cloud connection with saved configuration (domain model for UI).
 */
data class CloudConnection(
    val id: String,
    val provider: CloudProvider,
    val credentials: CloudCredentials,
    val config: CloudConnectionConfig,
    val createdAt: Long,
    val lastUsedAt: Long
)

/**
 * Transfer progress tracking.
 */
data class CloudTransferProgress(
    val totalFiles: Int,
    val completedFiles: Int,
    val currentFile: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val isUpload: Boolean
) {
    val progressPercent: Float
        get() = if (totalBytes > 0) (bytesTransferred.toFloat() / totalBytes * 100) else 0f

    val formattedSpeed: String
        get() {
            return formatBytes(bytesTransferred) + "/s"
        }

    val formattedProgress: String
        get() = "${formatBytes(bytesTransferred)} / ${formatBytes(totalBytes)}"

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
        else -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
    }
}

/**
 * Repository for persisting cloud connections.
 */
interface CloudConnectionRepository {
    suspend fun getAllConnections(): List<CloudConnection>
    suspend fun saveConnection(connection: CloudConnection)
    suspend fun deleteConnection(connectionId: String)
    suspend fun updateLastUsed(connectionId: String)
}

/**
 * Room-based implementation of CloudConnectionRepository.
 */
class RoomCloudConnectionRepository @Inject constructor(
    private val dao: CloudConnectionDao
) : CloudConnectionRepository {

    override suspend fun getAllConnections(): List<CloudConnection> {
        return dao.getAll().map { entity ->
            entity.toCloudConnection()
        }
    }

    override suspend fun saveConnection(connection: CloudConnection) {
        val entity = CloudConnectionEntity.fromCloudConnection(connection)
        dao.insert(entity)
    }

    override suspend fun deleteConnection(connectionId: String) {
        dao.deleteById(connectionId)
    }

    override suspend fun updateLastUsed(connectionId: String) {
        // TODO: Implement when DAO supports updateLastUsed
        // dao.updateLastUsed(connectionId, System.currentTimeMillis())
    }
}