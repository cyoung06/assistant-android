package com.syeyoung.assistant.notebook

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The bar across the top, as in Noteshelf: back, the title, the page (tap for quick pages), bookmark, search, new page, more. */
@Composable
fun NotebookHeader(ui: ToolbarState, title: String, onBack: () -> Unit, onSearch: (String) -> Unit, onHit: (Int) -> Unit, onChange: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().height(57.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ if (ui.searching) { ui.searching = false; onSearch("") } else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, if (ui.searching) "Close search" else "Back", Modifier.size(26.dp)) }
            if (ui.searching) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                TextField(
                    ui.query, { ui.query = it; onSearch(it) }, Modifier.weight(1f).focusRequester(focus),
                    placeholder = { Text("Search this PDF") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { if (ui.hits.isNotEmpty()) onHit((ui.hitIndex + 1) % ui.hits.size) }),
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                )
                if (ui.hits.isNotEmpty()) {
                    Text("${ui.hitIndex + 1}/${ui.hits.size}", Modifier.padding(horizontal = 6.dp), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton({ onHit((ui.hitIndex - 1 + ui.hits.size) % ui.hits.size) }) { Icon(Icons.Filled.KeyboardArrowUp, "Previous match", Modifier.size(26.dp)) }
                    IconButton({ onHit((ui.hitIndex + 1) % ui.hits.size) }) { Icon(Icons.Filled.KeyboardArrowDown, "Next match", Modifier.size(26.dp)) }
                }
                return@Row
            }
            Text(title.removeSuffix(".pdf"), Modifier.weight(1f), fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (ui.status.isNotEmpty()) Text(ui.status, Modifier.padding(horizontal = 8.dp), fontSize = 14.sp, color = MaterialTheme.colorScheme.outline)
            Text(
                if (ui.pages == 0) "…" else "${ui.page + 1} / ${ui.pages}",
                Modifier.clickable { ui.quickPages = !ui.quickPages }.padding(horizontal = 13.dp, vertical = 10.dp),
                fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val marked = ui.page in ui.bookmarks
            IconButton({ ui.bookmarks = if (marked) ui.bookmarks - ui.page else ui.bookmarks + ui.page; onChange() }) {
                Icon(if (marked) Icons.Filled.Star else Icons.Filled.StarBorder, if (marked) "Remove bookmark" else "Bookmark this page", Modifier.size(26.dp), tint = if (marked) Color(0xFFF2B600) else MaterialTheme.colorScheme.onSurface)
            }
            IconButton({ ui.searching = true }) { Icon(Icons.Filled.Search, "Search", Modifier.size(26.dp)) }
            IconButton({ ui.addingPage = true }) { Icon(Icons.Filled.Add, "New page", Modifier.size(26.dp)) }
            var open by remember { mutableStateOf(false) }
            Box {
                IconButton({ open = true }) { Icon(Icons.Filled.MoreVert, "More", Modifier.size(26.dp)) }
                DropdownMenu(open, { open = false }) {
                    DropdownMenuItem({ Text("Pages and bookmarks") }, { open = false; ui.browsing = true })
                    DropdownMenuItem(
                        { Text(if (ui.mode == ScrollMode.PAGED) "Scroll continuously" else "Turn page by page") },
                        { ui.mode = if (ui.mode == ScrollMode.PAGED) ScrollMode.CONTINUOUS else ScrollMode.PAGED; open = false; onChange() },
                    )
                    DropdownMenuItem({ Text("Delete this page") }, { open = false; ui.confirmDelete = true }, enabled = ui.pages > 1)
                }
            }
        }
    }
}

