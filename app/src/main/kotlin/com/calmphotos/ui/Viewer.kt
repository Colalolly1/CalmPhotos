package com.calmphotos.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.VideoView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Size
import com.calmphotos.data.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Past this zoom the screen-sized decode starts to look soft. */
private const val SHARP_AT = 1.5f

/**
 * Longest side of the detailed copy read for a zoomed photo. In RGB565 a 4:3
 * photo at this size is about 14 MB, and only the photo in front ever has one.
 */
private const val DETAIL_DECODE = 3072

/**
 * Full-screen viewer. Swipe between photos, pinch or double-tap to zoom,
 * words along the bottom for the four things you actually do with a photo.
 * Videos show their first frame and play here when tapped.
 *
 * [startIndex] is the app's record of what is open, and it follows every swipe
 * through [onCurrent]. The words and labels go by it rather than the pager's
 * page number, which still points into the old list for a moment after the
 * list changes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Viewer(
    items: List<MediaItem>,
    startIndex: Int,
    favourites: Set<Long>,
    showInfo: Boolean,
    pages: PageTurner,
    onShowInfo: (Boolean) -> Unit,
    onCurrent: (MediaItem) -> Unit,
    onClose: () -> Unit,
    onToggleFavourite: (MediaItem) -> Unit,
    onShare: (MediaItem) -> Unit,
    onDelete: (MediaItem) -> Unit,
    note: String? = null,
    onNoteDone: () -> Unit = {},
    inBin: Boolean = false,
    onRestore: (MediaItem) -> Unit = {},
    onDeleteForever: (MediaItem) -> Unit = {},
) {
    if (items.isEmpty()) { onClose(); return }
    val openIndex = startIndex.coerceIn(0, items.size - 1)
    val pager = rememberPagerState(initialPage = openIndex) { items.size }
    val scope = rememberCoroutineScope()
    var zoomed by remember { mutableStateOf(false) }
    // Which video is playing inline, if any. Held by id so swiping away stops it.
    var playingId by remember { mutableStateOf<Long?>(null) }
    var autoPlay by remember { mutableStateOf(items[openIndex].isVideo) }
    var message by remember { mutableStateOf<String?>(null) }
    // While a video plays the words step out of the way; a tap on the picture
    // brings them back. Photos always keep them.
    var chrome by remember { mutableStateOf(true) }
    var paused by remember { mutableStateOf(false) }
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    // Where a video stood when the app went to the background. Coming back
    // finds it there, paused, rather than starting over from the top with sound.
    var heldAt by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    LaunchedEffect(playingId) { chrome = playingId == null; paused = false; heldAt = null }
    val showChrome = chrome || playingId == null
    // Info is hidden with the other words while a video fills the screen; let
    // it go, or Back would spend a press closing something you can't see.
    LaunchedEffect(showChrome, showInfo) { if (!showChrome && showInfo) onShowInfo(false) }
    val ctx = LocalContext.current
    val current = items[openIndex]

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            val v = videoView
            val id = playingId
            if (event == Lifecycle.Event.ON_PAUSE && v != null && id != null) {
                heldAt = id to v.currentPosition
                v.pause()
                paused = true
                chrome = true
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Every settled page change goes back to the app, so it always knows what is
    // open. The page number only moves after a layout, by which time it matches
    // the list it was laid out from.
    val latestItems by rememberUpdatedState(items)
    val latestOnCurrent by rememberUpdatedState(onCurrent)
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { page -> latestItems.getOrNull(page)?.let { latestOnCurrent(it) } }
    }
    // The pager follows each photo by its id, so a list that changes under it (a
    // deletion, a new screenshot) leaves the same one on screen. This puts it
    // right if it ever did not.
    LaunchedEffect(items) {
        if (pager.currentPage != openIndex) pager.scrollToPage(openIndex)
    }

    // Moving to another photo stops whatever was playing, and landing on a video
    // starts it. Keyed on the item, so a list shifting under an open video
    // leaves it playing.
    LaunchedEffect(current.id) {
        onCurrent(current)
        message = null
        zoomed = false
        playingId = if (current.isVideo) current.id else null
        autoPlay = current.isVideo
    }

    // Volume keys move one photo, except while a video is up: then they are its volume.
    SideEffect {
        pages.viewer = { forward ->
            if (playingId != null) false
            else {
                val to = pager.currentPage + if (forward) 1 else -1
                if (to in items.indices) scope.launch { pager.scrollToPage(to) }
                true
            }
        }
    }
    DisposableEffect(pages) { onDispose { pages.viewer = null } }

    Column(Modifier.fillMaxSize().background(Ink.white).statusBarsPadding().navigationBarsPadding()) {
        // Top: back on the left, when it was taken in the middle, Info on the right.
        if (showChrome) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Word("← Photos", onClick = onClose)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(dayLabel(current.takenAt.toLocalDate()), style = Type.dayHeader.copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(timeLabel(current.takenAt) + "  ·  ${openIndex + 1} of ${items.size}", style = Type.dayMeta)
                }
                Word(if (showInfo) "Close" else "Info") { onShowInfo(!showInfo) }
            }
            Hairline()
        } else {
            // Full-screen video still needs a way out that is always on screen.
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
                Word("← Back", onClick = onClose)
            }
        }

        HorizontalPager(
            state = pager,
            userScrollEnabled = !zoomed,
            beyondBoundsPageCount = 1,
            key = { page -> items[page].id },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { page ->
            val item = items[page]
            if (item.isVideo) VideoPage(
                item = item,
                // Opening a video plays it. You tapped it to watch it.
                playing = playingId == item.id || (page == pager.currentPage && autoPlay),
                onPlay = { message = null; playingId = item.id },
                // Ended or failed: back to the cover and its Play, ready to go again.
                onStop = { playingId = null; autoPlay = false },
                onError = { why -> playingId = null; autoPlay = false; message = why },
                onTap = { chrome = !chrome },
                onViewReady = { v -> videoView = v },
                resumeAt = { heldAt?.takeIf { it.first == item.id }?.second },
            ) else ZoomablePhoto(
                item = item,
                isCurrent = page == pager.currentPage,
                onZoomChanged = { z -> if (page == pager.currentPage) zoomed = z },
            )
        }

        (message ?: note)?.let { text ->
            Row(
                Modifier.fillMaxWidth().background(Ink.paper).padding(start = 14.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text, style = Type.label, modifier = Modifier.weight(1f))
                Word("OK", quiet = true) { if (message != null) message = null else onNoteDone() }
            }
        }

        if (showInfo && showChrome) InfoPanel(current)

        if (showChrome) {
        Hairline()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (inBin) {
                Word("Restore") { onRestore(current) }
                if (current.isVideo && playingId == current.id) Word("Stop") { playingId = null; autoPlay = false }
                Word("Erase", quiet = true) { onDeleteForever(current) }
            } else {
                val fav = current.id in favourites
                Word(if (fav) "♥ Favourite" else "♡ Favourite") { onToggleFavourite(current) }
                Word("Share") { onShare(current) }
                if (current.isVideo) {
                    if (playingId == current.id) {
                        Word(if (paused) "Resume" else "Pause") {
                            val v = videoView
                            if (v != null) {
                                if (paused) { heldAt = null; v.start() } else v.pause()
                                paused = !paused
                            }
                        }
                        Word("Stop") { playingId = null; autoPlay = false }
                    } else Word("Open in…", quiet = true) { message = openExternally(ctx, current) }
                }
                Word("Delete", quiet = true) { onDelete(current) }
            }
        }
        }
    }
}

/** The screen in pixels, which is all the viewer ever needs to decode. */
@Composable
private fun rememberScreenSize(): Size {
    val conf = LocalConfiguration.current
    val metrics = LocalContext.current.resources.displayMetrics
    return remember(conf.screenWidthDp, conf.screenHeightDp) { Size(metrics.widthPixels, metrics.heightPixels) }
}

