package com.calmphotos.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

/**
 * Reads every image and video the media store knows about, newest first,
 * and re-reads whenever the store changes (a new screenshot, a deletion).
 * Nothing leaves the phone: no network, no indexing service, just a query.
 */
class MediaRepository(private val context: Context) {

    private val resolver: ContentResolver get() = context.contentResolver

    /** Emits the full library on start and again after any media-store change. */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun library(): Flow<List<MediaItem>> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        resolver.registerContentObserver(MediaStore.Files.getContentUri("external"), true, observer)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }
        .onStart { emit(Unit) }
        .debounce(400)
        .map { loadAll() }

    suspend fun loadAll(): List<MediaItem> = query(trashed = false)

    /** The system bin: what you removed, still recoverable for 30 days. */
    suspend fun loadTrashed(): List<MediaItem> = query(trashed = true)

    private suspend fun query(trashed: Boolean): List<MediaItem> = withContext(Dispatchers.IO) {
        val out = ArrayList<MediaItem>(2048)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.DATE_TAKEN,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.BUCKET_ID,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT,
            MediaStore.Files.FileColumns.DURATION,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
        )
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        val args = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
        val queryArgs = android.os.Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            putInt(
                MediaStore.QUERY_ARG_MATCH_TRASHED,
                if (trashed) MediaStore.MATCH_ONLY else MediaStore.MATCH_EXCLUDE,
            )
        }
        try {
            resolver.query(MediaStore.Files.getContentUri("external"), projection, queryArgs, null)?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val iType = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                val iTaken = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_TAKEN)
                val iMod = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val iAdded = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                val iBucket = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)
                val iBucketName = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
                val iName = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val iW = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH)
                val iH = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT)
                val iDur = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DURATION)
                val iMime = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val iPath = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.RELATIVE_PATH)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val isVideo = c.getInt(iType) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val taken = c.getLong(iTaken)
                    val modified = c.getLong(iMod) * 1000
                    val added = c.getLong(iAdded) * 1000
                    val base = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                               else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    out += MediaItem(
                        id = id,
                        uri = ContentUris.withAppendedId(base, id),
                        isVideo = isVideo,
                        takenAt = when {
                            taken > 0 -> taken
                            modified > 0 -> modified
                            else -> added
                        },
                        bucketId = c.getLong(iBucket),
                        bucket = c.getString(iBucketName) ?: "Other",
                        name = c.getString(iName) ?: "",
                        size = c.getLong(iSize),
                        width = c.getInt(iW),
                        height = c.getInt(iH),
                        durationMs = if (isVideo) c.getLong(iDur) else 0L,
                        mime = c.getString(iMime) ?: "",
                        relativePath = c.getString(iPath) ?: "",
                    )
                }
            }
        } catch (_: SecurityException) { }
        out.sortByDescending { it.takenAt }
        out
    }

    /** Group by folder, newest album first. */
    fun albums(items: List<MediaItem>): List<Album> =
        items.groupBy { it.bucketId }
            .map { (id, list) -> Album(id, list.first().bucket, list) }
            .sortedByDescending { it.latest }

    /** Move to the system bin (recoverable for 30 days). Returns the consent intent to launch. */
    fun trashRequest(items: List<MediaItem>) =
        MediaStore.createTrashRequest(resolver, items.map { it.uri }, true)

    /** Put binned items back where they were. */
    fun restoreRequest(items: List<MediaItem>) =
        MediaStore.createTrashRequest(resolver, items.map { it.uri }, false)

    /** Erase for good, with no way back. */
    fun deleteForeverRequest(items: List<MediaItem>) =
        MediaStore.createDeleteRequest(resolver, items.map { it.uri })

    fun uris(items: List<MediaItem>): List<Uri> = items.map { it.uri }
}
