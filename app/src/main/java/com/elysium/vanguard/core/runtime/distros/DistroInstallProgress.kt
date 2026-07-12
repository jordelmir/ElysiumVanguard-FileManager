package com.elysium.vanguard.core.runtime.distros

/** Observable phase of a transactional rootfs install. */
enum class DistroInstallStage(val label: String) {
    PREFLIGHT("checking storage"),
    DOWNLOADING("downloading"),
    VERIFYING("verifying"),
    EXTRACTING("extracting"),
    VALIDATING("validating rootfs"),
    ACTIVATING("activating")
}

/** Immutable progress snapshot suitable for a StateFlow and Compose rendering. */
data class DistroInstallProgress(
    val stage: DistroInstallStage,
    val bytesProcessed: Long = 0L,
    val attempt: Int = 1,
    val maxAttempts: Int = 1
) {
    val displayLabel: String
        get() = buildString {
            append(stage.label)
            if (stage == DistroInstallStage.DOWNLOADING && maxAttempts > 1) {
                append(" · attempt ").append(attempt).append('/').append(maxAttempts)
            }
            if (bytesProcessed > 0L) append(" · ").append(bytesProcessed.displayByteSize())
        }
}
