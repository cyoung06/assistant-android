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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
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

/** The bar across the top, as in Noteshelf: back, the title, the page (tap for all pages), bookmark, add a page, more. */
@Composable
fun NotebookHeader(ui: ToolbarState, title: String, onBack: () -> Unit, onAddPage: () -> Unit, onChange: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", Modifier.size(20.dp)) }
            Text(title.removeSuffix(".pdf"), Modifier.weight(1f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (ui.status.isNotEmpty()) Text(ui.status, Modifier.padding(horizontal = 6.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
            Text(
                if (ui.pages == 0) "…" else "${ui.page + 1} / ${ui.pages}",
                Modifier.clickable { ui.browsing = true }.padding(horizontal = 10.dp, vertical = 8.dp),
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val marked = ui.page in ui.bookmarks
            IconButton({ ui.bookmarks = if (marked) ui.bookmarks - ui.page else ui.bookmarks + ui.page; onChange() }) {
                Icon(if (marked) Icons.Filled.Star else Icons.Filled.StarBorder, if (marked) "Remove bookmark" else "Bookmark this page", Modifier.size(20.dp), tint = if (marked) Color(0xFFF2B600) else MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onAddPage) { Icon(Icons.Filled.Add, "Add a page after this one", Modifier.size(20.dp)) }
            var open by remember { mutableStateOf(false) }
            Box {
                IconButton({ open = true }) { Icon(Icons.Filled.MoreVert, "More", Modifier.size(20.dp)) }
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
