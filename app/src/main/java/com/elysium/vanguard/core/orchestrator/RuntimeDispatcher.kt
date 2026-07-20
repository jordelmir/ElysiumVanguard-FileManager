package com.elysium.vanguard.core.orchestrator

import com.elysium.vanguard.core.runtime.capsule.Capsule
import com.elysium.vanguard.core.runtime.capsule.EntryPoint

/**
 * Phase 76 second half (Universal Execution Engine) — the
 * **Runtime Dispatcher**, the orchestrator that
 * actually launches the process based on the
 * [RuntimeSelector]'s output.
 *
 * Per the master vision's Universal Execution Engine
 * (section 6), the dispatch flow is:
 *
 *   Runtime Selection
 *     ↓
 *   Sandbox and Mount Policy
 *     ↓
 *   Process Supervisor
 *
 * The [RuntimeSelector] decides **WHAT to do**; the
 * [RuntimeDispatcher] **DOES it**. The dispatcher's
 * input is a typed [RuntimeSelection] (the selector's
 * output); the dispatcher's output is a typed
 * [LaunchPlan] (the launch command + the runtime
 * arguments).
 *
 * The dispatcher is **pure-domain** (no I/O, no
 * Android dependencies). The dispatcher produces a
 * launch plan; a separate component (the `ProcessLauncher`
 * in the EV runtime, or a future `AndroidProcessLauncher`
 * in the Elysium Linux runtime) executes the plan.
 *
 * The dispatcher is **stateless** (no mutable
 * fields). The dispatcher is thread-safe.
 */
class RuntimeDispatcher {

    /**
     * Dispatch a runtime selection. The
     * function returns a typed [LaunchPlan]
     * with the launch command + the runtime
     * arguments.
     */
    fun dispatch(
        capsule: Capsule,
        selection: RuntimeSelection,
    ): LaunchPlan = when (selection) {
        is RuntimeSelection.Native -> nativePlan(capsule)
        is RuntimeSelection.Translated -> translatedPlan(capsule, selection)
        is RuntimeSelection.Unsupported -> throw IllegalArgumentException(
            "cannot dispatch an Unsupported selection: ${selection.reason}",
        )
    }

    /**
     * Build a native launch plan (no
     * translation). The launch command is
     * the capsule's entrypoint + args
     * verbatim.
     */
    private fun nativePlan(capsule: Capsule): LaunchPlan = LaunchPlan(
        runtime = LaunchRuntime.NATIVE,
        executable = capsule.entrypoint.executable,
        args = capsule.entrypoint.args,
        workingDirectory = capsule.entrypoint.workingDirectory,
        environment = emptyMap(),
    )

    /**
     * Build a translated launch plan (the
     * capsule is wrapped with the
     * translation layer). The launch
     * command is the translation layer
     * (e.g. `box64` for Box64, `wine` for
     * Wine) + the capsule's entrypoint +
     * args.
     */
    private fun translatedPlan(
        capsule: Capsule,
        selection: RuntimeSelection.Translated,
    ): LaunchPlan {
        val translation = selection.translation
        val wrapper = translation.wrapperExecutable
        return LaunchPlan(
            runtime = translation.toLaunchRuntime(),
            executable = wrapper,
            args = listOf(wrapper, capsule.entrypoint.executable) +
                capsule.entrypoint.args,
            workingDirectory = capsule.entrypoint.workingDirectory,
            environment = emptyMap(),
        )
    }
}

/**
 * The typed launch plan. The plan is the
 * dispatcher's output; a separate component
 * (the `ProcessLauncher`) executes the plan.
 *
 * The plan is **immutable** (a data class; no
 * setters). A new plan is a new value. The
 * plan's lifecycle (a launch attempt, a
 * cancellation) is a new `LaunchPlan` value
 * + a new process state, not a mutation of
 * the existing one.
 */
data class LaunchPlan(
    val runtime: LaunchRuntime,
    val executable: String,
    val args: List<String>,
    val workingDirectory: String,
    val environment: Map<String, String>,
) {
    init {
        require(executable.isNotBlank()) {
            "LaunchPlan.executable must not be blank"
        }
        require(workingDirectory.isNotBlank()) {
            "LaunchPlan.workingDirectory must not be blank"
        }
    }

    /**
     * The full command line. The command is
     * the executable + the args joined by
     * spaces (with proper quoting for args
     * that contain spaces — a future
     * increment).
     */
    val fullCommandLine: String
        get() = (listOf(executable) + args).joinToString(" ")

    /**
     * The first N elements of the command
     * line. A real process launcher
     * (e.g. `Process.exec`) takes the
     * executable as the program + the
     * args as the arguments.
     */
    val programAndArgs: List<String>
        get() = listOf(executable) + args
}

/**
 * The launch runtime. The runtime is the
 * **typed strategy** the process launcher
 * uses to execute the launch plan.
 */
enum class LaunchRuntime(val displayLabel: String) {
    /** The process is launched natively
     *  (no translation). */
    NATIVE("Native"),

    /** The process is launched via Box64
     *  (x86_64 user-mode translation). */
    BOX64("Box64"),

    /** The process is launched via FEX
     *  (x86 user-mode translation). */
    FEX("FEX-Emu"),

    /** The process is launched via Wine
     *  (Windows API re-implementation). */
    WINE("Wine"),

    /** The process is launched via PRoot
     *  (filesystem root in user space). */
    PROOT("PRoot"),

    /** The process is launched via chroot
     *  (filesystem root in kernel space;
     *  requires root). */
    CHROOT("chroot"),

    /** The process is launched via QEMU
     *  (full system emulation; slowest). */
    QEMU("QEMU"),

    /** The process is launched on the
     *  Oracle Free remote build server. */
    REMOTE("Remote"),
}

/**
 * The translation wrapper executable. The
 * extension function maps a [TranslationType]
 * to the executable that wraps the capsule's
 * entrypoint.
 */
val TranslationType.wrapperExecutable: String
    get() = when (this) {
        TranslationType.BOX64 -> "/usr/bin/box64"
        TranslationType.FEX -> "/usr/bin/FEXInterpreter"
        TranslationType.WINE -> "/usr/bin/wine"
        TranslationType.PROOT -> "/usr/bin/proot"
        TranslationType.CHROOT -> "/usr/sbin/chroot"
        TranslationType.QEMU -> "/usr/bin/qemu-x86_64"
        TranslationType.REMOTE -> "/usr/bin/elysium-remote-launcher"
    }

/**
 * The translation-to-launch-runtime mapping.
 * The extension function maps a
 * [TranslationType] to the corresponding
 * [LaunchRuntime].
 */
fun TranslationType.toLaunchRuntime(): LaunchRuntime = when (this) {
    TranslationType.BOX64 -> LaunchRuntime.BOX64
    TranslationType.FEX -> LaunchRuntime.FEX
    TranslationType.WINE -> LaunchRuntime.WINE
    TranslationType.PROOT -> LaunchRuntime.PROOT
    TranslationType.CHROOT -> LaunchRuntime.CHROOT
    TranslationType.QEMU -> LaunchRuntime.QEMU
    TranslationType.REMOTE -> LaunchRuntime.REMOTE
}
