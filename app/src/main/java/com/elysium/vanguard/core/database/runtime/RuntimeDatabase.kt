package com.elysium.vanguard.core.database.runtime

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "distro_installs")
data class DistroInstallEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "rootfs_path") val rootfsPath: String,
    @ColumnInfo(name = "installed_at_ms") val installedAtMs: Long,
    @ColumnInfo(name = "last_launched_at_ms") val lastLaunchedAtMs: Long = 0L,
    @ColumnInfo(name = "total_launches") val totalLaunches: Int = 0,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "distro_family") val distroFamily: String,
    @ColumnInfo(name = "package_manager") val packageManager: String,
    @ColumnInfo(name = "desktop_profile_id") val desktopProfileId: String = "tty",
    @ColumnInfo(name = "integrity_verified") val integrityVerified: Boolean = false,
    @ColumnInfo(name = "manifest_hash") val manifestHash: String = ""
)

@Dao
interface DistroInstallDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DistroInstallEntity)

    @Update
    suspend fun update(entity: DistroInstallEntity)

    @Query("SELECT * FROM distro_installs ORDER BY last_launched_at_ms DESC")
    fun observeAll(): Flow<List<DistroInstallEntity>>

    @Query("SELECT * FROM distro_installs ORDER BY last_launched_at_ms DESC")
    suspend fun listAll(): List<DistroInstallEntity>

    @Query("SELECT * FROM distro_installs WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DistroInstallEntity?

    @Query("SELECT * FROM distro_installs WHERE distro_family = :family ORDER BY display_name ASC")
    suspend fun listByFamily(family: String): List<DistroInstallEntity>

    @Query("SELECT COUNT(*) FROM distro_installs")
    suspend fun count(): Int

    @Query("SELECT SUM(size_bytes) FROM distro_installs")
    suspend fun totalBytes(): Long?

    @Query("DELETE FROM distro_installs WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM distro_installs")
    suspend fun clear()
}

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_uuid") val sessionUuid: String,
    @ColumnInfo(name = "distro_id") val distroId: String,
    @ColumnInfo(name = "backend_type") val backendType: String,
    @ColumnInfo(name = "state") val state: String = "created",
    @ColumnInfo(name = "started_at_ms") val startedAtMs: Long = 0L,
    @ColumnInfo(name = "stopped_at_ms") val stoppedAtMs: Long = 0L,
    @ColumnInfo(name = "exit_code") val exitCode: Int? = null,
    @ColumnInfo(name = "display_width") val displayWidth: Int = 1280,
    @ColumnInfo(name = "display_height") val displayHeight: Int = 720,
    @ColumnInfo(name = "vnc_port") val vncPort: Int? = null,
    @ColumnInfo(name = "is_fullscreen") val isFullscreen: Boolean = false
)

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SessionEntity)

    @Update
    suspend fun update(entity: SessionEntity)

    @Query("SELECT * FROM sessions ORDER BY started_at_ms DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY started_at_ms DESC")
    suspend fun listAll(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SessionEntity?

    @Query("SELECT * FROM sessions WHERE session_uuid = :uuid LIMIT 1")
    suspend fun getByUuid(uuid: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE distro_id = :distroId ORDER BY started_at_ms DESC")
    fun observeByDistro(distroId: String): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE state = :state ORDER BY started_at_ms DESC")
    suspend fun listByState(state: String): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE state = 'running' OR state = 'starting'")
    fun observeActive(): Flow<List<SessionEntity>>

    @Query("SELECT COUNT(*) FROM sessions")
    suspend fun count(): Int

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM sessions")
    suspend fun clear()
}

@Entity(tableName = "application_capsules")
data class ApplicationCapsuleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val version: String,
    @ColumnInfo(name = "capsule_type") val capsuleType: String,
    val path: String,
    val executable: String,
    @ColumnInfo(name = "integrity_hash") val integrityHash: String = "",
    @ColumnInfo(name = "installed_at_ms") val installedAtMs: Long,
    @ColumnInfo(name = "launch_count") val launchCount: Int = 0,
    @ColumnInfo(name = "last_launched_at_ms") val lastLaunchedAtMs: Long = 0L,
    @ColumnInfo(name = "metadata_json") val metadataJson: String? = null
)