/**
 * The whole picture at screen size. Inexact, so a small photo isn't blown up
 * in memory. Whatever copy is already cached stands in while it loads.
 */
private fun screenRequest(ctx: Context, item: MediaItem, screen: Size): ImageRequest =
    ImageRequest.Builder(ctx)
        .data(item.uri)
        .size(screen)
        .precision(Precision.INEXACT)
        .placeholderMemoryCacheKey(item.uri.toString())
        .build()

@Composable
private fun ZoomablePhoto(item: MediaItem, isCurrent: Boolean, onZoomChanged: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    var scale by remember(item.id) { mutableFloatStateOf(1f) }
    var offset by remember(item.id) { mutableStateOf(Offset.Zero) }
    // Held from past SHARP_AT until the zoom is undone, so a pinch hovering near
    // the line doesn't reload the photo over and over.
    var sharp by remember(item.id) { mutableStateOf(false) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(scale) {
        onZoomChanged(scale > 1.01f)
        if (scale > SHARP_AT) sharp = true else if (scale <= 1.01f) sharp = false
    }
    // A photo swiped or paged away goes back to rest, which also lets go of its
    // detailed copy.
    LaunchedEffect(isCurrent) {
        if (!isCurrent) { scale = 1f; offset = Offset.Zero }
    }

    val screen = rememberScreenSize()
    val fitted = remember(item.uri, screen) { screenRequest(ctx, item, screen) }
    // Never written to the memory cache: it lives only while it is on screen.
    val detailed = remember(item.uri) {
        ImageRequest.Builder(ctx)
            .data(item.uri)
            .size(DETAIL_DECODE)
            .precision(Precision.INEXACT)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .memoryCachePolicy(CachePolicy.READ_ONLY)
            .placeholderMemoryCacheKey(item.uri.toString())
            .build()
    }

    /** Keep the picture over the hole it is shown through, never beyond its edges. */
    fun clampOffset() {
        val maxX = (boxSize.width * (scale - 1f) / 2f).coerceAtLeast(0f)
        val maxY = (boxSize.height * (scale - 1f) / 2f).coerceAtLeast(0f)
        offset = Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it }
            .pointerInput(item.id) {
                // Hand-rolled instead of detectTransformGestures, which consumes
                // every drag once past touch slop — including one-finger swipes at
                // rest scale, so the pager behind it never saw them and you could
                // not move between photos. Here a single finger is only ours once
                // the picture is actually zoomed in.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        val ours = fingers > 1 || scale > 1.01f
                        if (ours) {
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) {
                                scale = (scale * zoom).coerceIn(1f, 6f)
                                if (scale <= 1.01f) { scale = 1f; offset = Offset.Zero }
                            }
                            if (scale > 1.01f) offset += event.calculatePan()
                            clampOffset()
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(item.id) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1.01f) { scale = 1f } else { scale = 2.5f }
                    offset = Offset.Zero
                    clampOffset()
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = if (sharp && isCurrent) detailed else fitted,
            contentDescription = item.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                },
        )
    }
}

