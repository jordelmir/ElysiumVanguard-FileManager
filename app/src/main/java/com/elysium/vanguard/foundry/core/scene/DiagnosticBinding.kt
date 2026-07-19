package com.elysium.vanguard.foundry.core.scene

/**
 * Phase 3 / I-3.6 — the **Diagnostic Binding**.
 *
 * The binding maps a [PartInstanceId] to a list
 * of [Diagnostic]s. The binding is the read-side
 * state for the digital twin's diagnostic panel.
 *
 * The binding is **immutable** (a data class; no
 * setters). A new binding is a new state. A
 * binding transition is a pure function of
 * `(currentBindings, newDiagnostic)`.
 *
 * The binding is **pure-domain**: no I/O, no
 * Android dependencies. The binding is
 * JVM-testable end-to-end.
 */
data class DiagnosticBinding(
    /**
     * The map of `PartInstanceId` to the
     * diagnostics for that part. The map
     * preserves insertion order (a `LinkedHashMap`
     * is used internally for deterministic
     * iteration).
     */
    val diagnosticsByPart: Map<PartInstanceId, List<Diagnostic>>,
) {
    init {
        // Every list is non-null + has at least
        // one diagnostic (a binding to an empty
        // list is a smell — the part has no
        // diagnostics).
        for ((partId, diagnostics) in diagnosticsByPart) {
            require(diagnostics.isNotEmpty()) {
                "DiagnosticBinding: part $partId has an empty " +
                    "diagnostic list; remove the part instead"
            }
        }
    }

    /**
     * The list of part ids that have at least
     * one diagnostic. The list is sorted for
     * deterministic iteration.
     */
    val partsWithDiagnostics: List<PartInstanceId>
        get() = diagnosticsByPart.keys.sortedBy { it.value.toString() }

    /**
     * The list of all diagnostics across all
     * parts. The list is sorted by `(partId,
     * dtcCode)` for deterministic iteration.
     */
    val allDiagnostics: List<Diagnostic>
        get() = diagnosticsByPart.entries
            .flatMap { (partId, diags) -> diags.map { partId to it } }
            .sortedWith(
                compareBy(
                    { it.first.value.toString() },
                    { it.second.dtcCode },
                ),
            )
            .map { it.second }

    /**
     * Look up the diagnostics for a given part.
     * Returns an empty list when the part has
     * no diagnostics.
     */
    fun diagnosticsFor(partId: PartInstanceId): List<Diagnostic> =
        diagnosticsByPart[partId] ?: emptyList()

    /**
     * Add a diagnostic to a part. Returns a new
     * [DiagnosticBinding] with the diagnostic
     * added. The transition is pure: the
     * original binding is unchanged.
     */
    fun addDiagnostic(partId: PartInstanceId, diagnostic: Diagnostic): DiagnosticBinding {
        val existing = diagnosticsByPart[partId] ?: emptyList()
        val updated = diagnosticsByPart + (partId to (existing + diagnostic))
        return copy(diagnosticsByPart = updated)
    }

    /**
     * Remove a part's diagnostics. Returns a
     * new [DiagnosticBinding] with the part's
     * diagnostics removed.
     */
    fun removePart(partId: PartInstanceId): DiagnosticBinding =
        copy(diagnosticsByPart = diagnosticsByPart - partId)

    companion object {
        /**
         * The empty binding. No part has
         * diagnostics.
         */
        val EMPTY: DiagnosticBinding = DiagnosticBinding(emptyMap())
    }
}
