package com.syeyoung.assistant.data

import java.net.URLDecoder
import java.util.UUID

// References in text are links. The app's own things are its pages ("<origin>/task/<id>"; older
// text has "#task/<id>"). Mail isn't ours: "mid:<Message-ID>" (RFC 2392). PLMS things are
// "plms:course/…", "plms:mod/<module>/…" or "plms:post/…".

const val PLMS_BASE = "https://plms.postech.ac.kr"

private val TASK = Regex("""[/#]task/([0-9a-f-]{36})""")

/** The task a link points to, if it's one of ours. */
fun taskOf(url: String): UUID? = if (url.startsWith("mid:") || url.startsWith("plms:")) null
else TASK.find(url)?.let { runCatching { UUID.fromString(it.groupValues[1]) }.getOrNull() }

/** The Message-ID of a `mid:` link. */
fun messageIdOf(url: String): String? = url.takeIf { it.startsWith("mid:") }?.let { URLDecoder.decode(it.removePrefix("mid:"), Charsets.UTF_8) }

/** PLMS's page for a `plms:` link. */
fun plmsWebUrl(link: String): String? {
    val parts = link.removePrefix("plms:").split('/')
    return when {
        !link.startsWith("plms:") -> null
        parts.size == 2 && parts[0] == "course" -> "$PLMS_BASE/course/view.php?id=${parts[1]}"
        parts.size == 3 && parts[0] == "mod" -> "$PLMS_BASE/mod/${parts[1]}/view.php?id=${parts[2]}"
        parts.size == 3 && parts[0] == "post" -> "$PLMS_BASE/mod/ubboard/article.php?id=${parts[1]}&bwid=${parts[2]}"
        else -> null
    }
}
