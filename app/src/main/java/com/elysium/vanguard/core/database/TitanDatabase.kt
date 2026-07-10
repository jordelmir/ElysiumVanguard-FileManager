package com.elysium.vanguard.core.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomWarnings
import androidx.room.RoomDatabase
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Update
import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.flow.Flow

/**
 * TITAN SEARCH ENTITY
 * FTS5-backed entity for lightning-fast, offline semantic search.
 */
@Entity(tableName = "file_search_index")
@Fts4
data class FileSearchEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowid: Long = 0,
    val fileName: String,
    val filePath: String,
    val contentSnippet: String?,
    val fileType: String,
    val lastModified: Long
)

@Dao
interface FileSearchDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(file: FileSearchEntity)

    @SuppressWarnings(RoomWarnings.CURSOR_MISMATCH)
    @Query("""
        SELECT rowid, * FROM file_search_index
        WHERE file_search_index MATCH :query
    """)
    suspend fun searchFiles(query: String): List<FileSearchEntity>

    @Query("DELETE FROM file_search_index")
    suspend fun clearIndex()
}

/**
 * PHASE 1.1 — Trash entity.
 */
@Entity(tableName = "trash_items")
data class TrashEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "original_name") val originalName: String,
    @ColumnInfo(name = "trash_uri") val trashUri: String,
    @ColumnInfo(name = "original_parent_uri") val originalParentUri: String,
    @ColumnInfo(name = "original_relative_path") val originalRelativePath: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long,
    @ColumnInfo(name = "mime_type") val mimeType: String? = null
)

@Dao
interface TrashDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: TrashEntity): Long

    @Query("SELECT * FROM trash_items ORDER BY deleted_at DESC")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<TrashEntity>>

    @Query("SELECT * FROM trash_items ORDER BY deleted_at DESC")
    suspend fun listAll(): List<TrashEntity>

    @Query("SELECT * FROM trash_items WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): TrashEntity?

    @Query("SELECT * FROM trash_items WHERE deleted_at < :thresholdMs")
    suspend fun listOlderThan(thresholdMs: Long): List<TrashEntity>

    @Query("DELETE FROM trash_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM trash_items")
    suspend fun clear()

    @Query("SELECT SUM(size_bytes) FROM trash_items")
    suspend fun totalTrashedBytes(): Long?

    @Query("SELECT COUNT(*) FROM trash_items")
    suspend fun count(): Int
}

/**
 * PHASE 2.1 — Vault metadata.
 *
 * Stores the row-level metadata for each encrypted file in the vault. The encrypted
 * payload itself lives on disk at [vaultPath]; the row only holds enough info to render
 * the locked vault list (size, name, timestamp) without ever loading keys.
 */
@Entity(tableName = "vault_items")
data class VaultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "vault_path") val vaultPath: String,
    @ColumnInfo(name = "original_name") val originalName: String,
    @ColumnInfo(name = "original_mime") val originalMime: String?,
    @ColumnInfo(name = "original_size") val originalSize: Long,
    @ColumnInfo(name = "vault_size") val vaultSize: Long,
    @ColumnInfo(name = "encrypted_at") val encryptedAt: Long,
    @ColumnInfo(name = "last_accessed_at") val lastAccessedAt: Long? = null
)

@Dao
interface VaultDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: VaultEntity): Long

    @Update
    suspend fun update(item: VaultEntity)

    @Query("SELECT * FROM vault_items ORDER BY encrypted_at DESC")
    fun observeAll(): Flow<List<VaultEntity>>

    @Query("SELECT * FROM vault_items ORDER BY encrypted_at DESC")
    suspend fun listAll(): List<VaultEntity>

    @Query("SELECT * FROM vault_items WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): VaultEntity?

    @Query("SELECT * FROM vault_items WHERE original_name LIKE '%' || :query || '%' ORDER BY encrypted_at DESC")
    suspend fun searchByName(query: String): List<VaultEntity>

    @Query("DELETE FROM vault_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM vault_items")
    suspend fun clear()

    @Query("SELECT SUM(vault_size) FROM vault_items")
    suspend fun totalVaultBytes(): Long?

    @Query("SELECT COUNT(*) FROM vault_items")
    suspend fun count(): Int
}

/**
 * PHASE 2.12 — Per-file metadata: tags + color + note.
 *
 * Stored independently of the file itself so metadata survives file moves (the URI
 * is just a stable key — caller normalizes to canonical path before lookup). Tags are
 * persisted as a comma-separated string to avoid pulling in a JSON library; this is
 * fine because tags can't contain commas by convention (we sanitize on input).
 */
