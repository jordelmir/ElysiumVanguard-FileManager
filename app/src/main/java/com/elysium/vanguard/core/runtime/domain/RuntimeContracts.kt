package com.elysium.vanguard.core.runtime.domain

import java.io.File
import java.util.UUID

@JvmInline
value class RuntimeId(val value: String) {
    init { require(value.isNotBlank()) { "runtime id must not be blank" } }
}

@JvmInline
value class SessionId(val value: String) {
    init { require(value.isNotBlank()) { "session id must not be blank" } }

    companion object {
        fun create(): SessionId = SessionId(UUID.randomUUID().toString())
    }
}

@JvmInline
value class DistroId(val value: String) {
    init { require(value.isNotBlank()) { "distro id must not be blank" } }
}

enum class RuntimeCapability {
    PTY,
    PROCESS_GROUP_SIGNALS,
    RESIZE,
    LINUX_ARM64,
    FILESYSTEM_BRIDGE,
    NETWORK_BRIDGE,
    DISPLAY,
    AUDIO,
    CLIPBOARD,
    SUSPEND,
    SNAPSHOT
}

data class CapabilityProfile(
    val available: Set<RuntimeCapability>,
    val unavailableReasons: Map<RuntimeCapability, String> = emptyMap()
) {
    fun supports(capability: RuntimeCapability): Boolean = capability in available

    init {
        require(available.intersect(unavailableReasons.keys).isEmpty()) {
            "a capability cannot be both available and unavailable"
        }
    }
}

data class TerminalSize(val columns: Int, val rows: Int) {
    init {
        require(columns in MIN_COLUMNS..MAX_COLUMNS) { "columns out of range: $columns" }
        require(rows in MIN_ROWS..MAX_ROWS) { "rows out of range: $rows" }
    }

    companion object {
        const val MIN_COLUMNS = 2
        const val MAX_COLUMNS = 1_000
        const val MIN_ROWS = 1
        const val MAX_ROWS = 1_000
    }
}

data class RuntimeSpec(
    val id: RuntimeId,
    val displayName: String,
    val backend: BackendKind
) {
    init { require(displayName.isNotBlank()) { "display name must not be blank" } }
}

enum class BackendKind {
    ANDROID_SHELL,
    PROOT_LINUX,
    LINUX_VM,
    WIN_LAYER,
    WINDOWS_VM,
    REMOTE
}

data class SessionSpec(
    val id: SessionId = SessionId.create(),
    val runtimeId: RuntimeId,
    val distroId: DistroId? = null,
    val argv: List<String>,
    val environment: Map<String, String> = emptyMap(),
    val workingDirectory: File? = null,
    val terminalSize: TerminalSize = TerminalSize(columns = 80, rows = 24)
) {
    init {
        require(argv.isNotEmpty()) { "argv must not be empty" }
        require(argv[0].isNotBlank()) { "argv[0] must not be blank" }
        require(environment.keys.all(::isValidEnvironmentKey)) { "invalid environment key" }
        require(environment.values.none { '\u0000' in it }) { "environment values cannot contain NUL" }
        require(argv.none { '\u0000' in it }) { "argv cannot contain NUL" }
    }

    private fun isValidEnvironmentKey(key: String): Boolean =
        key.isNotEmpty() && '=' !in key && '\u0000' !in key
}

sealed interface SessionState {
    data object Created : SessionState
    data object Validating : SessionState
    data object Preparing : SessionState
    data object Starting : SessionState
    data class Running(val pid: Long, val startedAtMs: Long) : SessionState
    data object Suspending : SessionState
    data object Suspended : SessionState
    data object Recovering : SessionState
    data object Stopping : SessionState
    data class Stopped(val report: ExitReport) : SessionState
    data class Failed(val error: RuntimeError) : SessionState
}

data class ExitReport(
    val exitCode: Int?,
    val signal: Int?,
    val startedAtMs: Long?,
    val finishedAtMs: Long,
    val forced: Boolean,
    val processGroupClean: Boolean,
    val closedFileDescriptors: Int,
    val diagnostic: String? = null
)

enum class RuntimeErrorCode {
    INVALID_SPEC,
    ROOTFS_MISSING,
    ARCHITECTURE_UNSUPPORTED,
    PTY_UNAVAILABLE,
    SPAWN_FAILED,
    IO_FAILED,
    RESIZE_FAILED,
    SIGNAL_FAILED,
    DNS_UNREACHABLE,
    DISPLAY_UNAVAILABLE,
    CAPABILITY_UNAVAILABLE,
    PERMISSION_DENIED,
    STORAGE_EXHAUSTED,
    PAGE_SIZE_UNSUPPORTED,
    TIMEOUT,
    UNEXPECTED_EXIT,
    INTERNAL
}

data class RuntimeError(
    val code: RuntimeErrorCode,
    val message: String,
    val evidence: String? = null,
    val recoverable: Boolean,
    val suggestedAction: String? = null,
    val cause: Throwable? = null
) {
    init { require(message.isNotBlank()) { "error message must not be blank" } }
}

interface RuntimeBackend {
    val runtime: RuntimeSpec
    fun capabilities(): CapabilityProfile
    suspend fun open(spec: SessionSpec): RuntimeSession
}

interface RuntimeSession {
    val id: SessionId
    val state: SessionState
    suspend fun write(bytes: ByteArray): Result<Int>
    suspend fun resize(size: TerminalSize): Result<Unit>
    suspend fun interrupt(): Result<Unit>
    suspend fun stop(): ExitReport
}
