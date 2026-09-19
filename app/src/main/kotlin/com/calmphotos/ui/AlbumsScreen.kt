package com.calmphotos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.calmphotos.data.Album

/**
 * Albums are the phone's own folders (Camera, Screenshots, WhatsApp…), two to
 * a row with a big square cover, plus Favourites and Videos gathered at the top.
 */
@Composable
fun AlbumsScreen(
    albums: List<Album>,
    smart: List<Album>,
    onOpen: (Album) -> Unit,
    header: @Composable () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = PagePadding, end = PagePadding, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { header() }
        if (smart.isNotEmpty()) {
            items(smart, key = { "s${it.id}" }) { AlbumCard(it, onOpen) }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Spacer(Modifier.height(6.dp))
                    Text("Folders", style = Type.subtitle)
                }
            }
        }
        items(albums, key = { it.id }) { AlbumCard(it, onOpen) }
    }
}

@Composable
private fun AlbumCard(album: Album, onOpen: (Album) -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.clickable { onOpen(album) }) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Ink.paper)) {
            album.cover?.let { c ->
                AsyncImage(
                    model = remember(c.uri) { ImageRequest.Builder(ctx).data(c.uri).size(640).build() },
                    contentDescription = album.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Hairline frame so pale covers still read as a tile.
            Box(Modifier.fillMaxSize().padding(0.dp)) {
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(Ink.hairline).align(Alignment.TopStart))
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(Ink.hairline).align(Alignment.BottomStart))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(album.name, style = Type.albumName, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${album.items.size}", style = Type.label)
    }
}
