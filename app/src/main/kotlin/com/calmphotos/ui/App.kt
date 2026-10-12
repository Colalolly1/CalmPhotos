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
import androidx.compose.foundation.lazy.grid.LazyGridState
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
import com.calmphotos.data.Smart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private enum class Tab(val label: String) { PHOTOS("Photos"), ALBUMS("Albums"), FAVOURITES("Favourites") }

/**
 * Volume keys turn pages. The activity hears the key; the screen in front says
 * what a page is. False hands the key back to the volume.
 */
class PageTurner {
    internal var app: ((forward: Boolean) -> Boolean)? = null
    internal var viewer: ((forward: Boolean) -> Boolean)? = null
    fun turn(forward: Boolean): Boolean = app?.invoke(forward) ?: false
}

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
    pages: PageTurner = remember { PageTurner() },
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
    // under it, and it follows every swipe.
    var viewerId by remember { mutableStateOf<Long?>(null) }
    // Where that photo last sat, so when it leaves the list (binned, restored,
    // unfavourited) the viewer shows the one that took its place.
    var viewerIndex by remember { mutableIntStateOf(0) }
    var viewerInfo by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<Set<Long>?>(null) }
    var showMonths by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    // Only erasing for good asks first. A move to the bin is already asked by
    // Android's own dialog, and it can be undone.
    var confirmForever by remember { mutableStateOf<List<MediaItem>?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    // Once a session, after the first move to the bin, a quiet line saying where it went.
    var binNote by remember { mutableStateOf(false) }
    var binNoteShown by remember { mutableStateOf(false) }
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
        else if (queueOp == BulkOp.TRASH && !binNoteShown) { binNote = true; binNoteShown = true }
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
        confirmForever = null
        selection = null
        binNote = false
        if (items.isEmpty()) return
        // The viewer stays open: when the item leaves the list it moves on to the next.
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

    val albumItems: List<MediaItem> = when (liveAlbum?.kind) {
        Smart.FAVOURITES -> favItems
        Smart.VIDEOS -> videoItems
        Smart.BIN -> trashed
        null -> liveAlbum?.items ?: emptyList()
    }
    // One list feeds the grid, the selection bar and the viewer alike. Keeping
    // two meant Select All could act on photos other than the ones on screen.
    val shown: List<MediaItem> = when {
        liveAlbum != null -> albumItems
        tab == Tab.FAVOURITES -> favItems
        else -> library
    }
    // Each list keeps its own day grouping and its own place. A grid handed
    // another list's rows, even for a frame, would lose its place, so the
    // Photos wall is never sent back to the top by a change of tab. An album
    // starts at the top each time it is opened.
    val photoEntries = rememberDayGroups(library, owner = Tab.PHOTOS)
    val favEntries = rememberDayGroups(favItems, owner = Tab.FAVOURITES)
    val albumEntries = rememberDayGroups(albumItems, owner = liveAlbum?.let { it.kind to it.id })
    val entries = when {
        liveAlbum != null -> albumEntries
        tab == Tab.FAVOURITES -> favEntries
        else -> photoEntries
    }
    val photosGrid = rememberLazyGridState()
    val favouritesGrid = rememberLazyGridState()
    val albumGrid = remember(openAlbum?.kind, openAlbum?.id) { LazyGridState() }
    val albumsList = rememberLazyGridState()
    val gridState = when {
        liveAlbum != null -> albumGrid
        tab == Tab.FAVOURITES -> favouritesGrid
        else -> photosGrid
    }
    val scope = rememberCoroutineScope()
    // A selection only ever means the list in front of you, so changing that
    // list drops it. Without this it followed you out and retargeted itself.
    LaunchedEffect(tab, liveAlbum?.id) {
        selection = null
        binNote = false
    }

    if (!hasPermission) { PermissionScreen(onRequestPermission); return }

    // Volume down and up: a screen of the wall, or one photo in the viewer, with
    // no scrolling animation. While a sheet is open they stay volume keys.
    SideEffect {
        pages.app = { forward ->
            when {
                notice != null || confirmForever != null || showMonths || showMore -> false
                viewerId != null -> pages.viewer?.invoke(forward) ?: false
                else -> {
                    val target = if (tab == Tab.ALBUMS && liveAlbum == null) albumsList else gridState
                    scope.launch { target.pageBy(forward) }
                    true
                }
            }
        }
    }
    DisposableEffect(pages) { onDispose { pages.app = null } }

    // Without this, back closed the whole app from anywhere: from an open photo,
    // from inside an album, mid-selection. Each layer now peels off in turn.
    val canGoBack = notice != null || confirmForever != null ||
        showMonths || showMore || viewerId != null || selection != null ||
        openAlbum != null || tab != Tab.PHOTOS
    BackHandler(enabled = canGoBack) {
        when {
            notice != null -> notice = null
            confirmForever != null -> confirmForever = null
            showMonths -> showMonths = false
            showMore -> showMore = false
            viewerId != null && viewerInfo -> viewerInfo = false
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
                    Modifier.fillMaxWidth().background(Ink.paper).padding(start = PagePadding, end = 10.dp, top = 2.dp, bottom = 2.dp),
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
                        header = {
                            TitleBlock(
                                title = "Albums",
                                subtitle = "${albums.size} folder${if (albums.size == 1) "" else "s"}",
                                onTitleTap = { scope.launch { albumsList.scrollToItem(0) } },
                            )
                        },
                        state = albumsList,
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
                                else { viewerId = item.id; viewerInfo = false }
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
                                    onTitleTap = { scope.launch { gridState.scrollToItem(0) } },
                                )
                            },
                            trailing = { if (list.isNotEmpty()) Colophon(list.size) },
                        )
                    }
                }
            }
            if (binNote) {
                Hairline()
                Row(
                    Modifier.fillMaxWidth().background(Ink.paper).padding(start = PagePadding, end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(BIN_NOTE, style = Type.label, modifier = Modifier.weight(1f))
                    Word("OK", quiet = true) { binNote = false }
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
                    onDelete = { trash(shown.filter { it.id in sel }) },
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
            val found = shown.indexOfFirst { it.id == id }
            // Gone from the list: stay open on the item that took its place, and
            // close only when nothing is left.
            val index = if (found >= 0) found else viewerIndex.coerceAtMost(shown.lastIndex)
            if (index < 0) {
                LaunchedEffect(id) { viewerId = null }
            } else {
                SideEffect { viewerIndex = index }
                Viewer(
                    items = shown,
                    startIndex = index,
                    favourites = favourites,
                    showInfo = viewerInfo,
                    pages = pages,
                    onShowInfo = { viewerInfo = it },
                    onCurrent = { viewerId = it.id },
                    onClose = { viewerId = null },
                    onToggleFavourite = { vm.toggleFavourite(it.id) },
                    onShare = { share(listOf(it)) },
                    onDelete = { trash(listOf(it)) },
                    note = if (binNote) BIN_NOTE else null,
                    onNoteDone = { binNote = false },
                    inBin = inBin,
                    onRestore = { restore(listOf(it)) },
                    onDeleteForever = { confirmForever = listOf(it) },
                )
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
            // Pulled left by the word's own padding, so the arrow lines up with the title.
            Word(back, quiet = true, modifier = Modifier.offset(x = (-8).dp), onClick = onBack)
        }
        Row {
            Text(title, style = Type.title, modifier = Modifier.weight(1f).alignByBaseline().clickable(onClick = onTitleTap))
            words.forEach { (w, act) -> Word(w, modifier = Modifier.alignByBaseline(), onClick = act) }
        }
        if (subtitle.isNotEmpty()) Text(subtitle, style = Type.subtitle, modifier = Modifier.padding(top = 2.dp, end = 8.dp))
        Spacer(Modifier.height(4.dp))
    }
}

/** Where binned photos go: the app's own album, not the Files app. */
private const val BIN_NOTE = "Moved to Recently deleted, in Albums. Kept there for 30 days."

/**
 * Day grouping, worked out off the main thread. While a list is regrouped the
 * old rows stay up; rows from a different [owner] (another album) never show.
 */
@Composable
private fun rememberDayGroups(items: List<MediaItem>, owner: Any?): List<Entry> {
    val grouped by produceState<Pair<Any?, List<Entry>>?>(null, items, owner) {
        value = owner to withContext(Dispatchers.Default) { groupByDay(items) }
    }
    return grouped?.takeIf { it.first == owner }?.second ?: emptyList()
}

/** Roughly how many URIs fit in one Binder transaction with room to spare. */
private const val BULK_BATCH = 500

/** Android's chooser has the same limit; past this it simply fails. */
private const val SHARE_LIMIT = 200

private enum class BulkOp { TRASH, RESTORE, ERASE }

@Composable
private fun BinSelectionBar(count: Int, onRestore: () -> Unit, onForever: () -> Unit, onAll: () -> Unit, onCancel: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Ink.paper).padding(horizontal = 10.dp).navigationBarsPadding(),
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
        Modifier.fillMaxWidth().background(Ink.paper).padding(horizontal = 10.dp).navigationBarsPadding(),
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
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
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
