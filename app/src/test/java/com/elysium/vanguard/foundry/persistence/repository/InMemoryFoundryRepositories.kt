package com.elysium.vanguard.foundry.persistence.repository

import com.elysium.vanguard.foundry.core.artifact.EngineeringArtifact
import com.elysium.vanguard.foundry.core.concurrency.OptimisticConcurrency
import com.elysium.vanguard.foundry.core.contributor.Contributor
import com.elysium.vanguard.foundry.core.ontology.ids.ContributorId
import com.elysium.vanguard.foundry.core.ontology.ids.EngineeringArtifactId
import com.elysium.vanguard.foundry.core.ontology.ids.ProjectId
import com.elysium.vanguard.foundry.core.ontology.ids.ProvenanceRecordId
import com.elysium.vanguard.foundry.core.ontology.ids.UserId
import com.elysium.vanguard.foundry.core.ontology.ids.VehicleProgramId
import com.elysium.vanguard.foundry.core.ontology.ids.VehicleRevisionId
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import com.elysium.vanguard.foundry.core.program.VehicleProgram
import com.elysium.vanguard.foundry.core.project.Project
import com.elysium.vanguard.foundry.core.provenance.ProvenanceRecord
import com.elysium.vanguard.foundry.core.revision.VehicleRevision
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory implementations of the 6 Foundry repositories.
 * The in-memory implementations live in `src/test/` because
 * they are test infrastructure; production code must use
 * the Room-backed implementations (per
 * `FoundryPersistenceModule`).
 *
 * The in-memory implementations are **contract-equivalent**
 * to the Room implementations: every operation has the
 * same signature, the same `Result<>` shape, and the same
 * optimistic-concurrency semantics. The integration test
 * suite (JVM-side) uses the in-memory impls; the
 * instrumented test suite (Android-side) uses the Room
 * impls.
 *
 * The `observeAll()` Flow is backed by a `MutableStateFlow`
 * that is updated on every mutation. Subscribers receive
 * the current snapshot + every future change.
 */

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE", "UNUSED_VARIABLE")
class InMemoryProjectRepository : ProjectRepository {

    private val store = ConcurrentHashMap<String, Project>()
    private val flow = MutableStateFlow<List<Project>>(emptyList())

    override suspend fun insert(project: Project): Result<Unit> {
        synchronized(this) {
        if (store.containsKey(project.id.value.toString())) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "Project.id",
                    reason = "project ${project.id.value} already exists",
                ),
            )
        }
        store[project.id.value.toString()] = project
        flow.value = store.values.toList()
        return Result.success(Unit)
        }
    }

    override suspend fun update(project: Project, expectedVersion: Long): Result<Project> {
        synchronized(this) {
        val current = store[project.id.value.toString()]
            ?: return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "Project.id",
                    reason = "project ${project.id.value} does not exist",
                ),
            )
        val conflict = OptimisticConcurrency.check(
            aggregateType = "Project",
            aggregateId = project.id.value.toString(),
            expectedVersion = expectedVersion,
            actualVersion = current.version,
        )
        if (conflict != null) {
            return Result.failure(conflict)
        }
        store[project.id.value.toString()] = project
        flow.value = store.values.toList()
        return Result.success(project)
        }
    }

    override suspend fun deleteById(id: ProjectId): Result<Unit> {
        synchronized(this) {
        val removed = store.remove(id.value.toString())
            ?: return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "Project.id",
                    reason = "project ${id.value} does not exist",
                ),
            )
        flow.value = store.values.toList()
        return Result.success(Unit)
        }
    }

    override suspend fun getById(id: ProjectId): Project? = store[id.value.toString()]

    override fun observeAll(): Flow<List<Project>> = flow.asStateFlow()

    override suspend fun count(): Int = store.size

    override suspend fun getByOwner(ownerId: UserId): List<Project> =
        store.values.filter { it.ownerId == ownerId }
}

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE", "UNUSED_VARIABLE")
class InMemoryVehicleProgramRepository : VehicleProgramRepository {

    private val store = ConcurrentHashMap<String, VehicleProgram>()
    private val flow = MutableStateFlow<List<VehicleProgram>>(emptyList())

