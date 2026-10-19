package com.syeyoung.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Folder
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
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
import com.syeyoung.assistant.data.EventTarget
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
    data object Initiatives : Screen { override val title = "Initiatives" }
    data object Notebooks : Screen { override val title = "Notebooks" }
    data object Files : Screen { override val title = "Files" }
    data class Initiative(val id: UUID, override val title: String) : Screen
    data object Settings : Screen { override val title = "Settings" }
}

/** The app: a drawer (beside the content on a tablet, which the menu button hides) with the task views, projects, calendar and settings. */
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
    var event by remember { mutableStateOf<EventTarget?>(null) }
    // Bumped when the event editor closes, so the calendar reads its range again.
    var calendarVersion by remember { mutableStateOf(0) }
    // The day a new event from the calendar starts on: today, or the first day shown when today isn't.
    var calendarDay by remember { mutableStateOf(LocalDate.now()) }
    val wide = LocalConfiguration.current.screenWidthDp >= 840
    val drawer = rememberDrawerState(DrawerValue.Closed)
    // On a tablet the drawer sits beside the content (which takes the width left); whether it's shown is remembered.
    val prefs = remember { context.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE) }
    var sideOpen by remember { mutableStateOf(prefs.getBoolean("sidebarOpen", true)) }
    val toggleSide = { sideOpen = !sideOpen; prefs.edit().putBoolean("sidebarOpen", sideOpen).apply() }

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

    event?.let { t ->
        val close = { event = null; calendarVersion += 1 }
        BackHandler { close() }
        EventEditor(t, onDone = close)
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
            NavigationDrawerItem(
                label = { Text("Initiatives") }, icon = { Icon(Icons.Filled.Flag, null) },
                selected = screen == Screen.Initiatives || screen is Screen.Initiative, onClick = { screen = Screen.Initiatives; scope.launch { drawer.close() } },
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            drawerProjects(state.data).forEach { p ->
                val open = state.data.tasks.count { it.projectId == p.id && !it.closed }
                NavigationDrawerItem(
                    label = { Text(p.name) }, icon = { Dot(p.color) }, badge = { if (open > 0) Text("$open") },
                    selected = (screen as? Screen.Project)?.id == p.id, onClick = { screen = Screen.Project(p.id, p.name); scope.launch { drawer.close() } },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            NavigationDrawerItem(label = { Text("Files") }, icon = { Icon(Icons.Filled.Folder, null) }, selected = screen == Screen.Files, onClick = { screen = Screen.Files; scope.launch { drawer.close() } }, modifier = Modifier.padding(horizontal = 12.dp))
            NavigationDrawerItem(label = { Text("Notebooks") }, icon = { Icon(Icons.Filled.Draw, null) }, selected = screen == Screen.Notebooks, onClick = { screen = Screen.Notebooks; scope.launch { drawer.close() } }, modifier = Modifier.padding(horizontal = 12.dp))
            NavigationDrawerItem(label = { Text("Calendar") }, icon = { Icon(Icons.Filled.CalendarMonth, null) }, selected = screen == Screen.Calendar, onClick = { screen = Screen.Calendar; scope.launch { drawer.close() } }, modifier = Modifier.padding(horizontal = 12.dp))
            NavigationDrawerItem(label = { Text("Settings") }, icon = { Icon(Icons.Filled.Settings, null) }, selected = screen == Screen.Settings, onClick = { screen = Screen.Settings; scope.launch { drawer.close() } }, modifier = Modifier.padding(horizontal = 12.dp))
        }
    }

    val content: @Composable () -> Unit = {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(screen.title) },
                    navigationIcon = {
                        IconButton({ if (wide) toggleSide() else scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, "Navigation") }
                    },
                )
            },
            floatingActionButton = {
                when (screen) {
                    Screen.Settings, Screen.Initiatives, is Screen.Initiative, Screen.Notebooks, Screen.Files -> {}
                    Screen.Calendar -> FloatingActionButton({ event = EventTarget.New(calendarDay) }) { Icon(Icons.Filled.Add, "New event") }
                    // A project's Files tab has its own (new notebook, upload, folder).
                    else -> if ((screen as? Screen.Project)?.let { ProjectTab.files[it.id] } != true) FloatingActionButton({ creating = true }) { Icon(Icons.Filled.Add, "New task") }
                }
            },
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
                    is Screen.Project -> ProjectTabs(s.id, s.title) { TaskList(
                        groupByDue(tasks.filter { it.projectId == s.id }, today), tasks.filter { it.projectId == s.id && it.closed },
                        state.data.projects, state.loading, { scope.launch { repo.refresh() } }, { detail = it.id }, onStatus, showProject = false,
                        header = {
                            Column {
                                InitiativeChips(s.id) { screen = Screen.Initiative(it.id, it.name) }
                                ProjectScheduleHeader(s.id, s.title)
                            }
                        },
                    ) }
                    Screen.Calendar -> CalendarScreen(calendarVersion, onOpenTask = { detail = it }, onOpenEvent = { event = it }, onDay = { calendarDay = it })
                    Screen.Triage -> TriageScreen(onOpenTask = { detail = it }, onOpenMail = { mail = it })
                    Screen.Settings -> SettingsScreen()
                    Screen.Notebooks -> NotebooksScreen()
                    Screen.Files -> FilesScreen()
                    Screen.Initiatives -> InitiativesScreen { screen = Screen.Initiative(it.id, it.name) }
                    is Screen.Initiative -> {
                        BackHandler { screen = Screen.Initiatives }
                        InitiativeDetail(s.id, onOpenProject = { id, name -> screen = Screen.Project(id, name) }, onDeleted = { screen = Screen.Initiatives })
                    }
                }
            }
        }
    }

    if (wide) {
        Row(Modifier.fillMaxSize()) {
            AnimatedVisibility(sideOpen, enter = expandHorizontally(), exit = shrinkHorizontally()) { PermanentDrawerSheet { nav() } }
            Box(Modifier.weight(1f)) { content() }
        }
    }
    else ModalNavigationDrawer(drawerContent = { ModalDrawerSheet { nav() } }, drawerState = drawer) { content() }

    if (creating) CreateTaskSheet((screen as? Screen.Project)?.id) { creating = false }
}

/** Which projects' pages show their Files tab. */
private object ProjectTab {
    val files = androidx.compose.runtime.mutableStateMapOf<UUID, Boolean>()
}

/** A project's page: its tasks, or its files (the project's own storage). */
@Composable
private fun ProjectTabs(id: UUID, name: String, tasks: @Composable () -> Unit) {
    val files = ProjectTab.files[id] == true
    Column(Modifier.fillMaxSize()) {
        androidx.compose.material3.SecondaryTabRow(if (files) 1 else 0) {
            androidx.compose.material3.Tab(!files, { ProjectTab.files[id] = false }, text = { Text("Tasks") })
            androidx.compose.material3.Tab(files, { ProjectTab.files[id] = true }, text = { Text("Files") })
        }
        Box(Modifier.weight(1f)) { if (files) ProjectFiles(id, name) else tasks() }
    }
}
