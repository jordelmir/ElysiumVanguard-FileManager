package com.elysium.vanguard.core.crdt

/**
 * PHASE 9.13 — Transport-agnostic sync adapter.
 *
 * A [CrdtSyncAdapter] is the boundary between the CRDT runtime
 * (which knows nothing about network) and the actual transport
 * (HTTP, SFTP, Bluetooth, USB, in-memory loopback for tests,
 * etc.). Every transport implements this interface so the rest
 * of the codebase can use a uniform `syncWith(adapter)` API.
 *
 * Two operations are enough for an anti-entropy sync round:
 *
 *   - [pushOps] — the local node ships its op log to the remote.
 *   - [pullOps] — the local node fetches the remote's op log.
 *   - [syncWith] — convenience wrapper: pull, then push, both
 *     sides converge in one call. Returns the count of ops
 *     absorbed.
 *
 * Phase 9.13 — first build; intentionally minimal.
 */
interface CrdtSyncAdapter {
    /**
     * Push the local log to the remote and merge the remote's
     * reply log back into the local session. Returns the count
     * of new ops absorbed (i.e. remote ops that were newer than
     * the local lastSeen).
     */
    fun syncWith(session: CrdtDocumentSession): Int
}