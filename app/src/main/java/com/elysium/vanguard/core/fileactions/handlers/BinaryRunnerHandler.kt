package com.elysium.vanguard.core.fileactions.handlers

import com.elysium.vanguard.core.fileactions.FileAction
import java.io.File
import javax.inject.Named

/**
 * Phase 99 — the **binary runner handler** for
 * AppImage and Windows `.exe` / `.msi` files.
 *
 * The handler validates the binary file exists,
 * looks up the target runtime (a Linux distro for
 * AppImage, a Windows VM for `.exe` / `.msi`),
 * and delegates the actual execution to the
 * [BinaryRunner] interface.
 *
 * **Why a single handler for two runtimes?** The
 * surface is the same: take a file + a target +
 * launch it. The runner is what differs (FUSE
 * for AppImage, QEMU + Wine for Windows).
 *
 * **JVM testability**: the handler takes a
 * [BinaryRunner] interface in its constructor;
 * production wires the AppImage runner + the
 * Windows runner. Tests pass a fake.
 *
 * The two `BinaryRunner` fields are
 * disambiguated by `@Named` qualifiers (the
 * Hilt graph has two providers that both return
 * `BinaryRunner`; the qualifiers let Hilt
 * pick the right one for each field).
 */
class BinaryRunnerHandler @javax.inject.Inject constructor(
    @Named("appImage") private val appImageRunner: BinaryRunner,
    @Named("windows") private val windowsRunner: BinaryRunner,
) {

    /**
     * Run the binary described by [action] in its
     * target runtime. The [action] carries the
     * file path + the target id (distro for
     * AppImage, VM for `.exe`).
     */
    suspend fun run(action: FileAction.RunAppImage): BinaryRunResult =
        runInternal(action.appImagePath, action.targetDistroId, "AppImage", appImageRunner)

    suspend fun run(action: FileAction.RunWindowsBinary): BinaryRunResult =
        runInternal(action.binaryPath, action.targetVmId, "Windows", windowsRunner)

    private suspend fun runInternal(
        binaryPath: String,
        targetId: String,
        runtimeLabel: String,
        runner: BinaryRunner,
    ): BinaryRunResult {
        val binary = File(binaryPath)
        if (!binary.exists() || !binary.isFile) {
            return BinaryRunResult.Failure(
                message = "binary not found: $binaryPath"
            )
        }
        if (!binary.canExecute()) {
            return BinaryRunResult.Failure(
                message = "binary is not executable: $binaryPath"
            )
        }
        return runner.run(binary, targetId, runtimeLabel)
    }
}

/**
 * The [BinaryRunner] decouples the [BinaryRunnerHandler]
 * from the actual execution primitive. Production:
 *
 * - AppImage → `ProcessLauncher` + `proot` + a Linux
 *   distro's FUSE mount (`/dev/fuse`); the AppImage
 *   binary mounts itself when executed.
 *
 * - Windows → `ProcessLauncher` + QEMU + Wine; the
 *   `.exe` is copied into the VM's `C:\elysium\`
 *   directory + invoked via `wine`.
 *
 * Tests use a fake.
 */
interface BinaryRunner {
    suspend fun run(binary: File, targetId: String, runtimeLabel: String): BinaryRunResult
}

/**
 * The result of a binary execution. A sealed class so
 * the caller pattern-matches on the outcome.
 */
sealed class BinaryRunResult {
    data class Launched(
        val runtimeLabel: String,
        val targetId: String,
        val binaryPath: String,
    ) : BinaryRunResult()

    data class Failure(
        val message: String,
    ) : BinaryRunResult()
}
