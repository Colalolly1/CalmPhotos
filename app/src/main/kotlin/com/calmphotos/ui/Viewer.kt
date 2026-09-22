package com.calmphotos.ui

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.calmphotos.data.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full-screen viewer. Swipe between photos, pinch or double-tap to zoom,
 * words along the bottom for the four things you actually do with a photo.
 * Videos show their first frame and a Play word that hands off to the
 * system player.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Viewer(
    items: List<MediaItem>,
    startIndex: Int,
    favourites: Set<Long>,
    onClose: () -> Unit,
    onToggleFavourite: (MediaItem) -> Unit,
    onShare: (MediaItem) -> Unit,
    onDelete: (MediaItem) -> Unit,
    inBin: Boolean = false,
    onRestore: (MediaItem) -> Unit = {},
    onDeleteForever: (MediaItem) -> Unit = {},
) {
    if (items.isEmpty()) { onClose(); return }
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, items.size - 1)) { items.size }
    var zoomed by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val current = items[pager.currentPage.coerceIn(0, items.size - 1)]

    Column(Modifier.fillMaxSize().background(Ink.white).statusBarsPadding().navigationBarsPadding()) {
        // Top: back on the left, when it was taken in the middle, Info on the right.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Word("← Photos", onClick = onClose)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(dayLabel(current.takenAt.toLocalDate()), style = Type.dayHeader.copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(timeLabel(current.takenAt) + "  ·  ${pager.currentPage + 1} of ${items.size}", style = Type.dayMeta)
            }
            Word(if (showInfo) "Close" else "Info") { showInfo = !showInfo }
        }
        Hairline()

        HorizontalPager(
            state = pager,
            userScrollEnabled = !zoomed,
            beyondBoundsPageCount = 1,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { page ->
            val item = items[page]
            if (item.isVideo) VideoPage(item)
            else ZoomablePhoto(item, onZoomChanged = { z -> if (page == pager.currentPage) zoomed = z })
        }

        if (showInfo) InfoPanel(current)

        Hairline()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (inBin) {
                Word("Restore") { onRestore(current) }
                if (current.isVideo) Word("Play") { play(ctx, current) }
                Word("Erase", quiet = true) { onDeleteForever(current) }
            } else {
                val fav = current.id in favourites
                Word(if (fav) "♥ Favourite" else "♡ Favourite") { onToggleFavourite(current) }
                Word("Share") { onShare(current) }
                if (current.isVideo) Word("Play") { play(ctx, current) }
                Word("Delete", quiet = true) { onDelete(current) }
            }
        }
    }
}

@Composable
private fun ZoomablePhoto(item: MediaItem, onZoomChanged: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    var scale by remember(item.id) { mutableFloatStateOf(1f) }
    var offset by remember(item.id) { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(scale) { onZoomChanged(scale > 1.01f) }

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
            model = remember(item.uri) { ImageRequest.Builder(ctx).data(item.uri).size(2048).build() },
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
private fun VideoPage(item: MediaItem) {
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize().clickable { play(ctx, item) }, contentAlignment = Alignment.Center) {
        AsyncImage(
            model = remember(item.uri) { ImageRequest.Builder(ctx).data(item.uri).size(1600).build() },
            contentDescription = item.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        FilledWord("▶  Play  ·  ${durationLabel(item.durationMs)}") { play(ctx, item) }
    }
}

private fun play(ctx: android.content.Context, item: MediaItem) {
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(item.uri, item.mime.ifEmpty { "video/*" })
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try { ctx.startActivity(i) } catch (_: Exception) { }
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
