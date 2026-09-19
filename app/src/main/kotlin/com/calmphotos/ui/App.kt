package com.calmphotos.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calmphotos.data.Album
import com.calmphotos.data.MediaItem
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private enum class Tab(val label: String) { PHOTOS("Photos"), ALBUMS("Albums"), FAVOURITES("Favourites") }

/**
 * The whole app on one screen: a wall of photos with day headings, Albums and
 * Favourites a word away along the bottom, and a full-screen viewer over the
 * top. No icons, no animation, nothing that leaves the phone.
 */
@Composable
fun CalmPhotosApp(vm: PhotosViewModel, hasPermission: Boolean, onRequestPermission: () -> Unit) {
    val library by vm.library.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val favourites by vm.favourites.collectAsStateWithLifecycle()
    val columns by vm.columns.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(Tab.PHOTOS) }
    var openAlbum by remember { mutableStateOf<Album?>(null) }
    var viewer by remember { mutableStateOf<Pair<List<MediaItem>, Int>?>(null) }
    var selection by remember { mutableStateOf<Set<Long>?>(null) }
    var showMonths by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<List<MediaItem>?>(null) }

    val ctx = LocalContext.current
    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { }
    fun trash(items: List<MediaItem>) {
        if (items.isEmpty()) return
        trashLauncher.launch(IntentSenderRequest.Builder(vm.trashRequest(items).intentSender).build())
        selection = null
        confirmDelete = null
    }
    fun share(items: List<MediaItem>) {
        if (items.isEmpty()) return
        val allImages = items.none { it.isVideo }
        val i = if (items.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, items[0].uri)
                else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(items.map { it.uri }))
        i.type = if (allImages) "image/*" else "*/*"
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, null))
    }

    // Keep the open album in step with the live library (deletions, new shots).
    // Smart albums (negative ids) aren't folders, so they pass straight through.
    val liveAlbum = openAlbum?.let { a -> if (a.id < 0) a else (albums.find { it.id == a.id } ?: a) }
    val favItems = remember(library, favourites) { library.filter { it.id in favourites } }
    val shown: List<MediaItem> = when {
        liveAlbum != null -> liveAlbum.items
        tab == Tab.FAVOURITES -> favItems
        else -> library
    }
    val entries = remember(shown) { groupByDay(shown) }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(tab, liveAlbum?.id) { gridState.scrollToItem(0) }

    if (!hasPermission) { PermissionScreen(onRequestPermission); return }

    Box(Modifier.fillMaxSize().background(Ink.white)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Box(Modifier.weight(1f)) {
                when {
                    tab == Tab.ALBUMS && liveAlbum == null -> AlbumsScreen(
                        albums = albums,
                        smart = listOfNotNull(
                            if (favItems.isNotEmpty()) Album(-1, "Favourites", favItems) else null,
                            library.filter { it.isVideo }.takeIf { it.isNotEmpty() }?.let { Album(-2, "Videos", it) },
                        ),
                        onOpen = { a -> openAlbum = a },
                        header = { TitleBlock("Albums", "${albums.size} folder${if (albums.size == 1) "" else "s"}") },
                    )
                    else -> {
                        val smartOpen = openAlbum?.takeIf { it.id < 0 }
                        val list = if (smartOpen != null) (if (smartOpen.id == -1L) favItems else library.filter { it.isVideo }) else shown
                        val ents = if (smartOpen != null) remember(list) { groupByDay(list) } else entries
                        PhotosGrid(
                            entries = ents,
                            columns = columns,
                            favourites = favourites,
                            selection = selection,
                            state = gridState,
                            onTap = { item ->
                                val sel = selection
                                if (sel != null) selection = if (item.id in sel) sel - item.id else sel + item.id
                                else viewer = list to list.indexOf(item)
                            },
                            onLongPress = { item -> selection = (selection ?: emptySet()) + item.id },
                            leading = {
                                val title = when {
                                    openAlbum != null -> openAlbum!!.name
                                    tab == Tab.FAVOURITES -> "Favourites"
                                    else -> "Photos"
                                }
                                val photos = list.count { !it.isVideo }
                                val videos = list.size - photos
                                val span = list.lastOrNull()?.takenAt?.toLocalDate()
                                val sub = buildString {
                                    if (photos > 0) append("$photos photo${if (photos == 1) "" else "s"}")
                                    if (videos > 0) { if (isNotEmpty()) append("  ·  "); append("$videos video${if (videos == 1) "" else "s"}") }
                                    if (span != null && list.size > 1) {
                                        val years = ChronoUnit.YEARS.between(span, LocalDate.now())
                                        append("  ·  since ${monthLabel(span)}")
                                        if (years >= 1) append(", $years year${if (years == 1L) "" else "s"}")
                                    }
                                    if (isEmpty() && loaded) append(if (tab == Tab.FAVOURITES) "Tap ♡ Favourite on any photo to keep it here." else "No photos yet.")
                                }
                                TitleBlock(
                                    title = title,
                                    subtitle = sub,
                                    back = if (openAlbum != null) "← Albums" else null,
                                    onBack = { openAlbum = null; tab = Tab.ALBUMS },
                                    words = if (list.isNotEmpty()) listOf(
                                        "Months" to { showMonths = true },
                                        "Select" to { selection = emptySet() },
                                    ) else emptyList(),
                                    onTitleTap = { scope.launch { gridState.animateScrollToItem(0) } },
                                )
                            },
                            trailing = { if (list.isNotEmpty()) Colophon(list.size) },
                        )
                    }
                }
            }
            Hairline()
            val sel = selection
            if (sel != null) {
                SelectionBar(
                    count = sel.size,
                    onShare = { share(shown.filter { it.id in sel }) },
                    onDelete = { confirmDelete = shown.filter { it.id in sel } },
                    onAll = { selection = shown.map { it.id }.toSet() },
                    onCancel = { selection = null },
                )
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = PagePadding).navigationBarsPadding(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Tab.entries.forEach { t ->
                            TabWord(t.label, selected = tab == t && (t != Tab.ALBUMS || liveAlbum == null) || (t == Tab.ALBUMS && openAlbum != null)) {
                                tab = t; openAlbum = null
                            }
                        }
                    }
                    Word("More", quiet = true) { showMore = true }
                }
            }
        }

        // ── Overlays ─────────────────────────────────────────────────────
        confirmDelete?.let { items ->
            Sheet(onDismiss = { confirmDelete = null }) {
                Text(
                    "Move ${items.size} ${if (items.size == 1) "item" else "items"} to the bin? The system keeps binned photos for 30 days (Files → Bin) before removing them.",
                    style = Type.body,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedWord("Keep", Modifier.weight(1f)) { confirmDelete = null }
                    FilledWord("Move to bin", Modifier.weight(1f)) { trash(items) }
                }
            }
        }
        if (showMonths) {
            MonthsSheet(entries = entries, onDismiss = { showMonths = false }) { index ->
                showMonths = false
                scope.launch { gridState.scrollToItem(index + 1) }  // +1 for the leading title block
            }
        }
        if (showMore) {
            Sheet(onDismiss = { showMore = false }) {
                Text("Photos across", style = Type.label)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3, 4, 5).forEach { n -> Chip("$n", columns == n) { vm.setColumns(n); showMore = false } }
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    "CalmPhotos reads the photos already on this phone. Nothing is uploaded, indexed elsewhere or shared unless you tap Share.",
                    style = Type.body.copy(color = Ink.grey),
                )
            }
        }
        viewer?.let { (list, index) ->
            Viewer(
                items = list.filter { it.id in library.map { l -> l.id }.toSet() }.ifEmpty { list },
                startIndex = index,
                favourites = favourites,
                onClose = { viewer = null },
                onToggleFavourite = { vm.toggleFavourite(it.id) },
                onShare = { share(listOf(it)) },
                onDelete = { confirmDelete = listOf(it) },
            )
        }
    }
}

