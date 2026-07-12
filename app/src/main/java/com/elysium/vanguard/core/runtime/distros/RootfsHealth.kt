package com.elysium.vanguard.core.runtime.distros

import java.io.File
import java.nio.file.Files

data class RootfsHealthReport(
    val isHealthy: Boolean,
    val reason: String? = null
)

/** Structural proof that an extracted directory is a usable Linux rootfs. */
object RootfsHealth {
    private val shellCandidates = listOf(
        "bin/sh",
        "usr/bin/sh",
        "bin/bash",
        "usr/bin/bash",
        "bin/ash",
        "usr/bin/ash"
    )

    fun inspect(rootfsDir: File, sizeOnDiskBytes: Long? = null): RootfsHealthReport {
        if (!rootfsDir.isDirectory) {
            return RootfsHealthReport(false, "rootfs directory is missing")
        }
        val measuredSize = sizeOnDiskBytes ?: rootfsDir.walkTopDown().sumOf { file ->
            if (file.isFile) file.length() else 0L
        }
        if (measuredSize <= 0L) {
            return RootfsHealthReport(false, "rootfs is empty")
        }
        val osRelease = File(rootfsDir, "etc/os-release")
        if (!osRelease.isFile || osRelease.length() == 0L) {
            return RootfsHealthReport(false, "rootfs has no /etc/os-release")
        }
        val shell = shellCandidates
            .asSequence()
            .map { File(rootfsDir, it) }
            .firstOrNull { candidate ->
                candidate.exists() || Files.isSymbolicLink(candidate.toPath())
            }
        if (shell == null) {
            return RootfsHealthReport(false, "rootfs has no supported shell")
        }
        return RootfsHealthReport(true)
    }
}
