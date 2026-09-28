package com.elysium.vanguard.core.runtime.wine

import com.elysium.vanguard.core.orchestrator.LaunchPlan
import com.elysium.vanguard.core.orchestrator.LaunchRuntime
import java.io.File

/**
 * PHASE 144 — the typed bridge between the new Universal
 * Execution Engine ([com.elysium.vanguard.core.orchestrator.RuntimeDispatcher],
 * Phase 76) and the existing Wine + Box64 stack (Phase 54).
 *
 * ## The two pipelines
 *
 * Phase 76 introduced the [LaunchPlan] model: a
 * `RuntimeDispatcher.dispatch(capsule, selection)` returns a
 * `LaunchPlan(runtime, executable, args, workingDirectory,
 * environment)`. The plan is the orchestrator's
 * language-neutral launch spec.
 *
 * Phase 54 introduced the [WineSessionSpec] model: a
 * `WineSessionRunner.start(manifest)` builds a
 * `WineSessionSpec` (sessionId + prefix + box64 + GPU
 * acceleration + workspaceId) that a
 * [WineSessionBackend] consumes to actually spawn
 * `box64 wine <exe>`.
 *
 * These two pipelines were not connected. The
 * [WineLaunchSpecBuilder] is the bridge: given a [LaunchPlan]
 * with `runtime = LaunchRuntime.WINE`, the builder
 *   1. Verifies the [WineStack] is complete (wine binary
 *      present; box64 binary present if the binary is x86-64).
 *   2. Resolves a [WinePrefix] (creates the directory tree
 *      on first run; reuses on subsequent runs).
 *   3. Merges the `LaunchPlan.environment` with the Wine-
 *      specific env vars (WINEPREFIX, WINEARCH, WINEDEBUG,
 *      BOX64_*).
 *   4. Produces a [WineLaunchSpecResult] carrying both the
 *      [WineSessionSpec] (consumable by [WineSessionRunner])
 *      AND the OS-level [commandLine] (consumable by any
 *      generic [com.elysium.vanguard.core.runtime.runner.ProcessLauncher]).
 *
 * The builder is **pure-domain** (no I/O beyond the prefix
 * directory creation, which is idempotent). JVM unit tests
 * exercise the merge + derivation logic with a fake [WineStack]
 * against a temp dir; on-device integration (Phase 145+)
 * exercises the actual Wine + Box64 spawn.
 *
 * ## Why a builder, not a switch in the dispatcher
 *
 * The [com.elysium.vanguard.core.orchestrator.RuntimeDispatcher]
 * (Phase 76) is intentionally minimal: it translates a
 * `RuntimeSelection.Translated` into a `LaunchPlan` with a
 * translation wrapper. The wrapper-for-Wine case already
 * produces `[wine, <exe>, <args>]` — but it does NOT know
 * about Wine prefixes, Box64, or the per-app env vars.
 * Putting all that knowledge in the dispatcher would
 * bloat the dispatcher with Windows-specific concerns. The
 * builder is the dedicated seam: the dispatcher produces a
 * plan, the builder specializes it for Wine.
 */
class WineLaunchSpecBuilder(
    private val wineStack: WineStack,
    private val prefixesBaseDir: File,
) {

    /**
     * Translate a [LaunchPlan] into a [WineLaunchSpecResult].
     *
     * Returns a [Result.failure] with a typed
     * [WineLaunchSpecError] when the [wineStack] is incomplete
     * (no wine binary, or no box64 binary when the binary is
     * x86-64). Otherwise returns a [Result.success] with the
     * initialized prefix + the spec.
     */
    fun fromLaunchPlan(plan: LaunchPlan): Result<WineLaunchSpecResult> {
        if (plan.runtime != LaunchRuntime.WINE) {
            return Result.failure(
                WineLaunchSpecError.WrongRuntime(plan.runtime, expected = LaunchRuntime.WINE)
            )
        }
        // Defensive: the plan's executable must be a non-blank
        // path. The LaunchPlan init block already enforces this
        // (Phase 76); the assert is belt-and-suspenders.
        val exePath = plan.executable
        if (exePath.isBlank()) {
            return Result.failure(
                WineLaunchSpecError.MissingExecutable(plan.executable)
            )
        }
        // Wine binary must be present.
        if (!wineStack.winePath.isFile) {
            return Result.failure(
                WineLaunchSpecError.WineBinaryMissing(wineStack.winePath.absolutePath)
            )
        }
        // Box64 is required for x86-64 binaries. A future
        // increment may inspect the PE header to choose
        // box64 vs box86 dynamically; Phase 144 requires
        // box64 unconditionally.
        if (wineStack.box64Path == null) {
            return Result.failure(
                WineLaunchSpecError.Box64BinaryMissing
            )
        }
        // Resolve the prefix: one per exe (deterministic from
        // the exe's hash). The prefix is created on first run;
        // subsequent runs reuse the existing tree.
        val sessionId = deriveSessionId(exePath)
        val prefix = WinePrefix(
            path = File(prefixesBaseDir, sessionId)
        )
        prefix.initialise()
        // Merge: WINEPREFIX/WINEARCH/WINEDEBUG + Box64
        // defaults + the plan's environment. The plan's env
        // wins (the user is the source of truth for env vars).
        val wineEnv = buildEnvironment(prefix, plan.environment)
        val spec = WineSessionSpec(
            sessionId = sessionId,
            manifestBinaryPath = exePath,
            commandLineArgs = plan.args,
            environmentVariables = wineEnv,
            prefix = prefix,
            box64 = Box64Config(), // Phase 144: dynarec = default; a future increment exposes a config UI.
            workspaceId = null,
        )
        // The OS-level command line (what ProcessBuilder
        // would see): [box64, wine, exe, args...].
        val commandLine = buildCommandLine(
            exePath = exePath,
            args = plan.args,
        )
        return Result.success(
            WineLaunchSpecResult(
                prefix = prefix,
                spec = spec,
                commandLine = commandLine,
                environment = wineEnv,
                workingDirectory = plan.workingDirectory,
            )
        )
    }

    /**
     * Build the OS-level command line for a Wine + Box64 run.
     * The order is [box64, wine, exe, args...] — Box64 is the
     * outermost wrapper (translates x86-64 to ARM64); wine is
     * the inner wrapper (re-implements the Windows API); the
     * exe is the actual Windows binary.
     *
     * Exposed publicly so a caller that already has a
     * [WineSessionSpec] (e.g. the
     * [com.elysium.vanguard.core.runtime.wine.InProcessWineSessionBackend])
     * can reconstruct the command line for display in the
     * UI or for logging.
     */
    fun buildCommandLine(exePath: String, args: List<String>): List<String> {
        val command = mutableListOf<String>()
        wineStack.box64Path?.let { command += it.absolutePath }
        command += wineStack.winePath.absolutePath
        command += exePath
        command += args
        return command
    }

    /**
     * Merge the Wine-specific environment with the plan's
     * environment. Wine-specific vars come first; the
     * plan's vars override (the user is the source of truth).
     */
    fun buildEnvironment(
        prefix: WinePrefix,
        planEnvironment: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val base = LinkedHashMap<String, String>()
        base["WINEPREFIX"] = prefix.path.absolutePath
        base["WINEARCH"] = prefix.architecture
        base["WINEDEBUG"] = "-all"
        // Box64 defaults: default translation mode emits
        // nothing; we add the env so the user can override
        // via the manifest without re-deriving.
        base.putAll(Box64Config().toEnvironment())
        base.putAll(planEnvironment)
        return base
    }

    /**
     * Derive a stable session id from the executable path.
     * The id is the path's hashCode rendered as an unsigned
     * 32-bit int prefixed with `wine-`. The same exe always
     * produces the same id, so a re-run uses the same Wine
     * prefix (and the same `drive_c/windows` install).
     */
    private fun deriveSessionId(exePath: String): String {
        val hash = exePath.hashCode()
        return "wine-${hash.toUInt()}"
    }
}

