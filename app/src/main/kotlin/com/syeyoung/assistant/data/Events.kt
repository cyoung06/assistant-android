package com.syeyoung.assistant.data

import com.syeyoung.assistant.api.models.EventResponse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/** What the event editor opens on: a new event on a day, or an existing one (from one of its occurrences). */
sealed interface EventTarget {
    data class New(val day: LocalDate) : EventTarget
    data class Existing(val id: String, val occurrence: LocalDate) : EventTarget
}

enum class Freq { daily, weekly, monthly }

/** The event editor's fields, as on the web: time or all-day, a simple repeat rule with skipped dates, project, notes. */
data class EventForm(
    val title: String = "",
    val notes: String = "",
    val allDay: Boolean = false,
    val date: LocalDate,
    val endDate: LocalDate = date,
    val start: LocalTime = LocalTime.of(9, 0),
    val end: LocalTime = LocalTime.of(10, 0),
    val projectId: UUID? = null,
    val repeats: Boolean = false,
    val freq: Freq = Freq.weekly,
    val interval: Int = 1,
    val weekdays: Set<DayOfWeek> = setOf(date.dayOfWeek),
    val until: LocalDate? = null,
    val skipped: List<LocalDate> = emptyList(),
    /** Repeats by a rule set in another app that this editor can't express: kept as is. */
    val foreignRule: Boolean = false,
) {
    val valid get() = title.isNotBlank() && (allDay || end.isAfter(start))

    /** The body for a create (POST) or a merge patch: nulls clear what was set (project, repeat). */
    fun toJson(zone: ZoneId = ZoneId.systemDefault(), creating: Boolean = false): JsonObject = buildJsonObject {
        put("title", title.trim())
        put("notes", notes)
        if (allDay) {
            put("startDate", date.toString())
            put("endDate", maxOf(date, endDate).toString())
        } else {
            put("start", date.atTime(start).atZone(zone).toOffsetDateTime().toString())
            put("end", date.atTime(end).atZone(zone).toOffsetDateTime().toString())
        }
        if (projectId != null) put("projectId", projectId.toString()) else if (!creating) put("projectId", JsonNull)
        if (!foreignRule) {
            if (repeats) put("repeat", buildJsonObject {
                put("freq", freq.name)
                put("interval", interval)
                put("weekdays", JsonArray(if (freq == Freq.weekly) weekdays.sorted().map { JsonPrimitive(it.name) } else emptyList()))
                put("until", until?.toString()?.let(::JsonPrimitive) ?: JsonNull)
                put("skipped", JsonArray(skipped.map { JsonPrimitive(it.toString()) }))
            }) else if (!creating) put("repeat", JsonNull)
        }
    }

    companion object {
        fun from(e: EventResponse, zone: ZoneId = ZoneId.systemDefault()): EventForm {
            val s = e.start?.let { OffsetDateTime.parse(it).atZoneSameInstant(zone).toLocalDateTime() }
            val en = e.end?.let { OffsetDateTime.parse(it).atZoneSameInstant(zone).toLocalDateTime() }
            val date = e.startDate?.let(LocalDate::parse) ?: s?.toLocalDate() ?: LocalDate.now()
            val r = e.repeat
            return EventForm(
                title = e.title,
                notes = e.notes,
                allDay = e.startDate != null,
                date = date,
                endDate = e.endDate?.let(LocalDate::parse) ?: date,
                start = s?.toLocalTime() ?: LocalTime.of(9, 0),
                end = en?.toLocalTime() ?: LocalTime.of(10, 0),
                projectId = e.projectId,
                repeats = r != null,
                freq = r?.freq?.value?.let { v -> Freq.entries.firstOrNull { it.name == v } } ?: Freq.weekly,
                interval = r?.interval ?: 1,
                weekdays = r?.weekdays?.mapNotNull { w -> runCatching { DayOfWeek.valueOf(w.value) }.getOrNull() }?.toSet()?.ifEmpty { null } ?: setOf(date.dayOfWeek),
                until = r?.until?.let(LocalDate::parse),
                skipped = r?.skipped?.map(LocalDate::parse) ?: emptyList(),
                foreignRule = e.repeats && r == null,
            )
        }
    }
}
