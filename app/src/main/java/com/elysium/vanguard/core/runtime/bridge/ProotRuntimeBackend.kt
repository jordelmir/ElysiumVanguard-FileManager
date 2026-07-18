package com.elysium.vanguard.core.runtime.bridge

import com.elysium.vanguard.core.runtime.distros.launcher.NativeProotLauncher
import com.elysium.vanguard.core.runtime.distros.launcher.ProotNativeLibrary
import com.elysium.vanguard.core.runtime.domain.BackendKind
import com.elysium.vanguard.core.runtime.domain.CapabilityProfile
import com.elysium.vanguard.core.runtime.domain.ExitReport
import com.elysium.vanguard.core.runtime.domain.RuntimeBackend
import com.elysium.vanguard.core.runtime.domain.RuntimeCapability
import com.elysium.vanguard.core.runtime.domain.RuntimeErrorCode
import com.elysium.vanguard.core.runtime.domain.RuntimeError
import com.elysium.vanguard.core.runtime.domain.RuntimeSession
import com.elysium.vanguard.core.runtime.domain.RuntimeSpec
import com.elysium.vanguard.core.runtime.domain.SessionSpec
import com.elysium.vanguard.core.runtime.domain.SessionState
import com.elysium.vanguard.core.runtime.domain.TerminalSize
import com.elysium.vanguard.core.runtime.terminal.pty.NativePty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Phase 2 — Concrete RuntimeBackend for PRoot-based Linux sessions.
 *
 * This adapter bridges the domain-layer [RuntimeBackend] interface to
 * the actual PRoot launcher and native PTY process management. It:
 *  - Validates session specs against PRoot capabilities
 *  - Constructs the proot command line through [NativeProotLauncher]
 *    so the user does not have to know the proot flag set
 *  - Launches processes via [NativePty] under a real PTY
 *  - Tracks session lifecycle and provides exit reports
 *  - Reports capabilities honestly (no DISPLAY unless X11 server exists)
 *  - Stops the entire process group, not just the parent PID, so PRoot
 *    child processes cannot outlive the session
 */
