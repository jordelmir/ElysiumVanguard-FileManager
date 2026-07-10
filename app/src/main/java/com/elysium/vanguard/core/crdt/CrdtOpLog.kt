package com.elysium.vanguard.core.crdt

/**
 * PHASE 9.9.5 — Unified op log + replay engine for CRDT docs and
 * sequences.
 *
 * Every CRDT mutation (on a [CrdtDoc] or [CrdtSequence]) is recorded
 * as a single line in a [CrdtOpLog]. The log is the wire format
 * nodes exchange to converge: each side replays the remote's log
 * into its own doc + sequence.
 *
 * Wire format (text, line-based, easy to debug):
 *
 *     DSET <hlc> <key> <value>
 *     DDEL <hlc> <key>
 *     SINS <hlc> <value>
 *     SDEL <hlc> <targetHlc>
 *
 * Each line carries the HLC that originated the op. Lines are
 * sorted by HLC on serialize so the receiver can replay in causal
 * order regardless of the order lines were appended locally.
 *
 * Properties we get for free because the underlying CRDTs are
 * convergent:
 *
 *   - **Replay is idempotent**: applying the same log twice has the
 *     same effect as applying it once.
 *   - **Replay is commutative across splits**: applying two halves of
 *     a log in either order produces the same doc + sequence.
 *
 * Phase 9.9.5 — first build; intentionally minimal.
 */
class CrdtOpLog {

    private val entries: MutableList<Entry> = ArrayList()

    /** Internal accessor for advanced callers (e.g. [CrdtSyncNode]). */
    internal fun sortedEntries(): List<Entry> = entries.sortedWith(compareBy { it.hlc })

    /**
     * Internal accessor for advanced callers. Replaces the entire
     * entry list with [replacement]. Used by [CrdtSyncNode.absorb]
     * to persist a merged log without exposing the private field.
     */
    internal fun replaceEntries(replacement: List<Entry>) {
        entries.clear()
        entries.addAll(replacement)
    }

    /** Internal accessor: the current entry list (unsorted). */
    internal fun rawEntries(): List<Entry> = entries.toList()

    /** Number of entries in the log (live + recorded). */
    val size: Int get() = entries.size

    /** Internal entry shape used for replay. */
    internal sealed interface Entry {
        val hlc: HybridLogicalClock
        fun applyTo(doc: CrdtDoc, seq: CrdtSequence)
    }

    internal data class DocSet(
        override val hlc: HybridLogicalClock,
        val key: String,
        val value: String
    ) : Entry {
        override fun applyTo(doc: CrdtDoc, seq: CrdtSequence) {
            doc.apply(CrdtOp.SetProperty(hlc, key, value))
        }
    }

    internal data class DocDel(
        override val hlc: HybridLogicalClock,
        val key: String
    ) : Entry {
        override fun applyTo(doc: CrdtDoc, seq: CrdtSequence) {
            doc.apply(CrdtOp.DeleteProperty(hlc, key))
        }
    }

    internal data class SeqIns(
        override val hlc: HybridLogicalClock,
        val value: String
    ) : Entry {
        override fun applyTo(doc: CrdtDoc, seq: CrdtSequence) {
            seq.apply(CrdtSeqOp.Insert(hlc, value))
        }
    }

    internal data class SeqDel(
        override val hlc: HybridLogicalClock,
        val targetHlc: HybridLogicalClock
    ) : Entry {
        override fun applyTo(doc: CrdtDoc, seq: CrdtSequence) {
            seq.apply(CrdtSeqOp.Delete(hlc, targetHlc))
        }
    }

    /** Record a [CrdtOp.SetProperty] into the log. */
    fun record(op: CrdtOp.SetProperty) {
        entries.add(DocSet(op.hlc, op.key, op.value))
    }

    /** Record a [CrdtOp.DeleteProperty] into the log. */
    fun record(op: CrdtOp.DeleteProperty) {
        entries.add(DocDel(op.hlc, op.key))
    }

    /** Record a [CrdtSeqOp.Insert] into the log. */
    fun record(op: CrdtSeqOp.Insert) {
        entries.add(SeqIns(op.hlc, op.value))
    }

    /** Record a [CrdtSeqOp.Delete] into the log. */
    fun record(op: CrdtSeqOp.Delete) {
        entries.add(SeqDel(op.hlc, op.targetHlc))
    }

