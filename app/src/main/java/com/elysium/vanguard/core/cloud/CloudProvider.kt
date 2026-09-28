package com.elysium.vanguard.core.cloud

/**
 * Cloud storage provider types supported by the app.
 * Aligned with MiXplorer/Solid Explorer supported providers.
 */
enum class CloudProvider(
    val displayName: String,
    val iconName: String,
    val authType: AuthType,
    val capabilities: Set<CloudCapability>,
    val baseUrl: String? = null
) {
    GOOGLE_DRIVE(
        displayName = "Google Drive",
        iconName = "google_drive",
        authType = AuthType.OAUTH2,
        capabilities = setOf(CloudCapability.LIST, CloudCapability.DOWNLOAD, CloudCapability.UPLOAD,
            CloudCapability.DELETE, CloudCapability.MOVE, CloudCapability.COPY, CloudCapability.MKDIR,
            CloudCapability.SEARCH, CloudCapability.SHARE, CloudCapability.TRASH),
        baseUrl = "https://www.googleapis.com/drive/v3"
    ),
    ONEDRIVE(
        displayName = "OneDrive",
        iconName = "onedrive",
        authType = AuthType.OAUTH2,
        capabilities = setOf(CloudCapability.LIST, CloudCapability.DOWNLOAD, CloudCapability.UPLOAD,
            CloudCapability.DELETE, CloudCapability.MOVE, CloudCapability.COPY, CloudCapability.MKDIR,
            CloudCapability.SEARCH, CloudCapability.SHARE, CloudCapability.TRASH),
        baseUrl = "https://graph.microsoft.com/v1.0"
    ),
    ONEDRIVE_BUSINESS(
        displayName = "OneDrive Business",
        iconName = "onedrive_business",
        authType = AuthType.OAUTH2,
        capabilities = setOf(CloudCapability.LIST, CloudCapability.DOWNLOAD, CloudCapability.UPLOAD,
            CloudCapability.DELETE, CloudCapability.MOVE, CloudCapability.COPY, CloudCapability.MKDIR,
            CloudCapability.SEARCH, CloudCapability.SHARE, CloudCapability.TRASH),
        baseUrl = "https://graph.microsoft.com/v1.0"
    ),
    DROPBOX(
        displayName = "Dropbox",
        iconName = "dropbox",
        authType = AuthType.OAUTH2,
        capabilities = setOf(CloudCapability.LIST, CloudCapability.DOWNLOAD, CloudCapability.UPLOAD,
            CloudCapability.DELETE, CloudCapability.MOVE, CloudCapability.COPY, CloudCapability.MKDIR,
            CloudCapability.SEARCH, CloudCapability.SHARE, CloudCapability.TRASH),
        baseUrl = "https://api.dropboxapi.com/2"
    ),
    BOX(
        displayName = "Box",
        iconName = "box",
        authType = AuthType.OAUTH2,
        capabilities = setOf(CloudCapability.LIST, CloudCapability.DOWNLOAD, CloudCapability.UPLOAD,
            CloudCapability.DELETE, CloudCapability.MOVE, CloudCapability.COPY, CloudCapability.MKDIR,
            CloudCapability.SEARCH, CloudCapability.SHARE, CloudCapability.TRASH),
        baseUrl = "https://api.box.com/2.0"
    ),
    MEGA(
        displayName = "MEGA",
        iconName = "mega",
        authType = AuthType.USERNAME_PASSWORD,
        capabilities = setOf(CloudCapability.LIST, CloudCapability.DOWNLOAD, CloudCapability.UPLOAD,
            CloudCapability.DELETE, CloudCapability.MOVE, CloudCapability.COPY, CloudCapability.MKDIR,
            CloudCapability.SEARCH, CloudCapability.SHARE),
        baseUrl = "https://g.api.mega.co.nz"
    ),
    YANDEX_DISK(
        displayName = "Yandex Disk",
        iconName = "yandex",
        authType = AuthType.OAUTH2,
        capabilities = setOf(CloudCapability.LIST, CloudCapability.DOWNLOAD, CloudCapability.UPLOAD,
            CloudCapability.DELETE, CloudCapability.MOVE, CloudCapability.COPY, CloudCapability.MKDIR,
            CloudCapability.SEARCH, CloudCapability.SHARE, CloudCapability.TRASH),
        baseUrl = "https://cloud-api.yandex.net/v1/disk"
    ),
    NEXTCLOUD(
        displayName = "Nextcloud / ownCloud",
        iconName = "nextcloud",
        authType = AuthType.USERNAME_PASSWORD,
        capabilities = setOf(CloudCapability.LIST, CloudCapability.DOWNLOAD, CloudCapability.UPLOAD,
            CloudCapability.DELETE, CloudCapability.MOVE, CloudCapability.COPY, CloudCapability.MKDIR,
            CloudCapability.SEARCH, CloudCapability.SHARE, CloudCapability.TRASH, CloudCapability.CALENDAR,
            CloudCapability.CONTACTS),
        baseUrl = null // User-provided
    ),
    WEBDAV(
        displayName = "WebDAV",
        iconName = "webdav",
        authType = AuthType.USERNAME_PASSWORD,
        capabilities = setOf(CloudCapability.LIST, CloudCapability.DOWNLOAD, CloudCapability.UPLOAD,
            CloudCapability.DELETE, CloudCapability.MOVE, CloudCapability.COPY, CloudCapability.MKDIR),
        baseUrl = null // User-provided
    ),
    SFTP(
        displayName = "SFTP / SSH",
        iconName = "sftp",
        authType = AuthType.USERNAME_PASSWORD_KEY,
        capabilities = setOf(CloudCapability.LIST, CloudCapability.DOWNLOAD, CloudCapability.UPLOAD,
            CloudCapability.DELETE, CloudCapability.MOVE, CloudCapability.COPY, CloudCapability.MKDIR,
            CloudCapability.SYMLINK),
        baseUrl = null // User-provided
    ),
    FTP(
        displayName = "FTP / FTPS",
        iconName = "ftp",
        authType = AuthType.USERNAME_PASSWORD,
        capabilities = setOf(CloudCapability.LIST, CloudCapability.DOWNLOAD, CloudCapability.UPLOAD,
            CloudCapability.DELETE, CloudCapability.MOVE, CloudCapability.COPY, CloudCapability.MKDIR),
        baseUrl = null // User-provided
    ),
    SMB(
        displayName = "SMB / CIFS (Samba)",
        iconName = "smb",
        authType = AuthType.USERNAME_PASSWORD,
        capabilities = setOf(CloudCapability.LIST, CloudCapability.DOWNLOAD, CloudCapability.UPLOAD,
            CloudCapability.DELETE, CloudCapability.MOVE, CloudCapability.COPY, CloudCapability.MKDIR),
        baseUrl = null // User-provided
    ),
    LOCAL_SERVER(
        displayName = "Local Network Server",
        iconName = "local_server",
        authType = AuthType.NONE,
        capabilities = setOf(CloudCapability.LIST, CloudCapability.DOWNLOAD, CloudCapability.UPLOAD),
        baseUrl = null
    );

    companion object {
        val all: List<CloudProvider> = entries.toList()
        val oauthProviders = all.filter { it.authType == AuthType.OAUTH2 }
        val credentialProviders = all.filter { it.authType in setOf(AuthType.USERNAME_PASSWORD, AuthType.USERNAME_PASSWORD_KEY) }
    }
}

enum class AuthType {
    OAUTH2,
    USERNAME_PASSWORD,
    USERNAME_PASSWORD_KEY,
    NONE
}

enum class CloudCapability {
    LIST,
    DOWNLOAD,
    UPLOAD,
    DELETE,
    MOVE,
    COPY,
    MKDIR,
    SEARCH,
    SHARE,
    TRASH,
    SYMLINK,
    CALENDAR,
    CONTACTS
}