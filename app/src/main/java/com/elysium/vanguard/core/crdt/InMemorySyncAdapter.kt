package com.elysium.vanguard.core.crdt

/**
 * PHASE 9.13 — In-memory implementation of [CrdtSyncAdapter].
 *
 * Holds a reference to a peer's [CrdtDocumentSession] directly.
 * Push/pull simply call the peer's [CrdtDocumentSession.save] /
 * [CrdtDocumentSession.absorbRemote] equivalents — there is no
 * serialization because the two sessions live in the same JVM.
 *
 * Use this in tests to simulate two-device sync without standing
 * up an actual HTTP server. The real network transport
 * (LocalServer, SftpServer, etc.) will plug in by providing a
 * different [CrdtSyncAdapter] implementation.
 *
 * Phase 9.13 — first build; intentionally minimal.
 */
class InMemorySyncAdapter(
    private val peer: CrdtDocumentSession
) : CrdtSyncAdapter {

    override fun syncWith(session: CrdtDocumentSession): Int {
        // Persist any pending local edits on the peer first so its
        // companion file on disk reflects its current state.
        peer.save()
        val remoteSync = ElysiumSyncFile.readFor(peer.file, peer.nodeId)
            ?: return 0
        return session.absorbRemote(remoteSync)
    }
}