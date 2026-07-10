package com.elysium.vanguard.core.crdt

/**
 * PHASE 9.9.2 — Operations on a [CrdtDoc].
 *
 * The op model is intentionally narrow:
 *   - [SetProperty]  — create or update a key with a value (last-writer-wins).
 *   - [DeleteProperty] — tombstone a key (last-writer-wins; future sets
 *                        can resurrect if they carry a higher HLC).
 *
 * Every op carries an [hlc] that establishes a total order across
 * nodes. Merge logic only needs `compareTo` from HLC to decide which
 * op wins.
 *
 * Phase 9.9.2 — first build; intentionally minimal.
 */
sealed interface CrdtOp {
    val hlc: HybridLogicalClock

    data class SetProperty(
        override val hlc: HybridLogicalClock,
        val key: String,
        val value: String
    ) : CrdtOp

    data class DeleteProperty(
        override val hlc: HybridLogicalClock,
        val key: String
    ) : CrdtOp
}

/**
 * Internal record used by [CrdtDoc] to track which HLC won each key.
 *
 * `tombstoned = true` means the most recent op for this key was a
 * [CrdtOp.DeleteProperty]; a later [CrdtOp.SetProperty] with a higher
 * HLC can resurrect the key.
 */
internal data class LwwEntry(
    val hlc: HybridLogicalClock,
    val value: String?,
    val tombstoned: Boolean
) {
    /**
     * Compare-and-merge: if [incoming] has a strictly greater HLC,
     * replace the entry; otherwise keep the existing one.
     */
    fun merge(incoming: LwwEntry): LwwEntry =
        if (incoming.hlc > hlc) incoming else this
}

/**
 * PHASE 9.9.2 — Last-Writer-Wins Element Map CRDT.
 *
 * The document is a map of `String` keys to `String?` values where
 * `null` represents a tombstone. The merge rule is commutative,
 * associative, and idempotent, which is what makes the structure a
 * valid CRDT:
 *
 *   - **Commutative**:  merge(A, B) == merge(B, A).
 *   - **Associative**:  merge(A, merge(B, C)) == merge(merge(A, B), C).
 *   - **Idempotent**:   merge(A, A) == A.
 *
 * We support three operations:
 *
 *   - [apply] (single-op application)
 *   - [merge] (combine two CRDT docs into one, preserving both
 *     histories' HLCs)
 *
 * Phase 9.9.2 — first build; intentionally minimal.
 */
class CrdtDoc {

    private val entries: MutableMap<String, LwwEntry> = LinkedHashMap()

    /** Number of live (non-tombstoned) keys in the document. */
    val size: Int get() = entries.count { (_, e) -> !e.tombstoned }

    /** True if the key is present AND not tombstoned. */
    fun contains(key: String): Boolean =
        entries[key]?.let { !it.tombstoned } ?: false

    /** Get a value; returns `null` if missing or tombstoned. */
    fun get(key: String): String? =
        entries[key]?.takeIf { !it.tombstoned }?.value

    /**
     * Apply a single op. The op is recorded against the matching key
     * and, if its HLC is greater than the current entry's HLC, replaces
     * it.
     */
    fun apply(op: CrdtOp) {
        val incoming = when (op) {
            is CrdtOp.SetProperty -> LwwEntry(op.hlc, op.value, tombstoned = false)
            is CrdtOp.DeleteProperty -> LwwEntry(op.hlc, value = null, tombstoned = true)
        }
        val existing = entries[opKey(op)]
        entries[opKey(op)] = if (existing == null) incoming else existing.merge(incoming)
    }

    private fun opKey(op: CrdtOp): String = when (op) {
        is CrdtOp.SetProperty -> op.key
        is CrdtOp.DeleteProperty -> op.key
    }

    /**
     * Merge another [CrdtDoc] into this one. The merge is
     * idempotent: applying the same remote twice produces the same
     * document. The merge mutates this doc in place — the canonical
     * CRDT semantics where every node applies the same set of ops
     * and converges.
     */
    fun merge(other: CrdtDoc): CrdtDoc {
        // Pull in the other side, replacing only when its HLC wins.
        for ((k, incoming) in other.entries) {
            val existing = entries[k]
            entries[k] = if (existing == null) incoming else existing.merge(incoming)
        }
        return this
    }

    /**
     * Return a stable JSON-like snapshot of the live entries, in
     * insertion order. Useful for testing and for an "export"
     * feature in 9.9.4.
     */
    fun snapshot(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, e) in entries) {
            if (!e.tombstoned && e.value != null) out[k] = e.value
        }
        return out
    }

    /**
     * Return the internal map of all entries (live + tombstoned) for
     * debugging / advanced callers. Most consumers should use
     * [snapshot] instead.
     */
    fun debugEntries(): Map<String, Pair<HybridLogicalClock, String?>> {
        val out = LinkedHashMap<String, Pair<HybridLogicalClock, String?>>()
        for ((k, e) in entries) {
            out[k] = e.hlc to e.value
        }
        return out
    }
}