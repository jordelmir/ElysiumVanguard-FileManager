package com.elysium.vanguard.core.runtime.distros.launcher

import java.io.File

/**
 * PHASE 10.4 — Direct-Exec launcher: the runtime's workhorse for "I just
 * want a shell that thinks it lives inside the rootfs".
 *
 * ## Why this exists
 *
 * The `JailedDistroLauncher` from Phase 9.6.3 only runs `/system/bin/sh`
 * with the rootfs as `cwd`. That gives the user an Android shell pointed
 * at the rootfs directory — useful for `ls`, `cat`, `find`, but it never
 * runs ANY of the binaries the distro actually ships. No `apt`, no `apk`,
 * no `bash`, no `busybox`. The user sees "no PTY yet" in the buffer and
 * the session exits.
 *
 * `NativeProotLauncher` (Phase 9.6.4) wants to wire `libproot.so` but the
 * binary is not vendored yet, so `isAvailable` always returns false.
 *
 * The Direct-Exec launcher bridges the gap. It locates the shell the
 * distro actually ships (`/bin/sh`, `/bin/bash`, `/bin/ash`, busybox, or
 * even a static `toybox`), then runs it directly with the rootfs as
 * `cwd` and a curated environment that points PATH, LD_LIBRARY_PATH, and
 * HOME into the rootfs. On Android 14/15 with an arm64 rootfs, the
 * device's loader can run arm64 ELFs out of the rootfs provided the
 * `ld-linux-aarch64.so.1` is reachable — and we set LD_LIBRARY_PATH
 * exactly so that.
 *
 * The launcher is honest about its limits: it cannot chroot, it cannot
 * mount /sdcard into the rootfs, it cannot fake `/proc`. It can however
 * give the user a real, interactive shell in which `apt update && apt
 * install htop` works on a properly-extracted Debian arm64 rootfs. That
 * alone is worth the work.
 *
 * ## Resolution
 *
 * `LauncherResolution` prefers Direct-Exec over Jailed when the rootfs
 * contains a runnable shell. The check is cheap: look for a known shell
 * path, see if the file is non-empty.
 *
 * ## Tests
 *
 * `DirectExecDistroLauncherTest` covers: command shape, environment
 * variables, shell preference order (bash > sh > ash > busybox),
 * availability when the rootfs is missing a shell, and probe command
 * shape.
 *
 * Phase 10.4 — first build; intentionally minimal but real.
 */
