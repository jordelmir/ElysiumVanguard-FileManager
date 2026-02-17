package com.elysium.vanguard.core.database

import androidx.room.*
import androidx.room.RoomWarnings

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

@Database(entities = [FileSearchEntity::class], version = 1, exportSchema = false)
abstract class TitanDatabase : RoomDatabase() {
    abstract fun fileSearchDao(): FileSearchDao
}