@Dao
interface ApplicationCapsuleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ApplicationCapsuleEntity)

    @Update
    suspend fun update(entity: ApplicationCapsuleEntity)

    @Query("SELECT * FROM application_capsules ORDER BY name ASC")
    fun observeAll(): Flow<List<ApplicationCapsuleEntity>>

    @Query("SELECT * FROM application_capsules ORDER BY name ASC")
    suspend fun listAll(): List<ApplicationCapsuleEntity>

    @Query("SELECT * FROM application_capsules WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ApplicationCapsuleEntity?

    @Query("SELECT * FROM application_capsules WHERE capsule_type = :type ORDER BY name ASC")
    suspend fun listByType(type: String): List<ApplicationCapsuleEntity>

    @Query("SELECT * FROM application_capsules WHERE name LIKE '%' || :query || '%' ORDER BY name ASC")
    suspend fun search(query: String): List<ApplicationCapsuleEntity>

    @Query("SELECT COUNT(*) FROM application_capsules")
    suspend fun count(): Int

    @Query("DELETE FROM application_capsules WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM application_capsules")
    suspend fun clear()
}

@Entity(tableName = "hardware_access_audit")
data class HardwareAccessAuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val resource: String,
    @ColumnInfo(name = "access_level") val accessLevel: String,
    val granted: Boolean,
    @ColumnInfo(name = "timestamp_ms") val timestampMs: Long,
    val reason: String? = null
)

@Dao
interface HardwareAccessAuditDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: HardwareAccessAuditEntity): Long

    @Query("SELECT * FROM hardware_access_audit ORDER BY timestamp_ms DESC")
    fun observeAll(): Flow<List<HardwareAccessAuditEntity>>

    @Query("SELECT * FROM hardware_access_audit ORDER BY timestamp_ms DESC")
    suspend fun listAll(): List<HardwareAccessAuditEntity>

    @Query("SELECT * FROM hardware_access_audit WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): HardwareAccessAuditEntity?

    @Query("SELECT * FROM hardware_access_audit WHERE session_id = :sessionId ORDER BY timestamp_ms DESC")
    suspend fun listBySession(sessionId: String): List<HardwareAccessAuditEntity>

    @Query("SELECT * FROM hardware_access_audit WHERE session_id = :sessionId AND granted = 1 ORDER BY timestamp_ms DESC")
    fun observeGrantsBySession(sessionId: String): Flow<List<HardwareAccessAuditEntity>>

    @Query("SELECT * FROM hardware_access_audit WHERE resource = :resource ORDER BY timestamp_ms DESC")
    suspend fun listByResource(resource: String): List<HardwareAccessAuditEntity>

    @Query("SELECT COUNT(*) FROM hardware_access_audit WHERE granted = 1")
    suspend fun countGrants(): Int

    @Query("SELECT COUNT(*) FROM hardware_access_audit WHERE granted = 0")
    suspend fun countDenials(): Int

    @Query("DELETE FROM hardware_access_audit WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM hardware_access_audit")
    suspend fun clear()
}

@Entity(tableName = "diagnostic_events")
data class DiagnosticEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val severity: String,
    val code: String? = null,
    val message: String,
    @ColumnInfo(name = "data_json") val dataJson: String? = null,
    @ColumnInfo(name = "timestamp_ms") val timestampMs: Long
)

@Dao
interface DiagnosticEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: DiagnosticEventEntity): Long

    @Query("SELECT * FROM diagnostic_events ORDER BY timestamp_ms DESC")
    fun observeAll(): Flow<List<DiagnosticEventEntity>>

    @Query("SELECT * FROM diagnostic_events ORDER BY timestamp_ms DESC")
    suspend fun listAll(): List<DiagnosticEventEntity>

    @Query("SELECT * FROM diagnostic_events WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): DiagnosticEventEntity?

    @Query("SELECT * FROM diagnostic_events WHERE session_id = :sessionId ORDER BY timestamp_ms DESC")
    suspend fun listBySession(sessionId: String): List<DiagnosticEventEntity>

    @Query("SELECT * FROM diagnostic_events WHERE severity = :severity ORDER BY timestamp_ms DESC")
    suspend fun listBySeverity(severity: String): List<DiagnosticEventEntity>

    @Query("SELECT * FROM diagnostic_events WHERE session_id = :sessionId AND severity IN ('error', 'fatal') ORDER BY timestamp_ms DESC")
    suspend fun listErrorsBySession(sessionId: String): List<DiagnosticEventEntity>

    @Query("SELECT * FROM diagnostic_events WHERE timestamp_ms > :sinceMs ORDER BY timestamp_ms DESC")
    suspend fun listSince(sinceMs: Long): List<DiagnosticEventEntity>

    @Query("SELECT COUNT(*) FROM diagnostic_events")
    suspend fun count(): Int

    @Query("DELETE FROM diagnostic_events WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM diagnostic_events")
    suspend fun clear()
}

