package com.elysium.vanguard.core.runtime.policy

/**
 * Phase 50 — the mount allowlist policy.
 *
 * A workspace's [MountPolicy] is the runtime's
 * answer to "what host paths is this workspace
 * allowed to bind-mount?". The policy is the
 * *defense in depth* on top of proot's
 * filesystem namespace: a path that is not in
 * the allowlist is invisible to the session,
 * and therefore unwritable.
 *
 * The policy is a list of [MountPolicyEntry]
 * records, each with:
 *
 *   - a `hostPathPrefix` (the path or
 *     path-prefix allowed; matching is
 *     case-sensitive on Android, case-sensitive
 *     on Linux),
 *   - a `readOnly` flag (the default
 *     read-only-ness for matches against this
 *     prefix),
 *   - an optional `label` for the UI.
 *
 * The policy also carries a [mode] (the
 * allowlist model) and a [defaultReadOnly]
 * flag (the read-only-ness the enforcer
 * applies when the proposed mount is allowed
 * but the policy entry does not specify a
 * read-only flag).
 *
 * The default mode is [MountPolicyMode.ALLOWLIST]
 * — a fresh workspace with no entries is
 * locked down. The user must explicitly add
 * paths to the policy before a session can
 * mount them.
 */
data class MountPolicy(
    val mode: MountPolicyMode = MountPolicyMode.ALLOWLIST,
    val entries: List<MountPolicyEntry> = emptyList(),
    val defaultReadOnly: Boolean = true
) {
    init {
        // Each entry's path-prefix must be an
        // absolute, normalised path. The
        // MountPolicyEntry init block enforces
        // that.
        val prefixes = entries.map { it.hostPathPrefix }
        require(prefixes.size == prefixes.toSet().size) {
            "mount policy has duplicate hostPathPrefix entries: $prefixes"
        }
    }

    companion object {
        /**
         * The most restrictive default policy: a
         * fresh workspace with no entries cannot
         * mount anything. The user must add
         * entries to opt-in to paths.
         */
        val LOCKED_DOWN: MountPolicy = MountPolicy(
            mode = MountPolicyMode.ALLOWLIST,
            entries = emptyList(),
            defaultReadOnly = true
        )

        /**
         * The most permissive policy: every path
         * is visible, every mount is writeable.
         * Use only for advanced users who have
         * explicitly opted in.
         */
        val OPEN: MountPolicy = MountPolicy(
            mode = MountPolicyMode.OPEN,
            entries = emptyList(),
            defaultReadOnly = false
        )
    }
}

/**
 * A single entry in the [MountPolicy]'s
 * allowlist (or blocklist, in `BLOCKLIST` mode).
 *
 * The [hostPathPrefix] is the path or
 * path-prefix this entry applies to. A proposed
 * mount whose host path starts with this prefix
 * matches the entry. The matching is
 * case-sensitive; on Android the file paths
 * are case-sensitive (ext4 / f2fs).
 *
 * The [readOnly] flag is the read-only-ness
 * the enforcer applies to the mount when the
 * proposed mount does not specify one. If the
 * proposed mount specifies its own read-only
 * flag, the proposed value is honoured.
 */
data class MountPolicyEntry(
    val hostPathPrefix: String,
    val readOnly: Boolean = true,
    val label: String? = null
) {
    init {
        require(hostPathPrefix.isNotBlank()) {
            "hostPathPrefix must not be blank"
        }
        require(hostPathPrefix.startsWith("/")) {
            "hostPathPrefix must be absolute: $hostPathPrefix"
        }
        // The hostPathPrefix is stored as-is. The
        // enforcer uses [normalisedPrefix] for
        // path-prefix matching, which strips a
        // single trailing slash so "/sdcard/" and
        // "/sdcard" match the same paths.
        require(hostPathPrefix.trimEnd('/').isNotEmpty()) {
            "hostPathPrefix must not be just '/': $hostPathPrefix"
        }
    }

    /**
     * The host-path prefix with a single trailing
     * slash stripped. The enforcer matches using
     * this value so a prefix of `/sdcard/` and a
     * prefix of `/sdcard` match the same paths.
     */
    val normalisedPrefix: String
        get() = hostPathPrefix.trimEnd('/')
}

/**
 * The mount allowlist model.
 *
 * - [ALLOWLIST] — only [MountPolicy.entries]
 *   apply; everything else is denied. The
 *   default.
 * - [BLOCKLIST] — every path is allowed except
 *   [MountPolicy.entries] (the entries are
 *   denials). Advanced users.
 * - [OPEN] — no policy; every path is allowed.
 *   Use only when the user has explicitly
 *   opted in.
 */
enum class MountPolicyMode {
    ALLOWLIST,
    BLOCKLIST,
    OPEN
}
