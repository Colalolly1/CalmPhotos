package com.calmphotos.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
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
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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

/** As far in as a pinch goes. */
private const val MAX_ZOOM = 6f

/** Where a double-tap takes a photo at rest. */
private const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * Full-screen viewer. The picture has the whole screen: the phone's status and
 * navigation bars are hidden while it is open, and the words float over the
 * picture rather than taking space from it. One tap hides them, another brings
 * them back. Swipe between photos, pinch or double-tap to zoom. Videos show
 * their first frame and play here when tapped, with the words out of the way.
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
    // The words over the picture. A tap on a photo or a playing video hides or
    // shows them; a video starting hides them. Hidden stays hidden from one
    // photo to the next, so you can look through a run of them full screen.
    var chrome by remember { mutableStateOf(true) }
    var paused by remember { mutableStateOf(false) }
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    // Where a video stood when the app went to the background. Coming back
    // finds it there, paused, rather than starting over from the top with sound.
    var heldAt by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    // A video starting hides the words; one stopping brings them back, with its Play.
    LaunchedEffect(playingId) { chrome = playingId == null; paused = false; heldAt = null }
    val showChrome = chrome
    // Info is hidden with the other words; let it go, or Back would spend a
    // press closing something you can't see.
    LaunchedEffect(showChrome, showInfo) { if (!showChrome && showInfo) onShowInfo(false) }
    val ctx = LocalContext.current
    val current = items[openIndex]

    HideSystemBars()

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

    // The picture fills the whole screen and never changes size. The words are
    // laid over it, so showing or hiding them can't make the picture jump.
    Box(Modifier.fillMaxSize().background(Ink.white)) {
        HorizontalPager(
            state = pager,
            userScrollEnabled = !zoomed,
            beyondBoundsPageCount = 1,
            key = { page -> items[page].id },
            modifier = Modifier.fillMaxSize(),
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
                // Swiping between two videos can release the old view after the
                // new one is made, so only the view in hand is let go.
                onViewReleased = { v -> if (videoView === v) videoView = null },
                resumeAt = { heldAt?.takeIf { it.first == item.id }?.second },
            ) else ZoomablePhoto(
                item = item,
                isCurrent = page == pager.currentPage,
                onZoomChanged = { z -> if (page == pager.currentPage) zoomed = z },
                onTap = { chrome = !chrome },
            )
        }

        // Top: back on the left, when it was taken in the middle, Info on the right.
        // Kept clear of a camera cutout; the hidden status bar takes no room.
        if (showChrome) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .swallowTaps()
                    .background(Ink.white)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
            ) {
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
            }
        }

        // Bottom: any note, Info when asked for, then the words. Laid over the
        // picture too, and only drawn when there is something to show.
        val line = message ?: note
        if (line != null || showChrome) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .swallowTaps()
                    .background(Ink.white)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
            ) {
                line?.let { text ->
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
    }
}

/**
 * Hides the status and navigation bars while the viewer is open, so the
 * picture has every pixel. A swipe in from the edge shows them for a moment
 * over the picture. Hidden again on returning to the app, since another app
 * or a system dialog in between can bring them back.
 */
