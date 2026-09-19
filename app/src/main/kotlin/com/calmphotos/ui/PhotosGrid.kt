package com.calmphotos.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.calmphotos.data.MediaItem
import java.time.LocalDate

/** A row in the grid: either a day heading spanning the width, or one photo. */
sealed class Entry {
    data class Header(val date: LocalDate, val count: Int) : Entry()
    data class Photo(val item: MediaItem) : Entry()
}

/** Group newest-first items by the day they were taken. */
fun groupByDay(items: List<MediaItem>): List<Entry> {
    val out = ArrayList<Entry>(items.size + 64)
    var current: LocalDate? = null
    var i = 0
    while (i < items.size) {
        val d = items[i].takenAt.toLocalDate()
        if (d != current) {
            current = d
            var j = i
            while (j < items.size && items[j].takenAt.toLocalDate() == d) j++
            out += Entry.Header(d, j - i)
        }
        out += Entry.Photo(items[i])
        i++
    }
    return out
}

/**
 * The wall of photos. Square thumbnails edge to edge with a 2dp gutter, day
 * headings set in serif between them. A leading slot holds whatever the
 * screen wants above the first day (the title block) so it scrolls away.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotosGrid(
    entries: List<Entry>,
    columns: Int,
    favourites: Set<Long>,
    selection: Set<Long>?,
    state: LazyGridState,
    onTap: (MediaItem) -> Unit,
    onLongPress: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = state,
        modifier = modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "leading") { leading() }
        items(
            count = entries.size,
            key = { i -> when (val e = entries[i]) { is Entry.Header -> "h${e.date}"; is Entry.Photo -> e.item.id } },
            span = { i -> if (entries[i] is Entry.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
            contentType = { i -> if (entries[i] is Entry.Header) 0 else 1 },
        ) { i ->
            when (val e = entries[i]) {
                is Entry.Header -> DayHeader(e)
                is Entry.Photo -> Thumb(
                    item = e.item,
                    favourite = e.item.id in favourites,
                    selected = selection?.contains(e.item.id),
                    onTap = { onTap(e.item) },
                    onLongPress = { onLongPress(e.item) },
                )
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }, key = "trailing") { trailing() }
    }
}

@Composable
private fun DayHeader(h: Entry.Header) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Ink.white)
            .padding(start = PagePadding, end = PagePadding, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(dayLabel(h.date), style = Type.dayHeader, modifier = Modifier.weight(1f))
        Text("${h.count}", style = Type.dayMeta, modifier = Modifier.padding(bottom = 2.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Thumb(
    item: MediaItem,
    favourite: Boolean,
    selected: Boolean?,     // null = not in selection mode
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    Box(
        modifier
            .aspectRatio(1f)
            .background(Ink.paper)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
    ) {
        AsyncImage(
            model = remember(item.uri) { ImageRequest.Builder(ctx).data(item.uri).size(480).build() },
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (item.isVideo) {
            Badge(durationLabel(item.durationMs), Modifier.align(Alignment.BottomEnd))
        }
        if (favourite) {
            Badge("♥", Modifier.align(Alignment.BottomStart))
        }
        if (selected != null) {
            // Selection mode: a small square in the corner, filled when chosen,
            // plus a heavy frame so it reads even on a busy thumbnail.
            if (selected) Box(Modifier.fillMaxSize().border(3.dp, Ink.black))
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(20.dp)
                    .background(if (selected) Ink.black else Ink.white)
                    .border(1.5.dp, Ink.black),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Text("✓", style = Type.badge.copy(fontSize = 12.sp))
            }
        }
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier) {
    Box(modifier.padding(4.dp).background(Ink.black).padding(horizontal = 4.dp, vertical = 1.dp)) {
        Text(text, style = Type.badge)
    }
}

private val Int.sp get() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)
