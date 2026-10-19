package com.syeyoung.assistant.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.syeyoung.assistant.api.models.MailMessage
import com.syeyoung.assistant.data.Repo
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A mail (from a mid: link), read from the mailbox through the server: Outlook can't open one by its Message-ID. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailReader(messageId: String, onBack: () -> Unit) {
    val repo = Repo.get(LocalContext.current)
    var mail by remember(messageId) { mutableStateOf<MailMessage?>(null) }
    var error by remember(messageId) { mutableStateOf<String?>(null) }
    LaunchedEffect(messageId) { runCatching { repo.mailMessage(messageId) }.onSuccess { mail = it }.onFailure { error = it.message ?: "Couldn't load the mail" } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mail") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            val m = mail
            when {
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                m == null -> Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> SelectionContainer {
                    Column {
                        Text(m.subject.ifBlank { "(no subject)" }, style = MaterialTheme.typography.titleLarge)
                        Text(m.from, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
                        m.received?.let {
                            Text(
                                OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE, MMM d HH:mm", Locale.ENGLISH)),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider(Modifier.padding(vertical = 12.dp))
                        Text(m.text?.ifBlank { null } ?: "(This mail has no plain-text part.)", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
