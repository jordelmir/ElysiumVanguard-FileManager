package com.elysium.vanguard.core.crdt

/**
 * PHASE 9.9.6 — Anti-entropy sync protocol on top of the op log.
 *
 * Two nodes converge by exchanging their [CrdtOpLog]s. The protocol
 * is symmetric: each side sends the entries it has that the other
 * hasn't seen yet. Each side replays the received entries into its
 * own (doc, seq).
 *
 * The "hasn't seen yet" rule is simple: the receiver compares the
 * sender's HLCs against its own clock — any op whose HLC is greater
 * than the receiver's last-seen HLC is new.
 *
 * Because the underlying CRDTs are commutative, associative, and
 * idempotent, the order in which entries arrive doesn't matter —
 * every node that has seen the same set of entries converges to the
 * same state.
 *
 * Phase 9.9.6 — first build; intentionally minimal.
 */
class CrdtSyncNode(val nodeId: String) {

    val log = CrdtOpLog()
    val doc = CrdtDoc()
    val seq = CrdtSequence()
    private var lastSeenHlc: HybridLogicalClock? = null
    val clock = HlcClock(nodeId)

    /**
     * Issue a fresh HLC for a local op and advance the local clock.
     */
    fun issueHlc(nowMs: Long = System.currentTimeMillis()): HybridLogicalClock =
        clock.issue(nowMs)

    /**
     * Record + apply a [CrdtOp.SetProperty] originating from this
     * node. Returns the recorded op so callers can echo it on the
     * wire if they want to.
     */
    fun setProperty(key: String, value: String, nowMs: Long = System.currentTimeMillis()): CrdtOp.SetProperty {
        val hlc = clock.issue(nowMs)
        val op = CrdtOp.SetProperty(hlc, key, value)
        record(op)
        return op
    }

    fun deleteProperty(key: String, nowMs: Long = System.currentTimeMillis()): CrdtOp.DeleteProperty {
        val hlc = clock.issue(nowMs)
        val op = CrdtOp.DeleteProperty(hlc, key)
        record(op)
        return op
    }

    fun insertSequence(value: String, nowMs: Long = System.currentTimeMillis()): CrdtSeqOp.Insert {
        val hlc = clock.issue(nowMs)
        val op = CrdtSeqOp.Insert(hlc, value)
        record(op)
        return op
    }

    fun deleteSequence(target: HybridLogicalClock, nowMs: Long = System.currentTimeMillis()): CrdtSeqOp.Delete {
        val hlc = clock.observe(target, nowMs) // observe target's HLC first
        val op = CrdtSeqOp.Delete(hlc, target)
        record(op)
        return op
    }

    private fun record(op: CrdtOp) {
        when (op) {
            is CrdtOp.SetProperty -> log.record(op)
            is CrdtOp.DeleteProperty -> log.record(op)
        }
        op.let { doc.apply(it) }
    }

    private fun record(op: CrdtSeqOp) {
        when (op) {
            is CrdtSeqOp.Insert -> log.record(op)
            is CrdtSeqOp.Delete -> log.record(op)
        }
        op.let { seq.apply(it) }
    }

    /**
     * Receive a remote log and apply only the entries we haven't
     * seen yet. The merged log dedupes by `hlc+kind` so a re-apply
     * is a no-op; we still iterate every entry because the
     * underlying CRDTs are idempotent.
     *
     * Returns the count of new entries applied (i.e. entries whose
     * HLC was not previously seen). A return of 0 means the remote
     * log was already absorbed.
     */
    fun absorb(remoteLog: CrdtOpLog): Int {
        var absorbed = 0
        // Snapshot which entries are already in our log so we don't
        // re-apply them. The underlying CRDTs are idempotent, so we
        // could just blindly re-apply everything — but tracking the
        // "new" count makes for a more useful return value.
        val alreadyHave = HashSet<String>()
        for (entry in log.rawEntries()) {
            alreadyHave.add(entryKey(entry))
        }
        val merged = log.merge(remoteLog)
        val sorted = merged.sortedEntries()
        for (entry in sorted) {
            val key = entryKey(entry)
            if (alreadyHave.add(key)) {
                entry.applyTo(doc, seq)
                val current = lastSeenHlc
                if (current == null || entry.hlc > current) {
                    lastSeenHlc = entry.hlc
                }
                absorbed++
            }
        }
        log.replaceEntries(sorted)
        return absorbed
    }

    private fun entryKey(entry: CrdtOpLog.Entry): String =
        "${entry.hlc.serialize()}:${entry::class.simpleName}"

    /**
     * Return the entries in the local log that are newer than the
     * remote node's lastSeenHlc. The caller sends these entries
     * to the remote.
     */
    fun entriesSince(remoteLastSeen: HybridLogicalClock?): CrdtOpLog {
        val out = CrdtOpLog()
        val sorted = log.sortedEntries()
        for (entry in sorted) {
            if (remoteLastSeen == null || entry.hlc > remoteLastSeen) {
                when (entry) {
                    is CrdtOpLog.DocSet -> out.record(CrdtOp.SetProperty(entry.hlc, entry.key, entry.value))
                    is CrdtOpLog.DocDel -> out.record(CrdtOp.DeleteProperty(entry.hlc, entry.key))
                    is CrdtOpLog.SeqIns -> out.record(CrdtSeqOp.Insert(entry.hlc, entry.value))
                    is CrdtOpLog.SeqDel -> out.record(CrdtSeqOp.Delete(entry.hlc, entry.targetHlc))
                }
            }
        }
        return out
    }

    /** The most recent HLC the node has absorbed. */
    fun lastSeen(): HybridLogicalClock? = lastSeenHlc
}