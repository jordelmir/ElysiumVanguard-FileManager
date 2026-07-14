package com.elysium.vanguard.core.runtime.network

import com.elysium.vanguard.core.runtime.distros.launcher.NativeProotLauncher
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridge between the session lifecycle and the DNS refresh registry.
 *
 * Master order §10.1: the PRoot guest's resolver must follow Android's
 * active network. The refresh is fire-and-forget unless a guest process
 * is *actually* running on the rootfs. This class is the single point
 * that translates "session X started on rootfs Y" / "session X stopped
 * on rootfs Y" into registry calls.
 *
 * Lifecycle contract for callers (typically [com.elysium.vanguard.core.runtime.terminal.session.TerminalSessionManager]):
 *
 *   1. When the first session for a rootfs starts, call [onSessionStarted].
 *   2. When the last session for a rootfs ends, call [onSessionStopped].
 *   3. Calling [onSessionStarted] twice on the same rootfs is a no-op
 *      (the registry replaces the previous closure with the same one);
 *      it does NOT increment a session count. The caller is responsible
 *      for the "first / last" bookkeeping.
 *
 * The launcher is a [NativeProotLauncher]; its `refreshDnsForRootfs`
 * writes the bind-mounted `resolv.conf` atomically. The registry holds
 * a closure that captures the rootfs and calls the launcher on every
 * network change.
 */
@Singleton
class DistroSessionRegistry @Inject constructor(
    private val registry: ActiveRootfsRegistry,
    private val launcher: NativeProotLauncher
) {
    /**
     * A new live session is using [rootfs]. Register the launcher's
     * `refreshDnsForRootfs` closure so the network tracker has a
     * place to land. Re-registering the same rootfs replaces the
     * closure with an equivalent one (idempotent).
     */
    fun onSessionStarted(rootfs: File) {
        registry.register(rootfs) { rf -> launcher.refreshDnsForRootfs(rf) }
    }

    /**
     * The last live session on [rootfs] has ended. Remove the
     * closure so the network tracker no longer touches the bind
     * mount. No-op if the rootfs was never registered.
     */
    fun onSessionStopped(rootfs: File) {
        registry.unregister(rootfs)
    }
}
