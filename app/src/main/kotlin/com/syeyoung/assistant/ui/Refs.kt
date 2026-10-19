package com.syeyoung.assistant.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.syeyoung.assistant.api.models.Ref
import com.syeyoung.assistant.data.Repo
import com.syeyoung.assistant.data.plmsWebUrl
import com.syeyoung.assistant.data.taskOf
import com.syeyoung.assistant.data.refMarkdown
import com.syeyoung.assistant.data.refParts
import com.syeyoung.assistant.data.value
import kotlinx.coroutines.delay

/** What a reference link points to, as a small marker before its title. */
fun refMarker(url: String): String = when {
    url.startsWith("mid:") || "#mail/" in url -> "✉"
    url.startsWith("plms:") || "plms.postech.ac.kr" in url -> "▣"
    "/tasks/project/" in url || "#tasks/project/" in url -> "▤"
    "/task/" in url || "#task/" in url -> "◯"
    "/event/" in url || "#event/" in url -> "▦"
    else -> "↗"
}

/**
 * Text with its references as tappable, highlighted spans. Links to tasks open them here
 * ([onTask]); everything else opens in the browser.
 */
@Composable
fun RefText(markdown: String, style: TextStyle = MaterialTheme.typography.bodyMedium, onTask: (String) -> Unit = {}) {
    val context = LocalContext.current
    val accent = MaterialTheme.colorScheme.primary
    val text: AnnotatedString = buildAnnotatedString {
        for (p in refParts(markdown)) {
            if (p.url == null) append(p.text)
            else withLink(LinkAnnotation.Clickable(p.url, TextLinkStyles(SpanStyle(color = accent, fontWeight = FontWeight.Medium))) { openRef(context, p.url, onTask) }) {
                append("${refMarker(p.url)} ${p.text}")
            }
        }
    }
    Text(text, style = style)
}

/** Tasks open here ([onTask]); mail in the app's reader; PLMS on its site; everything else in the browser. */
fun openRef(context: Context, url: String, onTask: (String) -> Unit) {
    taskOf(url)?.let { return onTask(it.toString()) }
    val intent = when {
        // The app handles mid: itself (MainActivity shows the mail).
        url.startsWith("mid:") -> Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(context.packageName)
        else -> Intent(Intent.ACTION_VIEW, Uri.parse(plmsWebUrl(url) ?: url))
    }
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** `@` and up to two more words before the cursor, at the start or after a space. */
private val MENTION = Regex("""(?:^|\s)@([^\s@\[\]()]*(?: [^\s@\[\]()]+){0,2})$""")

/**
 * A text field where `@` searches everything that can be referenced (tasks, PLMS files and
 * notices, mail, …) and inserts it as a Markdown link.
 */
@Composable
fun MentionField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
) {
    val context = LocalContext.current
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    if (field.text != value) field = field.copy(text = value, selection = TextRange(value.length))
    val match = MENTION.find(field.text.take(field.selection.start))
    val query = match?.groupValues?.get(1)
    var results by remember { mutableStateOf(emptyList<Ref>()) }

    LaunchedEffect(query) {
        if (query == null) return@LaunchedEffect run { results = emptyList() }
        delay(200)
        results = runCatching { Repo.get(context).api().refs.search(query, 8).value() }.getOrDefault(emptyList())
    }

    Column(modifier) {
        OutlinedTextField(
            value = field,
            onValueChange = { field = it; onValueChange(it.text) },
            modifier = Modifier.fillMaxWidth(),
            label = label?.let { { Text(it) } },
            singleLine = singleLine,
            minLines = minLines,
        )
        if (query != null && results.isNotEmpty()) {
            Surface(tonalElevation = 3.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Column {
                    results.forEachIndexed { i, r ->
                        if (i > 0) HorizontalDivider()
                        Column(
                            Modifier.fillMaxWidth().clickable {
                                val caret = field.selection.start
                                val start = caret - (query.length + 1)
                                val insert = refMarkdown(r.title, r.url) + " "
                                val text = field.text.substring(0, start) + insert + field.text.substring(caret)
                                field = TextFieldValue(text, TextRange(start + insert.length))
                                onValueChange(text)
                                results = emptyList()
                            }.padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text("${refMarker(r.url)} ${r.title}", style = MaterialTheme.typography.bodyMedium)
                            Text(listOfNotNull(r.kind.value.replace('_', ' '), r.context).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
