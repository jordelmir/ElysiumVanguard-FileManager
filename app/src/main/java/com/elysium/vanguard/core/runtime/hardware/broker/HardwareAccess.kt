package com.elysium.vanguard.core.runtime.hardware.broker

/**
 * Phase 18 — coarse access mode for a single hardware class.
 *
 * The runtime's hardware policy is a per-class tuple. The
 * [MODE] is the *default* for the class; the per-class
 * [classAccess] map overrides it for the listed classes. A
 * mode of [BLOCKED] means "no access even with explicit
 * consent"; [CONFIRM] means "ask the user every time";
 * [READ_ONLY] and [READ_WRITE] are the two non-interactive
 * modes.
 */
enum class HardwareAccess {
    /** No access. The enforcer refuses the call without a UI path. */
    BLOCKED,

    /** No interactive access. The enforcer returns empty / zero data. */
    SILENT,

    /** Reads allowed; writes / connect / pair requires confirmation. */
    READ_ONLY,

    /** Full access. The enforcer grants the call without UI. */
    READ_WRITE,

    /**
     * Every access requires an explicit user confirmation
     * dialog. The runtime still shows a typed
     * [com.elysium.vanguard.core.runtime.hardware.broker.HardwareDecision.AllowWithConfirmation]
     * decision so the UI can render the consent prompt with
     * the reason (e.g. "Bluetooth pair request for
     * 11:22:33:44:55:66").
     */
    CONFIRM
}
