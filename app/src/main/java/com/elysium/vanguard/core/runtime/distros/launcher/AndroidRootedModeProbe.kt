package com.elysium.vanguard.core.runtime.distros.launcher

import android.content.Context
import android.content.pm.PackageManager
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PHASE 102 — Android production implementation of [RootedModeProbe].
 *
 * **Detection strategy** (cheap → expensive):
 *
 *   1. PackageManager probe for known root providers (Magisk,
 *      KernelSU, APatch). Zero cost.
 *   2. `Runtime.exec("which su")` — fast.
 *   3. `Runtime.exec("su -c 'id; which unshare; which cgexec;
 *      cat /proc/sys/kernel/unprivileged_userns_clone;
 *      stat -fc %T /sys/fs/cgroup'"` with a 3s timeout.
 *      This is the one and only su call we ever make on a
 *      probe cycle, and the result is cached for 5s.
 *
 * **Why a single `su` call and not four**: Android's `su`
 * binary typically forks a daemon (Magisk su, KernelSU su) and
 * the first call pays the daemon-startup cost. Subsequent
 * calls are ~10 ms. Batching all checks into one shell command
 * turns "5s of su + 5x daemon overhead" into "5s + 10 ms".
 *
 * **Why the cache TTL is 5s**: the screen is the primary
 * consumer; the user toggles Rooted Mode ON/OFF and re-renders
 * the screen. 5s is short enough that toggling always shows
 * fresh diagnostics on the next open, long enough that a
 * `LaunchedEffect { probe() }` re-render doesn't re-spawn su.
 */
@Singleton
class AndroidRootedModeProbe @Inject constructor(
    private val appContext: Context,
    private val cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS,
    private val shellTimeoutMs: Long = DEFAULT_SHELL_TIMEOUT_MS,
) : RootedModeProbe {

    @Volatile
    private var cached: RootStatus? = null

    @Volatile
    private var cachedAtMs: Long = 0L

    override fun probe(): RootStatus {
        val now = System.currentTimeMillis()
        cached?.let {
            if (now - cachedAtMs < cacheTtlMs) return it
        }
        val fresh = detect()
        cached = fresh
        cachedAtMs = now
        return fresh
    }

    /**
     * Force the next [probe] to re-run detection. Call this
     * when the user installs Magisk mid-session, or after
     * they toggle "Zygisk deny-list" and we want fresh
     * capabilities.
     */
    fun invalidate() {
        cached = null
        cachedAtMs = 0L
    }

    private fun detect(): RootStatus {
        val provider = detectProvider()
        if (provider == RootProvider.NONE) {
            return RootStatus(
                isRooted = false,
                provider = RootProvider.NONE,
                unshareAvailable = false,
                cgexecAvailable = false,
                unprivilegedUserNsClone = null,
                cgroupVersion = null,
                diagnostics = "no su binary detected",
            )
        }
        // We have a su binary; do one comprehensive su -c '...' call.
        val (idOk, unshare, cgexec, usernsClone, cgroupFsType, rawDiagnostics) = runProbeShell()
        if (!idOk) {
            return RootStatus(
                isRooted = false,
                provider = provider,
                unshareAvailable = false,
                cgexecAvailable = false,
                unprivilegedUserNsClone = null,
                cgroupVersion = null,
                diagnostics = "su exists but su -c 'id' failed: $rawDiagnostics",
            )
        }
        return RootStatus(
            isRooted = true,
            provider = provider,
            unshareAvailable = unshare,
            cgexecAvailable = cgexec,
            unprivilegedUserNsClone = usernsClone,
            cgroupVersion = cgroupVersionFromFsType(cgroupFsType),
            diagnostics = rawDiagnostics,
        )
    }

    private fun detectProvider(): RootProvider {
        if (packageInstalled("com.topjohnwu.magisk")) return RootProvider.MAGISK
        if (packageInstalled("me.weishu.kernelsu")) return RootProvider.KERNEL_SU
        if (packageInstalled("me.bmax.apatch")) return RootProvider.APATCH
        // Fallback: look for the su binary itself.
        val suPaths = listOf("/system/xbin/su", "/system/bin/su", "/sbin/su", "/data/adb/su")
        if (suPaths.any { File(it).isFile }) return RootProvider.GENERIC_SU
        return RootProvider.NONE
    }

    private fun packageInstalled(pkg: String): Boolean = try {
        appContext.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: Exception) {
        false
    }

    /**
     * Run a single su -c call that gathers everything we need:
     *
     *   id; which unshare; which cgexec; cat /proc/sys/kernel/unprivileged_userns_clone; stat -fc %T /sys/fs/cgroup
     *
     * Each command is on its own line; we read stdout line by
     * line and parse. The shell pipeline exits with the last
     * command's status, so a non-existent binary doesn't kill
     * the whole probe.
     */
    private fun runProbeShell(): ProbeResult {
        val cmd = arrayOf(
            "su", "-c",
            "id -u; which unshare 2>/dev/null; which cgexec 2>/dev/null; " +
                "cat /proc/sys/kernel/unprivileged_userns_clone 2>/dev/null; " +
                "stat -fc %T /sys/fs/cgroup 2>/dev/null"
        )
        val process = try {
            Runtime.getRuntime().exec(cmd)
        } catch (e: Exception) {
            return ProbeResult(false, false, false, null, null, "exec failed: ${e.message}")
        }
        val finished = try {
            process.waitFor(shellTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            return ProbeResult(false, false, false, null, null, "interrupted: ${e.message}")
        }
        if (!finished) {
            process.destroyForcibly()
            return ProbeResult(false, false, false, null, null, "timeout after ${shellTimeoutMs}ms")
        }
        val stdout = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
        val stderr = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
        val exitCode = process.exitValue()
        if (exitCode != 0) {
            return ProbeResult(false, false, false, null, null, "exit=$exitCode stderr=$stderr")
        }
        val lines = stdout.lines().filter { it.isNotBlank() }
        val idLine = lines.getOrNull(0).orEmpty()
        val idOk = idLine == "0"  // su -c 'id -u' should print 0
        val unshare = lines.getOrNull(1).orEmpty().startsWith("/")
        val cgexec = lines.getOrNull(2).orEmpty().startsWith("/")
        val usernsClone = lines.getOrNull(3)?.trim()?.toIntOrNull()?.let { it == 1 }
        val cgroupFsType = lines.getOrNull(4)?.trim()
        return ProbeResult(
            idOk = idOk,
            unshare = unshare,
            cgexec = cgexec,
            usernsClone = usernsClone,
            cgroupFsType = cgroupFsType,
            diagnostics = "stdout=$stdout stderr=$stderr",
        )
    }

    private fun cgroupVersionFromFsType(fsType: String?): Int? = when (fsType) {
        "cgroup2fs" -> 2
        "tmpfs" -> 1  // cgroup v1 unified hierarchy mounts as tmpfs
        null, "" -> null
        else -> null  // unknown — don't pretend
    }

    private data class ProbeResult(
        val idOk: Boolean,
        val unshare: Boolean,
        val cgexec: Boolean,
        val usernsClone: Boolean?,
        val cgroupFsType: String?,
        val diagnostics: String,
    )

    companion object {
        const val DEFAULT_CACHE_TTL_MS = 5_000L
        const val DEFAULT_SHELL_TIMEOUT_MS = 3_000L
    }
}
