package com.elysium.vanguard.core.system

/**
 * PHASE 114 — a single **system sample** (one
 * point in time).
 *
 * The sample carries:
 *  - `atMs` — the wall-clock timestamp of
 *    the sample.
 *  - `cpuPercent` — the CPU utilization
 *    (0..100). The value is averaged
 *    across all cores; per-core
 *    breakdowns are a future phase.
 *  - `memoryUsedMb` / `memoryTotalMb` —
 *    the memory usage in megabytes. The
 *    `memoryUsedMb` is the platform's
 *    `MemTotal - MemAvailable` from
 *    `/proc/meminfo`. The `memoryTotalMb`
 *    is `MemTotal` (rounded down).
 *  - `temperatureCelsius` — the SoC
 *    temperature in °C. The value comes
 *    from `/sys/class/thermal/thermal_zone0/temp`
 *    (or zone1/zone2 if zone0 is missing).
 *    `null` if no thermal zone is
 *    readable (the device may not expose
 *    one — common on emulators).
 *  - `uptimeSeconds` — the system uptime
 *    in seconds. The value comes from
 *    `/proc/uptime` (the first field).
 */
data class SystemSample(
    val atMs: Long,
    val cpuPercent: Int,
    val memoryUsedMb: Int,
    val memoryTotalMb: Int,
    val temperatureCelsius: Double?,
    val uptimeSeconds: Long,
) {
    /**
     * The memory utilization (0..100).
     * Returns 0 when [memoryTotalMb] is 0
     * (avoids a divide-by-zero).
     */
    val memoryPercent: Int
        get() = if (memoryTotalMb > 0) {
            (memoryUsedMb * 100) / memoryTotalMb
        } else {
            0
        }
}

/**
 * PHASE 114 — the **system info provider**
 * facade.
 *
 * The provider is the seam between the
 * [com.elysium.vanguard.features.systemmonitor.SystemMonitorViewModel]
 * (pure-domain, no I/O) and the
 * Android-side readers (production:
 * `/proc/meminfo`, `/proc/stat`,
 * `/sys/class/thermal/`). The provider
 * returns a single [SystemSample] for
 * the current moment.
 *
 * **Why a facade (not calling Android
 * APIs directly from the ViewModel)**:
 * the ViewModel is pure-domain; the
 * platform files are I/O. Separating
 * the two concerns lets the ViewModel
 * be JVM-testable + the production
 * wiring straightforward.
 *
 * **Why a `suspend` function**: reading
 * the platform files is I/O (the kernel
 * may be slow to return); marking the
 * function `suspend` lets the caller
 * dispatch the read from a coroutine
 * scope + not block the UI thread.
 */
interface SystemInfoProvider {
    /**
     * Take a system sample. The returned
     * [SystemSample] carries the current
     * CPU + memory + temperature + uptime.
     * The function is read-only (no
     * side effects beyond reading the
     * kernel files).
     */
    suspend fun sample(): SystemSample
}
