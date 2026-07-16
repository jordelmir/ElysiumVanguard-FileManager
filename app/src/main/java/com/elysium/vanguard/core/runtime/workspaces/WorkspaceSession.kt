package com.elysium.vanguard.core.runtime.workspaces

/**
 * Phase 24 — a session inside a [Workspace].
 *
 * A session is the runtime's "this is a running app"
 * representation. A workspace can hold any number of
 * sessions; each session is one of:
 *
 *   - [LinuxProot] — a Linux proot session backed by a
 *     distro + profile (the [com.elysium.vanguard.core.runtime.distros.DistroManager]
 *     path). The session is described by the distro id
 *     + profile id; the actual rootfs is in the
 *     manager's storage.
 *   - [WindowsVm] — a Windows QEMU session (the
 *     [com.elysium.vanguard.core.runtime.windows.WindowsVmManager]
 *     path). The session is described by the Windows
 *     spec id; the actual VM is in the manager's
 *     process table.
 *
 * Sealed-class polymorphism: a workspace can hold
 * sessions of either type without losing the typed
 * surface. The runtime's UI switches on the kind to
 * render the right controls (terminal for Linux,
 * VM display for Windows).
 */
sealed class WorkspaceSession {
    abstract val id: String
    abstract val displayName: String
    abstract val kind: SessionKind

    enum class SessionKind { LINUX_PROOT, WINDOWS_VM }

    data class LinuxProot(
        override val id: String,
        override val displayName: String,
        val distroId: String,
        val profileId: String
    ) : WorkspaceSession() {
        override val kind: SessionKind = SessionKind.LINUX_PROOT
        init {
            require(id.isNotBlank()) { "session id must not be blank" }
            require(displayName.isNotBlank()) { "session displayName must not be blank" }
            require(distroId.isNotBlank()) { "distroId must not be blank" }
            require(profileId.isNotBlank()) { "profileId must not be blank" }
        }
    }

    data class WindowsVm(
        override val id: String,
        override val displayName: String,
        val windowsSpecId: String
    ) : WorkspaceSession() {
        override val kind: SessionKind = SessionKind.WINDOWS_VM
        init {
            require(id.isNotBlank()) { "session id must not be blank" }
            require(displayName.isNotBlank()) { "session displayName must not be blank" }
            require(windowsSpecId.isNotBlank()) { "windowsSpecId must not be blank" }
        }
    }
}
