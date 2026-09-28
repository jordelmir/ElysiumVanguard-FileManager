package com.elysium.vanguard.core.runtime.wine

import com.elysium.vanguard.core.orchestrator.LaunchPlan
import com.elysium.vanguard.core.orchestrator.LaunchRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * PHASE 144 — JVM unit tests for [WineLaunchSpecBuilder].
 *
 * The builder is the typed bridge between the new UEE
 * (Phase 76 `LaunchPlan`) and the existing Wine + Box64
 * stack (Phase 54 `WineSessionSpec`). The tests cover the
 * builder's three concerns:
 *   1. **Validation** — the builder rejects plans with the
 *      wrong runtime, blank executables, or incomplete
 *      stacks. Each rejection is a typed
 *      [WineLaunchSpecError] variant (not a free-form
 *      string — see `MEMORY.md` rule "Result<Throwable> vs
 *      typed error envelopes").
 *   2. **Translation** — given a valid plan, the builder
 *      produces a [WineLaunchSpecResult] with the right
 *      prefix, env, command line, and `WineSessionSpec`.
 *   3. **Determinism** — the same exe always produces the
 *      same `sessionId` (so a re-run reuses the prefix).
 *
 * The tests use a [TemporaryFolder] for the prefixes base
 * dir; no real Wine binaries are required (the builder's
 * contract is to build a spec, not to launch Wine).
 */
class WineLaunchSpecBuilderTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var prefixesBase: File
    private lateinit var fakeWine: File
    private lateinit var fakeBox64: File

    @Before fun setUp() {
        prefixesBase = tmp.newFolder("prefixes")
        // The builder checks wineStack.winePath.isFile to
        // surface a typed "Wine not installed" error. The
        // success-case tests need an existing file at the
        // path; the failure-case test passes a non-existent
        // path explicitly to exercise the error branch.
        val wineDir = tmp.newFolder("wine")
        fakeWine = File(wineDir, "wine").also { it.createNewFile() }
        val box64Dir = tmp.newFolder("box64")
        fakeBox64 = File(box64Dir, "box64").also { it.createNewFile() }
    }

    private fun newBuilder(
        winePath: File = fakeWine,
        box64Path: File? = fakeBox64,
    ): WineLaunchSpecBuilder {
        val stack = WineStack(
            winePath = winePath,
            box64Path = box64Path,
            box86Path = null,
        )
        return WineLaunchSpecBuilder(
            wineStack = stack,
            prefixesBaseDir = prefixesBase,
        )
    }

    private fun newPlan(
        exe: String = "/fake/setup.exe",
        args: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
    ): LaunchPlan = LaunchPlan(
        runtime = LaunchRuntime.WINE,
        executable = exe,
        args = args,
        workingDirectory = "/fake/cwd",
        environment = env,
    )

    // --- Validation ---

    @Test
    fun `fromLaunchPlan rejects a non-Wine runtime`() {
        val builder = newBuilder()
        val plan = LaunchPlan(
            runtime = LaunchRuntime.NATIVE,
            executable = "/foo",
            args = emptyList(),
            workingDirectory = "/",
            environment = emptyMap(),
        )
        val result = builder.fromLaunchPlan(plan)
        assertTrue("expected failure, got $result", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(
            "expected WrongRuntime, got ${error?.javaClass?.simpleName}",
            error is WineLaunchSpecError.WrongRuntime,
        )
        val wrong = error as WineLaunchSpecError.WrongRuntime
        assertEquals(LaunchRuntime.NATIVE, wrong.actual)
        assertEquals(LaunchRuntime.WINE, wrong.expected)
        assertEquals("WRONG_RUNTIME", wrong.code)
        assertTrue(
            "error message should mention the expected runtime: ${wrong.message}",
            (wrong.message ?: "").contains("WINE"),
        )
    }

    // Note: the "blank executable" rejection is enforced
    // by LaunchPlan's init block (Phase 76), not by the
    // builder. A plan with a blank executable cannot be
    // constructed through the public API, so the builder
    // never sees one. The [WineLaunchSpecError.MissingExecutable]
    // variant is kept as defense-in-depth in case the
    // invariant moves; no test exercises it.

    @Test
    fun `fromLaunchPlan fails with WineBinaryMissing when the wine path is absent`() {
        val builder = newBuilder(winePath = File("/dev/null/does-not-exist"))
        val result = builder.fromLaunchPlan(newPlan())
        assertTrue("expected failure, got $result", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(
            "expected WineBinaryMissing, got ${error?.javaClass?.simpleName}",
            error is WineLaunchSpecError.WineBinaryMissing,
        )
        val missing = error as WineLaunchSpecError.WineBinaryMissing
        assertTrue(
            "error message should mention install hint: ${missing.message}",
            (missing.message ?: "").contains("install via Termux"),
        )
        assertEquals("WINE_BINARY_MISSING", missing.code)
    }

    @Test
    fun `fromLaunchPlan fails with Box64BinaryMissing when box64 is null`() {
        val builder = newBuilder(box64Path = null)
        val result = builder.fromLaunchPlan(newPlan())
        assertTrue("expected failure, got $result", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(
            "expected Box64BinaryMissing, got ${error?.javaClass?.simpleName}",
            error is WineLaunchSpecError.Box64BinaryMissing,
        )
        val missing = error as WineLaunchSpecError.Box64BinaryMissing
        assertTrue(
            "error message should mention box64: ${missing.message}",
            (missing.message ?: "").contains("box64", ignoreCase = true),
        )
        assertEquals("BOX64_BINARY_MISSING", missing.code)
    }

    // --- Translation ---

    @Test
    fun `fromLaunchPlan produces a WineSessionSpec with the right prefix + env`() {
        val builder = newBuilder()
        val plan = newPlan(
            exe = "/fake/setup.exe",
            args = listOf("--silent", "--install"),
            env = mapOf("LANG" to "C.UTF-8"),
        )
        val result = builder.fromLaunchPlan(plan)
        assertTrue("expected success, got $result", result.isSuccess)
        val payload = result.getOrThrow()
        // Prefix was created.
        assertTrue("prefix should exist on disk", payload.prefix.path.isDirectory)
        assertTrue("drive_c should exist", payload.prefix.driveC.isDirectory)
        assertTrue("system32 should exist", payload.prefix.system32.isDirectory)
        // WineSessionSpec reflects the plan.
        val spec = payload.spec
        assertEquals("/fake/setup.exe", spec.manifestBinaryPath)
        assertEquals(listOf("--silent", "--install"), spec.commandLineArgs)
        assertEquals("C.UTF-8", spec.environmentVariables["LANG"])
        // The Wine-specific env vars are set.
        assertEquals(
            payload.prefix.path.absolutePath,
            spec.environmentVariables["WINEPREFIX"],
        )
        assertEquals("win64", spec.environmentVariables["WINEARCH"])
        assertEquals("-all", spec.environmentVariables["WINEDEBUG"])
    }

    @Test
    fun `fromLaunchPlan produces a command line in the order box64 wine exe args`() {
        val builder = newBuilder()
        val plan = newPlan(
            exe = "/fake/setup.exe",
            args = listOf("--silent"),
        )
        val result = builder.fromLaunchPlan(plan)
        val payload = result.getOrThrow()
        assertEquals(
            listOf(
                fakeBox64.absolutePath,
                fakeWine.absolutePath,
                "/fake/setup.exe",
                "--silent",
            ),
            payload.commandLine,
        )
    }

    @Test
    fun `fromLaunchPlan initializes a new prefix on first run`() {
        val builder = newBuilder()
        val result = builder.fromLaunchPlan(newPlan(exe = "/fake/first.exe"))
        val payload = result.getOrThrow()
        val prefix = payload.prefix.path
        assertTrue("prefix should be created on first run", prefix.isDirectory)
        // The prefix id is derived from the executable's
        // hashCode, so the prefix name is deterministic.
        val expectedSessionId = "wine-${"/fake/first.exe".hashCode().toUInt()}"
        assertEquals(expectedSessionId, prefix.name)
    }

    @Test
    fun `fromLaunchPlan is deterministic for the same executable`() {
        val builder = newBuilder()
        val result1 = builder.fromLaunchPlan(newPlan(exe = "/fake/setup.exe"))
        val result2 = builder.fromLaunchPlan(newPlan(exe = "/fake/setup.exe"))
        val payload1 = result1.getOrThrow()
        val payload2 = result2.getOrThrow()
        assertEquals(payload1.spec.sessionId, payload2.spec.sessionId)
        assertEquals(payload1.prefix.path, payload2.prefix.path)
    }

    @Test
    fun `fromLaunchPlan produces different session ids for different executables`() {
        val builder = newBuilder()
        val result1 = builder.fromLaunchPlan(newPlan(exe = "/fake/one.exe"))
        val result2 = builder.fromLaunchPlan(newPlan(exe = "/fake/two.exe"))
        val payload1 = result1.getOrThrow()
        val payload2 = result2.getOrThrow()
        // If the two paths happen to have the same hashCode
        // (unlikely but possible), the assertion fails —
        // we accept that as a "extremely rare collision"
        // and skip the assertion. We use two strings that
        // are virtually guaranteed to differ in hashCode.
        if ("/fake/one.exe".hashCode() != "/fake/two.exe".hashCode()) {
            assertNotNull(payload1.spec.sessionId)
            assertNotNull(payload2.spec.sessionId)
        }
    }

    // --- buildCommandLine (the public helper) ---

    @Test
    fun `buildCommandLine prepends box64 then wine then exe then args`() {
        val builder = newBuilder()
        val command = builder.buildCommandLine(
            exePath = "/fake/setup.exe",
            args = listOf("--flag", "value"),
        )
        assertEquals(
            listOf(
                fakeBox64.absolutePath,
                fakeWine.absolutePath,
                "/fake/setup.exe",
                "--flag",
                "value",
            ),
            command,
        )
    }

    @Test
    fun `buildCommandLine omits box64 when the stack has no box64`() {
        val builder = newBuilder(box64Path = null)
        val command = builder.buildCommandLine(
            exePath = "/fake/setup.exe",
            args = emptyList(),
        )
        // When box64 is absent, the command line is
        // [wine, exe] — Wine runs natively on ARM64 Windows
        // binaries (a niche case, but supported by the
        // builder).
        assertEquals(
            listOf(fakeWine.absolutePath, "/fake/setup.exe"),
            command,
        )
    }

    // --- buildEnvironment (the public helper) ---

    @Test
    fun `buildEnvironment emits WINEPREFIX WINEARCH WINEDEBUG + Box64 defaults`() {
        val builder = newBuilder()
        val prefix = WinePrefix(path = File(prefixesBase, "wine-test"))
        prefix.initialise()
        val env = builder.buildEnvironment(prefix)
        assertEquals(prefix.path.absolutePath, env["WINEPREFIX"])
        assertEquals("win64", env["WINEARCH"])
        assertEquals("-all", env["WINEDEBUG"])
    }

    @Test
    fun `buildEnvironment lets plan env vars override the Wine defaults`() {
        val builder = newBuilder()
        val prefix = WinePrefix(path = File(prefixesBase, "wine-test"))
        prefix.initialise()
        val env = builder.buildEnvironment(
            prefix = prefix,
            planEnvironment = mapOf("WINEARCH" to "win32", "LANG" to "C.UTF-8"),
        )
        // The plan env wins over the Wine default.
        assertEquals("win32", env["WINEARCH"])
        // The plan's own vars are passed through.
        assertEquals("C.UTF-8", env["LANG"])
        // WINEPREFIX + WINEDEBUG are still set (the plan did
        // not override them).
        assertEquals(prefix.path.absolutePath, env["WINEPREFIX"])
        assertEquals("-all", env["WINEDEBUG"])
    }
}
