package com.elysium.vanguard.core.runtime.wine

import com.elysium.vanguard.core.runtime.orchestrator.ExecutionManifest
import com.elysium.vanguard.core.runtime.orchestrator.RuntimeKind
import com.elysium.vanguard.core.runtime.orchestrator.RuntimeOrchestrator

/**
 * Phase 54 — the runner that consumes an
 * [ExecutionManifest] and starts a Wine +
 * Box64 session.
 *
 * The runner is the executor for the
 * orchestrator's `WINE_BOX64` branch
 * (Phase 53). Given a manifest, the runner:
 *
 * 1. Validates the manifest's `runtime =
 *    WINE_BOX64` (rejects otherwise).
 * 2. Resolves a [WinePrefix] for the
 *    session (creates one if missing).
 * 3. Builds a [WineSessionSpec] from the
 *    manifest + the prefix + a default
 *    [Box64Config].
 * 4. Delegates to the
 *    [WineSessionBackend] to actually
 *    start the process.
 * 5. Returns the [WineSessionState] (which
 *    is the orchestrator-visible state the
 *    caller can `when` on).
 *
 * The runner is JVM-testable end-to-end.
 * A test passes a [FakeWineSessionBackend]
 * that records every call; the runner's
 * orchestration is asserted without
 * actually invoking `wine` / `box64` (the
 * host has no Wine + Box64 installed).
 *
 * ## Why a standalone runner (not part of
 * the SessionRunnerRegistry)
 *
 * The existing
 * [com.elysium.vanguard.core.runtime.runner.SessionRunnerRegistry]
 * is workspace-based: it dispatches by
 * [com.elysium.vanguard.core.runtime.workspaces.WorkspaceSession.kind]
 * and is consumed by the
 * [com.elysium.vanguard.core.runtime.ui.WorkspacesViewModel].
 *
 * The orchestrator is manifest-based: it
 * returns an [ExecutionManifest] and a
 * caller decides how to execute it. The
 * Wine session is one such execution. A
 * user can:
 * - Run the orchestrator on a file, get
 *   a manifest, hand the manifest to the
 *   runner (the "I-just-want-it-to-work"
 *   path).
 * - Hand-write a manifest with
 *   `runtime = WINE_BOX64` and pass it
 *   directly to the runner (the
 *   "I-know-exactly-what-I-want" path).
 *
 * A future phase can wrap the runner in
 * a `WorkspaceSession.WineBox64` kind +
 * extend the registry; Phase 54 ships the
 * standalone runner.
 */
class WineSessionRunner(
    private val backend: WineSessionBackend,
    private val orchestrator: RuntimeOrchestrator = RuntimeOrchestrator(),
    private val defaultBox64Config: Box64Config = Box64Config()
) {

    /**
     * Start a session for the given
     * [ExecutionManifest]. The orchestrator
     * produces a manifest like:
     *
     * ```kotlin
     * val plan = orchestrator.planExecution(...)
     * if (plan is ExecutionPlan.Ready) {
     *     runner.start(plan.manifest)
     * }
     * ```
     *
     * The runner validates the manifest's
     * `runtime` field; a manifest with a
     * different runtime returns
     * [Result.failure] with a typed error.
     */
    fun start(manifest: ExecutionManifest): Result<WineSessionState> {
        if (manifest.runtime != RuntimeKind.WINE_BOX64) {
            return Result.failure(
                WineSessionError.UnsupportedRuntime(manifest.runtime.name)
            )
        }
        val prefix = WinePrefix(
            path = java.io.File(prefixesBaseDir(), deriveSessionId(manifest))
        )
        val spec = WineSessionSpec(
            sessionId = deriveSessionId(manifest),
            manifestBinaryPath = manifest.binaryPath,
            commandLineArgs = manifest.commandLineArgs,
            environmentVariables = manifest.environmentVariables,
            prefix = prefix,
            box64 = defaultBox64Config,
            workspaceId = manifest.workspaceId
        )
        val state = backend.start(spec)
        return if (state is WineSessionState.Error) {
            Result.failure(WineSessionError.BackendFailure(state.message))
        } else {
            Result.success(state)
        }
    }

    /**
     * Stop the session identified by the
     * manifest. The runner forwards the
     * stop to the backend.
     */
    fun stop(manifest: ExecutionManifest): Result<WineSessionState> {
        if (manifest.runtime != RuntimeKind.WINE_BOX64) {
            return Result.failure(
                WineSessionError.UnsupportedRuntime(manifest.runtime.name)
            )
        }
        val sessionId = deriveSessionId(manifest)
        return Result.success(backend.stop(sessionId))
    }

    /**
     * Query the current state of the
     * session identified by the manifest.
     */
    fun state(manifest: ExecutionManifest): WineSessionState? =
        backend.state(deriveSessionId(manifest))

    /**
     * The base directory under which the
     * runner creates per-app Wine prefixes.
     * Phase 54 default: `<filesDir>/wine-prefixes/`.
     * A future phase injects this from
     * Hilt (`<filesDir>` is the Android
     * app's private files directory).
     */
    private fun prefixesBaseDir(): java.io.File =
        java.io.File("/data/data/com.elysium.vanguard/files/wine-prefixes")

    /**
     * Derive a stable session id from the
     * manifest. The id is the binary path's
     * hash; the same binary produces the
     * same id. A user who re-runs the same
     * .exe gets the same Wine prefix.
     */
    private fun deriveSessionId(manifest: ExecutionManifest): String {
        val hash = manifest.binaryPath.hashCode()
        return "wine-${hash.toUInt()}"
    }
}

/**
 * Phase 54 — the typed errors the runner
 * returns. The caller branches on the kind
 * to surface a user-readable message.
 */
sealed class WineSessionError(message: String) : RuntimeException(message) {
    /** The manifest's `runtime` field is not
     *  `WINE_BOX64`. The runner refuses to
     *  start. */
    data class UnsupportedRuntime(val runtimeName: String) :
        WineSessionError("Wine runner does not handle runtime '$runtimeName'; expected WINE_BOX64")
    /** The backend returned an Error state.
     *  The message is the backend's error
     *  message. */
    data class BackendFailure(val backendMessage: String) :
        WineSessionError("Wine backend failed: $backendMessage")
}
