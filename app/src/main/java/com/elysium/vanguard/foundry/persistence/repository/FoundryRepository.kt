package com.elysium.vanguard.foundry.persistence.repository

import com.elysium.vanguard.foundry.core.contributor.Contributor
import com.elysium.vanguard.foundry.core.artifact.EngineeringArtifact
import com.elysium.vanguard.foundry.core.program.VehicleProgram
import com.elysium.vanguard.foundry.core.project.Project
import com.elysium.vanguard.foundry.core.provenance.ProvenanceRecord
import com.elysium.vanguard.foundry.core.revision.VehicleRevision
import kotlinx.coroutines.flow.Flow

/**
 * The Foundry repository layer — the **only** path to a
 * persistent mutation.
 *
 * Per `docs/foundry/implementation-roadmap.md` section 12 +
 * `.ai/AGENTS.md` section 24.1:
 *   - A repository owns ONE aggregate.
 *   - The repository is the boundary between the domain
 *     services (pure, no I/O) and the persistence layer
 *     (Room, in-memory, future: a remote store).
 *   - Every mutation returns a `Result<Unit, FoundryError>` —
 *     the typed error is the contract.
 *   - Optimistic concurrency is enforced at the repository
 *     boundary (the `expectedVersion` argument).
 *   - The repository does NOT enforce business invariants
 *     (the services do that). The repository enforces
 *     **persistence invariants** (the row exists, the
 *     version matches, the immutable aggregate is not
 *     mutated).
 *
 * The repository layer is split into 6 interfaces
 * (one per aggregate). Each interface is a
 * **sub-interface** of `MutableAggregateRepository`
 * (the aggregates that support update + delete) or
 * `AppendOnlyRepository` (the aggregates that are
 * immutable: `VehicleRevision` + `ProvenanceRecord`).
 *
 * The Hilt module (`FoundryPersistenceModule`) wires
 * the Room-backed implementations. The InMemory
 * implementations live in `src/test/` and are used
 * by the unit-test suite.
 */

/**
 * A repository for a **mutable** aggregate (one that supports
 * `insert` + `update` + `delete` + `getById` + `observeAll`).
 *
 * The `update` operation is **optimistic-concurrency-controlled**:
 * the caller supplies the `expectedVersion`; the repository
 * raises a `FoundryError.RevisionConflict` if the stored
 * version differs.
 */
interface MutableAggregateRepository<T, ID> {
    suspend fun insert(aggregate: T): Result<Unit>
    suspend fun update(aggregate: T, expectedVersion: Long): Result<T>
    suspend fun deleteById(id: ID): Result<Unit>
    suspend fun getById(id: ID): T?
    fun observeAll(): Flow<List<T>>
    suspend fun count(): Int
}

/**
 * A repository for an **append-only** aggregate. The aggregate
 * is inserted once + never modified. There is no `update` +
 * no `delete` (per ADR-0006).
 */
interface AppendOnlyRepository<T, ID> {
    suspend fun append(aggregate: T): Result<Unit>
    suspend fun getById(id: ID): T?
    fun observeAll(): Flow<List<T>>
    suspend fun count(): Int
}

// ============================================================
// Per-aggregate repository contracts
// ============================================================

/**
 * The `Project` repository. The `Project` is a mutable aggregate
 * with a `version: Long` field (per `OptimisticConcurrency`).
 */
interface ProjectRepository : MutableAggregateRepository<Project, com.elysium.vanguard.foundry.core.ontology.ids.ProjectId> {
    suspend fun getByOwner(ownerId: com.elysium.vanguard.foundry.core.ontology.ids.UserId): List<Project>
}

/**
 * The `VehicleProgram` repository. The program is a mutable
 * aggregate; the `revisions` list is append-only (the list
 * is mutated by the `addRevision` use case + the repository
 * is the persistence boundary).
 */
interface VehicleProgramRepository : MutableAggregateRepository<VehicleProgram, com.elysium.vanguard.foundry.core.ontology.ids.VehicleProgramId> {
    suspend fun getByProject(projectId: com.elysium.vanguard.foundry.core.ontology.ids.ProjectId): List<VehicleProgram>
}

/**
 * The `Contributor` repository. The contributor is a mutable
 * aggregate; the `email` PII is stored as-is in Phase F1 and
 * encrypted at rest in Phase 5 (per skill 12).
 */
interface ContributorRepository : MutableAggregateRepository<Contributor, com.elysium.vanguard.foundry.core.ontology.ids.ContributorId> {
    suspend fun getByEmail(email: String): Contributor?
}

/**
 * The `EngineeringArtifact` repository. The artifact is a
 * mutable aggregate; the `contentHash` + `format` +
 * `sizeBytes` are immutable (a change to the bytes is a
 * new artifact).
 */
interface EngineeringArtifactRepository : MutableAggregateRepository<EngineeringArtifact, com.elysium.vanguard.foundry.core.ontology.ids.EngineeringArtifactId> {
    suspend fun getByContentHash(hash: String): EngineeringArtifact?
    suspend fun getBySubject(subjectId: String): List<EngineeringArtifact>
}

/**
 * The `VehicleRevision` repository. The revision is
 * **immutable** (per ADR-0006) — the repository has only
 * `append` + `getById` + `count` (no `update` + no
 * `delete`).
 */
interface VehicleRevisionRepository : AppendOnlyRepository<VehicleRevision, com.elysium.vanguard.foundry.core.ontology.ids.VehicleRevisionId> {
    suspend fun getByProject(projectId: com.elysium.vanguard.foundry.core.ontology.ids.ProjectId): List<VehicleRevision>
    suspend fun getByContentHash(hash: String): VehicleRevision?
}

/**
 * The `ProvenanceRecord` repository. The record is
 * **append-only** (per ADR-0006) — the repository has
 * only `append` + `getById` + `getBySubject` + `count`
 * (no `update` + no `delete`).
 */
interface ProvenanceRecordRepository : AppendOnlyRepository<ProvenanceRecord, com.elysium.vanguard.foundry.core.ontology.ids.ProvenanceRecordId> {
    suspend fun getBySubject(subjectId: String): List<ProvenanceRecord>
}