    /**
     * Replay every entry in the log into the given [doc] + [seq] in
     * HLC order. The replay is idempotent — applying the same log
     * twice has the same effect as applying it once.
     */
    fun replay(doc: CrdtDoc, seq: CrdtSequence) {
        for (entry in sortedEntries()) {
            entry.applyTo(doc, seq)
        }
    }

    /**
     * Merge another [CrdtOpLog] into this one. The merged log has
     * every entry from both sides, deduplicated by `hlc+kind` so
     * applying both halves of a sync yields the same final state.
     * Mutates this log in place.
     */
    fun merge(other: CrdtOpLog): CrdtOpLog {
        for (e in other.entries) {
            if (!entries.any { sameKind(it, e) }) {
                entries.add(e)
            }
        }
        return this
    }

    /**
     * Serialize the log to a stable text format. Lines are sorted
     * by HLC so the receiver can replay in causal order. Newlines
     * in values are escaped as `\n`; backslashes as `\\`.
     */
    fun serialize(): String = buildString {
        for (entry in sortedEntries()) {
            when (entry) {
                is DocSet -> {
                    append("DSET ").append(entry.hlc.serialize()).append(' ')
                    append(escape(entry.key)).append(' ')
                    append(escape(entry.value)).append('\n')
                }
                is DocDel -> {
                    append("DDEL ").append(entry.hlc.serialize()).append(' ')
                    append(escape(entry.key)).append('\n')
                }
                is SeqIns -> {
                    append("SINS ").append(entry.hlc.serialize()).append(' ')
                    append(escape(entry.value)).append('\n')
                }
                is SeqDel -> {
                    append("SDEL ").append(entry.hlc.serialize()).append(' ')
                    append(entry.targetHlc.serialize()).append('\n')
                }
            }
        }
    }

    /**
     * Parse a log back from text. Returns `null` if any line is
     * malformed — the caller decides whether to retry, discard, or
     * surface an error.
     *
     * Note: we DO NOT trim() individual lines because a value
     * may legitimately be a single space (`" "`). Trimming would
     * eat that space and break the parse. Instead we walk the
     * line by indexOf(' ') splits so the value substring can be
     * empty, a single space, or arbitrary text.
     */
    fun parse(text: String): CrdtOpLog? {
        val log = CrdtOpLog()
        for (rawLine in text.split('\n')) {
            if (rawLine.isEmpty()) continue
            // Skip pure-comment lines. Companion files prepend
            // header lines starting with `#`; the log itself is
            // never `#`-prefixed so we ignore them defensively.
            if (rawLine.startsWith("#")) continue
            // kind = first whitespace-delimited token.
            val firstSpace = rawLine.indexOf(' ')
            if (firstSpace <= 0) return null
            val kind = rawLine.substring(0, firstSpace)
            // hlc = second token.
            val rest = rawLine.substring(firstSpace + 1)
            val hlcSpace = rest.indexOf(' ')
            if (hlcSpace < 0) return null
            val hlc = HybridLogicalClock.parse(rest.substring(0, hlcSpace)) ?: return null
            val value = rest.substring(hlcSpace + 1)
            when (kind) {
                "DSET" -> {
                    // DSET value is `key val` — split the first
                    // remaining space so multi-word values survive.
                    val third = value.indexOf(' ')
                    if (third < 0) return null
                    val key = unescape(value.substring(0, third))
                    val v = unescape(value.substring(third + 1))
                    log.entries.add(DocSet(hlc, key, v))
                }
                "DDEL" -> log.entries.add(DocDel(hlc, unescape(value)))
                "SINS" -> log.entries.add(SeqIns(hlc, unescape(value)))
                "SDEL" -> {
                    val target = HybridLogicalClock.parse(value) ?: return null
                    log.entries.add(SeqDel(hlc, target))
                }
                else -> return null
            }
        }
        return log
    }

    private fun sameKind(a: Entry, b: Entry): Boolean {
        if (a.hlc != b.hlc) return false
        return when {
            a is DocSet && b is DocSet -> a.key == b.key && a.value == b.value
            a is DocDel && b is DocDel -> a.key == b.key
            a is SeqIns && b is SeqIns -> a.value == b.value
            a is SeqDel && b is SeqDel -> a.targetHlc == b.targetHlc
            else -> false
        }
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")

    private fun unescape(value: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (val next = value[i + 1]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    '\\' -> sb.append('\\')
                    else -> { sb.append(c); sb.append(next) }
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}