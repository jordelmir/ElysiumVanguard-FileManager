package com.elysium.vanguard.core.runtime.capsule

/**
 * Phase 14 — in-memory registry of installed [ApplicationCapsule]s.
 *
 * The registry is the runtime's "what's installed" view. It is
 * process-local, thread-safe, and survives only the current
 * process. Persistence (a manifest file under `<rootfs>/var/
 * elysium/capsules.json` or a Room table) is the caller's job
 * and lives in a separate phase.
 *
 * The registry does NOT enforce capsule signatures — that's
 * [CapsuleInspector]'s job. The registry's contract is "the
 * caller has already verified the capsule, and now I just store
 * it". Splitting the concerns keeps the registry small and
 * makes the inspection path independently testable.
 */
class CapsuleRegistry {
    private val lock = Any()
    private val byId = mutableMapOf<String, ApplicationCapsule>()

    /**
     * Register [capsule]. Overwrites any existing entry with the
     * same id (the caller is expected to have already verified
     * the signature and checked compatibility).
     */
    fun install(capsule: ApplicationCapsule) {
        synchronized(lock) { byId[capsule.id] = capsule }
    }

    /**
     * Remove the capsule with [id]. Returns true if a capsule
     * was removed, false if no such id was registered.
     */
    fun uninstall(id: String): Boolean = synchronized(lock) {
        byId.remove(id) != null
    }

    /** Look up a capsule by id, or null if not installed. */
    fun find(id: String): ApplicationCapsule? = synchronized(lock) { byId[id] }

    /** All installed capsules, sorted by id. */
    fun list(): List<ApplicationCapsule> = synchronized(lock) {
        byId.values.sortedBy { it.id }
    }

    /** Number of installed capsules. */
    fun size(): Int = synchronized(lock) { byId.size }

    /** Remove every capsule. */
    fun clear() = synchronized(lock) { byId.clear() }
}
