package com.elysium.vanguard.core.sftp

import android.net.Uri
import java.io.File
import java.security.SecureRandom

/**
 * PHASE 2.4 — SFTP server configuration.
 *
 * Why a separate config object: the user gets a few settings that affect how the
 * server behaves (port, auth, root) and we don't want to bake them into the
 * server class. The orchestrator builds the config and hands it to the server.
 *
 * Auth model: shared username + auto-rotated password (the same "Bearer token"
 * pattern we use for the HTTP server). The password is shown to the user in
 * the UI next to a QR-encoded `sftp://user@host:port/` URL.
 *
 * Why a single shared user instead of per-device accounts: SFTP password auth
 * with multiple users adds a user table we don't need. The threat model is
 * "laptop on the same Wi-Fi" — anyone who can reach the port can MITM nothing
 * because they still need the password. For multi-user we'd need a UI to add
 * users, store hashed creds, and revoke access — out of scope for now.
 */
data class SftpConfig(
    val port: Int = DEFAULT_PORT,
    val bindAddress: String = DEFAULT_BIND_ADDRESS,
    val username: String = DEFAULT_USER,
    val password: String = generatePassword(),
    /** Root directory exposed to SFTP clients. Either a [File] (filesystem mode)
     *  or a [Uri] (SAF tree mode). */
    val root: RootSpec,
    /** Maximum concurrent SSH sessions. */
    val maxSessions: Int = 4
) {
    sealed class RootSpec {
        data class Filesystem(val dir: File) : RootSpec()
        data class SafTree(val treeUri: Uri) : RootSpec()
    }

    companion object {
        const val DEFAULT_PORT = 2222
        const val DEFAULT_BIND_ADDRESS = "0.0.0.0"
        const val DEFAULT_USER = "elysium"

        /** 16-byte URL-safe password. 22 base64url chars, copy-paste friendly. */
        fun generatePassword(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}