class ProotRuntimeBackend(
    private val rootfsDir: File,
    private val fileSystemBridge: FilesystemBridge? = null,
    private val bundledAbis: Set<String> = emptySet(),
    private val nativeLibraryDir: File? = null,
    private val userProotDir: File? = null,
    private val termuxProotCandidates: List<File> = ProotNativeLibrary.DEFAULT_TERMUX_PROBES,
    private val runtimeTmpDir: File? = null,
    private val additionalMounts: List<com.elysium.vanguard.core.runtime.bridge.MountEntry> = emptyList(),
    private val guestDnsConfigProvider: com.elysium.vanguard.core.runtime.network.GuestDnsConfigProvider =
        com.elysium.vanguard.core.runtime.network.GuestDnsConfigProvider.NONE
) : RuntimeBackend {

    private val prootLauncher: NativeProotLauncher = NativeProotLauncher(
        bundledAbis = bundledAbis,
        nativeLibrary = if (nativeLibraryDir != null || userProotDir != null || bundledAbis.isNotEmpty()) {
            ProotNativeLibrary(
                bundledAbis = bundledAbis,
                nativeLibraryDir = nativeLibraryDir,
                userProotDir = userProotDir,
                termuxProotCandidates = termuxProotCandidates
            )
        } else null,
        runtimeTmpDir = runtimeTmpDir,
        additionalMounts = additionalMounts,
        guestDnsConfigProvider = guestDnsConfigProvider
    )

    override val runtime: RuntimeSpec = RuntimeSpec(
        id = com.elysium.vanguard.core.runtime.domain.RuntimeId("proot-linux"),
        displayName = "PRoot Linux (ARM64)",
        backend = BackendKind.PROOT_LINUX
    )

    override fun capabilities(): CapabilityProfile {
        val available = mutableSetOf<RuntimeCapability>()
        val reasons = mutableMapOf<RuntimeCapability, String>()

        available += RuntimeCapability.PTY
        available += RuntimeCapability.RESIZE
        available += RuntimeCapability.LINUX_ARM64
        available += RuntimeCapability.PROCESS_GROUP_SIGNALS
        available += RuntimeCapability.FILESYSTEM_BRIDGE
        available += RuntimeCapability.NETWORK_BRIDGE

        if (rootfsDir.isDirectory) {
            val x11Sock = File(rootfsDir, "tmp/.X11-unix/X1")
            if (x11Sock.exists()) {
                available += RuntimeCapability.DISPLAY
            } else {
                reasons[RuntimeCapability.DISPLAY] = "No X11 socket found in rootfs"
            }
        } else {
            reasons[RuntimeCapability.DISPLAY] = "Rootfs not installed"
            reasons[RuntimeCapability.FILESYSTEM_BRIDGE] = "Rootfs not installed"
            reasons[RuntimeCapability.NETWORK_BRIDGE] = "Rootfs not installed"
        }

        if (!prootLauncher.isAvailable(rootfsDir)) {
            reasons[RuntimeCapability.PTY] = "PRoot library not available; using pipe-only fallback"
        }

        return CapabilityProfile(available = available, unavailableReasons = reasons)
    }

    override suspend fun open(spec: SessionSpec): RuntimeSession = withContext(Dispatchers.IO) {
        val validationError = validateSpec(spec)
        if (validationError != null) {
            throw IllegalStateException(validationError.message)
        }

        val script = spec.argv.joinToString(" ") { shellQuote(it) }
        val prootCommand = prootLauncher.buildShellCommand(rootfsDir, script)
        if (prootCommand.firstOrNull() == "proot-missing") {
            throw IllegalStateException(
                "PRoot binary not found; cannot start session ${spec.id.value}"
            )
        }
        val launcherEnv = prootLauncher.environmentVariables(rootfsDir)
        val env = buildEnvironment(spec, launcherEnv)

        try {
            val nativePty = NativePty.spawn(
                command = prootCommand,
                environment = env,
                workingDirectory = rootfsDir,
                columns = spec.terminalSize.columns,
                rows = spec.terminalSize.rows
            )
            PRootSession(
                id = spec.id,
                nativePty = nativePty,
                initialSize = spec.terminalSize,
                rootfsDir = rootfsDir
            )
        } catch (io: IOException) {
            throw IllegalStateException(
                "Failed to spawn PRoot process: ${io.message}",
                io
            )
        }
    }

    private fun validateSpec(spec: SessionSpec): RuntimeError? {
        if (!rootfsDir.isDirectory) {
            return RuntimeError(
                code = RuntimeErrorCode.ROOTFS_MISSING,
                message = "Rootfs directory does not exist: $rootfsDir",
                recoverable = true,
                suggestedAction = "Install a Linux distribution first"
            )
        }

        val bins = listOf("bin/sh", "bin/bash", "usr/bin/sh", "usr/bin/bash")
        val hasShell = bins.any { File(rootfsDir, it).canExecute() }
        if (!hasShell) {
            return RuntimeError(
                code = RuntimeErrorCode.ROOTFS_MISSING,
                message = "No shell found in rootfs at $rootfsDir",
                recoverable = false,
                suggestedAction = "Reinstall the distribution"
            )
        }

        if (spec.terminalSize.columns < 2 || spec.terminalSize.rows < 1) {
            return RuntimeError(
                code = RuntimeErrorCode.INVALID_SPEC,
                message = "Terminal size too small: ${spec.terminalSize}",
                recoverable = false
            )
        }

        return null
    }

    private fun buildEnvironment(
        spec: SessionSpec,
        launcherEnv: List<Pair<String, String>>
    ): Map<String, String> {
        val env = LinkedHashMap<String, String>()
        // PRoot-specific environment comes first so the caller's
        // spec.environment can override it (e.g. a different LD_LIBRARY_PATH).
        launcherEnv.forEach { (key, value) -> env[key] = value }
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"
        env["HOME"] = "/root"
        env["USER"] = "root"
        env["LANG"] = "en_US.UTF-8"
        env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        env["TMPDIR"] = "/tmp"
        spec.environment.forEach { (key, value) -> env[key] = value }
        return env
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}

/**
 * Concrete RuntimeSession backed by a NativePty process.
 *
 * stop() implements the contract from section 9 of the master order:
 * the entire process group is terminated (SIGTERM, then SIGKILL after
 * a grace deadline), the PTY is closed, the child is reaped, and an
 * ExitReport is produced. The previous draft only closed the PTY and
 * trusted waitForExit to return quickly; that left PRoot child
 * processes orphaned when their grandchild ignored SIGTERM on the
 * master FD close.
 */
internal class PRootSession(
    override val id: com.elysium.vanguard.core.runtime.domain.SessionId,
    private val nativePty: NativePty,
    private val initialSize: TerminalSize,
    private val rootfsDir: File
) : RuntimeSession {

    @Volatile
    override var state: SessionState = SessionState.Running(
        pid = nativePty.pid,
        startedAtMs = System.currentTimeMillis()
    )
        private set

    override suspend fun write(bytes: ByteArray): Result<Int> = runCatching {
        nativePty.write(bytes)
        bytes.size
    }

    override suspend fun resize(size: TerminalSize): Result<Unit> = runCatching {
        nativePty.resize(columns = size.columns, rows = size.rows)
    }

    override suspend fun interrupt(): Result<Unit> = runCatching {
        // Ctrl-C is 0x03; the terminal discipline forwards it to the
        // foreground process group, not just the shell PID.
        nativePty.write(byteArrayOf(0x03))
    }

    override suspend fun stop(): ExitReport {
        val startedAt = (state as? SessionState.Running)?.startedAtMs
        state = SessionState.Stopping

        // Phase 1 — graceful: SIGTERM the entire process group.
        val pid = nativePty.pid
        var forced = false
        var processGroupClean = false
        try {
            // NativePty.signal emits the signal to the negative PID,
            // which targets the whole process group that the child
            // was made leader of during spawn.
            nativePty.signal(SIGTERM)
            val exit = waitForExitUpTo(GRACEFUL_TERM_MS)
            if (exit != null) {
                processGroupClean = true
            }
        } catch (_: Exception) {
            // Continue to phase 2.
        }

        // Phase 2 — escalation: SIGKILL if graceful path did not finish.
        if (!processGroupClean) {
            try {
                nativePty.signal(SIGKILL)
                val exit = waitForExitUpTo(GRACEFUL_KILL_MS)
                if (exit != null) {
                    processGroupClean = true
                }
                forced = true
            } catch (_: Exception) {
                // We still close below.
            }
        }

        // Phase 3 — close the master FD. After SIGKILL, any descendant
        // holding the slave FD gets SIGHUP and exits.
        try {
            nativePty.close()
        } catch (_: Exception) {
            // Close is best-effort; the process group is already dead.
        }

        val exitCode = try {
            nativePty.waitForExit(WAIT_FOR_EXIT_TIMEOUT_MS)
        } catch (_: Exception) {
            null
        }

        val now = System.currentTimeMillis()
        val report = ExitReport(
            exitCode = exitCode,
            signal = if (forced) SIGKILL else null,
            startedAtMs = startedAt,
            finishedAtMs = now,
            forced = forced,
            processGroupClean = processGroupClean,
            closedFileDescriptors = 1,
            diagnostic = if (processGroupClean) {
                "PRoot session stopped"
            } else {
                "PRoot session stopped with possible process group residue at $rootfsDir"
            }
        )
        state = SessionState.Stopped(report)
        return report
    }

    private suspend fun waitForExitUpTo(timeoutMs: Int): Int? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val status = try {
                nativePty.waitForExit(50)
            } catch (_: Exception) {
                return null
            }
            if (status != null) return status
            delay(20)
        }
        return null
    }

    private companion object {
        // POSIX signal numbers.
        private const val SIGTERM = 15
        private const val SIGKILL = 9
        private const val GRACEFUL_TERM_MS = 1500
        private const val GRACEFUL_KILL_MS = 1000
        private const val WAIT_FOR_EXIT_TIMEOUT_MS = 500
    }
}
