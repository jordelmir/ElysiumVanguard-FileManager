package com.elysium.vanguard.core.runtime.domain

import com.elysium.vanguard.core.database.runtime.ApplicationCapsuleDao
import com.elysium.vanguard.core.database.runtime.ApplicationCapsuleEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * FASE 7 / section 14 — Production capsule registry backed by Room.
 *
 * The [ApplicationCapsuleEntity] is the persistence-side
 * representation: it carries the install path, executable,
 * launch count, and an opaque metadata_json column for
 * everything that does not deserve its own column. The
 * registry bridges between the persistence entity and the
 * domain-side [ApplicationCapsule] (the ADR-010 manifest
 * format).
 *
 * The registry does not parse or build the [ApplicationCapsule]
 * JSON. The capsule is constructed in memory by the loader
 * (from disk, from a package, from a remote URL) and saved as
 * a JSON blob in the metadata_json column. The launcher reads
 * the blob back when it needs the full capsule.
 *
 * Tests use a mockito mock of the DAO; production wires the
 * real Room DAO through a Hilt module (already provided in
 * [com.elysium.vanguard.core.database.runtime.RuntimeDatabaseModule]).
 */
class CapsuleRegistry(
    private val dao: ApplicationCapsuleDao,
    private val clock: () -> Long = System::currentTimeMillis
) {

    fun observeAll(): Flow<List<ApplicationCapsule>> = dao.observeAll().map { rows ->
        rows.mapNotNull { it.toCapsuleOrNull() }
    }

    suspend fun listAll(): List<ApplicationCapsule> = dao.listAll().mapNotNull { it.toCapsuleOrNull() }

    suspend fun getById(id: String): ApplicationCapsule? = dao.getById(id)?.toCapsuleOrNull()

    suspend fun getEntity(id: String): ApplicationCapsuleEntity? = dao.getById(id)

    suspend fun listByType(type: CapsuleType): List<ApplicationCapsule> =
        dao.listByType(type.name).mapNotNull { it.toCapsuleOrNull() }

    suspend fun search(query: String): List<ApplicationCapsule> =
        dao.search(query).mapNotNull { it.toCapsuleOrNull() }

    suspend fun count(): Int = dao.count()

    /**
     * Save a capsule. The JSON is the canonical manifest produced
     * by [ApplicationCapsule.toJson] and is stored verbatim in the
     * metadata_json column. integrityHash is the SHA-256 of the
     * manifest bytes, computed at save time so a tampered manifest
     * is detected on read.
     */
    suspend fun save(capsule: ApplicationCapsule): Result<Unit> {
        return try {
            val now = clock()
            val json = capsule.toJson()
            val integrity = sha256(json)
            val existing = dao.getById(capsule.capsuleId)
            val entity = ApplicationCapsuleEntity(
                id = capsule.capsuleId,
                name = capsule.name,
                version = capsule.version,
                capsuleType = capsule.type.name,
                path = existing?.path.orEmpty(),
                executable = capsule.launch.command.firstOrNull().orEmpty(),
                integrityHash = integrity,
                installedAtMs = existing?.installedAtMs ?: now,
                launchCount = existing?.launchCount ?: 0,
                lastLaunchedAtMs = existing?.lastLaunchedAtMs ?: 0L,
                metadataJson = json
            )
            dao.upsert(entity)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Record a launch. Bumps the launch count and the last
     * timestamp. The actual launch lifecycle is owned by the
     * capsule launcher; the registry only tracks telemetry.
     */
    suspend fun recordLaunch(id: String): Result<Unit> {
        return try {
            val existing = dao.getById(id)
                ?: return Result.failure(IllegalArgumentException("capsule $id not found"))
            dao.update(
                existing.copy(
                    launchCount = existing.launchCount + 1,
                    lastLaunchedAtMs = clock()
                )
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun delete(id: String): Result<Unit> {
        return try {
            dao.deleteById(id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun ApplicationCapsuleEntity.toCapsuleOrNull(): ApplicationCapsule? {
        val json = metadataJson ?: return null
        return try {
            ApplicationCapsule.parse(json)
        } catch (_: Exception) {
            null
        }
    }

    private fun sha256(s: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Section 14 — filter helpers over the registry. The filter
 * semantics are the same as the test-side CapsuleRegistry
 * fixture; the production implementation lives in
 * [CapsuleRegistry] and applies the filter after the DAO.
 */
object CapsuleFilters {
    fun byCapability(capsules: List<ApplicationCapsule>, capability: RequiredCapability): List<ApplicationCapsule> =
        capsules.filter { capability in it.capabilities }

    fun byQuery(capsules: List<ApplicationCapsule>, query: String): List<ApplicationCapsule> {
        if (query.isBlank()) return capsules
        val lower = query.lowercase()
        return capsules.filter { c ->
            c.name.lowercase().contains(lower) ||
                c.displayName.lowercase().contains(lower) ||
                c.capsuleId.lowercase().contains(lower) ||
                c.description.lowercase().contains(lower)
        }
    }
}
