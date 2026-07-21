package com.elysium.vanguard.core.runtime.distros.launcher

import java.io.File

/**
 * PHASE 102 — Pure builder for the **`unshare` + `chroot` + `cgexec`**
 * command line that the [NamespacedDistroLauncher] hands to
 * `ProcessBuilder`.
 *
 * Why a separate builder:
 * - The unshare + chroot flag set is non-trivial (8 flags, ordering,
 *   interaction with `-r`). A pure function with full unit coverage
 *   removes the "did I forget `--propagation private`?" risk.
 * - The cgroup v2 controllers are an orthogonal concern (CPU weight,
 *   memory high/max, IO weight, pids.max). Composing the cgexec
 *   argv from a typed [CgroupSpec] is easier to test in isolation.
 * - The result is `List<String>`, exactly what [DistroLauncher]
 *   returns. No side effects, no I/O, no Android imports.
 *
 * **What this is NOT**: a security boundary. The chroot is broken
 * trivially by anyone with `CAP_SYS_CHROOT` in the new namespace —
 * which is exactly who we're targeting (rooted users). Unprivileged
 * users on a non-rooted device must use [NativeProotLauncher] or
 * [DirectExecDistroLauncher] instead.
 *
 * **Constructed command shape** (rooted device):
 *
 * ```
 * su -c 'unshare -m -p -n -i -u -C
 *        cgexec -g cpu,memory,io,pids:<slice>
 *        chroot <rootfs> /usr/bin/env -i
 *          HOME=/root USER=root TERM=xterm-256color
 *          LANG=C.UTF-8 PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
 *        /bin/sh -lc "<script>"'
 * ```
 *
 * When [script] is empty we drop `-lc <script>` and append
 * `/bin/sh -l` (interactive login shell). When [RootedModeProbe]
 * reports the device is NOT rooted we return
 * `listOf("unshare-missing")` and the [LauncherResolver] falls
 * back to the next launcher (proot or jailed).
 */
object UnshareCommandBuilder {

    /**
     * Compose the full `su -c '<unshare>...'` command line.
     *
     * @param rootfsDir the on-disk rootfs directory
     * @param script the shell script to run inside the chroot
     *   (empty → interactive login shell)
     * @param namespaces the namespace spec (which flags to set)
     * @param cgroups the cgroup v2 spec (which controllers + limits)
     * @param sliceName the cgroup slice to put the process in
     *   (e.g. `"elysium.slice"`)
     * @param suBinary path to the su binary
     *   (defaults to `"su"`, resolved via `$PATH`)
     * @param unshareBinary path to the unshare binary
     *   (defaults to `"unshare"`)
     * @param chrootBinary path to the chroot binary
     *   (defaults to `"chroot"`)
     * @param cgexecBinary path to the cgexec binary
     *   (defaults to `"cgexec"`)
     * @param env environment variables to export inside the chroot
     */
    fun build(
        rootfsDir: File,
        script: String,
        namespaces: NamespaceSpec,
        cgroups: CgroupSpec,
        sliceName: String = "elysium.slice",
        suBinary: String = "su",
        unshareBinary: String = "unshare",
        chrootBinary: String = "chroot",
        cgexecBinary: String = "cgexec",
        env: List<Pair<String, String>> = DEFAULT_ENV,
    ): List<String> {
        require(rootfsDir.path.isNotBlank()) { "rootfsDir path is blank" }
        val argv = ArrayList<String>(32)

        // Layer 1: su -c '<rest>'
        argv += suBinary
        argv += "-c"
        argv += composeInner(
            rootfsDir = rootfsDir,
            script = script,
            namespaces = namespaces,
            cgroups = cgroups,
            sliceName = sliceName,
            unshareBinary = unshareBinary,
            chrootBinary = chrootBinary,
            cgexecBinary = cgexecBinary,
            env = env,
        )

        return argv
    }

