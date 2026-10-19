package com.syeyoung.assistant.data

import com.syeyoung.assistant.api.models.DueDto
import com.syeyoung.assistant.api.models.InitiativeResponse
import com.syeyoung.assistant.api.models.LabelResponse
import com.syeyoung.assistant.api.models.ProjectResponse
import com.syeyoung.assistant.api.models.TaskResponse
import com.syeyoung.assistant.api.models.TaskResponse.Priority
import com.syeyoung.assistant.api.models.TaskResponse.Status
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

// What tasks mean on screen; the same rules as the web app (src/tasks/model.ts).

val TaskResponse.closed get() = status == Status.done || status == Status.canceled

fun statusLabel(s: Status) = when (s) {
    Status.backlog -> "Backlog"
    Status.todo -> "Todo"
    Status.in_progress -> "In Progress"
    Status.done -> "Done"
    Status.canceled -> "Canceled"
    else -> "?"
}

fun priorityLabel(p: Priority) = when (p) {
    Priority.urgent -> "Urgent"
    Priority.high -> "High"
    Priority.medium -> "Medium"
    Priority.low -> "Low"
    else -> "No priority"
}

private val PRIORITY_RANK = mapOf(Priority.urgent to 0, Priority.high to 1, Priority.medium to 2, Priority.low to 3)

/** The last day of the week (Saturday; weeks start on Sunday). */
fun weekEnd(today: LocalDate): LocalDate = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))

enum class Bucket(val label: String) { OVERDUE("Overdue"), TODAY("Today"), WEEK("This week"), LATER("Later"), NONE("No due date") }

fun bucket(t: TaskResponse, today: LocalDate): Bucket {
    val due = t.due ?: return Bucket.NONE
    if (t.overdue) return Bucket.OVERDUE
    val d = LocalDate.parse(due.date)
    return when {
        !d.isAfter(today) -> Bucket.TODAY
        !d.isAfter(weekEnd(today)) -> Bucket.WEEK
        else -> Bucket.LATER
    }
}

fun sortTasks(tasks: List<TaskResponse>): List<TaskResponse> =
    tasks.sortedWith(compareBy<TaskResponse>({ PRIORITY_RANK[it.priority] ?: 4 }, { it.due?.let { d -> d.date + "T" + (d.time ?: "24:00") } ?: "~" }, { -it.number }))

data class Group(val key: String, val label: String, val tasks: List<TaskResponse>, val danger: Boolean = false, val color: String? = null)

/** Open tasks by due bucket, empty groups left out. */
fun groupByDue(tasks: List<TaskResponse>, today: LocalDate): List<Group> {
    val open = tasks.filter { !it.closed }
    return Bucket.entries.map { b -> Group(b.name, b.label, sortTasks(open.filter { bucket(it, today) == b }), danger = b == Bucket.OVERDUE) }
        .filter { it.tasks.isNotEmpty() }
}

fun groupByProject(tasks: List<TaskResponse>, projects: List<ProjectResponse>): List<Group> {
    val open = tasks.filter { !it.closed }
    return (projects.map { p -> Group(p.id.toString(), p.name, sortTasks(open.filter { it.projectId == p.id }), color = p.color) } +
        Group("none", "No project", sortTasks(open.filter { t -> t.projectId == null || projects.none { it.id == t.projectId } })))
        .filter { it.tasks.isNotEmpty() }
}

/** "Today 19:00", "Tomorrow", "Fri", "Nov 2". */
fun dueText(due: DueDto, today: LocalDate): String {
    val d = LocalDate.parse(due.date)
    val day = when {
        d == today -> "Today"
        d == today.plusDays(1) -> "Tomorrow"
        d == today.minusDays(1) -> "Yesterday"
        d.isAfter(today) && !d.isAfter(today.plusDays(6)) -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
        else -> d.format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH))
    }
    return due.time?.let { "$day ${it.take(5)}" } ?: day
}

/** Quick due choices: Today, Tomorrow, End of week (Friday), Next week (Monday); duplicates dropped. */
fun dueChoices(today: LocalDate): List<Pair<String, LocalDate>> {
    fun next(day: DayOfWeek) = today.with(TemporalAdjusters.next(day))
    return listOf("Today" to today, "Tomorrow" to today.plusDays(1), "End of week" to next(DayOfWeek.FRIDAY), "Next week" to next(DayOfWeek.MONDAY))
        .distinctBy { it.second }
}

fun parseTime(s: String?): LocalTime? = s?.let { runCatching { LocalTime.parse(it.take(5)) }.getOrNull() }

fun labelsOf(t: TaskResponse, labels: List<LabelResponse>) = t.labelIds.mapNotNull { id -> labels.find { it.id == id } }

// ---- references: Markdown links, `[title](url)` ---------------------------------------

private val LINK = Regex("""\[((?:\\.|[^\]\\])*)]\(((?:[^()\s]|\([^()\s]*\))+)\)""")

/** `[text](url)` → `text`. */
fun plainText(markdown: String) = LINK.replace(markdown) { it.groupValues[1].replace(Regex("""\\([\[\]])"""), "$1") }

data class Part(val text: String, val url: String? = null)

/** Text and references in order. */
fun refParts(markdown: String): List<Part> {
    val out = mutableListOf<Part>()
    var last = 0
    for (m in LINK.findAll(markdown)) {
        if (m.range.first > last) out += Part(markdown.substring(last, m.range.first))
        out += Part(m.groupValues[1].replace(Regex("""\\([\[\]])"""), "$1"), m.groupValues[2])
        last = m.range.last + 1
    }
    if (last < markdown.length) out += Part(markdown.substring(last))
    return out
}

fun refMarkdown(title: String, url: String) = "[" + title.replace(Regex("""([\[\]])"""), """\\$1""") + "](" + url.replace(")", "%29").replace(" ", "%20") + ")"

/** Open projects for the drawer: those in an active initiative, or in none (a finished semester's courses drop out). */
fun drawerProjects(data: Snapshot): List<ProjectResponse> {
    val active = data.initiatives.filter { it.status == InitiativeResponse.Status.active }.map { it.id }.toSet()
    return data.projects.filter { p ->
        p.state.value != "completed" && p.state.value != "canceled" && (p.initiativeIds.isEmpty() || p.initiativeIds.any { it in active })
    }
}
