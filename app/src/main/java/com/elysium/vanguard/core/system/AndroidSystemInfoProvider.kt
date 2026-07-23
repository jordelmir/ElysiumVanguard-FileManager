package com.elysium.vanguard.core.system

import javax.inject.Inject
import javax.inject.Singleton

/**
 * PHASE 114 — the **production**
 * [SystemInfoProvider] implementation.
 * Reads the kernel files
 * (`/proc/meminfo`, `/proc/stat`,
 * `/proc/uptime`,
 * `/sys/class/thermal/thermal_zoneN/temp`).
 *
 * The implementation is a thin shell over
 * the platform files. The math (CPU
 * utilization, memory percentage) lives
 * in the [SystemSample] data class.
 *
 * **Why a `suspend` function (and not
 * just a blocking one)**: the platform
 * reads are I/O-bound. Marking the
 * function `suspend` lets the caller
 * dispatch from a coroutine scope + not
 * block the UI thread. The implementation
 * itself is CPU-only (parsing the file
 * contents) but the `suspend` is the
 * contract for the caller.
 *
 * **Why `@Singleton`**: the provider
 * holds no state; one instance is
 * enough for the whole app. A future
 * phase adds a sample buffer (a ring
 * buffer of the last N samples) for the
 * chart; the buffer lives in the
 * provider.
 */
@Singleton
class AndroidSystemInfoProvider @Inject constructor() : SystemInfoProvider {

    override suspend fun sample(): SystemSample {
        val now = System.currentTimeMillis()
        val memInfo = readMemInfo()
        val cpuPercent = readCpuPercent()
        val uptimeSeconds = readUptimeSeconds()
        val temperatureCelsius = readTemperatureCelsius()
        return SystemSample(
            atMs = now,
            cpuPercent = cpuPercent,
            memoryUsedMb = memInfo.usedMb,
            memoryTotalMb = memInfo.totalMb,
            temperatureCelsius = temperatureCelsius,
            uptimeSeconds = uptimeSeconds,
        )
    }

    /**
     * Read `/proc/meminfo` and compute the
     * used + total memory. The file is
     * plain text (`MemTotal: 8192000 kB`,
     * `MemAvailable: 4096000 kB`, etc.).
     * The implementation reads the file
     * once + extracts the two fields by
     * line.
     */
    private fun readMemInfo(): MemoryInfo {
        return try {
            val contents = java.io.File("/proc/meminfo").readText()
            val totalKb = extractKb(contents, "MemTotal") ?: 0L
            val availableKb = extractKb(contents, "MemAvailable") ?: totalKb
            val totalMb = (totalKb / 1024L).toInt()
            val usedMb = ((totalKb - availableKb) / 1024L).toInt().coerceAtLeast(0)
            MemoryInfo(totalMb = totalMb, usedMb = usedMb)
        } catch (e: Exception) {
            MemoryInfo(totalMb = 0, usedMb = 0)
        }
    }

    /**
     * Read `/proc/stat` and compute the
     * CPU utilization. The file has
     * several lines; the first line is
     * `cpu  user nice system idle ...`.
     * The implementation reads the line,
     * parses the 7 standard fields
     * (user, nice, system, idle, iowait,
     * irq, softirq), and computes the
     * delta between two reads.
     *
     * The implementation does a single
     * read + uses a constant fallback
     * (5% CPU) when the read fails. A
     * real implementation would keep the
     * previous sample + compute the
     * delta; Phase 114 ships a simpler
     * version that returns the
     * instantaneous CPU utilization.
     */
    private fun readCpuPercent(): Int {
        return try {
            val contents = java.io.File("/proc/stat").readText()
            val firstLine = contents.lineSequence()
                .firstOrNull { it.startsWith("cpu ") }
                ?: return 0
            val fields = firstLine.split(Regex("\\s+"))
                .drop(1) // drop the "cpu" prefix
                .mapNotNull { it.toLongOrNull() }
            if (fields.size < 4) return 0
            val user = fields[0]
            val nice = fields[1]
            val system = fields[2]
            val idle = fields[3]
            val iowait = fields.getOrNull(4) ?: 0L
            val irq = fields.getOrNull(5) ?: 0L
            val softirq = fields.getOrNull(6) ?: 0L
            val total = user + nice + system + idle + iowait + irq + softirq
            val busy = total - idle - iowait
            if (total <= 0) return 0
            ((busy * 100L) / total).toInt().coerceIn(0, 100)
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Read `/proc/uptime` and return the
     * first field (the system uptime in
     * seconds, with a fractional part).
     * The implementation rounds the
     * fractional part down.
     */
    private fun readUptimeSeconds(): Long {
        return try {
            val contents = java.io.File("/proc/uptime").readText().trim()
            val first = contents.split(Regex("\\s+")).firstOrNull() ?: return 0L
            first.toDoubleOrNull()?.toLong() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Read the first available thermal
     * zone in `/sys/class/thermal/`. The
     * file `thermal_zone0/temp` is the
     * most common; if it's missing or
     * unreadable, the implementation
     * tries zone1 + zone2.
     *
     * The file value is in millidegrees
     * Celsius (e.g. `45000` for 45.0°C).
     * The implementation divides by 1000
     * to get the value in °C.
     *
     * Returns `null` when no thermal zone
     * is readable (common on emulators).
     */
    private fun readTemperatureCelsius(): Double? {
        for (i in 0..2) {
            val file = java.io.File("/sys/class/thermal/thermal_zone$i/temp")
            if (!file.exists()) continue
            try {
                val raw = file.readText().trim().toLongOrNull() ?: continue
                return raw / 1000.0
            } catch (e: Exception) {
                continue
            }
        }
        return null
    }

    /**
     * Helper for [readMemInfo] — extract
     * the integer value of a `Field: N
     * kB` line. The regex matches the
     * field name + the integer value
     * (the `kB` suffix is stripped).
     */
    private fun extractKb(contents: String, field: String): Long? {
        val regex = Regex("""$field:\s+(\d+)\s+kB""")
        val match = regex.find(contents) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    private data class MemoryInfo(val totalMb: Int, val usedMb: Int)
}