@Composable
private fun TitleBlock(
    title: String,
    subtitle: String,
    back: String? = null,
    onBack: () -> Unit = {},
    words: List<Pair<String, () -> Unit>> = emptyList(),
    onTitleTap: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(start = PagePadding, end = PagePadding - 8.dp, top = 10.dp)) {
        if (back != null) {
            Text(back, style = Type.wordQuiet, modifier = Modifier.clickable(onClick = onBack).padding(vertical = 4.dp))
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, style = Type.title, modifier = Modifier.weight(1f).clickable(onClick = onTitleTap))
            words.forEach { (w, act) -> Word(w, onClick = act) }
        }
        if (subtitle.isNotEmpty()) Text(subtitle, style = Type.subtitle, modifier = Modifier.padding(top = 2.dp, end = 8.dp))
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun SelectionBar(count: Int, onShare: () -> Unit, onDelete: () -> Unit, onAll: () -> Unit, onCancel: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Ink.paper).padding(horizontal = 10.dp, vertical = 4.dp).navigationBarsPadding(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (count == 0) "Tap photos to select" else "$count selected",
            style = Type.word,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        Word("All", quiet = true, onClick = onAll)
        Word("Share", enabled = count > 0, onClick = onShare)
        Word("Delete", enabled = count > 0, onClick = onDelete)
        Word("Cancel", quiet = true, onClick = onCancel)
    }
}