/**
 * The builder's output. The result is a triple: the
 * initialized [WinePrefix], the [WineSessionSpec] consumable
 * by the [com.elysium.vanguard.core.runtime.wine.WineSessionRunner]
 * (Phase 54), and the OS-level [commandLine] +
 * [environment] consumable by any generic
 * [com.elysium.vanguard.core.runtime.runner.ProcessLauncher].
 *
 * The triple exists because the two consumers (the existing
 * WineSessionRunner and the new UEE ProcessLauncher) want
 * overlapping but distinct shapes; the result hands each
 * consumer the slice it needs without forcing the other to
 * ignore fields.
 */
data class WineLaunchSpecResult(
    val prefix: WinePrefix,
    val spec: WineSessionSpec,
    val commandLine: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: String,
) {
    init {
        require(commandLine.isNotEmpty()) {
            "WineLaunchSpecResult.commandLine must not be empty"
        }
    }

    /**
     * The full command line as a single string for display
     * in the UI / log. Joins the [commandLine] elements with
     * spaces; does NOT shell-quote (a future increment may
     * add proper quoting for display).
     */
    val commandLineAsString: String
        get() = commandLine.joinToString(" ")
}

/**
 * The typed error envelope for the builder. Every variant
 * extends [RuntimeException] so callers can `try/catch` the
 * typed error directly (per the `ElysiumPackageInstallError`
 * pattern from Phase 73 second half — see `MEMORY.md` rule
 * "Result<Throwable> vs typed error envelopes").
 */
sealed class WineLaunchSpecError(
    message: String,
    val code: String,
) : RuntimeException(message) {

    /**
     * The [LaunchPlan.runtime] is not [LaunchRuntime.WINE].
     * The builder refuses to translate a non-Wine plan.
     */
    data class WrongRuntime(
        val actual: LaunchRuntime,
        val expected: LaunchRuntime,
    ) : WineLaunchSpecError(
        message = "WineLaunchSpecBuilder expects runtime=$expected, got $actual",
        code = "WRONG_RUNTIME",
    )

    /**
     * The plan's executable is blank. The builder refuses
     * to translate a plan with no exe.
     */
    data class MissingExecutable(
        val executable: String,
    ) : WineLaunchSpecError(
        message = "LaunchPlan.executable must not be blank, got '$executable'",
        code = "MISSING_EXECUTABLE",
    )

    /**
     * The [WineStack] has no `wine` binary. The APK does
     * not bundle `libwine.so` yet AND the user has not
     * installed Wine under `<filesDir>/wine/` AND no Termux
     * prefix has a usable `wine`. The UI should show
     * "Wine not installed — install via Termux (pkg i wine)
     * or drop libwine.so under <filesDir>/wine/".
     */
    data class WineBinaryMissing(
        val path: String,
    ) : WineLaunchSpecError(
        message = "Wine binary not found at $path; install via Termux or drop libwine.so under <filesDir>/wine/",
        code = "WINE_BINARY_MISSING",
    )

    /**
     * The [WineStack] has no `box64` binary. Wine alone can
     * run only ARM64 Windows binaries (very few exist);
     * x86-64 Windows apps require Box64 to translate the
     * instruction stream.
     */
    data object Box64BinaryMissing : WineLaunchSpecError(
        message = "Box64 binary not found; required for x86-64 Windows apps. Install via Termux (pkg i box64) or drop libbox64.so under <filesDir>/wine/",
        code = "BOX64_BINARY_MISSING",
    )
}
