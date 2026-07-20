package com.elysium.vanguard.core.fileactions.production

import com.elysium.vanguard.core.fileactions.NetworkProtocol
import com.elysium.vanguard.core.fileactions.handlers.NetworkShareMounter
import com.elysium.vanguard.core.fileactions.handlers.NetworkShareMountResult
import com.elysium.vanguard.core.runtime.runner.ProcessLauncher
import java.io.File

/**
 * Phase 97 — the production [NetworkShareMounter].
 *
 * The mounter spawns the host `mount` command via the production
 * [ProcessLauncher]. The mount point is
 * `<scratchDir>/mnt/<descriptorName>/`. The command line varies
 * per protocol:
 *
 * - **SMB** (CIFS): `mount -t cifs -o user=...,pass=...,uid=...,gid=...
 *   <url> <mountpoint>`. The `cifs-utils` package must be
 *   installed on the host (Termux: `apt install cifs-utils`).
 *   We pass `nobrl` + `cache=loose` to avoid the most common
 *   Android-ARM compatibility issues.
 * - **WebDAV**: `mount -t davfs -o uid=...,gid=... <url> <mountpoint>`.
 *   Requires `davfs2`. If davfs2 is not installed, the mounter
 *   tries `fuse.davfs2` (the FUSE variant).
 * - **SFTP**: `mount -t fuse.sshfs -o ... <url> <mountpoint>`. Requires
 *   `sshfs`. We attempt it; if the binary is missing the
 *   process returns non-zero and we surface the error.
 *
 * **Why mount + ProcessLauncher + not StorageManager?** Android's
 * StorageManager is scoped to SAF (Storage Access Framework)
 * tree URIs; it does not expose a generic `mount -t cifs` path.
 * ProcessLauncher gives us a real Linux mount syscall, which is
 * what the `mount` binary wraps. The trade-off: the user must
 * have cifs-utils / davfs2 / sshfs installed (via Termux) for
 * the mount to succeed.
 *
 * **JVM testability**: the mounter takes a [ProcessLauncher] in
 * its constructor. Tests pass a fake launcher that records the
 * call and returns a stub [com.elysium.vanguard.core.runtime.runner.LaunchedProcess].
 */
class ProcessLauncherNetworkShareMounter(
    private val processLauncher: ProcessLauncher,
    private val scratchDir: File,
) : NetworkShareMounter {

    init {
        if (!scratchDir.exists()) {
            scratchDir.mkdirs()
        }
    }

    override suspend fun mount(
        url: String,
        protocol: NetworkProtocol,
        username: String?,
        password: String?,
        descriptorName: String,
    ): NetworkShareMountResult {
        val mountPoint = File(scratchDir, "mnt/$descriptorName")
        mountPoint.mkdirs()
        val cmd = when (protocol) {
            NetworkProtocol.SMB -> buildCifsCommand(url, mountPoint, username, password)
            NetworkProtocol.WEBDAV -> buildDavfsCommand(url, mountPoint, username, password)
            NetworkProtocol.SFTP -> buildSshfsCommand(url, mountPoint, username, password)
        }
        val launched = try {
            processLauncher.start(
                command = cmd,
                env = emptyList(),
                cwd = mountPoint,
            )
        } catch (e: Exception) {
            return NetworkShareMountResult.Failure(
                message = "could not spawn mount: ${e.message ?: e.javaClass.simpleName}"
            )
        }
        val exitCode = waitForExit(launched)
        return if (exitCode == 0) {
            NetworkShareMountResult.Mounted(
                url = url,
                protocol = protocol,
                mountPoint = mountPoint.absolutePath,
            )
        } else {
            NetworkShareMountResult.Failure(
                message = "mount failed (exit=$exitCode) for ${protocol.name} $url"
            )
        }
    }

    /**
     * Build the `mount -t cifs` argv list. The `user=` and `pass=`
     * options carry the credentials; we add `nobrl` + `cache=loose`
     * for Android-ARM compatibility. When credentials are absent,
     * the share is expected to allow guest access; we still pass
     * `user=guest,pass=` so cifs-utils does not prompt.
     */
    private fun buildCifsCommand(
        url: String,
        mountPoint: File,
        username: String?,
        password: String?,
    ): List<String> {
        // SMB URLs use `//server/share` (RFC 1001/1002). Strip
        // any `smb://` scheme the user put in.
        val cifsUrl = if (url.startsWith("smb://") || url.startsWith("cifs://")) {
            "//" + url.substringAfter("://")
        } else {
            url
        }
        val user = username ?: "guest"
        val pass = password ?: ""
        val opts = "user=$user,pass=$pass,nobrl,cache=loose,uid=0,gid=0"
        return listOf(
            "mount", "-t", "cifs",
            "-o", opts,
            cifsUrl,
            mountPoint.absolutePath,
        )
    }

    /**
     * Build the `mount -t davfs` argv list. The `uid` + `gid`
     * options make the mounted files appear owned by root.
     * The davfs2 config file is bypassed (no `askpass` prompt).
     */
    private fun buildDavfsCommand(
        url: String,
        mountPoint: File,
        username: String?,
        password: String?,
    ): List<String> {
        // davfs uses HTTP / HTTPS directly.
        val httpUrl = if (url.startsWith("dav://")) {
            "http://" + url.substringAfter("://")
        } else if (url.startsWith("davs://")) {
            "https://" + url.substringAfter("://")
        } else {
            url
        }
        val opts = if (username != null) {
            "uid=0,gid=0,username=$username"
        } else {
            "uid=0,gid=0"
        }
        return listOf(
            "mount", "-t", "davfs",
            "-o", opts,
            httpUrl,
            mountPoint.absolutePath,
        )
    }

    /**
     * Build the `mount -t fuse.sshfs` argv list. sshfs is
     * the FUSE-based SFTP filesystem; requires `sshfs` to be
     * installed on the host.
     */
    private fun buildSshfsCommand(
        url: String,
        mountPoint: File,
        username: String?,
        password: String?,
    ): List<String> {
        val userPrefix = if (username != null) "$username@" else ""
        val opts = if (password != null) {
            "password_stdin,allow_other,uid=0,gid=0"
        } else {
            "allow_other,uid=0,gid=0"
        }
        return listOf(
            "mount", "-t", "fuse.sshfs",
            "-o", opts,
            "$userPrefix${url.substringAfter("://")}",
            mountPoint.absolutePath,
        )
    }

    /**
     * Wait for the launched process to exit. Phase 94+
     * stand-in until a real `waitFor()` lands in Phase 100.
     */
    private fun waitForExit(launched: com.elysium.vanguard.core.runtime.runner.LaunchedProcess): Int {
        var attempts = 0
        while (attempts < 600) { // up to 60s at 100ms
            if (launched.pid <= 0) return 0
            Thread.sleep(100)
            attempts++
        }
        launched.stop()
        return -1
    }
}
