package com.elysium.vanguard.core.runtime.distros.launcher

/**
 * PHASE 102 — typed wrapper for the **cgroup v2 controller limits**
 * the [NamespacedDistroLauncher] writes into the distro's cgroup
 * slice via `cgexec(1)`.
 *
 * **Why cgroup v2 and not v1**: Android has been on cgroup v2
 * since API 28 (Android 9 Pie). The v1 controllers (`cpu`,
 * `cpuacct`, `memory`, `blkio`, `pids`) are deprecated on modern
 * kernels and many rooted devices ship a hybrid hierarchy where
 * v1 is read-only. Writing limits to v2 is the only future-proof
 * path.
 *
 * **Controller list** (what we activate):
 *
 *  - `cpu`     → CPU weight (1..10000, default 100)
 *  - `memory`  → `memory.high` (soft) + `memory.max` (hard)
 *  - `io`      → IO weight (1..10000, default 100)
 *  - `pids`    → `pids.max` (process count ceiling)
 *
 * Each field is nullable; null means "don't write a limit, let
 * the kernel default apply". The builder only emits the
 * corresponding `-g` token if at least one controller is active.
 *
 * **Why a typed wrapper (not raw `Map<String, String>`)**: A
 * map is too loose — we want compile-time guarantees that
 * `cpuWeight` is in [1, 10000], not a stringly-typed "100" or
 * "ten thousand". A data class is also Hilt-bindable in
 * aggregate (one Hilt provider builds the whole spec from
 * saved preferences).
 */
data class CgroupSpec(
    /**
     * `cpu.weight` value. Range 1..10000. Null = inherit
     * parent's weight (kernel default: 100).
     */
    val cpuWeight: Int? = null,

    /**
     * `memory.high` value in bytes. Soft limit; the kernel
     * reclaims the cgroup's cache under memory pressure.
     * Null = no soft limit.
     */
    val memoryHighBytes: Long? = null,

    /**
     * `memory.max` value in bytes. Hard limit; the OOM
     * killer activates if the cgroup exceeds this.
     * Null = no hard limit.
     */
    val memoryMaxBytes: Long? = null,

    /**
     * `io.weight` value. Range 1..10000. Null = inherit
     * parent's weight (kernel default: 100).
     */
    val ioWeight: Int? = null,

    /**
     * `pids.max` value (process count ceiling). Null = no
     * limit (kernel default `max`).
     */
    val pidsMax: Int? = null,
) {
    init {
        cpuWeight?.let { require(it in 1..10000) { "cpuWeight out of range: $it" } }
        ioWeight?.let { require(it in 1..10000) { "ioWeight out of range: $it" } }
        memoryHighBytes?.let { require(it >= 0L) { "memoryHighBytes negative: $it" } }
        memoryMaxBytes?.let { require(it >= 0L) { "memoryMaxBytes negative: $it" } }
        pidsMax?.let { require(it > 0) { "pidsMax must be positive: $it" } }
        memoryHighBytes?.let { high ->
            memoryMaxBytes?.let { max ->
                require(high <= max) { "memoryHighBytes ($high) must be <= memoryMaxBytes ($max)" }
            }
        }
    }

    /**
     * Comma-separated list of cgroup v2 controllers that have
     * at least one non-null field. Returned in the canonical
     * order `cpu,memory,io,pids` so the resulting `-g` flag is
     * stable across runs (helps tests + reproducibility).
     */
    fun controllerList(): String {
        val active = ArrayList<String>(4)
        if (cpuWeight != null) active += "cpu"
        if (memoryHighBytes != null || memoryMaxBytes != null) active += "memory"
        if (ioWeight != null) active += "io"
        if (pidsMax != null) active += "pids"
        return active.joinToString(",")
    }

    /**
     * True iff no controller is configured. The builder drops
     * the entire `cgexec` layer when this is true (saves a
     * fork on devices without cgexec installed).
     */
    val isEmpty: Boolean
        get() = cpuWeight == null &&
            memoryHighBytes == null &&
            memoryMaxBytes == null &&
            ioWeight == null &&
            pidsMax == null

    companion object {
        /**
         * No limits — the process inherits the parent's
         * cgroup configuration. Equivalent to not invoking
         * `cgexec` at all.
         */
        val NONE = CgroupSpec()

        /**
         * A conservative "background" preset. Caps memory
         * at 2 GiB (soft 1.5 GiB) and pids at 256. Useful
         * when a user wants to launch a Linux distro but
         * not have it eat the whole device.
         */
        val BACKGROUND = CgroupSpec(
            cpuWeight = 100,
            memoryHighBytes = 1_610_612_736L,    // 1.5 GiB
            memoryMaxBytes = 2_147_483_648L,     // 2.0 GiB
            ioWeight = 100,
            pidsMax = 256,
        )
    }
}
