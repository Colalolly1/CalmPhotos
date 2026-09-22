package com.calmphotos.data

import android.net.Uri

/** One photo or video from the phone's media store. */
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val isVideo: Boolean,
    /** Best guess at when it was taken, in millis. */
    val takenAt: Long,
    val bucketId: Long,
    val bucket: String,
    val name: String,
    val size: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val mime: String,
    val relativePath: String,
)

/** The three albums that aren't folders on disk. */
enum class Smart { FAVOURITES, VIDEOS, BIN }

data class Album(
    val id: Long,
    val name: String,
    val items: List<MediaItem>,
    /**
     * Null for a real folder. Folder ids come from MediaStore's BUCKET_ID, which
     * is a signed hash of the path and is negative for about half of all folders
     * (DCIM/Camera among them), so the sign of the id says nothing about kind.
     */
    val kind: Smart? = null,
) {
    val cover: MediaItem? get() = items.firstOrNull()
    val latest: Long get() = items.firstOrNull()?.takenAt ?: 0L
}
