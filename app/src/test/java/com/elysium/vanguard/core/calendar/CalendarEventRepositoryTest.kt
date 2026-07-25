package com.elysium.vanguard.core.calendar

import com.elysium.vanguard.core.database.CalendarEventDao
import com.elysium.vanguard.core.database.CalendarEventEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PHASE 137 — CalendarEventRepository unit tests.
 *
 * Uses an in-memory [CalendarEventDao] stub (no Android Room / no
 * Robolectric) so the repository's business logic — timestamp
 * stamping, trim, delete — can be verified in a plain JVM test.
 *
 * What we cover:
 *  - add() stamps createdAt + updatedAt + assigns an id
 *  - add() trims the title and note
 *  - update() preserves createdAt, refreshes updatedAt
 *  - delete() removes the row
 *  - observeForMonth() returns only events in (year, month)
 *  - observeForDay() returns only events in (year, month, day)
 *  - add() with all-day event stores hour = -1, minute = -1
 */
class CalendarEventRepositoryTest {

    private lateinit var dao: InMemoryCalendarEventDao
    private lateinit var repo: CalendarEventRepository
    private var fakeNow: Long = 1_000_000L

    @Before fun setUp() {
        dao = InMemoryCalendarEventDao()
        repo = CalendarEventRepository(dao, now = { fakeNow })
    }

    @Test fun `add stamps createdAt and updatedAt and returns id`() = runBlocking {
        val entity = repo.add(year = 2026, month = 6, day = 24, title = "Dentist")
        assertEquals(1L, entity.id)
        assertEquals(1_000_000L, entity.createdAt)
        assertEquals(1_000_000L, entity.updatedAt)
        assertEquals("Dentist", entity.title)
    }

    @Test fun `add trims title and note whitespace`() = runBlocking {
        val entity = repo.add(
            year = 2026, month = 6, day = 24,
            title = "  Team standup  ",
            note = "  weekly  \n",
        )
        assertEquals("Team standup", entity.title)
        assertEquals("weekly", entity.note)
    }

    @Test fun `add with all-day event stores negative hour minute`() = runBlocking {
        val entity = repo.add(
            year = 2026, month = 6, day = 24,
            title = "Holiday", hour = -1, minute = -1,
        )
        assertEquals(-1, entity.hour)
        assertEquals(-1, entity.minute)
    }

    @Test fun `add with specific time stores hour minute as-is`() = runBlocking {
        val entity = repo.add(
            year = 2026, month = 6, day = 24,
            title = "Standup", hour = 9, minute = 30,
        )
        assertEquals(9, entity.hour)
        assertEquals(30, entity.minute)
    }

    @Test fun `update preserves createdAt and refreshes updatedAt`() = runBlocking {
        val original = repo.add(year = 2026, month = 6, day = 24, title = "Old title")
        fakeNow = 1_000_500L  // advance the clock
        repo.update(
            existing = original,
            title = "New title",
            note = "with detail",
            hour = 10,
            minute = 15,
            colorHex = "#FF0000",
        )
        val reloaded = repo.getById(original.id)!!
        assertEquals("New title", reloaded.title)
        assertEquals("with detail", reloaded.note)
        assertEquals(10, reloaded.hour)
        assertEquals(15, reloaded.minute)
        assertEquals("#FF0000", reloaded.colorHex)
        assertEquals(original.createdAt, reloaded.createdAt)
        assertEquals(1_000_500L, reloaded.updatedAt)
    }

    @Test fun `delete removes the row`() = runBlocking {
        val entity = repo.add(year = 2026, month = 6, day = 24, title = "Throwaway")
        assertNotNull(repo.getById(entity.id))
        repo.delete(entity)
        assertNull(repo.getById(entity.id))
    }

    @Test fun `deleteById works`() = runBlocking {
        val entity = repo.add(year = 2026, month = 6, day = 24, title = "Throwaway 2")
        repo.deleteById(entity.id)
        assertNull(repo.getById(entity.id))
    }

    @Test fun `observeForMonth returns only events in the given month`() = runBlocking {
        repo.add(year = 2026, month = 5, day = 15, title = "May event")
        repo.add(year = 2026, month = 6, day = 1, title = "June 1")
        repo.add(year = 2026, month = 6, day = 30, title = "June 30")
        repo.add(year = 2026, month = 7, day = 1, title = "July event")
        val june = repo.observeForMonth(2026, 6).first()
        assertEquals(2, june.size)
        assertTrue(june.all { it.month == 6 })
    }

    @Test fun `observeForDay returns only events for that exact day`() = runBlocking {
        repo.add(year = 2026, month = 6, day = 24, title = "Day 24 first", hour = 9)
        repo.add(year = 2026, month = 6, day = 24, title = "Day 24 second", hour = 14)
        repo.add(year = 2026, month = 6, day = 25, title = "Day 25")
        val day24 = repo.observeForDay(2026, 6, 24).first()
        assertEquals(2, day24.size)
        assertTrue(day24.all { it.day == 24 })
        // ordered by hour
        assertEquals(9, day24[0].hour)
        assertEquals(14, day24[1].hour)
    }

    @Test fun `ids are unique across many inserts`() = runBlocking {
        val ids = (1..20).map {
            repo.add(year = 2026, month = 6, day = 24, title = "Event $it").id
        }
        assertEquals(ids.size, ids.toSet().size)
    }
}

// --- Test doubles -------------------------------------------------------

/**
 * Minimal in-memory [CalendarEventDao] stub for unit tests. Mimics Room
 * enough to back the repository's behavior: insert assigns monotonically
 * increasing ids, update preserves the id, delete is a no-op on miss.
 */
internal class InMemoryCalendarEventDao : CalendarEventDao {
    private val rows = mutableListOf<CalendarEventEntity>()
    private val lock = Any()
    private var nextId = 1L

    private fun snapshot(): List<CalendarEventEntity> = synchronized(lock) { rows.toList() }

    override suspend fun insert(event: CalendarEventEntity): Long = synchronized(lock) {
        val withId = event.copy(id = nextId++)
        rows.add(withId)
        withId.id
    }

    override suspend fun update(event: CalendarEventEntity) = synchronized(lock) {
        val idx = rows.indexOfFirst { it.id == event.id }
        if (idx >= 0) rows[idx] = event
    }

    override suspend fun deleteById(id: Long): Unit = synchronized(lock) {
        rows.removeAll { it.id == id }
        Unit
    }

    override fun observeAll(): Flow<List<CalendarEventEntity>> =
        MutableStateFlow(snapshot()).map { snapshot() }

    override fun observeForMonth(year: Int, month: Int): Flow<List<CalendarEventEntity>> =
        MutableStateFlow(snapshot()).map {
            it.filter { e -> e.year == year && e.month == month }
                .sortedWith(compareBy({ it.day }, { it.hour }, { it.minute }, { it.id }))
        }

    override fun observeForDay(year: Int, month: Int, day: Int): Flow<List<CalendarEventEntity>> =
        MutableStateFlow(snapshot()).map {
            it.filter { e -> e.year == year && e.month == month && e.day == day }
                .sortedWith(compareBy({ it.hour }, { it.minute }, { it.id }))
        }

    override suspend fun getById(id: Long): CalendarEventEntity? = synchronized(lock) {
        rows.firstOrNull { it.id == id }
    }
}
