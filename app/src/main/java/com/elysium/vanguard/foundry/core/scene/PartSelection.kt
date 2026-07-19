package com.elysium.vanguard.foundry.core.scene

/**
 * Phase 3 / I-3.4 — the **Part Selection** state.
 *
 * The selection is the read-side state for the
 * digital twin's user-facing surface. Per the
 * implementation roadmap I-3.4:
 *
 *   - "The user-facing selection. The selection
 *     is the read-side state for the UI + the
 *     input to the diagnostic engine."
 *
 * The selection has:
 *   - A selected part (a [PartInstanceId]; the
 *     selection is single-part; multi-select is
 *     a future enhancement).
 *   - An isolation mode (the part is "isolated"
 *     in the 3D view; the rest of the graph is
 *     dimmed / hidden).
 *
 * The selection is **immutable** (a data class; no
 * setters). A new selection is a new state. The
 * selection transitions are pure functions of
 * `(currentSelection, action)`.
 *
 * The selection is **pure-domain**: no I/O, no
 * Android dependencies. The selection is the
 * input to the 3D renderer + the diagnostic
 * engine.
 */
data class PartSelection(
    /**
     * The currently selected part's id. `null`
     * when nothing is selected (the default
     * state; the user has not selected anything).
     */
    val selectedId: PartInstanceId? = null,
    /**
     * The isolation mode. `true` when the
     * selected part is isolated (the rest of
     * the graph is dimmed / hidden). `false`
     * when the part is highlighted but the
     * rest of the graph is visible.
     */
    val isolated: Boolean = false,
) {
    /**
     * The selection is empty when no part is
     * selected. The empty selection is the
     * default state.
     */
    val isEmpty: Boolean = selectedId == null

    /**
     * The selection is isolated when a part
     * is selected AND the isolation mode is on.
     * A selection can't be "isolated" without
     * a selected part.
     */
    val isIsolated: Boolean
        get() = selectedId != null && isolated

    init {
        // The isolation mode requires a selected
        // part. A selection with no part is not
        // "isolated" (it's just empty).
        if (selectedId == null) {
            require(!isolated) {
                "PartSelection.isolated=true with no selectedId is invalid; " +
                    "isolation requires a selected part"
            }
        }
    }

    /**
     * Select a part. The selection transitions:
     *   - No part selected → the part is selected
     *     (not isolated).
     *   - The same part already selected → the
     *     selection is unchanged.
     *   - A different part selected → the
     *     selection transitions to the new part
     *     (not isolated; isolation is off when
     *     the selection changes).
     */
    fun select(id: PartInstanceId): PartSelection =
        if (selectedId == id) this else PartSelection(selectedId = id, isolated = false)

    /**
     * Deselect the current part. The selection
     * transitions to the empty state.
     */
    fun deselect(): PartSelection = EMPTY

    /**
     * Toggle the isolation mode. The transition:
     *   - Not isolated → isolated.
     *   - Isolated → not isolated.
     *
     * A selection with no part cannot be
     * isolated; the toggle is a no-op in that
     * case.
     */
    fun toggleIsolation(): PartSelection {
        if (selectedId == null) return this
        return PartSelection(selectedId = selectedId, isolated = !isolated)
    }

    companion object {
        /**
         * The empty selection. The default state;
         * the user has not selected anything.
         */
        val EMPTY: PartSelection = PartSelection()
    }
}
