package com.elysium.vanguard.core.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * PHASE 137 — a calendar event stored in the Elysium calendar.
 *
 * Each event is anchored to a specific calendar day (year + month + day
 * in `java.util.Calendar` convention: month is 0..11). The optional
 * `hour` / `minute` fields are -1 for all-day events. This makes the
 * month-aggregation query trivial — we just filter by (year, month) and
 * group by day in code.
 *
 * The `note` field is a free-form multi-line description. The `colorHex`
 * is a UI hint (e.g. "#FFB86C" for personal, "#50FA7B" for work) — it
 * defaults to null and the UI falls back to the theme primary.
 */
@Entity(tableName = "calendar_events")
data class CalendarEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val year: Int,
    val month: Int,
    val day: Int,
    @ColumnInfo(name = "hour") val hour: Int = -1,
    @ColumnInfo(name = "minute") val minute: Int = -1,
    val title: String,
    val note: String = "",
    @ColumnInfo(name = "color_hex") val colorHex: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Dao
interface CalendarEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: CalendarEventEntity): Long

    @Update
    suspend fun update(event: CalendarEventEntity)

    @Query("DELETE FROM calendar_events WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM calendar_events ORDER BY year, month, day, hour, minute")
    fun observeAll(): Flow<List<CalendarEventEntity>>

    @Query("SELECT * FROM calendar_events WHERE year = :year AND month = :month ORDER BY day, hour, minute")
    fun observeForMonth(year: Int, month: Int): Flow<List<CalendarEventEntity>>

    @Query("SELECT * FROM calendar_events WHERE year = :year AND month = :month AND day = :day ORDER BY hour, minute, id")
    fun observeForDay(year: Int, month: Int, day: Int): Flow<List<CalendarEventEntity>>

    @Query("SELECT * FROM calendar_events WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): CalendarEventEntity?
}
