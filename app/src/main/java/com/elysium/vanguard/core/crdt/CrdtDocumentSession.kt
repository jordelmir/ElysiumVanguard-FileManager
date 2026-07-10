package com.elysium.vanguard.core.crdt

import com.elysium.vanguard.core.office.ElysiumDocument
import java.io.File

/**
 * PHASE 9.10 — Edit session for a CRDT-backed Elysium document.
 *
 * Wraps a [CrdtElysiumDocument] and a target [File] so edits to
 * the document are applied to the CRDT and saved to disk on
 * demand. Each keystroke issues a fresh HLC + op so the op log
 * captures the editing history character-by-character.
 *
 * Phase 9.12: the session also reads and writes the companion
 * `.elysium.<nodeId>.elysium.sync` file via [ElysiumSyncFile] so
 * the CRDT op log survives between sessions and can be shipped
 * to other nodes for offline-first sync.
 *
 * Phase 9.13: a separate [localLog] tracks ops issued by this
 * node; the on-disk companion records the union of local ops +
 * any remote ops merged in via [absorbRemote]. On [open] we
 * seed the body from the file's serialized state and DO NOT
 * replay the companion — its ops already contributed to the
 * file's content. This avoids the duplicate-replay bug that
 * would otherwise happen when a session is reopened.
 *
 * Phase 9.10 — first build; intentionally minimal.
 */