@Composable
private fun VideoPage(
    item: MediaItem,
    playing: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onError: (String) -> Unit,
    onTap: () -> Unit = {},
    onViewReady: (VideoView?) -> Unit = {},
    resumeAt: () -> Int? = { null },
) {
    val ctx = LocalContext.current
    val latestResumeAt by rememberUpdatedState(resumeAt)
    Box(
        Modifier.fillMaxSize().then(if (playing) Modifier.clickable(onClick = onTap) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (playing) {
            // The white sits behind the surface, not on it.
            Box(Modifier.fillMaxSize().background(Ink.white))
            // Plays here, on the phone's own codecs. Handing the video to another
            // app meant nothing happened at all when no installed app claimed that
            // format, which is easy to arrange on a phone kept this bare.
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    VideoView(c).apply {
                        // No background here: VideoView is a SurfaceView, and an
                        // opaque background is painted straight over the video,
                        // which showed as a white screen with sound.
                        setOnPreparedListener {
                            // Prepared again after the screen slept: back to the
                            // same moment, waiting for Resume.
                            val at = latestResumeAt()
                            if (at != null) seekTo(at) else start()
                        }
                        setOnCompletionListener { onStop() }
                        setOnErrorListener { _, _, _ ->
                            onError("This phone can't decode ${item.mime.ifEmpty { "that format" }}. Try Open in… instead.")
                            true
                        }
                        // No MediaController: its popup was another white bar over
                        // the picture. Pause/Resume live in the app's own words.
                        setVideoURI(item.uri)
                        onViewReady(this)
                    }
                },
                onRelease = { it.stopPlayback(); onViewReady(null) },
            )
        } else {
            val screen = rememberScreenSize()
            AsyncImage(
                model = remember(item.uri, screen) { screenRequest(ctx, item, screen) },
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().clickable(onClick = onPlay),
            )
            FilledWord("▶  Play  ·  ${durationLabel(item.durationMs)}", onClick = onPlay)
        }
    }
}

