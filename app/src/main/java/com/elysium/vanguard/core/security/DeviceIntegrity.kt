package com.elysium.vanguard.core.security

/**
 * The device's integrity state. The state is
 * computed at app start + before every security-
 * sensitive operation. A failed integrity check
 * is a hard rejection.
 *
 * The Zero Trust model (per `.ai/skills/12-security-
 * zero-trust/SKILL.md`): trust nothing, verify
 * everything. The integrity check is the
 * **first** check; a failed check means the
 * platform refuses to operate.
 *
 * Three checks:
 *   1. **Rooted**: the device's bootloader is
 *      unlocked OR `su` is available. A rooted
 *      device can bypass the Android security
 *      model.
 *   2. **Debugger**: a debugger is attached to
 *      the process. A debugger can read the
 *      process's memory + the Tink keyset.
 *   3. **Signature**: the app's signature
 *      matches the expected publisher. A
 *      tampered signature means the app was
 *      re-signed (e.g. by a malicious actor).
 */
data class DeviceIntegrity(
    val isRooted: Boolean,
    val isDebuggerAttached: Boolean,
    val isSignatureValid: Boolean,
    val appPackageName: String,
    val appSignatureDigest: String?,
) {
    /**
     * The integrity is "trusted" when ALL three
     * checks pass. A single failure is a hard
     * rejection.
     */
    val isTrusted: Boolean
        get() = !isRooted && !isDebuggerAttached && isSignatureValid

    /**
     * A human-readable list of failures. An
     * empty list means the device is trusted.
     */
    val failures: List<IntegrityFailure>
        get() = buildList {
            if (isRooted) add(IntegrityFailure.ROOTED)
            if (isDebuggerAttached) add(IntegrityFailure.DEBUGGER_ATTACHED)
            if (!isSignatureValid) add(IntegrityFailure.SIGNATURE_INVALID)
        }
}

enum class IntegrityFailure {
    ROOTED,
    DEBUGGER_ATTACHED,
    SIGNATURE_INVALID,
}
