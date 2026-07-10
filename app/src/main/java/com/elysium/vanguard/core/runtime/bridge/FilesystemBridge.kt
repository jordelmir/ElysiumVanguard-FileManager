package com.elysium.vanguard.core.runtime.bridge

import java.io.File

/**
 * PHASE 9.6.3 — One row of the bridge: a host path mapped to a path
 * inside the distro's filesystem namespace.
 *
 * The proot launcher consumes these via `-b <host>:<guest>` flags; the
 * jailed-shell launcher ignores them entirely (it has no namespace
 * separation). Future phases use the same data structure for the X11
 * forwarding and SSH filesystem passthrough.
 *
 * Phase 9.6.3 — first build; intentionally minimal.
 */
data class MountEntry(
    /** Path on the host filesystem (canonical absolute path). */
    val hostPath: String,

    /** Path inside the distro where this gets exposed. */
    val guestPath: String,

    /**
     * Read-only mount. The proot launcher honors this via
     * `--bind-ro`. Defaults to `true` because the distro is sandboxed;
     * the host can opt out per mount when a write-through is needed.
     */
    val readOnly: Boolean = true,

    /** Diagnostic label for logs. Optional. */
    val label: String? = null
) {
    init {
        require(hostPath.isNotBlank()) { "hostPath must not be blank" }
        require(guestPath.isNotBlank()) { "guestPath must not be blank" }
        require(guestPath.startsWith("/")) { "guestPath must be absolute: $guestPath" }
    }
}

/**
 * PHASE 9.6.3 — Central registry of Elysium-managed filesystems.
 *
 * Each entry knows its on-disk host path and the path the distro will
 * see it under. [FilesystemBridge] composes these into a final bind-mount
 * list for a given distro rootfs.
 *
 * Phase 9.6.3 ships concrete entries for the sdcard + vault + time-travel
 * namespaces. Phase 9.6.4 onwards wires individual subsystems in.
 *
 * Phase 9.6.3 — first build; intentionally minimal.
 */
data class ElysiumNamespaces(
    /** On-disk root of the user's external storage (might be null on devices without sdcard). */
    val sdcardPath: File?,

    /** On-disk root of the encrypted vault (always present; lives under `filesDir/vault/`). */
    val vaultPath: File?,

    /**
     * On-disk root of the time-travel snapshots directory. 9.6.3 leaves
     * this null because Phase 9.4 hasn't shipped the snapshot engine
     * yet; the bridge accepts the null and excludes the mount.
     */
    val timeTravelPath: File?,

    /**
     * Cloud mount root. Each provider exposes a `cloud/<provider>/`
     * directory; we expose the umbrella so distros can `ls /elysium/cloud`.
     */
    val cloudPath: File?
)

/**
 * PHASE 9.6.3 — Filesystem bridge: maps Elysium-owned paths into a
 * distro's namespace.
 *
 * The bridge is a pure function over its inputs, so it can be exercised
 * without any filesystem access. Callers (the proot launcher) feed the
 * resulting list of [MountEntry] into `proot -b` flags.
 *
 * Important: the bridge is the ONLY place where "what the distro sees
 * as `/sdcard`" lives. Future "smarter" mounts (cipher-aware decrypt,
 * cloud-source aggregation) all extend this single class instead of
 * spreading translate logic across launchers.
 *
 * Phase 9.6.3 — first build; intentionally minimal.
 */
object FilesystemBridge {

    /**
     * Build the mount list for a given rootfs + namespacing.
     *
     * Returns at minimum:
     *   - sdcard → /sdcard (read-only by default)
     *   - vault  → /elysium/vault (always exposed)
     *   - time-travel → /elysium/time-travel (when configured)
     *   - cloud → /elysium/cloud (when configured)
     *
     * The list is in priority order: earlier mounts win when paths
     * collide inside the distro.
     */
    fun mountsFor(namespaces: ElysiumNamespaces): List<MountEntry> {
        val out = ArrayList<MountEntry>()
        namespaces.sdcardPath?.let {
            out += MountEntry(
                hostPath = it.absolutePath,
                guestPath = "/sdcard",
                readOnly = true,
                label = "android sdcard"
            )
        }
        namespaces.vaultPath?.let {
            out += MountEntry(
                hostPath = it.absolutePath,
                guestPath = "/elysium/vault",
                readOnly = false,
                label = "elysium vault (decrypted)"
            )
        }
        namespaces.timeTravelPath?.let {
            out += MountEntry(
                hostPath = it.absolutePath,
                guestPath = "/elysium/time-travel",
                readOnly = true,
                label = "time-travel snapshots"
            )
        }
        namespaces.cloudPath?.let {
            out += MountEntry(
                hostPath = it.absolutePath,
                guestPath = "/elysium/cloud",
                readOnly = false,
                label = "cloud providers"
            )
        }
        return out
    }

    /**
     * Convenience for the most common case: just the sdcard + vault,
     * which is what 9.6.3 has wired in prod.
     */
    fun standardMounts(
        sdcardPath: File?,
        vaultPath: File?
    ): List<MountEntry> =
        mountsFor(
            ElysiumNamespaces(
                sdcardPath = sdcardPath,
                vaultPath = vaultPath,
                timeTravelPath = null,
                cloudPath = null
            )
        )
}
