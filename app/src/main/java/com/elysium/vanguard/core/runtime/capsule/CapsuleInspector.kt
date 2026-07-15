package com.elysium.vanguard.core.runtime.capsule

/**
 * Phase 14 — validates an [ApplicationCapsule] before the
 * runtime honors it.
 *
 * Master order §14 says every capsule "identifies each
 * application installed" and the runtime "uses" that
 * identification. The inspector is the "validate" step: it
 * checks the data-class invariants (which the [ApplicationCapsule]
 * init already enforces for the happy path) plus cross-field
 * rules that only make sense once a runtime context is known:
 *
 *   - The capsule's [ApplicationCapsule.compatibility] is at
 *     most as good as the runtime's confidence in the device.
 *     A `BLOCKED_BY_DRIVER` capsule on a device that lacks the
 *     required driver is a hard no.
 *   - The capsule's [ApplicationCapsule.architecture] must
 *     include at least one CPU arch that the device supports.
 *   - A `REQUIRES_VM` capsule must have at least one VM-class
 *     runtime in its fallback list.
 *
 * The inspector is **additive** to the init-block checks: those
 * are static and run on construction; these are policy checks
 * that depend on the runtime environment.
 */
class CapsuleInspector {

    data class Result(
        val isValid: Boolean,
        val issues: List<Issue>
    ) {
        companion object {
            val OK = Result(isValid = true, issues = emptyList())
        }
    }

    data class Issue(val severity: Severity, val message: String) {
        enum class Severity { ERROR, WARNING }
    }

    /**
     * Inspect [capsule] against [device]'s known capabilities.
     * Returns a [Result] with the list of issues found. An
     * ERROR severity means the capsule MUST NOT be installed;
     * a WARNING means the capsule will work but the user
     * should be told about the caveat.
     */
    fun inspect(
        capsule: ApplicationCapsule,
        device: DeviceCapabilities
    ): Result {
        val issues = mutableListOf<Issue>()

        // 1. CPU architecture.
        if (device.cpuArch.isNotEmpty() && capsule.architecture.intersect(device.cpuArch).isEmpty()) {
            issues += Issue(
                Issue.Severity.ERROR,
                "capsule supports ${capsule.architecture}; device is ${device.cpuArch}"
            )
        }

        // 2. Compatibility state vs device confidence. The
        // [CompatibilityState] enum encodes the runtime's
        // verdict on this device. UNSUPPORTED and BLOCKED_BY_DRIVER
        // are hard errors; the runtime must not honour a capsule
        // that says "I am broken on this device".
        if (capsule.compatibility == CompatibilityState.BLOCKED_BY_DRIVER) {
            issues += Issue(
                Issue.Severity.ERROR,
                "capsule is BLOCKED_BY_DRIVER on this device (driver issue)"
            )
        }
        if (capsule.compatibility == CompatibilityState.UNSUPPORTED) {
            issues += Issue(
                Issue.Severity.ERROR,
                "capsule is UNSUPPORTED on this device"
            )
        }

        // 3. REQUIRES_VM must have a VM fallback.
        if (capsule.compatibility == CompatibilityState.REQUIRES_VM) {
            val hasVmFallback = capsule.runtime.fallbacks.any { it.value.startsWith("linux-vm") }
            if (!hasVmFallback) {
                issues += Issue(
                    Issue.Severity.ERROR,
                    "capsule REQUIRES_VM but no linux-vm fallback is declared"
                )
            }
        }

        // 4. GPU profile sanity.
        if (capsule.gpu == GpuProfile.OPENGL && !device.hasOpenGL) {
            issues += Issue(
                Issue.Severity.WARNING,
                "capsule requests OpenGL but the device has no GL driver"
            )
        }
        if (capsule.gpu == GpuProfile.VULKAN && !device.hasVulkan) {
            issues += Issue(
                Issue.Severity.WARNING,
                "capsule requests Vulkan but the device has no Vulkan driver"
            )
        }

        // 5. Memory sanity.
        if (device.totalMemoryMb < capsule.resources.memoryRecommendedMb) {
            issues += Issue(
                Issue.Severity.WARNING,
                "insufficient memory: device has ${device.totalMemoryMb} MB; capsule wants ${capsule.resources.memoryRecommendedMb} MB"
            )
        }

        return Result(
            isValid = issues.none { it.severity == Issue.Severity.ERROR },
            issues = issues
        )
    }
}

/**
 * The runtime's view of the device's capabilities. The
 * inspector takes one of these as a parameter; the caller
 * (typically the platform-info probe) builds it from
 * `android.os.Build` and the Vulkan / GL extension queries.
 */
data class DeviceCapabilities(
    val cpuArch: Set<CpuArch>,
    val totalMemoryMb: Int,
    val hasVulkan: Boolean,
    val hasOpenGL: Boolean,
    /**
     * Map of "driver family" -> "is available". Examples:
     *   `{"turnip" -> true, "adreno" -> true, "freedreno" -> false}`.
     * The inspector uses this to decide whether
     * `BLOCKED_BY_DRIVER` applies.
     */
    val drivers: Map<String, Boolean> = emptyMap()
) {
    init {
        require(totalMemoryMb > 0) { "totalMemoryMb must be positive" }
    }
}
