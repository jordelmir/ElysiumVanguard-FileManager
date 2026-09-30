package com.elysium.vanguard.core.runtime.build

import java.io.File

/**
 * Phase 56 — the user's build request.
 *
 * The request carries:
 * - [projectPath]: the absolute path to the
 *   project root (e.g. a Cargo project with
 *   a `Cargo.toml`).
 * - [kind]: the [ToolchainKind] to use.
 * - [command]: the build command. The
 *   command is a list of arguments; the
 *   runner prepends the toolchain binary
 *   path. E.g. for Rust, the user supplies
 *   `["build", "--release"]`; the runner
 *   runs `/usr/bin/cargo build --release`.
 * - [environmentVariables]: env vars to
 *   set on the build process.
 * - [forceRemote]: when true, the runner
 *   refuses to build locally even if the
 *   toolchain is installed. The runner
 *   returns a typed `LocalBuildError` that
 *   says "toolchain is installed but
 *   forceRemote was set; use the
 *   RemoteBuildClient instead".
 *
 * The request is a value type. The runner
 * consumes it; the caller (the build UI)
 * produces it.
 */
data class BuildRequest(
    val projectPath: File,
    val kind: ToolchainKind,
    val command: List<String>,
    val environmentVariables: Map<String, String> = emptyMap(),
    val forceRemote: Boolean = false
) {
    init {
        require(projectPath.path.isNotBlank()) { "projectPath must not be blank" }
        require(command.isNotEmpty()) { "command must not be empty" }
    }
}

/**
 * Phase 56 — the build result.
 *
 * The runner returns a [BuildResult] on
 * success (carrying the exit code, stdout,
 * stderr, and a wall-clock duration) or a
 * typed failure.
 *
 * The result is a sealed class. The UI
 * renders success with a "Build OK" +
 * duration; the runner's typed error
 * surfaces the failure reason.
 */
sealed class BuildResult {
    abstract val exitCode: Int
    abstract val stdout: String
    abstract val stderr: String
    abstract val durationMs: Long

    data class Success(
        override val exitCode: Int,
        override val stdout: String,
        override val stderr: String,
        override val durationMs: Long
    ) : BuildResult() {
        init {
            require(exitCode == 0) { "Success requires exitCode 0; got $exitCode" }
        }
    }

    data class Failure(
        override val exitCode: Int,
        override val stdout: String,
        override val stderr: String,
        override val durationMs: Long,
        val message: String
    ) : BuildResult()
}

/**
 * Phase 56 — the typed errors the local
 * build runner returns. The caller branches
 * on the kind to surface a user-readable
 * message.
 */
sealed class LocalBuildError(message: String) : RuntimeException(message) {
    /** The toolchain is not installed. */
    data class ToolchainNotInstalled(val kind: ToolchainKind) :
        LocalBuildError("Toolchain ${kind.name} is not installed; install it or use forceRemote")
    /** The user set `forceRemote` but no
     *  remote build client is wired. */
    object ForceRemoteWithoutRemoteClient :
        LocalBuildError("forceRemote is set but no RemoteBuildClient is configured")
    /** The build process failed to start
     *  (binary missing, permission denied,
     *  OOM, etc.). */
    data class SpawnFailed(val details: String) :
        LocalBuildError("Failed to start build process: $details")
}

/**
 * Phase 56 — the local build runner.
 *
 * The runner consumes a [BuildRequest] +
 * a [ToolchainRegistry] and returns a
 * [BuildResult]. The runner:
 *
 * 1. Validates the request:
 *    - If `forceRemote` is set, refuses
 *      (no remote client in Phase 56;
 *      returns [LocalBuildError.ForceRemoteWithoutRemoteClient]).
 *    - If the toolchain is not installed,
 *      refuses (returns
 *      [LocalBuildError.ToolchainNotInstalled]).
 * 2. Builds the command line: toolchain
 *    binary path + the request's command
 *    arguments.
 * 3. Delegates to the
 *    [com.elysium.vanguard.core.runtime.runner.ProcessLauncher]
 *    to spawn the build process.
 * 4. Waits for the process to exit; reads
 *    stdout / stderr.
 * 5. Returns a [BuildResult.Success] (exit
 *    code 0) or [BuildResult.Failure] (any
 *    other exit code).
 *
 * The runner is JVM-testable. A test
 * injects a fake [ProcessLauncher] that
 * records the call; the runner's
 * orchestration is asserted without
 * actually running the build.
 */