    override suspend fun insert(program: VehicleProgram): Result<Unit> {
        synchronized(this) {
        if (store.containsKey(program.id.value.toString())) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "VehicleProgram.id",
                    reason = "program ${program.id.value} already exists",
                ),
            )
        }
        store[program.id.value.toString()] = program
        flow.value = store.values.toList()
        return Result.success(Unit)
        }
    }

    override suspend fun update(program: VehicleProgram, expectedVersion: Long): Result<VehicleProgram> {
        synchronized(this) {
        val current = store[program.id.value.toString()]
            ?: return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "VehicleProgram.id",
                    reason = "program ${program.id.value} does not exist",
                ),
            )
        val conflict = OptimisticConcurrency.check(
            aggregateType = "VehicleProgram",
            aggregateId = program.id.value.toString(),
            expectedVersion = expectedVersion,
            actualVersion = current.version,
        )
        if (conflict != null) {
            return Result.failure(conflict)
        }
        store[program.id.value.toString()] = program
        flow.value = store.values.toList()
        return Result.success(program)
        }
    }

    override suspend fun deleteById(id: VehicleProgramId): Result<Unit> {
        synchronized(this) {
        val removed = store.remove(id.value.toString())
            ?: return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "VehicleProgram.id",
                    reason = "program ${id.value} does not exist",
                ),
            )
        flow.value = store.values.toList()
        return Result.success(Unit)
        }
    }

    override suspend fun getById(id: VehicleProgramId): VehicleProgram? = store[id.value.toString()]

    override fun observeAll(): Flow<List<VehicleProgram>> = flow.asStateFlow()

    override suspend fun count(): Int = store.size

    override suspend fun getByProject(projectId: ProjectId): List<VehicleProgram> =
        store.values.filter { it.projectId == projectId }
}

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE", "UNUSED_VARIABLE")
class InMemoryContributorRepository : ContributorRepository {

    private val store = ConcurrentHashMap<String, Contributor>()
    private val flow = MutableStateFlow<List<Contributor>>(emptyList())

    override suspend fun insert(contributor: Contributor): Result<Unit> {
        synchronized(this) {
        if (store.containsKey(contributor.id.value.toString())) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "Contributor.id",
                    reason = "contributor ${contributor.id.value} already exists",
                ),
            )
        }
        store[contributor.id.value.toString()] = contributor
        flow.value = store.values.toList()
        return Result.success(Unit)
        }
    }

    override suspend fun update(contributor: Contributor, expectedVersion: Long): Result<Contributor> {
        synchronized(this) {
        val current = store[contributor.id.value.toString()]
            ?: return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "Contributor.id",
                    reason = "contributor ${contributor.id.value} does not exist",
                ),
            )
        val conflict = OptimisticConcurrency.check(
            aggregateType = "Contributor",
            aggregateId = contributor.id.value.toString(),
            expectedVersion = expectedVersion,
            actualVersion = current.version,
        )
        if (conflict != null) {
            return Result.failure(conflict)
        }
        store[contributor.id.value.toString()] = contributor
        flow.value = store.values.toList()
        return Result.success(contributor)
        }
    }

    override suspend fun deleteById(id: ContributorId): Result<Unit> {
        synchronized(this) {
        val removed = store.remove(id.value.toString())
            ?: return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "Contributor.id",
                    reason = "contributor ${id.value} does not exist",
                ),
            )
        flow.value = store.values.toList()
        return Result.success(Unit)
        }
    }

    override suspend fun getById(id: ContributorId): Contributor? = store[id.value.toString()]

    override fun observeAll(): Flow<List<Contributor>> = flow.asStateFlow()

    override suspend fun count(): Int = store.size

    override suspend fun getByEmail(email: String): Contributor? =
        store.values.firstOrNull { it.email == email }
}

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE", "UNUSED_VARIABLE")
class InMemoryEngineeringArtifactRepository : EngineeringArtifactRepository {

    private val store = ConcurrentHashMap<String, EngineeringArtifact>()
    private val flow = MutableStateFlow<List<EngineeringArtifact>>(emptyList())

