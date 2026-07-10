package com.elysium.vanguard.core.conflict

/**
 * PHASE 1.10 — Conflict resolution model.
 *
 * Conflicts arise during batch operations (copy, move, rename) when the destination
 * already has a file with the same name. Two flavors:
 *
 *   - NAME conflict: same name, different content. The user picks one of three
 *     resolutions per file: keep source, keep existing, keep both (auto-rename).
 *   - HASH conflict: same name AND same content (true duplicate). The user's only
 *     meaningful choice is which copy survives — we don't bother with the "keep
 *     both" option because the bytes are byte-identical.
 *
 * Why we model it as data and not just enums: the UI needs to show a list of
 * conflicts with per-row checkboxes, the user might resolve them in any order,
 * and we want to express the resolution as a value (not a void callback) so the
 * ViewModel can compute a final operation plan.
 */
data class Conflict(
    val sourcePath: String,
    val sourceName: String,
    val sourceSize: Long,
    val destinationPath: String,
    val destinationName: String,
    val destinationSize: Long,
    val kind: Kind
) {
    enum class Kind {
        /** Same name, different bytes. User can keep source, keep destination, or rename. */
        NAME,
        /** Same name AND same bytes (true duplicate). Source is redundant. */
        DUPLICATE
    }

    /**
     * How the user wants this conflict resolved. The default — used until the
     * user makes an explicit choice — is [PENDING] so the UI can highlight
     * unresolved rows.
     */
    enum class Resolution {
        PENDING,        // user hasn't decided
        KEEP_SOURCE,    // overwrite destination with source
        KEEP_DESTINATION, // skip source, leave destination as-is
        KEEP_BOTH,      // rename source to "<name> (1).<ext>" and proceed
        SKIP            // cancel the operation for this row only
    }
}

/**
 * Summary of how many conflicts were detected and how they were resolved.
 * Returned by the batch operation after the user has decided on every conflict.
 */
data class ConflictSummary(
    val total: Int,
    val resolved: Int,
    val sourceKept: Int,
    val destinationKept: Int,
    val bothKept: Int,
    val skipped: Int
) {
    val isComplete: Boolean get() = resolved == total
}