@Entity(tableName = "file_metadata")
data class FileMetadataEntity(
    @PrimaryKey val uri: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "color_hex") val colorHex: String? = null,
    @ColumnInfo(name = "note") val note: String? = null,
    @ColumnInfo(name = "tags") val tags: String = "",
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Dao
interface FileMetadataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FileMetadataEntity)

    @Query("SELECT * FROM file_metadata WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): FileMetadataEntity?

    @Query("SELECT * FROM file_metadata ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<FileMetadataEntity>>

    @Query("SELECT * FROM file_metadata ORDER BY updated_at DESC")
    suspend fun listAll(): List<FileMetadataEntity>

    @Query("SELECT * FROM file_metadata WHERE tags LIKE '%' || :tag || '%' ORDER BY updated_at DESC")
    suspend fun findByTag(tag: String): List<FileMetadataEntity>

    @Query("DELETE FROM file_metadata WHERE uri = :uri")
    suspend fun delete(uri: String)
}

/**
 * PHASE 2.13 — Saved search ("smart folder").
 *
 * Persists a [FileFilterParser] query string plus the root path the user wants to
 * scan. At open time the repository re-evaluates the query against a fresh directory
 * listing — there's no cached result set because the filesystem is the source of
 * truth and we'd rather be slightly slower than slightly wrong.
 */
@Entity(tableName = "smart_folders")
data class SmartFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val query: String,
    @ColumnInfo(name = "root_path") val rootPath: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_evaluated_at") val lastEvaluatedAt: Long? = null
)

@Dao
interface SmartFolderDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(folder: SmartFolderEntity): Long

    @Update
    suspend fun update(folder: SmartFolderEntity)

    @Query("SELECT * FROM smart_folders ORDER BY created_at DESC")
    fun observeAll(): Flow<List<SmartFolderEntity>>

    @Query("SELECT * FROM smart_folders ORDER BY created_at DESC")
    suspend fun listAll(): List<SmartFolderEntity>

    @Query("SELECT * FROM smart_folders WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SmartFolderEntity?

    @Query("DELETE FROM smart_folders WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Database(
    entities = [
        FileSearchEntity::class,
        TrashEntity::class,
        VaultEntity::class,
        FileMetadataEntity::class,
        SmartFolderEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class TitanDatabase : RoomDatabase() {
    abstract fun fileSearchDao(): FileSearchDao
    abstract fun trashDao(): TrashDao
    abstract fun vaultDao(): VaultDao
    abstract fun fileMetadataDao(): FileMetadataDao
    abstract fun smartFolderDao(): SmartFolderDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS trash_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        original_name TEXT NOT NULL,
                        trash_uri TEXT NOT NULL,
                        original_parent_uri TEXT NOT NULL,
                        original_relative_path TEXT NOT NULL,
                        size_bytes INTEGER NOT NULL,
                        deleted_at INTEGER NOT NULL,
                        mime_type TEXT
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_trash_items_deleted_at ON trash_items(deleted_at)")
            }
        }

        /**
         * PHASE 2.1 — Adds the vault_items table for the encrypted vault.
         * No data migration needed; new feature on existing schema.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS vault_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        vault_path TEXT NOT NULL,
                        original_name TEXT NOT NULL,
                        original_mime TEXT,
                        original_size INTEGER NOT NULL,
                        vault_size INTEGER NOT NULL,
                        encrypted_at INTEGER NOT NULL,
                        last_accessed_at INTEGER
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_vault_items_encrypted_at ON vault_items(encrypted_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_vault_items_original_name ON vault_items(original_name)")
            }
        }

        /**
         * PHASE 2.12 — Adds the file_metadata table for tags/colors/notes.
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS file_metadata (
                        uri TEXT NOT NULL PRIMARY KEY,
                        display_name TEXT NOT NULL,
                        color_hex TEXT,
                        note TEXT,
                        tags TEXT NOT NULL DEFAULT '',
                        updated_at INTEGER NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_file_metadata_updated_at ON file_metadata(updated_at)")
            }
        }

        /**
         * PHASE 2.13 — Adds the smart_folders table for saved searches.
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS smart_folders (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        query TEXT NOT NULL,
                        root_path TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        last_evaluated_at INTEGER
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_smart_folders_created_at ON smart_folders(created_at)")
            }
        }
    }
}