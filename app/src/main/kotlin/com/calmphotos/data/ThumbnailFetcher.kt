package com.calmphotos.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.provider.MediaStore
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import coil.size.Dimension
import coil.size.Scale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Above this the request is the viewer's, not a tile, and wants the real decode. */
private const val MAX_TILE = 512

/**
 * Tiles from Android's own thumbnail cache instead of decoding every original.
 * If Android can't help (an odd format, a missing file), this returns null
 * and Coil carries on down its usual path: the full decoder for photos, the
 * frame grabber for videos.
 */
class ThumbnailFetcher(
    private val uri: Uri,
    private val options: Options,
    private val width: Int,
    private val height: Int,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val tile = try {
            // Asked at twice the tile so the result always covers a square crop;
            // toTile cuts it down to size.
            toTile(options.context.contentResolver.loadThumbnail(uri, android.util.Size(width * 2, height * 2), null))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        return DrawableResult(
            drawable = BitmapDrawable(options.context.resources, tile),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    /**
     * Exactly covers (or fits) the tile. Anything smaller than the tile would be
     * refused by Coil's memory cache and fetched again every time it scrolled in.
     */
    private fun toTile(src: Bitmap): Bitmap {
        val sx = width / src.width.toFloat()
        val sy = height / src.height.toFloat()
        val s = if (options.scale == Scale.FILL) max(sx, sy) else min(sx, sy)
        val w = (src.width * s).roundToInt().coerceAtLeast(1)
        val h = (src.height * s).roundToInt().coerceAtLeast(1)
        val config = if (options.allowRgb565 && !src.hasAlpha()) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        if (w == src.width && h == src.height && config == src.config) return src
        val out = Bitmap.createBitmap(w, h, config)
        Canvas(out).drawBitmap(src, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        return out
    }

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            // content://media/<volume>/images/media/<id> or .../video/media/<id>
            if (data.scheme != "content" || data.authority != MediaStore.AUTHORITY) return null
            val path = data.pathSegments
            if (path.size < 4 || path[path.size - 2] != "media" || path[path.size - 3] !in setOf("images", "video")) return null
            val w = (options.size.width as? Dimension.Pixels)?.px ?: return null
            val h = (options.size.height as? Dimension.Pixels)?.px ?: return null
            if (max(w, h) > MAX_TILE) return null
            return ThumbnailFetcher(data, options, w, h)
        }
    }
}
