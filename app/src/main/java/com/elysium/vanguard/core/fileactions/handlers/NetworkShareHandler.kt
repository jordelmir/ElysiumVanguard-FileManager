package com.elysium.vanguard.core.fileactions.handlers

import com.elysium.vanguard.core.fileactions.FileAction
import com.elysium.vanguard.core.fileactions.NetworkProtocol
import java.io.File

/**
 * Phase 97 — the **network share handler** for SMB / WebDAV /
 * SFTP share descriptors.
 *
 * The handler is a thin shell over the existing
 * [NetworkShareMounter]: it (1) reads the URL + credentials from
 * the file body, (2) validates the URL scheme matches the
 * declared [NetworkProtocol], (3) delegates the actual mount to
 * the mounter.
 *
 * **Descriptor format** — the file body is a simple line-based
 * format. Lines starting with `#` are comments. The first
 * non-blank, non-comment line is the URL. Subsequent
 * `key=value` lines supply credentials:
 *
 * ```
 * # My home share
 * smb://192.168.1.10/jordan
 * username=jordan
 * password=secret
 * ```
 *
 * Embedded credentials in the URL are also supported:
 * `smb://jordan:secret@192.168.1.10/jordan`.
 *
 * **JVM testability**: the handler takes a [NetworkShareMounter]
 * interface in its constructor; production uses the
 * `ProcessLauncher`-backed impl; tests use a fake.
 */
class NetworkShareHandler @javax.inject.Inject constructor(
    private val mounter: NetworkShareMounter,
) {

    /**
     * Mount the share described by [action] in the [FileActionContext].
     * The [action] carries the file path (handler reads the body),
     * the protocol, and the target mount point.
     */
    suspend fun mount(action: FileAction.MountNetworkShare): NetworkShareMountResult {
        val descriptor = File(action.url)
        if (!descriptor.exists() || !descriptor.isFile) {
            return NetworkShareMountResult.Failure(
                message = "share descriptor not found: ${action.url}"
            )
        }
        val parsed = try {
            parseDescriptor(descriptor)
        } catch (e: Exception) {
            return NetworkShareMountResult.Failure(
                message = "could not read share descriptor: ${e.message ?: e.javaClass.simpleName}"
            )
        }
        val url = parsed.url
        if (url.isBlank()) {
            return NetworkShareMountResult.Failure(
                message = "share descriptor has no URL line: ${descriptor.absolutePath}"
            )
        }
        if (!isUrlSchemeCompatible(url, action.protocol)) {
            return NetworkShareMountResult.Failure(
                message = "URL scheme does not match declared protocol " +
                    "${action.protocol.name}: $url"
            )
        }
        return mounter.mount(
            url = url,
            protocol = action.protocol,
            username = parsed.username,
            password = parsed.password,
            descriptorName = descriptor.nameWithoutExtension,
        )
    }

    /**
     * The parsed view of a share descriptor file. The URL is the
     * first non-blank, non-comment line. Credentials are extracted
     * from `key=value` lines (keys: `username`, `password`,
     * `user`, `pass`) or from the URL's `user:pass@` segment.
     */
    internal data class ParsedDescriptor(
        val url: String,
        val username: String?,
        val password: String?,
    )

    internal fun parseDescriptor(file: File): ParsedDescriptor {
        val lines = file.readLines()
        val firstUrl = lines.firstOrNull {
            it.isNotBlank() && !it.trim().startsWith("#")
        }?.trim() ?: ""
        var username: String? = null
        var password: String? = null
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val eq = trimmed.indexOf('=')
            if (eq <= 0) continue
            val key = trimmed.substring(0, eq).trim().lowercase()
            val value = trimmed.substring(eq + 1).trim()
            when (key) {
                "username", "user" -> username = value
                "password", "pass" -> password = value
            }
        }
        // If the URL has embedded user:pass@, prefer it over the
        // file-level credentials (more specific).
        val (urlAfterCreds, urlUser, urlPass) = splitEmbeddedCredentials(firstUrl)
        return ParsedDescriptor(
            url = urlAfterCreds,
            username = urlUser ?: username,
            password = urlPass ?: password,
        )
    }

    /**
     * Split `scheme://user:pass@host/path` into
     * `scheme://host/path` + `user` + `pass`. Returns
     * `(url, null, null)` when no embedded credentials.
     */
    internal fun splitEmbeddedCredentials(url: String): Triple<String, String?, String?> {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return Triple(url, null, null)
        val afterScheme = url.substring(schemeEnd + 3)
        val atIdx = afterScheme.indexOf('@')
        if (atIdx < 0) return Triple(url, null, null)
        val creds = afterScheme.substring(0, atIdx)
        val rest = afterScheme.substring(atIdx + 1)
        val colon = creds.indexOf(':')
        return if (colon < 0) {
            Triple("${url.substring(0, schemeEnd + 3)}$rest", creds, null)
        } else {
            val u = creds.substring(0, colon)
            val p = creds.substring(colon + 1)
            Triple("${url.substring(0, schemeEnd + 3)}$rest", u, p)
        }
    }

    /**
     * The URL scheme must match the declared protocol. We accept
     * a small set of aliases per protocol so a `.webdav` file
     * can declare `dav://` (RFC 4918) or `davs://` (TLS).
     */
    private fun isUrlSchemeCompatible(url: String, protocol: NetworkProtocol): Boolean {
        val scheme = url.substringBefore("://", missingDelimiterValue = "").lowercase()
        return when (protocol) {
            NetworkProtocol.SMB -> scheme in setOf("smb", "cifs")
            NetworkProtocol.WEBDAV -> scheme in setOf("webdav", "dav", "davs", "http", "https")
            NetworkProtocol.SFTP -> scheme == "sftp"
        }
    }
}

/**
 * The [NetworkShareMounter] decouples the [NetworkShareHandler]
 * from the actual mount primitive. Production wraps the
 * [com.elysium.vanguard.core.runtime.runner.ProcessLauncher] +
 * `mount -t cifs` (SMB) / `mount -t davfs` (WebDAV); tests use
 * a fake.
 */
interface NetworkShareMounter {
    suspend fun mount(
        url: String,
        protocol: NetworkProtocol,
        username: String?,
        password: String?,
        descriptorName: String,
    ): NetworkShareMountResult
}

/**
 * The result of a network-share mount. A sealed class so the
 * caller pattern-matches on the outcome.
 */
sealed class NetworkShareMountResult {
    data class Mounted(
        val url: String,
        val protocol: NetworkProtocol,
        val mountPoint: String,
    ) : NetworkShareMountResult()

    data class Failure(
        val message: String,
    ) : NetworkShareMountResult()
}
