package com.elysium.vanguard.core.runtime.hardware.broker

/**
 * Phase 18 — the runtime's hardware access policy for a
 * session.
 *
 * The policy is a pair of
 *
 *   1. a [defaultMode] applied to every [HardwareClass] not
 *      listed in [classAccess], and
 *   2. a per-class override map ([classAccess]).
 *
 * The pattern is identical to the network policy: a default
 * + a per-target allow-list. The runtime combines the two
 * with a strict "the most restrictive wins" rule — if the
 * default is [HardwareAccess.READ_WRITE] and the per-class
 * entry is [HardwareAccess.BLOCKED], the class is blocked.
 *
 * The [classAccess] map is intentionally typed against the
 * [HardwareClass] enum, not a free-form string. The
 * [com.elysium.vanguard.core.runtime.hardware.broker.HardwareBroker]
 * uses the same enum and a typo in a catalog entry is a
 * compile error.
 *
 * The policy is a value type. The broker (Phase 18) is
 * the decision engine; the enforcer (Phase 19) is the
 * platform integration. Splitting decisions from enforcement
 * is the same seam the network policy uses (Phase 13 + 15).
 */
data class HardwarePolicy(
    val defaultMode: HardwareAccess = HardwareAccess.BLOCKED,
    /**
     * Per-class override. A class not in the map falls
     * through to [defaultMode]. A class in the map gets the
     * listed access regardless of the default — the override
     * is always more specific.
     */
    val classAccess: Map<HardwareClass, HardwareAccess> = emptyMap(),
    /**
     * Whether the user has opted into "remember my choice"
     * for [HardwareAccess.CONFIRM] decisions during this
     * session. The broker refuses to remember across
     * sessions (the policy is re-built on every boot).
     */
    val rememberConfirmations: Boolean = false
) {
    init {
        // The default mode is what most classes will get; we
        // do not restrict it. CONFIRM as a default is a
        // common "ask me about every access" stance and is
        // supported. BLOCKED as a default is the safe choice
        // and is the actual default value.
    }

    /**
     * Resolve the effective access for [hardwareClass]. The
     * per-class map wins; otherwise the default applies.
     */
    fun accessFor(hardwareClass: HardwareClass): HardwareAccess =
        classAccess[hardwareClass] ?: defaultMode
}
