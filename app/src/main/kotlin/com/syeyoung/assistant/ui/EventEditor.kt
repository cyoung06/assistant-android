package com.syeyoung.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.syeyoung.assistant.data.EventForm
import com.syeyoung.assistant.data.EventTarget
import com.syeyoung.assistant.data.Freq
import com.syeyoung.assistant.data.Repo
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val DATE = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH)
private val WEEK = listOf(DayOfWeek.SUNDAY) + DayOfWeek.entries.filter { it != DayOfWeek.SUNDAY }

/**
 * Create or edit an event, as on the web and the Mac: title, time or all-day, repeat (with skipped
 * dates that can be brought back), project and notes. Events from a source are shown read-only.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EventEditor(target: EventTarget, onDone: () -> Unit) {
    val repo = Repo.get(LocalContext.current)
    val state by repo.state.collectAsStateCompat()
    val scope = rememberCoroutineScope()
    var form by remember(target) { mutableStateOf<EventForm?>((target as? EventTarget.New)?.let { EventForm(date = it.day) }) }
    var fixed by remember(target) { mutableStateOf(false) }
    var calendarName by remember(target) { mutableStateOf("") }
    var error by remember(target) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val existing = target as? EventTarget.Existing

    LaunchedEffect(target) {
        if (existing != null) runCatching { repo.event(existing.id) }
            .onSuccess { form = EventForm.from(it); fixed = it.fixed; calendarName = it.calendarName }
            .onFailure { error = it.message ?: "Couldn't load the event" }
    }

    fun save(f: EventForm) {
        busy = true
        error = null
        scope.launch {
            runCatching { repo.saveEvent(existing?.id, f.toJson(creating = existing == null)) }
                .onSuccess { onDone() }.onFailure { error = it.message ?: "Couldn't save" }
            busy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "New event" else if (fixed) calendarName else "Edit event") },
                navigationIcon = { IconButton(onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    val f = form
                    if (existing != null && !fixed && f != null) IconButton({ confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
                    if (!fixed && f != null) TextButton({ save(f) }, enabled = !busy && f.valid) { Text(if (busy) "Saving…" else "Save") }
                },
            )
        },
    ) { padding ->
        val f = form
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (f == null) {
                if (error == null) Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            if (fixed) Text("From $calendarName; it can't be changed here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val set = { g: EventForm -> form = g }
            val enabled = !fixed
            OutlinedTextField(f.title, { set(f.copy(title = it)) }, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true, enabled = enabled)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("All day", Modifier.weight(1f))
                Switch(f.allDay, { set(f.copy(allDay = it)) }, enabled = enabled)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                if (f.repeats) Text("From")
                DateChip(f.date, enabled) { d -> set(f.copy(date = d, endDate = maxOf(d, f.endDate), weekdays = if (f.weekdays == setOf(f.date.dayOfWeek)) setOf(d.dayOfWeek) else f.weekdays)) }
                if (f.allDay) {
                    Text("to")
                    DateChip(f.endDate, enabled, min = f.date) { set(f.copy(endDate = it)) }
                } else {
                    TimeChip(f.start, enabled) { t ->
                        // Moving the start keeps the length, as calendars do.
                        val length = java.time.Duration.between(f.start, f.end).takeIf { !it.isNegative && !it.isZero } ?: java.time.Duration.ofHours(1)
                        val end = t.plus(length).takeIf { it.isAfter(t) } ?: LocalTime.of(23, 59)
                        set(f.copy(start = t, end = end))
                    }
                    Text("–")
                    TimeChip(f.end, enabled) { set(f.copy(end = it)) }
                }
            }
            if (!f.allDay && !f.end.isAfter(f.start)) Text("End must be after start.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            ProjectField(f, state.data.projects.filter { (it.state.value != "completed" && it.state.value != "canceled") || it.id == f.projectId }, enabled) { set(f.copy(projectId = it)) }
            if (f.foreignRule) {
                Text("Repeats by a rule set in another app; edit the rule there.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Repeats", Modifier.weight(1f))
                    Switch(f.repeats, { set(f.copy(repeats = it)) }, enabled = enabled)
                }
                if (f.repeats) RepeatFields(f, enabled, set)
            }
            OutlinedTextField(f.location, { set(f.copy(location = it)) }, Modifier.fillMaxWidth(), label = { Text("Room or place") }, singleLine = true, enabled = enabled)
            OutlinedTextField(f.notes, { set(f.copy(notes = it)) }, Modifier.fillMaxWidth().heightIn(min = 120.dp), label = { Text("Notes") }, enabled = enabled)
            if (existing != null && !fixed && f.repeats && existing.occurrence !in f.skipped) {
                HorizontalDivider()
                OutlinedButton({ save(f.copy(skipped = f.skipped + existing.occurrence)) }, enabled = !busy) { Text("Skip ${existing.occurrence.format(DATE)} only") }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this event?") },
            text = { if (form?.repeats == true) Text("Every occurrence goes; to drop one date, use Skip instead.") },
            confirmButton = {
                TextButton({
                    confirmDelete = false
                    scope.launch { runCatching { repo.deleteEvent(existing!!.id) }.onSuccess { onDone() }.onFailure { error = it.message ?: "Couldn't delete" } }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RepeatFields(f: EventForm, enabled: Boolean, set: (EventForm) -> Unit) {
    Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Every")
            TextButton({ set(f.copy(interval = (f.interval - 1).coerceAtLeast(1))) }, enabled = enabled && f.interval > 1) { Text("−") }
            Text("${f.interval}")
            TextButton({ set(f.copy(interval = (f.interval + 1).coerceAtMost(99))) }, enabled = enabled) { Text("+") }
            var open by remember { mutableStateOf(false) }
            AssistChip({ open = true }, { Text(unit(f.freq, f.interval)) }, enabled = enabled)
            DropdownMenu(open, { open = false }) {
                Freq.entries.forEach { q -> DropdownMenuItem({ Text(unit(q, f.interval)) }, { set(f.copy(freq = q)); open = false }) }
            }
        }
        if (f.freq == Freq.weekly) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                WEEK.forEach { d ->
                    val on = d in f.weekdays
                    FilterChip(on, { set(f.copy(weekdays = if (on) f.weekdays - d else f.weekdays + d)) }, { Text(d.getDisplayName(TextStyle.NARROW, Locale.ENGLISH)) }, enabled = enabled)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Until")
            val until = f.until
            if (until == null) {
                AssistChip({ set(f.copy(until = f.date.plusMonths(3))) }, { Text("Forever") }, enabled = enabled)
            } else {
                DateChip(until, enabled, min = f.date) { set(f.copy(until = it)) }
                TextButton({ set(f.copy(until = null)) }, enabled = enabled) { Text("Forever") }
            }
        }
        if (f.skipped.isNotEmpty()) {
            Text("Skipped", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            f.skipped.sorted().forEach { d ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(d.format(DATE), Modifier.width(140.dp))
                    TextButton({ set(f.copy(skipped = f.skipped - d)) }, enabled = enabled) { Text("Restore") }
                }
            }
        }
    }
}

private fun unit(q: Freq, n: Int) = when (q) {
    Freq.daily -> if (n == 1) "day" else "days"
    Freq.weekly -> if (n == 1) "week" else "weeks"
    Freq.monthly -> if (n == 1) "month" else "months"
}

@Composable
private fun ProjectField(f: EventForm, projects: List<com.syeyoung.assistant.api.models.ProjectResponse>, enabled: Boolean, onPick: (java.util.UUID?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val p = projects.firstOrNull { it.id == f.projectId }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Project")
        Spacer(Modifier.weight(1f))
        Column {
            AssistChip({ open = true }, { Text(p?.name ?: "None") }, leadingIcon = p?.let { { Dot(it.color) } }, enabled = enabled)
            ProjectMenu(open, projects, { open = false }, onPick)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateChip(date: LocalDate, enabled: Boolean, min: LocalDate? = null, onPick: (LocalDate) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    AssistChip({ picking = true }, { Text(date.format(DATE)) }, enabled = enabled)
    if (picking) {
        val millis = { d: LocalDate -> d.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }
        val state = rememberDatePickerState(
            initialSelectedDateMillis = millis(date),
            selectableDates = object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = min == null || utcTimeMillis >= millis(min)
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton({
                    state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ picking = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeChip(time: LocalTime, enabled: Boolean, onPick: (LocalTime) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    AssistChip({ picking = true }, { Text("%02d:%02d".format(time.hour, time.minute)) }, enabled = enabled)
    if (picking) {
        val state = rememberTimePickerState(time.hour, time.minute, true)
        AlertDialog(
            onDismissRequest = { picking = false },
            text = { TimePicker(state) },
            confirmButton = { TextButton({ onPick(LocalTime.of(state.hour, state.minute)); picking = false }) { Text("OK") } },
            dismissButton = { TextButton({ picking = false }) { Text("Cancel") } },
        )
    }
}
