package com.syeyoung.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.syeyoung.assistant.api.models.TaskResponse
import com.syeyoung.assistant.data.drawerProjects
import com.syeyoung.assistant.data.Repo
import com.syeyoung.assistant.data.TaskChange
import com.syeyoung.assistant.data.closed
import com.syeyoung.assistant.data.groupByDue
import com.syeyoung.assistant.data.statusLabel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

sealed interface Screen {
    val title: String
    data object Triage : Screen { override val title = "Triage" }
    data object Active : Screen { override val title = "Active" }
    data object Backlog : Screen { override val title = "Backlog" }
    data object All : Screen { override val title = "All tasks" }
    data class Project(val id: UUID, override val title: String) : Screen
    data object Calendar : Screen { override val title = "Calendar" }
    data object Settings : Screen { override val title = "Settings" }
}

/** The app: a drawer (always open on a tablet) with the task views, projects, calendar and settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantApp(openTask: UUID? = null, openMail: String? = null, onMailClosed: () -> Unit = {}) {
    val context = LocalContext.current
    val repo = Repo.get(context)
    val state by repo.state.collectAsStateCompat()
    val triage by repo.triage.collectAsStateCompat()
    var mail by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var screen by remember { mutableStateOf<Screen>(Screen.Active) }
    var detail by remember { mutableStateOf(openTask) }
    var creating by remember { mutableStateOf(false) }
    val wide = LocalConfiguration.current.screenWidthDp >= 840
    val drawer = rememberDrawerState(DrawerValue.Closed)

    LaunchedEffect(Unit) { repo.refresh() }
    LaunchedEffect(openTask) { if (openTask != null) detail = openTask }

    val onStatus: (TaskResponse, TaskResponse.Status) -> Unit = { t, s ->
        scope.launch {
            runCatching { repo.change(t, TaskChange(status = s)) }.onFailure { snackbar.showSnackbar(it.message ?: "Couldn't save") }
            if ((s == TaskResponse.Status.done || s == TaskResponse.Status.canceled) && !t.closed) {
                if (snackbar.showSnackbar("#${t.number} marked ${statusLabel(s)}", "Undo") == SnackbarResult.ActionPerformed) {
                    repo.change(t, TaskChange(status = t.status))
                }
            }
        }
    }

    openMail?.let { id ->
        BackHandler { onMailClosed() }
        MailReader(id, onBack = onMailClosed)
        return
    }
    mail?.let { id ->
        BackHandler { mail = null }
        MailReader(id, onBack = { mail = null })
        return
    }

    detail?.let { id ->
        BackHandler { detail = null }
        TaskDetail(id, onBack = { detail = null }, onOpenTask = { detail = it })
        return
    }

    val nav: @Composable () -> Unit = {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
            Text("Assistant", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
            val waiting = triageCount(triage)
            NavigationDrawerItem(
                label = { Text("Triage") }, icon = { Icon(Icons.Filled.MoveToInbox, null) }, badge = { if (waiting > 0) Text("$waiting") },
                selected = screen == Screen.Triage, onClick = { screen = Screen.Triage; scope.launch { drawer.close() } }, modifier = Modifier.padding(horizontal = 12.dp),
            )
            listOf(Screen.Active to Icons.Filled.CheckCircleOutline, Screen.Backlog to Icons.Filled.Inbox, Screen.All to Icons.Filled.List).forEach { (s, icon) ->
                NavigationDrawerItem(label = { Text(s.title) }, icon = { Icon(icon, null) }, selected = screen == s, onClick = { screen = s; scope.launch { drawer.close() } }, modifier = Modifier.padding(horizontal = 12.dp))
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Projects", Modifier.padding(horizontal = 28.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            drawerProjects(state.data).forEach { p ->
                val open = state.data.tasks.count { it.projectId == p.id && !it.closed }
                NavigationDrawerItem(
                    label = { Text(p.name) }, icon = { Dot(p.color) }, badge = { if (open > 0) Text("$open") },
                    selected = (screen as? Screen.Project)?.id == p.id, onClick = { screen = Screen.Project(p.id, p.name); scope.launch { drawer.close() } },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            NavigationDrawerItem(label = { Text("Calendar") }, icon = { Icon(Icons.Filled.CalendarMonth, null) }, selected = screen == Screen.Calendar, onClick = { screen = Screen.Calendar; scope.launch { drawer.close() } }, modifier = Modifier.padding(horizontal = 12.dp))
            NavigationDrawerItem(label = { Text("Settings") }, icon = { Icon(Icons.Filled.Settings, null) }, selected = screen == Screen.Settings, onClick = { screen = Screen.Settings; scope.launch { drawer.close() } }, modifier = Modifier.padding(horizontal = 12.dp))
        }
    }

    val content: @Composable () -> Unit = {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(screen.title) },
                    navigationIcon = { if (!wide) IconButton({ scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, "Navigation") } },
                )
            },
            floatingActionButton = { if (screen != Screen.Settings) FloatingActionButton({ creating = true }) { Icon(Icons.Filled.Add, "New task") } },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                val today = LocalDate.now()
                val tasks = state.data.tasks
                when (val s = screen) {
                    Screen.Active -> TaskList(
                        groupByDue(tasks.filter { it.status == TaskResponse.Status.todo || it.status == TaskResponse.Status.in_progress }, today), emptyList(),
                        state.data.projects, state.loading, { scope.launch { repo.refresh() } }, { detail = it.id }, onStatus, empty = state.error ?: "Nothing to do.",
                    )
                    Screen.Backlog -> TaskList(
                        groupByDue(tasks.filter { it.status == TaskResponse.Status.backlog }, today), emptyList(),
                        state.data.projects, state.loading, { scope.launch { repo.refresh() } }, { detail = it.id }, onStatus, empty = state.error ?: "Backlog is empty.",
                    )
                    Screen.All -> TaskList(
                        groupByDue(tasks, today), tasks.filter { it.closed }.sortedByDescending { it.closedAt },
                        state.data.projects, state.loading, { scope.launch { repo.refresh() } }, { detail = it.id }, onStatus, empty = state.error ?: "No tasks.",
                    )
                    is Screen.Project -> TaskList(
                        groupByDue(tasks.filter { it.projectId == s.id }, today), tasks.filter { it.projectId == s.id && it.closed },
                        state.data.projects, state.loading, { scope.launch { repo.refresh() } }, { detail = it.id }, onStatus, showProject = false,
                        header = { ProjectScheduleHeader(s.id, s.title) },
                    )
                    Screen.Calendar -> CalendarScreen { detail = it }
                    Screen.Triage -> TriageScreen(onOpenTask = { detail = it }, onOpenMail = { mail = it })
                    Screen.Settings -> SettingsScreen()
                }
            }
        }
    }

    if (wide) PermanentNavigationDrawer(drawerContent = { PermanentDrawerSheet(Modifier.padding(end = 0.dp)) { nav() } }) { content() }
    else ModalNavigationDrawer(drawerContent = { ModalDrawerSheet { nav() } }, drawerState = drawer) { content() }

    if (creating) CreateTaskSheet((screen as? Screen.Project)?.id) { creating = false }
}
