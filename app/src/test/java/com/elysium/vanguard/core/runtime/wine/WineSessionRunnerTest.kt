package com.elysium.vanguard.core.runtime.wine

import com.elysium.vanguard.core.runtime.orchestrator.ExecutionManifest
import com.elysium.vanguard.core.runtime.orchestrator.RuntimeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 54 — tests for [WineSessionRunner].
 *
 * The runner consumes an [ExecutionManifest]
 * (from Phase 53) and starts a Wine + Box64
 * session. The tests use a
 * [FakeWineSessionBackend] that records
 * every call in a thread-safe list; the
 * runner's orchestration is asserted
 * without actually invoking `wine` /
 * `box64`.
 *
 * The tests pin:
 *
 *   - The runner rejects a manifest with a
 *     non-WINE_BOX64 runtime.
 *   - The runner delegates to the backend
 *     on a valid manifest.
 *   - The session id is derived
 *     deterministically from the binary
 *     path (same binary → same id; a re-
 *     run uses the same Wine prefix).
 *   - The runner forwards stop() to the
 *     backend.
 *   - The runner's typed errors
 *     (UnsupportedRuntime, BackendFailure)
 *     carry the right message.
 */
class WineSessionRunnerTest {

    @Test
    fun `start rejects a manifest with a non-WINE_BOX64 runtime`() {
        val backend = FakeWineSessionBackend()
        val runner = WineSessionRunner(backend = backend)
        val manifest = ExecutionManifest(
            binaryPath = "/fake/setup.exe",
            runtime = RuntimeKind.ANDROID_NATIVE
        )
        val result = runner.start(manifest)
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is WineSessionError.UnsupportedRuntime)
        assertTrue(
            "error message should mention the runtime: ${error?.message}",
            (error?.message ?: "").contains("ANDROID_NATIVE", ignoreCase = true)
        )
    }

    @Test
    fun `start delegates to the backend on a WINE_BOX64 manifest`() {
        val backend = FakeWineSessionBackend()
        val runner = WineSessionRunner(backend = backend)
        val manifest = ExecutionManifest(
            binaryPath = "/fake/setup.exe",
            runtime = RuntimeKind.WINE_BOX64,
            commandLineArgs = listOf("--silent"),
            environmentVariables = mapOf("LANG" to "C.UTF-8"),
            workspaceId = "ws-1",
            selectionReason = "WINE_BOX64 via orchestrator"
        )
        val result = runner.start(manifest)
        assertTrue("expected success, got $result", result.isSuccess)
        val state = result.getOrThrow()
        assertTrue(state is WineSessionState.Running)
        val running = state as WineSessionState.Running
        assertTrue("pid should be a positive integer", running.pid > 0)

        // The backend received a spec with
        // the manifest's binary + args.
        val calls = backend.startCalls
        assertEquals(1, calls.size)
        val spec = calls[0]
        assertEquals("/fake/setup.exe", spec.manifestBinaryPath)
        assertEquals(listOf("--silent"), spec.commandLineArgs)
        assertEquals("C.UTF-8", spec.environmentVariables["LANG"])
        assertEquals("ws-1", spec.workspaceId)
    }

    @Test
    fun `start derives a deterministic session id from the binary path`() {
        val backend = FakeWineSessionBackend()
        val runner = WineSessionRunner(backend = backend)
        val manifest1 = ExecutionManifest(
            binaryPath = "/fake/setup.exe",
            runtime = RuntimeKind.WINE_BOX64
        )
        val manifest2 = ExecutionManifest(
            binaryPath = "/fake/setup.exe",
            runtime = RuntimeKind.WINE_BOX64
        )
        val result1 = runner.start(manifest1)
        val result2 = runner.start(manifest2)
        // Both should succeed; both should
        // target the same session id (the
        // Wine prefix is shared).
        assertTrue(result1.isSuccess)
        assertTrue(result2.isSuccess)
        val call1 = backend.startCalls[0]
        val call2 = backend.startCalls[1]
        assertEquals(
            "session id should be derived from the binary path (deterministic)",
            call1.sessionId,
            call2.sessionId
        )
    }

    @Test
    fun `start surfaces a backend Error as a typed failure`() {
        val backend = FakeWineSessionBackend(
            nextStartResult = WineSessionState.Error("Box64 not installed")
        )
        val runner = WineSessionRunner(backend = backend)
        val manifest = ExecutionManifest(
            binaryPath = "/fake/setup.exe",
            runtime = RuntimeKind.WINE_BOX64
        )
        val result = runner.start(manifest)
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is WineSessionError.BackendFailure)
        assertTrue(
            "error message should mention Box64: ${error?.message}",
            (error?.message ?: "").contains("Box64", ignoreCase = true)
        )
    }

    @Test
    fun `stop delegates to the backend`() {
        val backend = FakeWineSessionBackend()
        val runner = WineSessionRunner(backend = backend)
        val manifest = ExecutionManifest(
            binaryPath = "/fake/setup.exe",
            runtime = RuntimeKind.WINE_BOX64
        )
        runner.start(manifest).getOrThrow()
        val stopResult = runner.stop(manifest)
        assertTrue(stopResult.isSuccess)
        val state = stopResult.getOrThrow()
        assertTrue(state is WineSessionState.Stopped)
        // The backend received a stop call.
        assertEquals(1, backend.stopCalls.size)
        assertEquals(backend.startCalls[0].sessionId, backend.stopCalls[0])
    }

    @Test
    fun `state returns the backend's state for the manifest`() {
        val backend = FakeWineSessionBackend()
        val runner = WineSessionRunner(backend = backend)
        val manifest = ExecutionManifest(
            binaryPath = "/fake/setup.exe",
            runtime = RuntimeKind.WINE_BOX64
        )
        runner.start(manifest).getOrThrow()
        val state = runner.state(manifest)
        assertNotNull(state)
        assertTrue(state is WineSessionState.Running)
    }
}

/**
 * Hand-rolled [WineSessionBackend] for unit
 * tests. Records every call in a
 * thread-safe list; the runner's
 * orchestration is asserted without
 * actually invoking `wine` / `box64`.
 */
internal class FakeWineSessionBackend(
    private var nextStartResult: WineSessionState = WineSessionState.Running(
        pid = 12345,
        stop = {}
    )
) : WineSessionBackend {

    data class StartCall(
        val sessionId: String,
        val spec: WineSessionSpec
    )

    val startCalls = java.util.Collections.synchronizedList(mutableListOf<WineSessionSpec>())
    val stopCalls = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val states = java.util.concurrent.ConcurrentHashMap<String, WineSessionState>()

    override fun start(spec: WineSessionSpec): WineSessionState {
        startCalls += spec
        val state = nextStartResult
        states[spec.sessionId] = state
        return state
    }

    override fun state(sessionId: String): WineSessionState? = states[sessionId]

    override fun stop(sessionId: String): WineSessionState {
        stopCalls += sessionId
        val stopped = WineSessionState.Stopped(exitCode = 0)
        states[sessionId] = stopped
        return stopped
    }
}