    override suspend fun insert(artifact: EngineeringArtifact): Result<Unit> {
        synchronized(this) {
        if (store.containsKey(artifact.id.value.toString())) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "EngineeringArtifact.id",
                    reason = "artifact ${artifact.id.value} already exists",
                ),
            )
        }
        store[artifact.id.value.toString()] = artifact
        flow.value = store.values.toList()
        return Result.success(Unit)
        }
    }

    override suspend fun update(artifact: EngineeringArtifact, expectedVersion: Long): Result<EngineeringArtifact> {
        synchronized(this) {
        val current = store[artifact.id.value.toString()]
            ?: return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "EngineeringArtifact.id",
                    reason = "artifact ${artifact.id.value} does not exist",
                ),
            )
        val conflict = OptimisticConcurrency.check(
            aggregateType = "EngineeringArtifact",
            aggregateId = artifact.id.value.toString(),
            expectedVersion = expectedVersion,
            actualVersion = current.version,
        )
        if (conflict != null) {
            return Result.failure(conflict)
        }
        store[artifact.id.value.toString()] = artifact
        flow.value = store.values.toList()
        return Result.success(artifact)
        }
    }

    override suspend fun deleteById(id: EngineeringArtifactId): Result<Unit> {
        synchronized(this) {
        val removed = store.remove(id.value.toString())
            ?: return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "EngineeringArtifact.id",
                    reason = "artifact ${id.value} does not exist",
                ),
            )
        flow.value = store.values.toList()
        return Result.success(Unit)
        }
    }

    override suspend fun getById(id: EngineeringArtifactId): EngineeringArtifact? =
        store[id.value.toString()]

    override fun observeAll(): Flow<List<EngineeringArtifact>> = flow.asStateFlow()

    override suspend fun count(): Int = store.size

    override suspend fun getByContentHash(hash: String): EngineeringArtifact? =
        store.values.firstOrNull { it.contentHash.value == hash }

    override suspend fun getBySubject(subjectId: String): List<EngineeringArtifact> =
        store.values.filter { it.subjectId == subjectId }
}

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE", "UNUSED_VARIABLE")
class InMemoryVehicleRevisionRepository : VehicleRevisionRepository {

    private val store = ConcurrentHashMap<String, VehicleRevision>()
    private val flow = MutableStateFlow<List<VehicleRevision>>(emptyList())

    override suspend fun append(revision: VehicleRevision): Result<Unit> {
        synchronized(this) {
        if (store.containsKey(revision.id.value.toString())) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "VehicleRevision.id",
                    reason = "revision ${revision.id.value} already exists",
                ),
            )
        }
        store[revision.id.value.toString()] = revision
        flow.value = store.values.toList()
        return Result.success(Unit)
        }
    }

    override suspend fun getById(id: VehicleRevisionId): VehicleRevision? =
        store[id.value.toString()]

    override fun observeAll(): Flow<List<VehicleRevision>> = flow.asStateFlow()

    override suspend fun count(): Int = store.size

    override suspend fun getByProject(projectId: ProjectId): List<VehicleRevision> =
        store.values.filter { it.projectId == projectId }
            .sortedBy { it.createdAt.epochMs }

    override suspend fun getByContentHash(hash: String): VehicleRevision? =
        store.values.firstOrNull { it.contentHash.value == hash }
}

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE", "UNUSED_VARIABLE")
class InMemoryProvenanceRecordRepository : ProvenanceRecordRepository {

    private val store = ConcurrentHashMap<String, ProvenanceRecord>()
    private val flow = MutableStateFlow<List<ProvenanceRecord>>(emptyList())

    override suspend fun append(record: ProvenanceRecord): Result<Unit> {
        synchronized(this) {
        if (store.containsKey(record.id.value.toString())) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "ProvenanceRecord.id",
                    reason = "provenance record ${record.id.value} already exists",
                ),
            )
        }
        store[record.id.value.toString()] = record
        flow.value = store.values.toList()
        return Result.success(Unit)
        }
    }

    override suspend fun getById(id: ProvenanceRecordId): ProvenanceRecord? =
        store[id.value.toString()]

    override fun observeAll(): Flow<List<ProvenanceRecord>> = flow.asStateFlow()

    override suspend fun count(): Int = store.size

    override suspend fun getBySubject(subjectId: String): List<ProvenanceRecord> =
        store.values.filter { it.subjectId == subjectId }
            .sortedBy { it.createdAt.epochMs }
}