    /**
     * The inner command (everything after `su -c`). Returned as
     * a single shell-quoted string suitable for passing to `sh -c`.
     *
     * Split out so the [RootedModeProbe] can ALSO call this to
     * surface a "what would we run?" preview in [RootedModeSettingsScreen]
     * without re-deriving the order.
     */
    fun composeInner(
        rootfsDir: File,
        script: String,
        namespaces: NamespaceSpec,
        cgroups: CgroupSpec,
        sliceName: String,
        unshareBinary: String,
        chrootBinary: String,
        cgexecBinary: String,
        env: List<Pair<String, String>>,
    ): String {
        val parts = ArrayList<String>(32)

        // Layer 2: unshare [flags]
        parts += unshareBinary
        parts += "--mount"               // -m  : mount namespace
        parts += "--pid"                 // -p  : PID namespace (after -m)
        parts += "--network"             // -n  : network namespace
        parts += "--ipc"                 // -i  : IPC namespace
        parts += "--uts"                 // -u  : UTS namespace
        parts += "--cgroup"              // -C  : cgroup namespace (v2)
        parts += "--propagation"
        parts += "private"               // make mount propagation private
        if (namespaces.user) {
            parts += "--user"            // -U  : user namespace (only if requested)
        }
        // Fork so we can chroot without being PID 1
        // (chroot from PID 1 is allowed but can confuse init).
        // We always fork; cgexec also wants a child.
        parts += "--fork"

        // Layer 3: cgexec -g <controllers>:<slice>
        val controllers = cgroups.controllerList()
        if (controllers.isNotEmpty()) {
            parts += cgexecBinary
            parts += "-g"
            parts += "$controllers:$sliceName"
        }

        // Layer 4: chroot <rootfs> /usr/bin/env -i <env...> <shell> <args>
        parts += chrootBinary
        parts += rootfsDir.absolutePath
        parts += "/usr/bin/env"
        parts += "-i"
        for ((k, v) in env) {
            parts += "$k=$v"
        }
        // Layer 5: the shell + script
        if (script.isBlank()) {
            parts += "/bin/sh"
            parts += "-l"
        } else {
            parts += "/bin/sh"
            parts += "-lc"
            parts += script
        }

        return parts.joinToString(" ") { shellQuote(it) }
    }

    /**
     * The "is this launcher viable at all?" sentinel returned by
     * [NamespacedDistroLauncher.buildShellCommand] when the device
     * is not rooted. The [LauncherResolver] matches on this
     * exact string.
     */
    const val MISSING_SENTINEL = "unshare-missing"

    /**
     * Quote a single argument for the `su -c` shell. The argument
     * is wrapped in single quotes; any embedded single quote
     * becomes `'\''` (close-quote, escaped-quote, open-quote). This
     * is the POSIX-portable way to make a string safe for `sh -c`.
     */
    fun shellQuote(value: String): String {
        // Special-case: empty string MUST be passed as "" or it
        // would be dropped by the shell.
        if (value.isEmpty()) return "''"
        // If the string is already shell-safe (alnum + a few
        // punctuation) we skip quoting to keep the command line
        // legible in `ps` / log output.
        if (SAFE_PATTERN.matches(value)) return value
        return "'" + value.replace("'", "'\\''") + "'"
    }

    private val SAFE_PATTERN = Regex("^[A-Za-z0-9._/:@=,+\\-]+$")

    /**
     * Sensible default environment inside the chroot. Mirrors the
     * env that [NativeProotLauncher] exports so a process behaves
     * identically regardless of which launcher the resolver picked.
     */
    val DEFAULT_ENV: List<Pair<String, String>> = listOf(
        "HOME" to "/root",
        "USER" to "root",
        "LOGNAME" to "root",
        "SHELL" to "/bin/sh",
        "TERM" to "xterm-256color",
        "LANG" to "C.UTF-8",
        "TMPDIR" to "/tmp",
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
    )
}
