package com.elysium.vanguard.core.cloud

import java.io.File
import java.util.Date

/**
 * Represents a file or folder in cloud storage.
 * Unified model across all cloud providers.
 */
data class CloudFile(
    val id: String,
    val name: String,
    val parentId: String?,
    val path: String,
    val isFolder: Boolean,
    val size: Long = 0,
    val mimeType: String? = null,
    val modifiedTime: Date = Date(),
    val createdTime: Date = Date(),
    val isTrashed: Boolean = false,
    val isShared: Boolean = false,
    val sharedLink: String? = null,
    val thumbnailUrl: String? = null,
    val provider: CloudProvider,
    val properties: Map<String, String> = emptyMap()
) {
    val isRoot: Boolean
        get() = parentId == null || parentId.isBlank()

    fun toLocalFile(): File = File(path)

    companion object {
        fun fromLocalFile(file: File, provider: CloudProvider, parentId: String? = null): CloudFile {
            return CloudFile(
                id = file.absolutePath,
                name = file.name,
                parentId = parentId,
                path = file.absolutePath,
                isFolder = file.isDirectory,
                size = if (file.isFile) file.length() else 0,
                mimeType = if (file.isFile) getMimeType(file) else "application/vnd.folder",
                modifiedTime = Date(file.lastModified()),
                createdTime = Date(file.lastModified()),
                provider = provider
            )
        }

        private fun getMimeType(file: File): String {
            val extension = file.extension.lowercase()
            return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: "application/octet-stream"
        }
    }
}

/**
 * Cloud folder listing result with pagination support.
 */
data class CloudFileList(
    val files: List<CloudFile>,
    val nextPageToken: String? = null,
    val hasMore: Boolean = false
)

/**
 * Upload/download progress callback.
 */
interface CloudProgressListener {
    fun onProgress(bytesTransferred: Long, totalBytes: Long, currentFile: String)
    fun onComplete(success: Boolean, error: String?)
}

/**
 * Authentication credentials for cloud providers.
 */
sealed interface CloudCredentials {
    data class OAuth2(
        val accessToken: String,
        val refreshToken: String?,
        val expiresAt: Long,
        val scope: List<String>
    ) : CloudCredentials

    data class UsernamePassword(
        val username: String,
        val password: String,
        val domain: String? = null
    ) : CloudCredentials

    data class UsernamePasswordKey(
        val username: String,
        val password: String?,
        val privateKeyPath: String?,
        val passphrase: String? = null
    ) : CloudCredentials

    object None : CloudCredentials
}

/**
 * Configuration for a cloud connection.
 */
data class CloudConnectionConfig(
    val provider: CloudProvider,
    val credentials: CloudCredentials,
    val customBaseUrl: String? = null,
    val rootFolderId: String = "root",
    val displayName: String? = null,
    val autoSync: Boolean = false,
    val syncIntervalMinutes: Int = 60,
    val onlyWifi: Boolean = true,
    val encryptFiles: Boolean = false,
    val encryptionPassword: String? = null
)

/**
 * Cloud operation result.
 */
sealed class CloudResult<out T> {
    abstract val data: T?
    abstract val error: String?

    data class Success<T>(override val data: T) : CloudResult<T>() {
        override val error: String? = null
    }

    data class Failure(override val error: String, val code: String? = null) : CloudResult<Nothing>() {
        override val data: Nothing? = null
    }

    companion object {
        fun <T> success(data: T) = Success(data)
        fun failure(error: String, code: String? = null) = Failure(error, code)
    }

    val isSuccess: Boolean
        get() = this is Success<*>
}