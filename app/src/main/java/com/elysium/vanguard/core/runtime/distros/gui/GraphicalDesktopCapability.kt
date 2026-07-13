package com.elysium.vanguard.core.runtime.distros.gui

import com.elysium.vanguard.core.runtime.distros.launcher.LauncherKind
import java.io.File

/**
 * Honest capability report for the graphical-Linux route.
 *
 * A rootfs can contain a VNC/X server without the APK having a real
 * framebuffer client or compositor. This model makes that distinction
 * explicit so the UI never turns a placeholder bitmap into a false desktop.
 */
data class GraphicalDesktopCapability(
    val state: State,
    val detail: String,
    val detectedServer: String? = null
) {
    enum class State {
        ROOTFS_UNAVAILABLE,
        TERMINAL_READY,
        SERVER_DETECTED_RENDERER_AVAILABLE
    }

    val title: String
        get() = when (state) {
            State.ROOTFS_UNAVAILABLE -> "Runtime unavailable"
            State.TERMINAL_READY -> "Terminal workspace ready"
            State.SERVER_DETECTED_RENDERER_AVAILABLE -> "Graphical workspace ready"
        }

    /** True only when the rootfs has a local graphical server and Elysium has an RFB renderer. */
    val canRenderDesktop: Boolean
        get() = state == State.SERVER_DETECTED_RENDERER_AVAILABLE
}

/** Filesystem-only detector; safe to run before any Linux process is spawned. */
object GraphicalDesktopCapabilityDetector {
    private val serverCandidates = listOf(
        "usr/bin/Xvnc",
        "usr/bin/Xtigervnc",
        "usr/bin/vncserver",
        "usr/bin/Xorg",
        "usr/bin/Xwayland",
        "usr/bin/weston"
    )

    fun inspect(rootfsDir: File?, launcherKind: LauncherKind?): GraphicalDesktopCapability {
        if (rootfsDir == null || !rootfsDir.isDirectory) {
            return GraphicalDesktopCapability(
                state = GraphicalDesktopCapability.State.ROOTFS_UNAVAILABLE,
                detail = "Install a healthy rootfs before opening a Linux workspace."
            )
        }
        if (launcherKind != LauncherKind.NATIVE_PROOT) {
            return GraphicalDesktopCapability(
                state = GraphicalDesktopCapability.State.TERMINAL_READY,
                detail = "The verified surface is the interactive terminal. PRoot is required before a graphical guest can be started."
            )
        }
        val server = serverCandidates.firstOrNull { File(rootfsDir, it).canExecute() }
        return if (server == null) {
            GraphicalDesktopCapability(
                state = GraphicalDesktopCapability.State.TERMINAL_READY,
                detail = "No X11, Wayland, or VNC server exists in this rootfs. The terminal is available now."
            )
        } else {
            GraphicalDesktopCapability(
                state = GraphicalDesktopCapability.State.SERVER_DETECTED_RENDERER_AVAILABLE,
                detail = "Found $server. Start an authenticated local workspace to render its real framebuffer.",
                detectedServer = server
            )
        }
    }
}
