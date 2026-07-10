package com.elysium.vanguard.core.crdt

/**
 * PHASE 9.9.1 — Hybrid Logical Clock.
 *
 * A [HybridLogicalClock] produces timestamps that are:
 *   1. totally ordered (every timestamp can be compared with any other),
 *   2. consistent with wall-clock time (we never go backwards without
 *      a reason), and
 *   3. deterministic across nodes when causality is preserved (if event A
 *      happens-before event B, then `ts(A) < ts(B)`).
 *
 * The HLC layout:
 *
 *     ms (long, physical ms since epoch)  |  counter (int, logical)  |  nodeId (String)
 *
 * The `counter` increments when two events on the same node share the
 * same `ms` so we never collide. The `nodeId` is a short random string
 * used as a tie-breaker when `ms` and `counter` are equal across nodes
 * — it guarantees total order.
 *
 * This is the timestamp used by every [CrdtOp] in Phase 9.9. We keep
 * it as a data class (not a primitive) so we can serialize it later
 * with the document without a custom format.
 *
 * Phase 9.9.1 — first build; intentionally minimal.
 */
data class HybridLogicalClock(
    val ms: Long,
    val counter: Int,
    val nodeId: String
) : Comparable<HybridLogicalClock> {

    override fun compareTo(other: HybridLogicalClock): Int {
        // Total order: ms first, counter second, nodeId last as
        // a deterministic tie-breaker so we never say "equal" when
        // two distinct events collided.
        val msCmp = ms.compareTo(other.ms)
        if (msCmp != 0) return msCmp
        val counterCmp = counter.compareTo(other.counter)
        if (counterCmp != 0) return counterCmp
        return nodeId.compareTo(other.nodeId)
    }

    /**
     * Serialize to a stable string of the form
     * `<ms>:<counter>:<nodeId>`. Safe for transport over JSON.
     */
    fun serialize(): String = "$ms:$counter:$nodeId"

    override fun toString(): String = "HLC($ms:$counter:$nodeId)"

    companion object {
        /**
         * Parse a serialized HLC produced by [serialize]. Returns
         * `null` if the input is malformed.
         */
        fun parse(text: String): HybridLogicalClock? {
            val parts = text.split(':')
            if (parts.size != 3) return null
            val ms = parts[0].toLongOrNull() ?: return null
            val counter = parts[1].toIntOrNull() ?: return null
            val nodeId = parts[2]
            if (nodeId.isEmpty()) return null
            return HybridLogicalClock(ms, counter, nodeId)
        }
    }
}

/**
 * A locally-generated HLC + ability to issue new timestamps with the
 * "happens-before" rule baked in.
 *
 * Each `HlcClock` instance represents one node (one device, one
 * process). Use a single instance per process so the counter is
 * monotonic per node.
 *
 * Phase 9.9.1 — first build; intentionally minimal.
 */
class HlcClock(private val nodeId: String) {

    private var lastMs: Long = 0L
    private var counter: Int = 0

    /**
     * Initialize from a previously-known HLC so that, when this clock
     * re-starts (e.g. after process restart), it picks up where the
     * remote events left off rather than colliding.
     */
    fun seed(initial: HybridLogicalClock?) {
        if (initial == null) return
        if (initial.nodeId != nodeId) {
            // We're seeing a foreign clock; align to its ms but keep our
            // nodeId so future events still attribute to us.
            lastMs = initial.ms
            counter = initial.counter
        } else {
            lastMs = initial.ms
            counter = initial.counter
        }
    }

    /**
     * Issue a fresh HLC. [nowMs] is the local wall-clock time at the
     * moment of the call (caller passes `System.currentTimeMillis()`
     * for production; tests pass a fixed value).
     */
    fun issue(nowMs: Long = System.currentTimeMillis()): HybridLogicalClock {
        val newMs = maxOf(nowMs, lastMs)
        val newCounter = if (newMs == lastMs) counter + 1 else 0
        lastMs = newMs
        counter = newCounter
        return HybridLogicalClock(newMs, newCounter, nodeId)
    }

    /**
     * Receive a remote HLC and produce a local HLC that is strictly
     * greater than both the remote and the local clock's previous
     * tick. This is the merge step: in CRDT terms, we advance our
     * clock to absorb the remote event's causality.
     */
    fun observe(remote: HybridLogicalClock, nowMs: Long = System.currentTimeMillis()): HybridLogicalClock {
        val newMs = maxOf(nowMs, lastMs, remote.ms)
        val newCounter = when {
            newMs == lastMs && newMs == remote.ms ->
                maxOf(counter, remote.counter) + 1
            newMs == lastMs -> counter + 1
            newMs == remote.ms -> remote.counter + 1
            else -> 0
        }
        lastMs = newMs
        counter = newCounter
        return HybridLogicalClock(newMs, newCounter, nodeId)
    }
}