@Composable
private fun HideSystemBars() {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(view, lifecycle) {
        val window = view.context.findActivity()?.window
        if (window == null) return@DisposableEffect onDispose { }
        val bars = WindowCompat.getInsetsController(window, view)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(WindowInsetsCompat.Type.systemBars())
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) bars.hide(WindowInsetsCompat.Type.systemBars())
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            bars.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/**
 * A bar laid over the picture keeps its taps. Without this a tap between two
 * words fell through to the photo underneath and hid the bar you were aiming at.
 */
private fun Modifier.swallowTaps(): Modifier = pointerInput(Unit) { detectTapGestures { } }

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * The whole screen in pixels, bars included, which is all the viewer ever needs
 * to decode. The display metrics leave out the navigation bar, and with the
 * bars hidden a photo filling the height was drawn a little larger than it was
 * decoded, and a little soft.
 */
@Composable
private fun rememberScreenSize(): Size {
    val conf = LocalConfiguration.current
    val ctx = LocalContext.current
    return remember(conf.screenWidthDp, conf.screenHeightDp, conf.orientation) {
        val bounds = ctx.findActivity()?.windowManager?.maximumWindowMetrics?.bounds
        if (bounds != null && bounds.width() > 0 && bounds.height() > 0) Size(bounds.width(), bounds.height())
        else ctx.resources.displayMetrics.let { Size(it.widthPixels, it.heightPixels) }
    }
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
private fun ZoomablePhoto(
    item: MediaItem,
    isCurrent: Boolean,
    onZoomChanged: (Boolean) -> Unit,
    onTap: () -> Unit,
) {
    val ctx = LocalContext.current
    var scale by remember(item.id) { mutableFloatStateOf(1f) }
    var offset by remember(item.id) { mutableStateOf(Offset.Zero) }
    // Held from past SHARP_AT until the zoom is undone, so a pinch hovering near
    // the line doesn't reload the photo over and over.
    var sharp by remember(item.id) { mutableStateOf(false) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    // The photo's own shape once it has loaded, turned the right way up. Panning
    // stops at the photo's edges, which for a wide photo on a tall screen are
    // well inside the screen's.
    var picture by remember(item.id) { mutableStateOf(GeoSize.Unspecified) }
    val latestOnTap by rememberUpdatedState(onTap)
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

    /** The photo's size on screen at rest: fitted inside the screen, centred. */
    fun restingSize(): GeoSize {
        val bw = boxSize.width.toFloat()
        val bh = boxSize.height.toFloat()
        if (bw <= 0f || bh <= 0f) return GeoSize.Zero
        val p = picture
        val (pw, ph) = when {
            p.isSpecified && p.width > 0f && p.height > 0f -> p.width to p.height
            item.width > 0 && item.height > 0 -> item.width.toFloat() to item.height.toFloat()
            else -> return GeoSize(bw, bh)
        }
        val fit = minOf(bw / pw, bh / ph)
        return GeoSize(pw * fit, ph * fit)
    }

    /** Keep the photo covering the screen where it can, and never dragged off it. */
    fun clampOffset() {
        val rest = restingSize()
        val maxX = ((rest.width * scale - boxSize.width) / 2f).coerceAtLeast(0f)
        val maxY = ((rest.height * scale - boxSize.height) / 2f).coerceAtLeast(0f)
        offset = Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
    }

    /** Zoom by [factor], keeping the spot under [focus] where it is on screen. */
    fun zoomAround(focus: Offset, factor: Float) {
        val target = (scale * factor).coerceIn(1f, MAX_ZOOM)
        val step = target / scale
        val fromCentre = focus - Offset(boxSize.width / 2f, boxSize.height / 2f)
        offset = fromCentre * (1f - step) + offset * step
        scale = target
        if (scale <= 1.01f) { scale = 1f; offset = Offset.Zero }
    }

    // Turning the phone or the photo finishing loading can move the edges.
    LaunchedEffect(boxSize, picture) { clampOffset() }

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
                            // Zoom about the point between the fingers, not the
                            // middle of the screen, so what you pinch stays put.
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) zoomAround(event.calculateCentroid(useCurrent = false), zoom)
                            if (scale > 1.01f) offset += event.calculatePan()
                            clampOffset()
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(item.id) {
                detectTapGestures(
                    onTap = { latestOnTap() },
                    // In on the spot you tapped; out to the whole photo.
                    onDoubleTap = { at ->
                        if (scale > 1.01f) { scale = 1f; offset = Offset.Zero }
                        else zoomAround(at, DOUBLE_TAP_ZOOM / scale)
                        clampOffset()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = if (sharp && isCurrent) detailed else fitted,
            contentDescription = item.name,
            contentScale = ContentScale.Fit,
            onSuccess = { state -> picture = state.painter.intrinsicSize },
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
    onViewReady: (VideoView) -> Unit = {},
    onViewReleased: (VideoView) -> Unit = {},
    resumeAt: () -> Int? = { null },
) {
    val ctx = LocalContext.current
    val latestResumeAt by rememberUpdatedState(resumeAt)
    val latestOnTap by rememberUpdatedState(onTap)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (playing) {
            // The white sits behind the surface, not on it.
            Box(Modifier.fillMaxSize().background(Ink.white))
            // Plays here, on the phone's own codecs. Handing the video to another
            // app meant nothing happened at all when no installed app claimed that
            // format, which is easy to arrange on a phone kept this bare.
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    val video = VideoView(c).apply {
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
                    // VideoView shrinks itself to the video's shape inside the
                    // space it is given. The frame puts that shape in the middle
                    // of the screen, as large as it fits, never stretched.
                    FrameLayout(c).apply {
                        addView(
                            video,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                Gravity.CENTER,
                            ),
                        )
                    }
                },
                onRelease = { frame ->
                    (frame.getChildAt(0) as? VideoView)?.let { v -> v.stopPlayback(); onViewReleased(v) }
                },
            )
            // Taps are caught here, above the video, rather than left to find their
            // way through the Android view underneath. A swipe still turns the page.
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { latestOnTap() }) })
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
