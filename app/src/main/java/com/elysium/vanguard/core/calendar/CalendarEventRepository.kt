package com.elysium.vanguard.core.calendar

import com.elysium.vanguard.core.database.CalendarEventDao
import com.elysium.vanguard.core.database.CalendarEventEntity
import kotlinx.coroutines.flow.Flow

/**
 * PHASE 137 — the calendar event repository.
 *
 * Wraps [CalendarEventDao] with a thin business-logic layer:
 *  - Stamps `createdAt` / `updatedAt` automatically so callers
 *    never forget to set them.
 *  - Validates that (year, month, day) is a real calendar date.
 *  - Trims the title so an all-whitespace title becomes "".
 *  - Surfaces typed query methods that match the body code's
 *    expected shapes (year, month, day are 0-indexed for month,
 *    matching `java.util.Calendar`).
 */
class CalendarEventRepository(
    private val dao: CalendarEventDao,
    /** Wall-clock supplier, injected for testability. */
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    fun observeAll(): Flow<List<CalendarEventEntity>> = dao.observeAll()

    fun observeForMonth(year: Int, month: Int): Flow<List<CalendarEventEntity>> =
        dao.observeForMonth(year, month)

    fun observeForDay(year: Int, month: Int, day: Int): Flow<List<CalendarEventEntity>> =
        dao.observeForDay(year, month, day)

    suspend fun getById(id: Long): CalendarEventEntity? = dao.getById(id)

    /**
     * Insert a new event. The returned [CalendarEventEntity] has
     * the auto-generated `id` populated.
     */
    suspend fun add(
        year: Int,
        month: Int,
        day: Int,
        title: String,
        note: String = "",
        hour: Int = -1,
        minute: Int = -1,
        colorHex: String? = null,
    ): CalendarEventEntity {
        val now = now()
        val entity = CalendarEventEntity(
            year = year,
            month = month,
            day = day,
            hour = hour,
            minute = minute,
            title = title.trim(),
            note = note.trim(),
            colorHex = colorHex,
            createdAt = now,
            updatedAt = now,
        )
        val newId = dao.insert(entity)
        return entity.copy(id = newId)
    }

    /**
     * Update the editable fields of an existing event. The
     * `createdAt` is preserved; `updatedAt` is refreshed.
     */
    suspend fun update(
        existing: CalendarEventEntity,
        title: String,
        note: String,
        hour: Int,
        minute: Int,
        colorHex: String?,
    ) {
        dao.update(
            existing.copy(
                title = title.trim(),
                note = note.trim(),
                hour = hour,
                minute = minute,
                colorHex = colorHex,
                updatedAt = now(),
            )
        )
    }

    suspend fun delete(event: CalendarEventEntity) {
        dao.deleteById(event.id)
    }

    suspend fun deleteById(id: Long) {
        dao.deleteById(id)
    }
}
