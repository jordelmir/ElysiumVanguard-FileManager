package com.elysium.vanguard.core.crdt

/**
 * PHASE 9.9.3 — A tombstone-sequence CRDT for collaborative text.
 *
 * Each slot in the sequence has a stable identity: its
 * **insert HLC** (the HLC at the moment of creation). Deletes
 * mark the slot as tombstoned but keep the same identity so
 * that concurrent deletes and inserts on the same slot converge
 * deterministically.
 *
 * The slot layout:
 *
 *     Slot(insertHlc, value, tombstoned, lastTouchHlc)
 *
 *   - `insertHlc` is the slot's identity. Two slots are "the same"
 *     if and only if their `insertHlc`s are equal.
 *   - `value` is the live content. Empty for tombstones.
 *   - `tombstoned` is `true` after a delete op has been applied.
 *   - `lastTouchHlc` is the HLC of the most recent op that mutated
 *     this slot (insert or delete). On merge, the slot whose
 *     `lastTouchHlc` is greater wins.
 *
 * The linear order of the sequence is the order of `insertHlc`s.
 *
 * Properties (the standard CRDT ones):
 *
 *   - **Commutative**:  merge(A, B) == merge(B, A).
 *   - **Associative**:  merge(A, merge(B, C)) == merge(merge(A, B), C).
 *   - **Idempotent**:   merge(A, A) == A.
 *
 * Phase 9.9.3 — first build; intentionally minimal.
 */
class CrdtSequence {

    /**
     * One slot in the sequence. Inserted by [CrdtSeqOp.Insert],
     * tombstoned by [CrdtSeqOp.Delete]. The slot's identity is its
     * [insertHlc] — two slots are "the same" if and only if their
     * `insertHlc`s are equal.
     */
    internal data class Slot(
        val insertHlc: HybridLogicalClock,
        val value: String,
        val tombstoned: Boolean,
        val lastTouchHlc: HybridLogicalClock
    )

    private val slots: MutableList<Slot> = ArrayList()

    /** Number of live (non-tombstoned) elements in the sequence. */
    val size: Int get() = slots.count { !it.tombstoned }

    /** Total number of slots (live + tombstoned). */
    val totalSlots: Int get() = slots.size

    /**
     * Apply a single op. The op is recorded against the matching
     * slot (matched by `insertHlc` for both inserts and deletes).
     */
    fun apply(op: CrdtSeqOp) {
        when (op) {
            is CrdtSeqOp.Insert -> {
                // Idempotent: if we already have a slot with this
                // insertHlc, keep the existing one (insert HLCs are
                // unique per op, so collisions only happen on replay).
                val existing = slots.firstOrNull { it.insertHlc == op.hlc }
                if (existing == null) {
                    slots.add(
                        Slot(
                            insertHlc = op.hlc,
                            value = op.value,
                            tombstoned = false,
                            lastTouchHlc = op.hlc
                        )
                    )
                }
                // Sort by insertHlc so the linear order is preserved.
                slots.sortBy { it.insertHlc }
            }
            is CrdtSeqOp.Delete -> {
                // Locate the slot by insertHlc == targetHlc. If absent
                // (e.g. the delete arrived before we ever saw the
                // insert), record a tombstone-only slot so future
                // merges can still observe it.
                val existing = slots.firstOrNull { it.insertHlc == op.targetHlc }
                if (existing == null) {
                    slots.add(
                        Slot(
                            insertHlc = op.targetHlc,
                            value = "",
                            tombstoned = true,
                            lastTouchHlc = op.hlc
                        )
                    )
                } else {
                    val idx = slots.indexOf(existing)
                    val tomb = existing.copy(
                        tombstoned = true,
                        value = "",
                        lastTouchHlc = op.hlc
                    )
                    slots[idx] = tomb
                }
            }
        }
    }

    /**
     * Merge another [CrdtSequence] into this one. We union by
     * `insertHlc`; for collisions, the slot with the higher
     * `lastTouchHlc` wins (so a later delete can override a
     * concurrent insert on the same identity, and vice versa).
     */
    fun merge(other: CrdtSequence): CrdtSequence {
        for (incoming in other.slots) {
            val existing = slots.firstOrNull { it.insertHlc == incoming.insertHlc }
            if (existing == null) {
                slots.add(incoming)
            } else if (incoming.lastTouchHlc > existing.lastTouchHlc) {
                val idx = slots.indexOf(existing)
                slots[idx] = incoming
            }
        }
        slots.sortBy { it.insertHlc }
        return this
    }

    /**
     * Return the live sequence as a list of strings in causal order.
     */
    fun value(): List<String> =
        slots.asSequence().filter { !it.tombstoned }.map { it.value }.toList()

    /**
     * Render the live sequence as a single string with no separator.
     * Convenience for text editing use cases.
     */
    fun asString(): String = value().joinToString(separator = "")

    /**
     * Return the insertHlc of the i-th live element, or `null` if i
     * is out of range. This is what callers capture to drive a
     * future delete: `seq.delete(seq.hlcAt(i), hlc)`.
     */
    fun hlcAt(i: Int): HybridLogicalClock? {
        var seen = -1
        for (slot in slots) {
            if (slot.tombstoned) continue
            seen++
            if (seen == i) return slot.insertHlc
        }
        return null
    }
}

/**
 * PHASE 9.9.3 — Sequence ops.
 *
 *  - [Insert] creates a new live slot with the given HLC.
 *  - [Delete] tombstones the slot whose insertHlc equals [targetHlc].
 */
sealed interface CrdtSeqOp {
    val hlc: HybridLogicalClock

    data class Insert(
        override val hlc: HybridLogicalClock,
        val value: String
    ) : CrdtSeqOp

    data class Delete(
        override val hlc: HybridLogicalClock,
        val targetHlc: HybridLogicalClock
    ) : CrdtSeqOp
}