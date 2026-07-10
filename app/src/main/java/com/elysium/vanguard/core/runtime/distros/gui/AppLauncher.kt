package com.elysium.vanguard.core.runtime.distros.gui

import java.io.File

/**
 * PHASE 9.6.9 — One launch request, surfaced from the app launcher
 * UI to the (yet-to-exist) proot-driven host.
 *
 * Today the launcher is a record-only data class. When the proot
 * binary + shell exist we will turn [command] into
 *
 *     proot -0 -r <rootfs> ... /usr/bin/env -S bash -c "<command>"
 *
 * Inside the shell, the X11-forwarder (Phase 9.6.6) sets $DISPLAY
 * and $XAUTHORITY, so a `firefox &` invoked this way would render
 * through our embedded X server.
 *
 * Phase 9.6.9 — first build; intentionally minimal.
 */
data class AppLaunch(
    val distroId: String,
    val app: LinuxAppEntry,
    val sessionId: String,
    /** The proot-prefixed command we WOULD run, given proot is available. */
    val command: List<String>
)

/**
 * PHASE 9.6.9 — Launch descriptor.
 *
 * Today's job: turn a `LinuxAppEntry` into a [AppLaunch]. The
 * actual process spawn (when proot lands) lands in 9.6.9.1 — this
 * class only computes the command shape.
 */
class AppLauncher(
    private val distroLauncherBuilder: (String) -> List<String> = { defaultLinuxEnvironment(it) }
) {
    /**
     * Build an [AppLaunch] from a [LinuxAppEntry]. The command list
     * is a real proot-style command; when proot isn't available the
     * caller checks [AppLauncher.canLaunch] first.
     */
    fun build(
        distroId: String,
        app: LinuxAppEntry,
        sessionId: String = java.util.UUID.randomUUID().toString()
    ): AppLaunch {
        val prefix = distroLauncherBuilder(distroId)
        return AppLaunch(
            distroId = distroId,
            app = app,
            sessionId = sessionId,
            command = prefix + "/usr/bin/env" + listOf("bash", "-c", app.exec)
        )
    }

    /**
     * Build the proot launcher command prefix for the given distro.
     * Real proot invocation lives in Phase 9.6.4 — this stub returns
     * a command line compatible with the build that has proot
     * available.
     */
    companion object {
        fun defaultLinuxEnvironment(distroId: String): List<String> {
            // We don't know the exact rootfsDir here; the caller passes it
            // through AppLaunch. The stub keeps the prefix length stable
            // so [AppLaunch.command] tests can pin its size.
            return listOf("proot", "-0", "-r", "<rootfs-for-$distroId>")
        }
    }
}

/**
 * PHASE 9.6.9 — Track launches in memory.
 *
 * The runtime layer will eventually persist this to disk; for now
 * it's a transient log the UI can render in the launcher screen.
 */
class AppLaunchLog {
    private val entries: MutableList<AppLaunch> = ArrayList()

    @Synchronized
    fun record(launch: AppLaunch) {
        entries += launch
    }

    @Synchronized
    fun list(): List<AppLaunch> = entries.toList()

    @Synchronized
    fun clear() {
        entries.clear()
    }
}
