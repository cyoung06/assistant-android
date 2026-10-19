package com.syeyoung.assistant

import com.syeyoung.assistant.api.models.DueDto
import com.syeyoung.assistant.api.models.TaskResponse
import com.syeyoung.assistant.api.models.InitiativeResponse
import com.syeyoung.assistant.api.models.ProjectResponse
import com.syeyoung.assistant.data.Bucket
import com.syeyoung.assistant.data.Snapshot
import com.syeyoung.assistant.data.drawerProjects
import com.syeyoung.assistant.data.messageIdOf
import com.syeyoung.assistant.data.plmsWebUrl
import com.syeyoung.assistant.data.taskOf
import com.syeyoung.assistant.data.bucket
import com.syeyoung.assistant.data.dueChoices
import com.syeyoung.assistant.data.dueText
import com.syeyoung.assistant.data.groupByDue
import com.syeyoung.assistant.data.plainText
import com.syeyoung.assistant.data.refMarkdown
import com.syeyoung.assistant.data.refParts
import com.syeyoung.assistant.notify.Reminders
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

class TasksTest {
    private val today = LocalDate.parse("2026-10-18") // a Sunday
    private var n = 0L

    private fun task(due: DueDto? = null, status: TaskResponse.Status = TaskResponse.Status.todo, overdue: Boolean = false, priority: TaskResponse.Priority = TaskResponse.Priority.none) =
        TaskResponse(
            attachments = emptyList(), blocked = false, createdAt = "", description = "", id = UUID.randomUUID(), labelIds = emptyList(), number = ++n,
            overdue = overdue, priority = priority, relations = emptyList(), status = status, subtasks = 0, subtasksDone = 0, title = "t$n", updatedAt = "", version = 1, due = due,
        )

    @Test fun buckets() {
        assertEquals(Bucket.OVERDUE, bucket(task(DueDto("2026-10-17"), overdue = true), today))
        assertEquals(Bucket.TODAY, bucket(task(DueDto("2026-10-18", "23:59:00")), today))
        assertEquals(Bucket.WEEK, bucket(task(DueDto("2026-10-24")), today))
        assertEquals(Bucket.LATER, bucket(task(DueDto("2026-10-25")), today))
        assertEquals(Bucket.NONE, bucket(task(), today))
    }

    @Test fun groupsLeaveOutClosedAndOrderByPriority() {
        val low = task(DueDto("2026-10-18"), priority = TaskResponse.Priority.low)
        val urgent = task(DueDto("2026-10-18"), priority = TaskResponse.Priority.urgent)
        val groups = groupByDue(listOf(low, urgent, task(DueDto("2026-10-18"), TaskResponse.Status.done)), today)
        assertEquals(listOf("TODAY"), groups.map { it.key })
        assertEquals(listOf(urgent, low), groups.single().tasks)
    }

    @Test fun dueTextNamesNearbyDays() {
        assertEquals("Today 19:00", dueText(DueDto("2026-10-18", "19:00:00"), today))
        assertEquals("Tomorrow", dueText(DueDto("2026-10-19"), today))
        assertEquals("Fri", dueText(DueDto("2026-10-23"), today))
        assertEquals("Nov 2", dueText(DueDto("2026-11-02"), today))
        assertEquals(listOf("Today", "Tomorrow", "End of week", "Next week"), dueChoices(LocalDate.parse("2026-10-20")).map { it.first })
    }

    @Test fun references() {
        val md = "Do " + refMarkdown("[Lab 3] BombLab", "https://x.test/a b") + " now"
        assertEquals("Do [Lab 3] BombLab now", plainText(md))
        assertEquals("https://x.test/a%20b", refParts(md)[1].url)
        assertEquals("[Lab 3] BombLab", refParts(md)[1].text)
    }

    @Test fun reminderTimes() {
        val timed = Reminders.moments("2026-10-20", "23:59:00")
        assertEquals(listOf("2026-10-19T23:59+09:00[Asia/Seoul]", "2026-10-20T22:59+09:00[Asia/Seoul]"), timed.map { it.first.toString() })
        val dateOnly = Reminders.moments("2026-10-20", null)
        assertEquals(listOf("2026-10-19T09:00+09:00[Asia/Seoul]", "2026-10-20T09:00+09:00[Asia/Seoul]"), dateOnly.map { it.first.toString() })
        assertEquals("Due today", dateOnly[1].second)
    }

    @Test fun drawerShowsProjectsOfActiveInitiativesOrNone() {
        val fall = UUID.randomUUID()
        val spring = UUID.randomUUID()
        fun project(name: String, initiatives: List<UUID>, state: ProjectResponse.State = ProjectResponse.State.in_progress) =
            ProjectResponse(color = "#000000", description = "", doneCount = 0, id = UUID.randomUUID(), initiativeIds = initiatives, name = name, state = state, taskCount = 0)
        fun initiative(id: UUID, status: InitiativeResponse.Status) =
            InitiativeResponse(description = "", doneCount = 0, id = id, name = "", projectIds = emptyList(), status = status, taskCount = 0)
        val data = Snapshot(
            projects = listOf(
                project("now", listOf(fall)), project("old", listOf(spring)), project("loose", emptyList()), project("both", listOf(spring, fall)),
                project("done", listOf(fall), ProjectResponse.State.completed),
            ),
            initiatives = listOf(initiative(fall, InitiativeResponse.Status.active), initiative(spring, InitiativeResponse.Status.completed)),
        )
        assertEquals(listOf("now", "loose", "both"), drawerProjects(data).map { it.name })
    }

    @Test fun linksPointToTheRightThing() {
        val id = UUID.fromString("0b3b0b3b-0000-4000-8000-000000000001")
        assertEquals(id, taskOf("https://assistant.example/task/$id"))
        assertEquals(id, taskOf("https://assistant.example/#task/$id"))
        assertEquals("lab@postech.ac.kr", messageIdOf("mid:lab@postech.ac.kr"))
        assertEquals("https://plms.postech.ac.kr/mod/ubboard/article.php?id=55&bwid=9", plmsWebUrl("plms:post/55/9"))
        assertEquals(null, taskOf("mid:x@y"))
    }
}