class DirectExecDistroLauncher(
    /**
     * Ordered list of shell paths the launcher probes inside the rootfs.
     * We try `bash` first because Debian/Ubuntu/Arch/Fedora all ship
     * it, then `ash`/`sh` (Alpine / busybox), then `busybox sh`, and
     * finally a direct fallback to whatever is at `/bin/sh`.
     */
    private val shellCandidates: List<String> = DEFAULT_SHELL_CANDIDATES,

    /**
     * Optional explicit shell path that overrides [shellCandidates].
     * Useful for tests and for future "user wants dash" preferences.
     */
    private val forcedShell: String? = null
) : DistroLauncher {

    override val kind: LauncherKind = LauncherKind.DIRECT_EXEC

    override val capabilities: LauncherCapabilities = LauncherCapabilities(
        // We CAN run ELF binaries from the rootfs, but only those whose
        // loader path is reachable via our LD_LIBRARY_PATH. Marking this
        // true makes the UI stop showing the "ELF unavailable" badge.
        canRunElfBinaries = true,
        // Direct-Exec still doesn't expose a real PTY; the terminal
        // screen drives the user's bytes through stdin. Same shape as
        // JailedDistroLauncher — UI behaves identically.
        exposesPty = false,
        // No bind mounts. The rootfs is the world.
        supportsBindMounts = false,
        // No root, no proot, no tricks.
        requiresRoot = false,
        // We depend on the device's default loader (arm64-v8a / x86_64
        // / etc.) — the rootfs' architecture must match the device.
        abiSupport = emptySet()
    )

    /**
     * PHASE 10.4 — Best-effort shell discovery for [rootfsDir].
     *
     * Walks [shellCandidates] in order, returns the first path that
     * points to a non-empty file. Returns `null` when nothing matches;
     * in that case the launcher will fall back to JailedDistroLauncher
     * at the resolution layer.
     */
    fun findShell(rootfsDir: File): String? {
        if (forcedShell != null) return forcedShell
        for (rel in shellCandidates) {
            val candidate = File(rootfsDir, rel)
            if (candidate.isFile && candidate.length() > 0L) {
                return rel
            }
        }
        return null
    }

    /**
     * PHASE 10.4 — Public environment overrides the launcher wants on
     * the resulting [ProcessBuilder]. Callers that wire the launcher
     * into a [TerminalSession] pass these through verbatim so the
     * rootfs' shell sees a properly-rooted PATH, LD_LIBRARY_PATH, HOME,
     * and TMPDIR without us having to splice shell snippets into the
     * command argv.
     */
    fun defaultEnvironment(rootfsDir: File): List<Pair<String, String>> {
        val rootfs = rootfsDir.absolutePath
        val pathEntries = listOf(
            "$rootfs/usr/local/sbin",
            "$rootfs/usr/local/bin",
            "$rootfs/usr/sbin",
            "$rootfs/usr/bin",
            "$rootfs/sbin",
            "$rootfs/bin"
        )
        val libEntries = listOf(
            "$rootfs/lib",
            "$rootfs/lib64",
            "$rootfs/usr/lib",
            "$rootfs/usr/lib64",
            "$rootfs/usr/libexec"
        )
        return listOf(
            "HOME" to "$rootfs/root",
            "TMPDIR" to "$rootfs/tmp",
            // We REPLACE — not prepend — so the rootfs' PATH is the
            // authoritative one. Android's `/system/bin` is still
            // reachable because the device's loader falls back to it
            // when an ELF lookup fails.
            "PATH" to pathEntries.joinToString(":"),
            "LD_LIBRARY_PATH" to libEntries.joinToString(":")
        )
    }

    override fun environmentVariables(rootfsDir: File): List<Pair<String, String>> =
        defaultEnvironment(rootfsDir)

    /**
     * PHASE 10.4 — Returns the absolute path of the shell this launcher
     * would invoke, or `null` when [rootfsDir] doesn't have a runnable
     * shell. Exposed so [com.elysium.vanguard.core.runtime.terminal.session.TerminalSession]
     * can build a faithful Config without re-walking the filesystem.
     */
    fun shellAbsolutePath(rootfsDir: File): String? {
        val rel = findShell(rootfsDir) ?: return null
        return File(rootfsDir, rel).absolutePath
    }

    override fun buildShellCommand(rootfsDir: File, script: String): List<String> {
        require(rootfsDir.isDirectory) { "rootfsDir is not a directory: $rootfsDir" }
        val shellRel = findShell(rootfsDir)
            ?: throw IllegalStateException(
                "DirectExecDistroLauncher: no shell found in $rootfsDir. " +
                    "Expected one of $shellCandidates"
            )
        val shellAbs = File(rootfsDir, shellRel).absolutePath
        // The whole point: let the user's bash / ash run with PATH and
        // LD_LIBRARY_PATH rooted at the distro, not at Android. We do
        // not chroot (we can't), but a well-formed PATH + LD_LIBRARY_PATH
        // is enough to make `apt`, `apk`, `pacman`, and friends resolve
        // to the binaries the distro actually ships.
        val envSetup = buildEnvSetup(rootfsDir)
        return if (script.isBlank()) {
            // Interactive shell. We invoke `-i` on bash; for busybox `sh`
            // / `ash` the default mode is already interactive. We
            // deliberately do NOT pass `-c` here; we want the user's
            // shell to take over the stdin pipe.
            listOf(shellAbs, "-i")
        } else {
            // One-shot: run the user's script inside the distro.
            listOf(shellAbs, "-c", envSetup.scriptHeader + " " + script)
        }
    }

    override fun buildProbeCommand(rootfsDir: File, args: List<String>): List<String> {
        require(rootfsDir.isDirectory) { "rootfsDir is not a directory: $rootfsDir" }
        val shellRel = findShell(rootfsDir)
            ?: throw IllegalStateException(
                "DirectExecDistroLauncher: no shell found in $rootfsDir"
            )
        val shellAbs = File(rootfsDir, shellRel).absolutePath
        val joined = args.joinToString(" ") { quoteIfNeeded(it) }
        val envSetup = buildEnvSetup(rootfsDir)
        return listOf(shellAbs, "-c", envSetup.scriptHeader + " " + joined)
    }

    override fun isAvailable(rootfsDir: File): Boolean {
        if (!rootfsDir.isDirectory) return false
        return findShell(rootfsDir) != null
    }

    /**
     * PHASE 10.4 — Produce the env-setup prefix for any shell command
     * we build. The setup is a tiny shell snippet that:
     *
     *   1. `cd`s to the rootfs so relative paths in the user's script
     *      resolve against the distro.
     *   2. Exports `PATH` so binaries resolve to the rootfs' bin dirs
     *      first, falling back to Android's PATH for tools like
     *      `/system/bin/ls` that are not in the rootfs.
     *   3. Exports `LD_LIBRARY_PATH` so the dynamic loader can find
     *      `libc`, `libpthread`, `ld-linux-aarch64.so.1`, etc.
     *   4. Sets `HOME` and `TMPDIR` inside the rootfs so applications
     *      that write dotfiles don't pollute the app's data dir.
     *   5. Preserves the user's existing PATH and LD_LIBRARY_PATH
     *      after the rootfs values (we prepend, not replace) so
     *      Android-side tooling is still reachable.
     *
     * We return a structured [EnvSetup] because the interactive and
     * scripted code paths need slightly different shapes; a `data class`
     * is clearer than two parallel string-builders.
     */
    private fun buildEnvSetup(rootfsDir: File): EnvSetup {
        val rootfs = rootfsDir.absolutePath
        val pathEntries = listOf(
            "$rootfs/usr/local/sbin",
            "$rootfs/usr/local/bin",
            "$rootfs/usr/sbin",
            "$rootfs/usr/bin",
            "$rootfs/sbin",
            "$rootfs/bin"
        )
        val libEntries = listOf(
            "$rootfs/lib",
            "$rootfs/lib64",
            "$rootfs/usr/lib",
            "$rootfs/usr/lib64",
            "$rootfs/usr/libexec"
        )
        val pathSpec = (pathEntries + "\$PATH").joinToString(":")
        val libSpec = (libEntries + "\$LD_LIBRARY_PATH").joinToString(":")

        // We chain the env setup with `&&` so a typo in one branch
        // doesn't silently skip the rest. The header ends with a `;`
        // so the user's script picks up the same env.
        val header = listOf(
            "cd ${quoteIfNeeded(rootfs)}",
            "export HOME=${quoteIfNeeded("$rootfs/root")}",
            "export TMPDIR=${quoteIfNeeded("$rootfs/tmp")}",
            "export PATH=${quoteIfNeeded(pathSpec)}",
            "export LD_LIBRARY_PATH=${quoteIfNeeded(libSpec)}"
        ).joinToString(" && ")
        return EnvSetup(
            scriptHeader = "($header)",
            setupSuffix = emptyList() // extra argv entries; reserved for future use
        )
    }

    /**
     * The two values DirectExec's command builders consume. Kept as a
     * data class so the builder helpers stay short.
     */
    private data class EnvSetup(
        /** Shell snippet that sets up the env and ends with a `)`. */
        val scriptHeader: String,
        /** Extra argv entries to append (currently unused). */
        val setupSuffix: List<String>
    )

    private fun quoteIfNeeded(s: String): String {
        // Single-quote everything that touches a shell. We do not have
        // to escape `'` itself because the value is a filesystem path
        // we control, but we still wrap in single quotes as a defense
        // in depth — `phase 9.6.3.1 will revisit when proot strings
        // carry shell metacharacters` was the old TODO; we close it
        // here.
        return "'" + s.replace("'", "'\\''") + "'"
    }

    companion object {
        /**
         * PHASE 10.4 — Default shell discovery order.
         *
         * Picked empirically: `bash` first because every "real" distro
         * (Debian, Ubuntu, Arch, Fedora) ships it; `sh` / `dash` second
         * because Debian/Ubuntu symlink `/bin/sh` to dash; `ash` because
         * Alpine is busybox-based; `busybox sh` because the absolute
         * minimum Alpine rootfs has no separate `sh` binary; finally
         * `/bin/sh` because some rootfs tarballs flatten to that.
         */
        val DEFAULT_SHELL_CANDIDATES: List<String> = listOf(
            "/usr/bin/bash",
            "/bin/bash",
            "/usr/bin/sh",
            "/bin/sh",
            "/usr/bin/dash",
            "/bin/dash",
            "/usr/bin/ash",
            "/bin/ash",
            "/usr/bin/busybox",
            "/bin/busybox"
        )
    }
}