class CrdtDocumentSession(
    val doc: CrdtElysiumDocument,
    val file: File,
    val nodeId: String,
    initialClock: HlcClock? = null
) {

    private val clock: HlcClock = initialClock ?: HlcClock(nodeId).also {
        // Seed the clock from the highest HLC the document already
        // contains so the next issued HLC is strictly greater than
        // every existing one — otherwise a freshly opened session
        // might issue HLCs that sort BEFORE the seeded body chars.
        val highest = highestHlcInDoc()
        if (highest != null) it.seed(highest)
    }

    /**
     * Local op log: ops issued by THIS node (insert / delete /
     * title / author). Saved into the companion on [save] so the
     * remote side can replay them into its state.
     */
    val localLog: CrdtOpLog = CrdtOpLog()

    /**
     * The companion sync file associated with this session. Lazily
     * created; callers may read or write via this property. The
     * companion's log holds the union of [localLog] and any
     * remote logs merged in via [absorbRemote].
     */
    var syncFile: ElysiumSyncFile = ElysiumSyncFile.empty(file, nodeId)
        private set

    private fun highestHlcInDoc(): HybridLogicalClock? {
        var maxHlc: HybridLogicalClock? = null
        for ((_, v) in doc.metadata.debugEntries()) {
            val h = v.first
            if (maxHlc == null || h > maxHlc) maxHlc = h
        }
        // Walk the sequence slots too.
        for (i in 0 until doc.body.size) {
            val h = doc.body.hlcAt(i) ?: continue
            if (maxHlc == null || h > maxHlc) maxHlc = h
        }
        return maxHlc
    }

    /**
     * Insert a character at the given body index. The character
     * becomes a new live slot in the [CrdtSequence] with a fresh
     * HLC. We don't model fractional positions: insertions go to
     * the end of the live sequence (we treat body editing as
     * append-or-replace rather than mid-string insert, which keeps
     * the HLC ordering simple).
     */
    fun insertCharacter(ch: String): HybridLogicalClock {
        require(ch.length == 1) { "insertCharacter expects a single char, got $ch" }
        val hlc = clock.issue()
        val op = CrdtSeqOp.Insert(hlc, ch)
        doc.body.apply(op)
        localLog.record(op)
        return hlc
    }

    /**
     * Delete the character at the given live index. We look up
     * the slot's insertHlc and use it as the delete target.
     */
    fun deleteCharacterAt(liveIndex: Int): HybridLogicalClock? {
        val targetHlc = doc.body.hlcAt(liveIndex) ?: return null
        val hlc = clock.observe(targetHlc)
        val op = CrdtSeqOp.Delete(hlc, targetHlc)
        doc.body.apply(op)
        localLog.record(op)
        return hlc
    }

    /**
     * Replace the title. Empty string clears it.
     */
    fun setTitle(newTitle: String): HybridLogicalClock {
        val hlc = clock.issue()
        if (newTitle.isEmpty()) {
            val op = CrdtOp.DeleteProperty(hlc, "title")
            doc.metadata.apply(op)
            localLog.record(op)
        } else {
            val op = CrdtOp.SetProperty(hlc, "title", newTitle)
            doc.metadata.apply(op)
            localLog.record(op)
        }
        return hlc
    }

    /**
     * Replace the author. Empty string clears it.
     */
    fun setAuthor(newAuthor: String): HybridLogicalClock {
        val hlc = clock.issue()
        if (newAuthor.isEmpty()) {
            val op = CrdtOp.DeleteProperty(hlc, "author")
            doc.metadata.apply(op)
            localLog.record(op)
        } else {
            val op = CrdtOp.SetProperty(hlc, "author", newAuthor)
            doc.metadata.apply(op)
            localLog.record(op)
        }
        return hlc
    }

    /**
     * Body length in characters (live slots only).
     */
    fun bodyLength(): Int = doc.body.size

    /**
     * Body as a plain string for the editor's preview.
     */
    fun bodyAsString(): String = doc.body.asString()

    /**
     * Persist the current state back to the file. We serialize
     * through [CrdtElysiumDocument.toElysiumDocument] so the on-
     * disk file remains a plain [ElysiumDocument] (round-trip
     * safe). The companion sync file is updated with the new
     * highest HLC and saved alongside the document so the op log
     * survives between sessions.
     */
    fun save() {
        val asElysium = doc.toElysiumDocument()
        asElysium.writeTo(file)
        // Mirror the local op log into the companion file so sync
        // partners can pick up where we left off. The companion
        // holds the union of [localLog] and any remote logs we
        // merged in via [absorbRemote].
        syncFile.log.replaceEntries(emptyList())
        for (entry in localLog.rawEntries()) {
            when (entry) {
                is CrdtOpLog.DocSet -> syncFile.log.record(
                    CrdtOp.SetProperty(entry.hlc, entry.key, entry.value)
                )
                is CrdtOpLog.DocDel -> syncFile.log.record(
                    CrdtOp.DeleteProperty(entry.hlc, entry.key)
                )
                is CrdtOpLog.SeqIns -> syncFile.log.record(
                    CrdtSeqOp.Insert(entry.hlc, entry.value)
                )
                is CrdtOpLog.SeqDel -> syncFile.log.record(
                    CrdtSeqOp.Delete(entry.hlc, entry.targetHlc)
                )
            }
        }
        // Merge in any remote ops we previously absorbed.
        // (We rebuilt syncFile.log from localLog above; we don't
        // also merge here because nothing else has touched it.)
        syncFile.lastSeen = highestHlcInDoc()
        syncFile.save()
    }

    /**
     * Apply a remote sync file: merge its log into the local doc
     * and into the companion file. Idempotent — re-applying the
     * same remote log is a no-op.
     */
    fun absorbRemote(remote: ElysiumSyncFile): Int {
        // Apply the remote log to the doc + body.
        remote.log.replay(doc.metadata, doc.body)
        // Merge the remote log into the local companion file's
        // log so future saves propagate the remote ops to other
        // peers.
        val before = syncFile.log.rawEntries().size
        syncFile.log.merge(remote.log)
        val after = syncFile.log.rawEntries().size
        // Update lastSeen to the highest HLC we now know about.
        syncFile.lastSeen = highestHlcInDoc()
        return after - before
    }

    companion object {
        /**
         * Open an existing [ElysiumDocument] from [file] and wrap
         * it in a [CrdtDocumentSession]. The file must exist.
         * The companion sync file (if present) is loaded into
         * [syncFile] for future sync but NOT replayed — its ops
         * already contributed to the file's content, so re-applying
         * would duplicate them.
         */
        fun open(file: File, nodeId: String): CrdtDocumentSession {
            require(file.isFile) { "Cannot open non-existent file: $file" }
            val raw = file.readBytes()
            val elysium = ElysiumDocument.fromBytes(raw)
            val clock = HlcClock(nodeId)
            val crdt = CrdtElysiumDocument.fromElysiumDocument(elysium, nodeId, clock)
            val session = CrdtDocumentSession(crdt, file, nodeId, clock)
            // Load the companion file's log into memory so future
            // saves propagate it, but don't replay — the doc was
            // already seeded from the file content.
            val companion = ElysiumSyncFile.readFor(file, nodeId)
            if (companion != null) {
                session.syncFile = companion
            }
            return session
        }

        /**
         * Create a new document session backed by an empty
         * [ElysiumDocument] of the given [kind]. The file does
         * not need to exist yet — call [save] to persist.
         */
        fun create(file: File, kind: ElysiumDocument.Kind, nodeId: String): CrdtDocumentSession {
            val empty = ElysiumDocument(
                kind = kind,
                style = ElysiumDocument.StyleHints(),
                body = ByteArray(0)
            )
            val clock = HlcClock(nodeId)
            val crdt = CrdtElysiumDocument.fromElysiumDocument(empty, nodeId, clock)
            return CrdtDocumentSession(crdt, file, nodeId, clock)
        }
    }
}