/** Bottom sheet drawn by hand: a scrim and a white panel, no animation. */
@Composable
private fun Sheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Ink.scrim).clickable(onClick = onDismiss), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().background(Ink.white).clickable(enabled = false) {}
                .padding(horizontal = PagePadding, vertical = 20.dp).navigationBarsPadding(),
            content = content,
        )
    }
}

/** Jump straight to a month: the e-ink answer to a fast scroller. */
@Composable
private fun MonthsSheet(entries: List<Entry>, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val months = remember(entries) {
        val out = ArrayList<Triple<String, Int, Int>>()   // label, first index, count
        var key: Pair<Int, Int>? = null
        entries.forEachIndexed { i, e ->
            if (e is Entry.Header) {
                val k = e.date.year to e.date.monthValue
                if (k != key) { key = k; out += Triple(monthLabel(e.date), i, 0) }
                val last = out.last(); out[out.lastIndex] = last.copy(third = last.third + e.count)
            }
        }
        out
    }
    Box(Modifier.fillMaxSize().background(Ink.white).statusBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Months", style = Type.title.copy(fontSize = 24.sp), modifier = Modifier.weight(1f).padding(start = 8.dp))
                Word("Close", onClick = onDismiss)
            }
            Hairline()
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(months) { (label, index, count) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(index) }.padding(horizontal = PagePadding, vertical = 14.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(label, style = Type.dayHeader, modifier = Modifier.weight(1f))
                        Text("$count", style = Type.dayMeta)
                    }
                    Hairline()
                }
            }
        }
    }
}

@Composable
private fun Colophon(count: Int) {
    Column(Modifier.fillMaxWidth().padding(top = 36.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("❧", style = Type.dayHeader)
        Spacer(Modifier.height(6.dp))
        Text("$count ${if (count == 1) "picture" else "pictures"}, all here on the phone.", style = Type.subtitle, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Ink.white).statusBarsPadding().padding(PagePadding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Photos", style = Type.title)
        Spacer(Modifier.height(10.dp))
        Text(
            "CalmPhotos shows the pictures already on this phone, newest first. It needs permission to read them. Nothing is uploaded anywhere.",
            style = Type.body, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        FilledWord("Allow access to photos", onClick = onRequest)
    }
}

private val Int.sp get() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)
