package com.calmphotos.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calmphotos.data.Album
import com.calmphotos.data.MediaItem
import com.calmphotos.data.Smart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
fun CalmPhotosApp(
    vm: PhotosViewModel,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    partialAccess: Boolean = false,
) {
    val library by vm.library.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val favourites by vm.favourites.collectAsStateWithLifecycle()
    val trashed by vm.trashed.collectAsStateWithLifecycle()
    val columns by vm.columns.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(Tab.PHOTOS) }
    var openAlbum by remember { mutableStateOf<Album?>(null) }
    // The open photo is held by id, not by index into a list that can change
    // under it: binning a photo used to leave the viewer pointing at a gap.
    var viewerId by remember { mutableStateOf<Long?>(null) }
    var selection by remember { mutableStateOf<Set<Long>?>(null) }
    var showMonths by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<List<MediaItem>?>(null) }
    var confirmForever by remember { mutableStateOf<List<MediaItem>?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    // Android marshals every URI of a trash/delete request through one Binder
    // transaction, which is capped near 1 MB, so a whole-library selection has
    // to go in batches. Each batch asks for consent once.
    var queue by remember { mutableStateOf<List<List<MediaItem>>>(emptyList()) }
    var queueOp by remember { mutableStateOf(BulkOp.TRASH) }
    var awaitingConsent by remember { mutableStateOf(false) }

    val ctx = LocalContext.current
    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        awaitingConsent = false
        vm.refreshTrash()
        // Said no to a batch: abandon the rest rather than asking over and over.
        if (result.resultCode != android.app.Activity.RESULT_OK) queue = emptyList()
    }
    LaunchedEffect(queue, awaitingConsent) {
        if (awaitingConsent) return@LaunchedEffect
        val batch = queue.firstOrNull() ?: return@LaunchedEffect
        queue = queue.drop(1)
        awaitingConsent = true
        val request = when (queueOp) {
            BulkOp.TRASH -> vm.trashRequest(batch)
            BulkOp.RESTORE -> vm.restoreRequest(batch)
            BulkOp.ERASE -> vm.deleteForeverRequest(batch)
        }
        trashLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
    }

    /** Clears the sheet first, so a confirmation can never be left with a dead button. */
    fun startBulk(op: BulkOp, items: List<MediaItem>) {
        confirmDelete = null
        confirmForever = null
        selection = null
        if (items.isEmpty()) return
        viewerId = null   // whatever you were looking at is on its way out
        queueOp = op
        queue = items.chunked(BULK_BATCH)
    }
    fun trash(items: List<MediaItem>) = startBulk(BulkOp.TRASH, items)
    fun restore(items: List<MediaItem>) = startBulk(BulkOp.RESTORE, items)
    fun deleteForever(items: List<MediaItem>) = startBulk(BulkOp.ERASE, items)
    fun share(items: List<MediaItem>) {
        if (items.isEmpty()) return
        if (items.size > SHARE_LIMIT) {
            notice = "That is ${items.size} items. Android can only hand over about $SHARE_LIMIT at once, so pick fewer."
            return
        }
        val allImages = items.none { it.isVideo }
        val i = if (items.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, items[0].uri)
                else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(items.map { it.uri }))
        i.type = if (allImages) "image/*" else "*/*"
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            ctx.startActivity(Intent.createChooser(i, null))
        } catch (_: Exception) {
            notice = "Nothing on this phone offered to take them."
        }
    }

    // Keep the open album in step with the live library (deletions, new shots).
    // Smart albums aren't folders, so they pass straight through.
    val liveAlbum = openAlbum?.let { a -> if (a.kind != null) a else albums.find { it.id == a.id } }
    val inBin = liveAlbum?.kind == Smart.BIN
    val favItems = remember(library, favourites) { library.filter { it.id in favourites } }
    val videoItems = remember(library) { library.filter { it.isVideo } }

    // Bin the last photo in a folder and the folder is gone; showing its saved
    // contents would leave you tapping thumbnails of things that no longer exist.
    LaunchedEffect(openAlbum, albums, loaded) {
        val open = openAlbum
        if (open != null && open.kind == null && loaded && albums.none { it.id == open.id }) {
            openAlbum = null
            tab = Tab.ALBUMS
        }
    }

    // One list feeds the grid, the selection bar and the viewer alike. Keeping
    // two meant Select All could act on photos other than the ones on screen.
    val shown: List<MediaItem> = when (liveAlbum?.kind) {
        Smart.FAVOURITES -> favItems
        Smart.VIDEOS -> videoItems
        Smart.BIN -> trashed
        null -> when {
            liveAlbum != null -> liveAlbum.items
            tab == Tab.FAVOURITES -> favItems
            else -> library
        }
    }
    // Grouping a whole library by day is real work, and doing it inside layout
    // showed as a stutter once the library grew. It happens off the main thread
    // now, so the grid keeps the previous grouping for the frame it takes.
    val entries by produceState(initialValue = emptyList<Entry>(), shown) {
        value = withContext(Dispatchers.Default) { groupByDay(shown) }
    }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    // A selection only ever means the list in front of you, so changing that
    // list drops it. Without this it followed you out and retargeted itself.
    LaunchedEffect(tab, liveAlbum?.id) {
        selection = null
        gridState.scrollToItem(0)
    }

    if (!hasPermission) { PermissionScreen(onRequestPermission); return }

    // Without this, back closed the whole app from anywhere: from an open photo,
    // from inside an album, mid-selection. Each layer now peels off in turn.
    val canGoBack = notice != null || confirmForever != null || confirmDelete != null ||
        showMonths || showMore || viewerId != null || selection != null ||
        openAlbum != null || tab != Tab.PHOTOS
    BackHandler(enabled = canGoBack) {
        when {
            notice != null -> notice = null
            confirmForever != null -> confirmForever = null
            confirmDelete != null -> confirmDelete = null
            showMonths -> showMonths = false
            showMore -> showMore = false
            viewerId != null -> viewerId = null
            selection != null -> selection = null
            openAlbum != null -> { openAlbum = null; tab = Tab.ALBUMS }
            else -> tab = Tab.PHOTOS
        }
    }

    Box(Modifier.fillMaxSize().background(Ink.white)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            if (partialAccess) {
                Row(
                    Modifier.fillMaxWidth().background(Ink.paper).padding(horizontal = PagePadding, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Only the photos you picked are visible, not your whole library.",
                        style = Type.label,
                        modifier = Modifier.weight(1f),
                    )
                    Word("Choose", onClick = onRequestPermission)
                }
                Hairline()
            }
            Box(Modifier.weight(1f)) {
                when {
                    tab == Tab.ALBUMS && liveAlbum == null -> AlbumsScreen(
                        albums = albums,
                        smart = listOfNotNull(
                            favItems.takeIf { it.isNotEmpty() }?.let { Album(-1, "Favourites", it, Smart.FAVOURITES) },
                            videoItems.takeIf { it.isNotEmpty() }?.let { Album(-2, "Videos", it, Smart.VIDEOS) },
                            trashed.takeIf { it.isNotEmpty() }?.let { Album(-3, "Recently deleted", it, Smart.BIN) },
                        ),
                        onOpen = { a -> openAlbum = a },
                        header = { TitleBlock("Albums", "${albums.size} folder${if (albums.size == 1) "" else "s"}") },
                    )
                    else -> {
                        val list = shown
                        PhotosGrid(
                            entries = entries,
                            columns = columns,
                            favourites = favourites,
                            selection = selection,
                            state = gridState,
                            onTap = { item ->
                                val sel = selection
                                if (sel != null) selection = if (item.id in sel) sel - item.id else sel + item.id
                                else viewerId = item.id
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
                                val sub = if (inBin) "Kept for 30 days, then gone for good" else buildString {
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
            if (sel != null && inBin) {
                BinSelectionBar(
                    count = sel.size,
                    onRestore = { restore(shown.filter { it.id in sel }) },
                    onForever = { confirmForever = shown.filter { it.id in sel } },
                    onAll = { selection = shown.map { it.id }.toSet() },
                    onCancel = { selection = null },
                )
            } else if (sel != null) {
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
        viewerId?.let { id ->
            val index = shown.indexOfFirst { it.id == id }
            if (index < 0) {
                // The photo went to the bin, or the list moved on without it.
                LaunchedEffect(id) { viewerId = null }
            } else Viewer(
                items = shown,
                startIndex = index,
                favourites = favourites,
                onClose = { viewerId = null },
                onToggleFavourite = { vm.toggleFavourite(it.id) },
                onShare = { share(listOf(it)) },
                onDelete = { confirmDelete = listOf(it) },
                inBin = inBin,
                onRestore = { restore(listOf(it)) },
                onDeleteForever = { confirmForever = listOf(it) },
            )
        }
        confirmDelete?.takeIf { it.isNotEmpty() }?.let { items ->
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
        confirmForever?.takeIf { it.isNotEmpty() }?.let { items ->
            Sheet(onDismiss = { confirmForever = null }) {
                Text(
                    "Erase ${items.size} ${if (items.size == 1) "item" else "items"} for good? This cannot be undone.",
                    style = Type.body,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedWord("Keep", Modifier.weight(1f)) { confirmForever = null }
                    FilledWord("Erase", Modifier.weight(1f)) { deleteForever(items) }
                }
            }
        }
        notice?.let { text ->
            Sheet(onDismiss = { notice = null }) {
                Text(text, style = Type.body)
                Spacer(Modifier.height(16.dp))
                FilledWord("Right you are", Modifier.fillMaxWidth()) { notice = null }
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

/** Roughly how many URIs fit in one Binder transaction with room to spare. */
private const val BULK_BATCH = 500

/** Android's chooser has the same limit; past this it simply fails. */
private const val SHARE_LIMIT = 200

private enum class BulkOp { TRASH, RESTORE, ERASE }

@Composable
private fun BinSelectionBar(count: Int, onRestore: () -> Unit, onForever: () -> Unit, onAll: () -> Unit, onCancel: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Ink.paper).padding(horizontal = 10.dp, vertical = 4.dp).navigationBarsPadding(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (count == 0) "Tap to select" else "$count selected",
            style = Type.word,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        Word("All", quiet = true, onClick = onAll)
        // Hidden rather than greyed: on E-Ink a disabled word is indistinguishable
        // from a quiet one, so it just looked broken.
        if (count > 0) {
            Word("Restore", onClick = onRestore)
            Word("Erase", onClick = onForever)
        }
        Word("Cancel", quiet = true, onClick = onCancel)
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
        if (count > 0) {
            Word("Share", onClick = onShare)
            Word("Delete", onClick = onDelete)
        }
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