/** Every page as a thumbnail (or only the bookmarked ones); tap one to go there, the star to (un)bookmark it. */
@Composable
fun PageBrowser(ui: ToolbarState, thumbnail: suspend (Int) -> Bitmap?, onGo: (Int) -> Unit, onChange: () -> Unit) {
    var onlyMarked by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton({ ui.browsing = false }) { Icon(Icons.Filled.Close, "Close") }
                FilterChip(!onlyMarked, { onlyMarked = false }, { Text("All pages") })
                FilterChip(onlyMarked, { onlyMarked = true }, { Text("Bookmarked (${ui.bookmarks.size})") })
            }
            HorizontalDivider()
            val pages = (0 until ui.pages).filter { !onlyMarked || it in ui.bookmarks }
            if (pages.isEmpty()) Text("No bookmarked pages yet. Tap ☆ at the top to bookmark the page you're on.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyVerticalGrid(GridCells.Adaptive(132.dp), Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(pages, key = { it }) { i ->
                    var bmp by remember(i) { mutableStateOf<Bitmap?>(null) }
                    LaunchedEffect(i) { bmp = thumbnail(i) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(bmp?.let { it.width.toFloat() / it.height } ?: 0.707f)
                                .background(Color.White)
                                .border(if (i == ui.page) 2.dp else 1.dp, if (i == ui.page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                                .clickable { ui.browsing = false; onGo(i) },
                        ) {
                            bmp?.let { Image(it.asImageBitmap(), "Page ${i + 1}", Modifier.fillMaxSize()) }
                            val marked = i in ui.bookmarks
                            Icon(
                                if (marked) Icons.Filled.Star else Icons.Filled.StarBorder, if (marked) "Remove bookmark" else "Bookmark",
                                Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clickable { ui.bookmarks = if (marked) ui.bookmarks - i else ui.bookmarks + i; onChange() },
                                tint = if (marked) Color(0xFFF2B600) else Color(0x66000000),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("${i + 1}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Choosing a new page: its style (with a small picture of each) and where it goes. */
@Composable
fun AddPageDialog(ui: ToolbarState, onAdd: (PageStyle, atEnd: Boolean) -> Unit) {
    var style by remember { mutableStateOf(ui.pageStyle) }
    var atEnd by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { ui.addingPage = false },
        title = { Text("New page") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                StylePicker(style) { style = it }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!atEnd, { atEnd = false }, { Text("After page ${ui.page + 1}") })
                    FilterChip(atEnd, { atEnd = true }, { Text("At the end") })
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton({ ui.addingPage = false; ui.pageStyle = style; onAdd(style, atEnd) }) { Text("Add page") } },
        dismissButton = { androidx.compose.material3.TextButton({ ui.addingPage = false }) { Text("Cancel") } },
    )
}

/** The page styles side by side, each as a tiny page. */
@Composable
fun StylePicker(selected: PageStyle, onPick: (PageStyle) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PageStyle.entries.forEach { s ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onPick(s) }) {
                androidx.compose.foundation.Canvas(
                    Modifier.size(width = 56.dp, height = 78.dp).background(Color.White)
                        .border(if (s == selected) 3.dp else 1.dp, if (s == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                ) {
                    val step = 6.dp.toPx()
                    val ink = Color(0xFFB8BCC8)
                    when (s) {
                        PageStyle.DOTS -> { var y = step; while (y < size.height) { var x = step; while (x < size.width) { drawCircle(ink, 0.9f * density, androidx.compose.ui.geometry.Offset(x, y)); x += step }; y += step } }
                        PageStyle.GRID -> {
                            var x = step; while (x < size.width) { drawLine(ink, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), 0.6f * density); x += step }
                            var y = step; while (y < size.height) { drawLine(ink, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 0.6f * density); y += step }
                        }
                        PageStyle.LINES -> { var y = step * 1.6f; while (y < size.height) { drawLine(ink, androidx.compose.ui.geometry.Offset(step / 2, y), androidx.compose.ui.geometry.Offset(size.width - step / 2, y), 0.7f * density); y += step * 1.3f } }
                        PageStyle.BLANK -> {}
                    }
                }
                Text(s.label, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** Noteshelf-style quick pages: a strip of thumbnails along the bottom; tap one to go there. */
@Composable
fun QuickPages(ui: ToolbarState, thumbnail: suspend (Int) -> Bitmap?, onGo: (Int) -> Unit, onAll: () -> Unit) {
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(Unit) { list.scrollToItem((ui.page - 2).coerceAtLeast(0)) }
    Surface(Modifier.fillMaxWidth().height(176.dp), color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 8.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.lazy.LazyRow(
                Modifier.weight(1f), state = list,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(count = ui.pages, key = { it }) { i ->
                    var bmp by remember(i) { mutableStateOf<Bitmap?>(null) }
                    LaunchedEffect(i) { bmp = thumbnail(i) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onGo(i) }) {
                        Box(
                            Modifier.height(128.dp).aspectRatio(bmp?.let { it.width.toFloat() / it.height } ?: 0.707f).background(Color.White)
                                .border(if (i == ui.page) 3.dp else 1.dp, if (i == ui.page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            bmp?.let { Image(it.asImageBitmap(), "Page ${i + 1}", Modifier.fillMaxSize()) }
                            if (i in ui.bookmarks) Icon(Icons.Filled.Star, "Bookmarked", Modifier.align(Alignment.TopEnd).padding(2.dp).size(18.dp), tint = Color(0xFFF2B600))
                        }
                        Text("${i + 1}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Column(Modifier.padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.material3.TextButton(onAll) { Text("All pages") }
                androidx.compose.material3.TextButton({ ui.quickPages = false }) { Text("Close") }
            }
        }
    }
}

/** Search results under the header: page, and the line each match is on. */
@Composable
fun SearchResults(ui: ToolbarState, onPick: (Int) -> Unit) {
    if (ui.query.isBlank()) return
    Surface(Modifier.width(380.dp).heightIn(max = 420.dp), color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 6.dp, shape = androidx.compose.foundation.shape.RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)) {
        androidx.compose.foundation.lazy.LazyColumn {
            item {
                Text(
                    when {
                        ui.searchIndexing -> "Reading the PDF's text…"
                        ui.hits.isEmpty() -> "No matches in the PDF's text. (Handwriting isn't searchable yet.)"
                        else -> "${ui.hits.size} match${if (ui.hits.size == 1) "" else "es"}"
                    },
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(ui.hits.size) { k ->
                val h = ui.hits[k]
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(k) }
                        .background(if (k == ui.hitIndex) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text("p. ${h.page + 1}", Modifier.width(52.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    Text(h.snippet, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
