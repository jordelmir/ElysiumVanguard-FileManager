package com.elysium.vanguard.foundry.core.dsl.editor

/**
 * Phase 2 / I-2.8 — the **SourceMap**.
 *
 * The [SourceMap] is the typed bridge between a
 * parsed [com.elysium.vanguard.foundry.core.dsl.schema.CompiledVehicleSpec]
 * (the schema) and the source line/column the
 * user is editing.
 *
 * The map is:
 *   - **Built during parsing.** The
 *     [com.elysium.vanguard.foundry.core.dsl.parser.JsonVehicleSpecParser]
 *     records the source position of every JSON
 *     field; the source map is the accumulated record.
 *   - **Consumed by the editor.** The editor's "go
 *     to definition" feature uses the map to navigate
 *     from a `$.body.architecture` path to the source
 *     line.
 *   - **Consumed by the live validator.** The live
 *     validator attaches source positions to
 *     diagnostics so the editor can render red
 *     squiggles.
 *
 * The map is a value object (immutable). New
 * positions are added by `with(path, position)`.
 * The map is JSON-path-keyed (e.g. `$.body.architecture`,
 * `$.propulsion.energySource`).
 *
 * The map is **complete**: every path the parser
 * has seen is in the map. The map is **deterministic**:
 * the same source produces the same map.
 */
data class SourceMap(
    private val positions: Map<String, SourcePosition>,
) {
    init {
        positions.forEach { (path, _) ->
            require(path.isNotBlank()) { "SourceMap path must not be blank, got: '$path'" }
            require(path.startsWith("$")) {
                "SourceMap path must start with $, got: '$path'"
            }
        }
    }

    /**
     * Look up the source position for a path.
     * Returns `null` when the path is not in
     * the map (e.g. a synthetic path the parser
     * didn't see).
     */
    fun positionFor(path: String): SourcePosition? = positions[path]

    /**
     * All paths in the map. The list is sorted
     * alphabetically for determinism.
     */
    val paths: List<String> = positions.keys.sorted()

    /**
     * The map is empty (no positions recorded).
     */
    fun isEmpty(): Boolean = positions.isEmpty()

    /**
     * The map's size (the count of paths).
     */
    val size: Int = positions.size

    /**
     * Add a path → position mapping. Returns a
     * new [SourceMap] (the source map is
     * immutable).
     */
    fun with(path: String, position: SourcePosition): SourceMap =
        copy(positions = positions + (path to position))

    /**
     * Add multiple path → position mappings. The
     * helper is the typical builder for the
     * parser's accumulated map.
     */
    fun withAll(entries: Map<String, SourcePosition>): SourceMap =
        copy(positions = positions + entries)

    /**
     * The map's entries as a sorted list of
     * `path → position` pairs. The list is a
     * copy; the consumer cannot mutate the map.
     */
    val entries: List<Map.Entry<String, SourcePosition>>
        get() = positions.toSortedMap().entries.toList()

    companion object {
        /**
         * The empty source map. The map has no
         * paths. The parser starts with an empty
         * map + accumulates positions as it
         * parses the JSON.
         */
        val EMPTY: SourceMap = SourceMap(positions = emptyMap())
    }
}

/**
 * A position in the source text. The position is
 * `(line, column)` — 1-indexed (per the editor
 * convention). The position is **inclusive**:
 * the character at `(line, column)` is the first
 * character of the field's value.
 *
 * The position is a value object (immutable).
 * The position is JSON-path-keyed: the same
 * source always produces the same position.
 */
data class SourcePosition(
    val line: Int,
    val column: Int,
) {
    init {
        require(line >= 1) { "SourcePosition.line must be >= 1, got $line" }
        require(column >= 1) { "SourcePosition.column must be >= 1, got $column" }
    }

    /**
     * The position as a human-readable string
     * (e.g. `line 12, column 5`).
     */
    fun displayString(): String = "line $line, column $column"
}
