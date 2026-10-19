package com.syeyoung.assistant

import com.syeyoung.assistant.api.models.DueDto
import com.syeyoung.assistant.api.models.EventOccurrence
import com.syeyoung.assistant.api.models.TaskResponse
import com.syeyoung.assistant.data.allDayOn
import com.syeyoung.assistant.data.calItems
import com.syeyoung.assistant.data.dayLayout
import com.syeyoung.assistant.data.monthGrid
import com.syeyoung.assistant.data.rangeStart
import com.syeyoung.assistant.data.visibleDays
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

class CalendarTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val day = LocalDate.parse("2026-10-20")

    private fun timed(id: String, start: String, end: String) = EventOccurrence(
        calendarId = "c", calendarName = "C", categories = emptyList(), fixed = false, id = id, repeats = false, title = id,
        start = "2026-10-20T$start:00+09:00", end = "2026-10-20T$end:00+09:00",
    )

    private fun task(due: DueDto) = TaskResponse(
        attachments = emptyList(), blocked = false, createdAt = "", description = "", id = UUID.randomUUID(), labelIds = emptyList(), number = 1,
        overdue = false, priority = TaskResponse.Priority.none, relations = emptyList(), status = TaskResponse.Status.todo, subtasks = 0, subtasksDone = 0,
        title = "HW", updatedAt = "", version = 1, due = due,
    )

    @Test fun overlappingEventsSitSideBySide() {
        val items = calItems(listOf(timed("a", "10:00", "12:00"), timed("b", "11:00", "13:00"), timed("c", "12:00", "13:00"), timed("d", "14:00", "15:00")), emptyList(), emptyMap(), seoul)
        val placed = dayLayout(items, day).associate { it.item.title to (it.col to it.cols) }
        assertEquals(mapOf("a" to (0 to 2), "b" to (1 to 2), "c" to (0 to 2), "d" to (0 to 1)), placed)
    }

    @Test fun deadlinesAreMarkersOrAllDay() {
        val items = calItems(emptyList(), listOf(task(DueDto("2026-10-20", "23:59:00")), task(DueDto("2026-10-20", "00:00:00")), task(DueDto("2026-10-20"))), emptyMap(), seoul)
        val late = dayLayout(items, day).single()
        assertEquals(23 * 60 + 30, late.startMin)
        assertEquals(24 * 60, late.endMin)
        assertEquals(2, allDayOn(items, day).size)
    }

    @Test fun multiDayAllDayEventsShowOnEachDay() {
        val e = EventOccurrence(calendarId = "c", calendarName = "C", categories = emptyList(), fixed = false, id = "trip", repeats = false, title = "Trip", startDate = "2026-10-19", endDate = "2026-10-21")
        val items = calItems(listOf(e), emptyList(), emptyMap(), seoul)
        assertEquals(listOf(true, true, true, false), (19..22).map { allDayOn(items, LocalDate.of(2026, 10, it)).isNotEmpty() })
    }

    @Test fun weekFallsBackOnNarrowScreens() {
        assertEquals(listOf(1, 3, 7), listOf(280f, 411f, 800f).map(::visibleDays))
        assertEquals(LocalDate.parse("2026-10-18"), rangeStart(day, 7))
        assertEquals(day, rangeStart(day, 3))
    }

    @Test fun monthGridStartsOnSunday() {
        val grid = monthGrid(YearMonth.of(2026, 10))
        assertEquals(LocalDate.parse("2026-09-27"), grid.first())
        assertEquals(42, grid.size)
    }
}
