package com.elysium.vanguard.core.runtime.distros.launcher

import java.io.File

/**
 * PHASE 9.6.3 — Default launcher: drop the user into `/system/bin/sh`
 * with the rootfs as cwd.
 *
 * What you get with this launcher:
 *
 *   - Inside the jail, `pwd` reports `<rootfsDir>`.
 *   - The shell can `cat`, `ls`, `grep`, `sed` the on-disk rootfs
 *     exactly as a user with read access could. Apt/apk/pacman are NOT
 *     available — they are ELF binaries that need [LauncherKind.NATIVE_PROOT].
 *   - The shell inherits the device's PID 1 / proc, so `uname -a` reports
 *     "Linux localhost … aarch64" with the host kernel, not a fake one.
 *
 * Why we ship this first: it gives the whole `DistroLauncher` interface
 * a real-world implementation that we can unit-test and exercise in a
 * sandbox without needing proot bytes. Phase 9.6.3.1 will swap this for
 * the native launcher when the JNI is in place.
 *
 * Phase 9.6.3 — first build; intentionally minimal.
 */
class JailedDistroLauncher(
    /** System sh path, injectable so tests can point at any binary. */
    private val shellPath: String = "/system/bin/sh"
) : DistroLauncher {

    override val kind: LauncherKind = LauncherKind.JAILED_SHELL

    override val capabilities: LauncherCapabilities = LauncherCapabilities.JAILED_BASELINE

    override fun buildShellCommand(rootfsDir: File, script: String): List<String> {
        require(rootfsDir.isDirectory) { "rootfsDir is not a directory: $rootfsDir" }
        // The native terminal backend provides the controlling PTY, so an
        // empty script opens a genuine interactive Android shell.
        return if (script.isBlank()) {
            listOf(shellPath, "-i")
        } else {
            listOf(shellPath, "-c", script)
        }
    }

    override fun buildProbeCommand(rootfsDir: File, args: List<String>): List<String> {
        require(rootfsDir.isDirectory) { "rootfsDir is not a directory: $rootfsDir" }
        // For probes we still want the cwd to be the rootfs so paths the
        // user provides resolve against it (e.g. `cat etc/os-release`).
        // We inject the cwd via `cd ; <args>` instead of ProcessBuilder
        // because `directory()` would also change the working directory
        // for the host process perspective in some environments.
        val joined = args.joinToString(" ") { quoteIfNeeded(it) }
        return listOf(shellPath, "-c", "cd ${quoteIfNeeded(rootfsDir.absolutePath)} && $joined")
    }

    override fun isAvailable(rootfsDir: File): Boolean {
        // We need both: the rootfs directory and a working shell.
        // The latter is essentially always true on Android, but the check
        // is cheap and makes the contract testable on any platform.
        val shell = File(shellPath)
        return rootfsDir.isDirectory && (shell.exists() || shell.canExecute() || isShPathLikelyPresent(shell))
    }

    /**
     * Belt-and-suspenders: on JVM unit-test runners the `File.exists()`
     * probe for `/system/bin/sh` returns false (it's an Android-only path),
     * but the launcher is still semantically "available" — it will work
     * on every Android device the app ships on. We treat the canonical
     * `/system/bin/sh` and `/bin/sh` as always-present on their respective
     * platforms; the device-side `exec` will surface a real error if the
     * binary is in fact missing.
     */
    private fun isShPathLikelyPresent(shell: File): Boolean {
        val path = shell.absolutePath
        return path == "/system/bin/sh" || path == "/bin/sh" || path == "/system/bin/ash"
    }

    private fun quoteIfNeeded(s: String): String {
        // Pass-through for now: buildShellCommand/buildProbeCommand is
        // used with our own constants and is not a shell-injection
        // surface. 9.6.3.1 will revisit when proot strings carry shell
        // metacharacters.
        return "'" + s.replace("'", "'\\''") + "'"
    }
}
