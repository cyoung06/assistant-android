package com.syeyoung.assistant

import com.syeyoung.assistant.api.models.EventResponse
import com.syeyoung.assistant.api.models.RepeatDto
import com.syeyoung.assistant.data.EventForm
import com.syeyoung.assistant.data.Freq
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class EventsTest {
    private val seoul = ZoneId.of("Asia/Seoul")

    @Test
    fun `a timed weekly event round-trips`() {
        val e = EventResponse(
            calendarId = "c", calendarName = "Personal", categories = emptyList(), fixed = false, id = "e1", notes = "", repeats = true, title = "Lecture",
            start = "2026-10-20T12:30:00+09:00", end = "2026-10-20T13:45:00+09:00",
            repeat = RepeatDto(freq = RepeatDto.Freq.weekly, interval = 1, weekdays = listOf(RepeatDto.Weekdays.TUESDAY, RepeatDto.Weekdays.THURSDAY), skipped = listOf("2026-11-03")),
        )
        val f = EventForm.from(e, seoul)
        assertEquals(LocalTime.of(12, 30), f.start)
        assertEquals(setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), f.weekdays)
        assertEquals(listOf(LocalDate.parse("2026-11-03")), f.skipped)
        val j = f.toJson(seoul)
        assertEquals("2026-10-20T12:30+09:00", j["start"]!!.jsonPrimitive.content)
        val r = j["repeat"]!!.jsonObject
        assertEquals(listOf("TUESDAY", "THURSDAY"), r["weekdays"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(JsonNull, r["until"])
        assertEquals(JsonNull, j["projectId"])
    }

    @Test
    fun `a new all-day event leaves out what it doesn't set`() {
        val f = EventForm(title = " Trip ", allDay = true, date = LocalDate.parse("2026-10-20"), endDate = LocalDate.parse("2026-10-18"))
        val j = f.toJson(seoul, creating = true)
        assertEquals("Trip", j["title"]!!.jsonPrimitive.content)
        assertEquals("2026-10-20", j["endDate"]!!.jsonPrimitive.content)
        assertFalse("repeat" in j || "projectId" in j || "start" in j)
    }

    @Test
    fun `a rule from another app is kept as is`() {
        val e = EventResponse(calendarId = "c", calendarName = "Personal", categories = emptyList(), fixed = false, id = "e1", notes = "", repeats = true, title = "X", startDate = "2026-10-20")
        val f = EventForm.from(e, seoul)
        assertTrue(f.foreignRule && f.allDay)
        assertFalse("repeat" in f.toJson(seoul))
        assertFalse(f.copy(title = " ").valid)
        assertEquals(Freq.weekly, f.freq)
    }
}