@Entity(tableName = "workspaces")
data class WorkspaceEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "config_json") val configJson: String,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "last_launched_at_ms") val lastLaunchedAtMs: Long = 0L,
    @ColumnInfo(name = "launch_count") val launchCount: Int = 0
)

@Dao
interface WorkspaceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WorkspaceEntity)

    @Update
    suspend fun update(entity: WorkspaceEntity)

    @Query("SELECT * FROM workspaces ORDER BY last_launched_at_ms DESC")
    fun observeAll(): Flow<List<WorkspaceEntity>>

    @Query("SELECT * FROM workspaces ORDER BY last_launched_at_ms DESC")
    suspend fun listAll(): List<WorkspaceEntity>

    @Query("SELECT * FROM workspaces WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): WorkspaceEntity?

    @Query("SELECT * FROM workspaces WHERE name LIKE '%' || :query || '%' ORDER BY name ASC")
    suspend fun search(query: String): List<WorkspaceEntity>

    @Query("SELECT COUNT(*) FROM workspaces")
    suspend fun count(): Int

    @Query("DELETE FROM workspaces WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM workspaces")
    suspend fun clear()
}

@Entity(tableName = "network_rules")
data class NetworkRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val policy: String,
    @ColumnInfo(name = "allow_ports") val allowPorts: String? = null,
    @ColumnInfo(name = "deny_ports") val denyPorts: String? = null,
    @ColumnInfo(name = "rate_limit_kbps") val rateLimitKbps: Int? = null,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long
)

@Dao
interface NetworkRuleDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: NetworkRuleEntity): Long

    @Update
    suspend fun update(entity: NetworkRuleEntity)

    @Query("SELECT * FROM network_rules ORDER BY created_at_ms DESC")
    fun observeAll(): Flow<List<NetworkRuleEntity>>

    @Query("SELECT * FROM network_rules ORDER BY created_at_ms DESC")
    suspend fun listAll(): List<NetworkRuleEntity>

    @Query("SELECT * FROM network_rules WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): NetworkRuleEntity?

    @Query("SELECT * FROM network_rules WHERE session_id = :sessionId ORDER BY created_at_ms DESC")
    suspend fun listBySession(sessionId: String): List<NetworkRuleEntity>

    @Query("SELECT * FROM network_rules WHERE session_id = :sessionId AND policy = :policy LIMIT 1")
    suspend fun getBySessionAndPolicy(sessionId: String, policy: String): NetworkRuleEntity?

    @Query("SELECT * FROM network_rules WHERE policy = :policy ORDER BY created_at_ms DESC")
    suspend fun listByPolicy(policy: String): List<NetworkRuleEntity>

    @Query("SELECT COUNT(*) FROM network_rules")
    suspend fun count(): Int

    @Query("DELETE FROM network_rules WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM network_rules")
    suspend fun clear()
}

@Database(
    entities = [
        DistroInstallEntity::class,
        SessionEntity::class,
        ApplicationCapsuleEntity::class,
        HardwareAccessAuditEntity::class,
        DiagnosticEventEntity::class,
        WorkspaceEntity::class,
        NetworkRuleEntity::class,
        com.elysium.vanguard.core.database.media.MediaIndexEntity::class,
    ],
    version = 1,
    exportSchema = false
)
abstract class RuntimeDatabase : RoomDatabase() {
    abstract fun distroInstallDao(): DistroInstallDao
    abstract fun sessionDao(): SessionDao
    abstract fun applicationCapsuleDao(): ApplicationCapsuleDao
    abstract fun hardwareAccessAuditDao(): HardwareAccessAuditDao
    abstract fun diagnosticEventDao(): DiagnosticEventDao
    abstract fun workspaceDao(): WorkspaceDao
    abstract fun networkRuleDao(): NetworkRuleDao
    abstract fun mediaIndexDao(): com.elysium.vanguard.core.database.media.MediaIndexDao
}
