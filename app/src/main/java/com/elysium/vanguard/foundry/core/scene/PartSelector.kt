package com.elysium.vanguard.foundry.core.scene

/**
 * Phase 3 / I-3.4 — the **Part Selector**.
 *
 * The selector is the operation layer for the
 * digital twin's user-facing selection. The
 * selector:
 *   - Selects a part by id (transitions the
 *     selection).
 *   - Deselects the current part (clears the
 *     selection).
 *   - Toggles the isolation mode (highlights
 *     the rest of the graph).
 *   - Resolves the selection to the underlying
 *     [PartInstance] (the part the user
 *     selected).
 *   - Computes the "visible" set: when a part
 *     is isolated, only the part + its
 *     descendants are visible; when not
 *     isolated, the entire graph is visible.
 *
 * The selector is **stateless**: the
 * [PartInstanceGraph] + the [PartSelection]
 * are the inputs; the result is the computed
 * visible set + the resolved instance.
 *
 * The selector is **pure-domain**: no I/O, no
 * Android dependencies. The selector is
 * JVM-testable end-to-end.
 */
object PartSelector {

    /**
     * The "visible" set. The list is the part
     * ids the renderer should draw. When the
     * selection is empty or not isolated, the
     * visible set is every instance in the
     * graph. When the selection is isolated, the
     * visible set is the selected part + its
     * descendants.
     *
     * The list is sorted by (depth, label) for
     * deterministic rendering.
     */
    fun visibleInstances(
        graph: PartInstanceGraph,
        selection: PartSelection,
    ): List<PartInstance> {
        val rootId = selection.selectedId ?: return sortedAll(graph)
        if (!selection.isIsolated) return sortedAll(graph)
        // Isolation: only the selected part + its
        // descendants are visible.
        val selected = graph.findById(rootId) ?: return sortedAll(graph)
        return listOf(selected) + graph.descendantsOf(rootId)
    }

    /**
     * The "hidden" set. The list is the part ids
     * the renderer should NOT draw (dimmed /
     * hidden). The hidden set is the complement
     * of the visible set.
     */
    fun hiddenInstances(
        graph: PartInstanceGraph,
        selection: PartSelection,
    ): List<PartInstance> {
        val visibleIds = visibleInstances(graph, selection).map { it.id }.toSet()
        return graph.instances.filter { it.id !in visibleIds }
    }

    /**
     * Resolve the selection to the underlying
     * [PartInstance]. Returns `null` when:
     *   - The selection is empty.
     *   - The selected id is not in the graph
     *     (a stale selection).
     */
    fun resolveSelection(
        graph: PartInstanceGraph,
        selection: PartSelection,
    ): PartInstance? {
        val id = selection.selectedId ?: return null
        return graph.findById(id)
    }

    /**
     * The "focused" instance: the selected part,
     * or the first root if no part is selected,
     * or `null` if the graph is empty.
     *
     * The focused instance is the part the UI
     * shows in the "selected part" panel +
     * the diagnostic engine uses as the input.
     */
    fun focusedInstance(
        graph: PartInstanceGraph,
        selection: PartSelection,
    ): PartInstance? {
        val resolved = resolveSelection(graph, selection)
        if (resolved != null) return resolved
        // No selection; default to the first root.
        return graph.roots.firstOrNull()
    }

    private fun sortedAll(graph: PartInstanceGraph): List<PartInstance> =
        graph.instances.sortedWith(
            compareBy(
                { graph.depthOf(it.id) },
                { it.displayLabel },
            ),
        )
}
