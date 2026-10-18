package com.syeyoung.assistant.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.syeyoung.assistant.data.Repo
import com.syeyoung.assistant.data.Settings
import com.syeyoung.assistant.data.TailscaleBridge
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val repo = Repo.get(context)
    val state by repo.state.collectAsStateCompat()
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(repo.settings.serverUrl) }
    var embedded by remember { mutableStateOf(repo.settings.useEmbeddedTailscale) }
    var notificationsAllowed by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notificationsAllowed = it }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Server", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Address") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ repo.settings.serverUrl = server; scope.launch { repo.refresh() } }) { Text("Save & sync") }
            OutlinedButton({ server = Settings.DEFAULT_SERVER }) { Text("Default") }
        }
        Text(
            if (state.data.fetchedAt > 0) "Last synced ${DateFormat.getDateTimeInstance().format(Date(state.data.fetchedAt))}" else "Not synced yet",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Built-in Tailscale", style = MaterialTheme.typography.titleSmall)
                Text("The app joins your tailnet by itself, so it works with the Tailscale VPN off.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(embedded, { embedded = it; repo.settings.useEmbeddedTailscale = it; scope.launch { repo.refresh() } })
        }
        if (embedded) TailscaleStatus()

        HorizontalDivider()
        Text("Reminders", style = MaterialTheme.typography.titleSmall)
        Text(
            "A notification a day and an hour before each deadline; for tasks due on a day without a time, at 9:00 the day before and on the day.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!notificationsAllowed && Build.VERSION.SDK_INT >= 33) Button({ askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow notifications") }
        else Text("Notifications are on.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The embedded node's state, with a sign-in link (or an auth key) when it needs one. */
@Composable
fun TailscaleStatus() {
    val context = LocalContext.current
    val repo = Repo.get(context)
    val bridge = TailscaleBridge.get(context)
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<String?>(null) }
    var authUrl by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf<String?>(null) }
    var key by remember { mutableStateOf(repo.settings.tailscaleAuthKey) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { bridge.start(repo.settings.serverUrl) }.onFailure { error = it.message }
        while (true) {
            state = bridge.state(); authUrl = bridge.authUrl(); name = bridge.name()
            if (state == "Running" && repo.state.value.error != null) repo.refresh()
            delay(2000)
        }
    }

    Text(
        when {
            state == "Running" -> "Connected as ${name ?: "assistant-android"}"
            state == "NeedsLogin" -> "Needs signing in to your tailnet"
            state == null -> "Starting…"
            else -> state!!
        },
        color = if (state == "Running") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (state == "NeedsLogin") {
        if (authUrl != null) Button({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))) }) { Text("Sign in to Tailscale") }
        else OutlinedButton({ scope.launch { runCatching { bridge.login(repo.settings.serverUrl) }.onFailure { error = it.message } } }) { Text("Get sign-in link") }
        Text("Or use an auth key from the Tailscale admin console:", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Auth key") })
        OutlinedButton({
            repo.settings.tailscaleAuthKey = key
            scope.launch { bridge.stop(); runCatching { bridge.start(repo.settings.serverUrl) }.onFailure { error = it.message } }
        }) { Text("Use key") }
    }
}