/** Hand the video to another app, and say so plainly when none will take it. */
private fun openExternally(ctx: android.content.Context, item: MediaItem): String? {
    val i = Intent(Intent.ACTION_VIEW)
        .setDataAndType(item.uri, item.mime.ifEmpty { "video/*" })
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        ctx.startActivity(i)
        null
    } catch (_: Exception) {
        "No app on this phone opens ${item.mime.ifEmpty { "that format" }}. Tap the picture to play it here."
    }
}

/** The facts about a photo, read from the media store and the file's EXIF. */
@Composable
private fun InfoPanel(item: MediaItem) {
    val ctx = LocalContext.current
    var camera by remember(item.id) { mutableStateOf<String?>(null) }
    var exposure by remember(item.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(item.id) {
        if (item.isVideo) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                ctx.contentResolver.openInputStream(item.uri)?.use { s ->
                    val x = ExifInterface(s)
                    val make = x.getAttribute(ExifInterface.TAG_MAKE)?.trim()
                    val model = x.getAttribute(ExifInterface.TAG_MODEL)?.trim()
                    camera = listOfNotNull(make, model?.takeIf { m -> make == null || !m.startsWith(make, true) } ?: model)
                        .distinct().joinToString(" ").ifBlank { null }
                    val f = x.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)
                    val t = x.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
                    val iso = x.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)
                    val fl = x.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
                    exposure = listOfNotNull(
                        if (f > 0) "ƒ/%.1f".format(f) else null,
                        if (t > 0) (if (t < 1) "1/${(1 / t).toInt()} s" else "%.1f s".format(t)) else null,
                        if (iso > 0) "ISO $iso" else null,
                        if (fl > 0) "%.0f mm".format(fl) else null,
                    ).joinToString("  ·  ").ifBlank { null }
                }
            } catch (_: Exception) { }
        }
    }
    Column(Modifier.fillMaxWidth().background(Ink.paper).padding(horizontal = PagePadding, vertical = 12.dp)) {
        InfoRow("Taken", fullDateLabel(item.takenAt) + "  ·  " + timeLabel(item.takenAt))
        InfoRow("Folder", item.bucket)
        InfoRow("File", item.name)
        if (item.width > 0) InfoRow("Size", "${item.width} × ${item.height}  ·  ${sizeLabel(item.size)}") else InfoRow("Size", sizeLabel(item.size))
        if (item.isVideo) InfoRow("Length", durationLabel(item.durationMs))
        camera?.let { InfoRow("Camera", it) }
        exposure?.let { InfoRow("Exposure", it) }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = Type.label, modifier = Modifier.width(72.dp))
        Text(value, style = Type.body.copy(fontSize = 13.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private val Int.sp get() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)