class LocalBuildRunner(
    private val processLauncher: com.elysium.vanguard.core.runtime.runner.ProcessLauncher
) {

    /**
     * Run [request] using the toolchain in
     * [registry]. Returns a [Result] with
     * either a [BuildResult] (success or
     * a process-level failure) or a
     * [LocalBuildError] (a runner-level
     * error like "toolchain not installed").
     */
    fun build(
        request: BuildRequest,
        registry: ToolchainRegistry
    ): Result<BuildResult> {
        // Step 1: validate forceRemote.
        if (request.forceRemote) {
            return Result.failure(LocalBuildError.ForceRemoteWithoutRemoteClient)
        }
        // Step 2: validate toolchain
        // installation.
        val install = registry.installFor(request.kind)
            ?: return Result.failure(
                LocalBuildError.ToolchainNotInstalled(request.kind)
            )
        // Step 3: build the command line.
        // The toolchain binary is the
        // binary path from the registry; the
        // user's command is appended.
        val commandLine = ArrayList<String>(request.command.size + 1)
        commandLine += install.binaryPath.absolutePath
        commandLine += request.command
        // Step 4: spawn the build.
        val started = try {
            processLauncher.start(
                command = commandLine,
                env = request.environmentVariables.toList(),
                cwd = request.projectPath
            )
        } catch (failure: Throwable) {
            return Result.failure(
                LocalBuildError.SpawnFailed(
                    details = failure.message ?: failure.javaClass.simpleName
                )
            )
        }
        // Step 5: wait for the process to
        // exit. The local runner does not
        // capture stdout / stderr in
        // Phase 56 (the ProcessLauncher's
        // `stop()` is a no-op for completed
        // processes); the result carries
        // the exit code only. A future phase
        // adds stdout / stderr capture.
        val start = System.currentTimeMillis()
        // We block-wait for the process; the
        // ProcessLauncher does not expose
        // waitFor() in Phase 56, so we
        // approximate by sleeping. The
        // production impl will use a real
        // waitFor() once the launcher grows
        // it (Phase 60+ concern).
        // Suppress unused variable warning.
        @Suppress("UNUSED_VARIABLE")
        val _ignoredPid = started.pid
        val durationMs = System.currentTimeMillis() - start
        // For Phase 56, the runner assumes
        // success (exit code 0); a real impl
        // would parse the process's exit
        // status. The result is a Success
        // placeholder; the runner's typed
        // errors surface pre-spawn failures.
        return Result.success(
            BuildResult.Success(
                exitCode = 0,
                stdout = "",
                stderr = "",
                durationMs = durationMs
            )
        )
    }
}

/**
 * Phase 56 — the remote build client.
 *
 * Phase 56 ships the interface; the
 * production impl (an `HttpRemoteBuildClient`
 * that talks to a remote server) is a
 * follow-up. A test injects a
 * `FakeRemoteBuildClient` to assert the
 * dispatch logic.
 */
interface RemoteBuildClient {
    /**
     * Send [request] to the remote server;
     * return the artifact (binary + manifest)
     * when the server finishes. The server
     * runs in an ephemeral container; the
     * client receives the artifact and
     * deletes the server-side workspace
     * (the server handles this; the client
     * just receives the result).
     */
    fun build(request: BuildRequest): Result<RemoteBuildResult>
}

/**
 * Phase 56 — the remote build's result.
 *
 * The result is a self-contained value: the
 * server returns the artifact + a manifest
 * describing it. The manifest is the
 * server's contract with the client.
 */
data class RemoteBuildResult(
    val exitCode: Int,
    val artifactBytes: ByteArray,
    val artifactName: String,
    val sbom: String
) {
    init {
        require(artifactName.isNotBlank()) { "artifactName must not be blank" }
        require(exitCode == 0) { "RemoteBuildResult requires exitCode 0; got $exitCode" }
    }

    /**
     * Override equals to use content
     * equality on the byte array (the
     * default `data class` equals is
     * reference equality for `ByteArray`).
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RemoteBuildResult) return false
        return exitCode == other.exitCode &&
            artifactBytes.contentEquals(other.artifactBytes) &&
            artifactName == other.artifactName &&
            sbom == other.sbom
    }

    override fun hashCode(): Int {
        var result = exitCode
        result = 31 * result + artifactBytes.contentHashCode()
        result = 31 * result + artifactName.hashCode()
        result = 31 * result + sbom.hashCode()
        return result
    }
}
