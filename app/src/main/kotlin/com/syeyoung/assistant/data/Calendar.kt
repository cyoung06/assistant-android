package com.syeyoung.assistant.data

import com.syeyoung.assistant.api.models.EventOccurrence
import com.syeyoung.assistant.api.models.RecurringSummary
import com.syeyoung.assistant.api.models.TaskResponse
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/**
 * Something drawn on the calendar: an event occurrence or a task's deadline. [start] and [end] are
 * local; an all-day item runs from midnight of its first day to midnight after its last.
 */
data class CalItem(
    val key: String,
    val title: String,
    val color: String?,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val allDay: Boolean,
    val event: EventOccurrence? = null,
    val task: TaskResponse? = null,
) {
    fun on(day: LocalDate) = start.isBefore(day.plusDays(1).atStartOfDay()) && end.isAfter(day.atStartOfDay())
}

/** How long a deadline's marker is; deadlines in a day's last half hour start at 23:30 so they stay in it (as on the web). */
const val DEADLINE_MINUTES = 30L

fun calItems(events: List<EventOccurrence>, tasks: List<TaskResponse>, projectColors: Map<UUID, String>, zone: ZoneId = ZoneId.systemDefault()): List<CalItem> {
    val evs = events.mapNotNull { e ->
        val sd = e.startDate
        if (sd != null) {
            val first = LocalDate.parse(sd)
            val last = e.endDate?.let(LocalDate::parse) ?: first
            CalItem("e:${e.id}:$sd", plainText(e.title), e.color, first.atStartOfDay(), last.plusDays(1).atStartOfDay(), true, event = e)
        } else {
            val s = e.start?.let { OffsetDateTime.parse(it).atZoneSameInstant(zone).toLocalDateTime() } ?: return@mapNotNull null
            val en = e.end?.let { OffsetDateTime.parse(it).atZoneSameInstant(zone).toLocalDateTime() }?.takeIf { it.isAfter(s) } ?: s.plusHours(1)
            CalItem("e:${e.id}:${e.start}", plainText(e.title), e.color, s, en, false, event = e)
        }
    }
    val deadlines = tasks.mapNotNull { t ->
        val due = t.due ?: return@mapNotNull null
        if (t.status == TaskResponse.Status.canceled) return@mapNotNull null
        val day = LocalDate.parse(due.date)
        val time = due.time?.let { LocalTime.parse(it) }?.takeIf { it != LocalTime.MIDNIGHT }
        val color = t.projectId?.let(projectColors::get)
        if (time == null) CalItem("d:${t.id}", plainText(t.title), color, day.atStartOfDay(), day.plusDays(1).atStartOfDay(), true, task = t)
        else {
            val s = day.atTime(minOf(time, LocalTime.of(23, 30)))
            CalItem("d:${t.id}", plainText(t.title), color, s, s.plusMinutes(DEADLINE_MINUTES), false, task = t)
        }
    }
    return evs + deadlines
}

/** A timed item's piece within one day, in minutes from midnight, in column [col] of [cols] side by side. */
data class Placed(val item: CalItem, val startMin: Int, val endMin: Int, val col: Int, val cols: Int)

/** Timed items on [day], with overlapping ones placed side by side; short ones count as at least [minMinutes] long. */
fun dayLayout(items: List<CalItem>, day: LocalDate, minMinutes: Int = 20): List<Placed> {
    val dayStart = day.atStartOfDay()
    val segs = items.filter { !it.allDay && it.on(day) }.map { i ->
        val s = ChronoUnit.MINUTES.between(dayStart, maxOf(i.start, dayStart)).toInt()
        val e = ChronoUnit.MINUTES.between(dayStart, minOf(i.end, dayStart.plusDays(1))).toInt()
        Triple(i, s, maxOf(e, minOf(s + minMinutes, 24 * 60)))
    }.sortedWith(compareBy<Triple<CalItem, Int, Int>> { it.second }.thenByDescending { it.third })

    val out = mutableListOf<Placed>()
    var cluster = mutableListOf<Pair<Triple<CalItem, Int, Int>, Int>>()
    val colEnds = mutableListOf<Int>()
    var clusterEnd = -1
    fun flush() {
        out += cluster.map { (seg, col) -> Placed(seg.first, seg.second, seg.third, col, colEnds.size) }
        cluster = mutableListOf(); colEnds.clear()
    }
    for (seg in segs) {
        if (seg.second >= clusterEnd) flush()
        val col = colEnds.indexOfFirst { it <= seg.second }.takeIf { it >= 0 } ?: colEnds.size.also { colEnds += 0 }
        colEnds[col] = seg.third
        cluster += seg to col
        clusterEnd = maxOf(clusterEnd, seg.third)
    }
    flush()
    return out
}

/** All-day items on [day]: events first, then deadlines. */
fun allDayOn(items: List<CalItem>, day: LocalDate) = items.filter { it.allDay && it.on(day) }.sortedBy { if (it.task != null) 1 else 0 }

/** Everything on [day] for the month grid: all-day items, then timed ones by start. */
fun monthDayItems(items: List<CalItem>, day: LocalDate) = allDayOn(items, day) + items.filter { !it.allDay && it.on(day) }.sortedBy { it.start }

/** The six weeks (Sunday first) shown for [month]. */
fun monthGrid(month: YearMonth): List<LocalDate> {
    val first = month.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
    return (0 until 42).map { first.plusDays(it.toLong()) }
}

/** How many days the week view fits: the whole week on wide screens, three on a phone, one when very narrow. */
fun visibleDays(widthDp: Float): Int = when {
    widthDp >= 600 -> 7
    widthDp >= 300 -> 3
    else -> 1
}

/** The first day shown: the week's Sunday when showing a whole week, else [anchor] itself. */
fun rangeStart(anchor: LocalDate, days: Int): LocalDate =
    if (days == 7) anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)) else anchor

/** A project's repeating event in one line: "Tue · Thu 12:30–13:45", "Every 2 weeks · Fri", "Daily 09:00–10:00". */
fun scheduleLine(r: RecurringSummary): String {
    val time = if (r.start != null && r.end != null) " ${r.start.take(5)}–${r.end.take(5)}" else ""
    val days = r.weekdays.joinToString(" · ") { d -> d.value.take(3).lowercase().replaceFirstChar { it.uppercase() } }
    return when (r.freq) {
        RecurringSummary.Freq.weekly -> (if (r.interval > 1) "Every ${r.interval} weeks · " else "") + days + time
        RecurringSummary.Freq.daily -> (if (r.interval > 1) "Every ${r.interval} days" else "Daily") + time
        else -> (if (r.interval > 1) "Every ${r.interval} months" else "Monthly") + time
    }
}
