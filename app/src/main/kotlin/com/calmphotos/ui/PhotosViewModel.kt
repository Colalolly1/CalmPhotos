package com.calmphotos.ui

import android.app.Application
import android.app.PendingIntent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.calmphotos.data.Album
import com.calmphotos.data.MediaItem
import com.calmphotos.data.MediaRepository
import com.calmphotos.data.Prefs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class PhotosViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = MediaRepository(app)
    private val prefs = Prefs(app)

    private val hasPermission = MutableStateFlow(false)
    fun permissionGranted() { hasPermission.value = true }

    /**
     * Re-read everything on demand. Granting access to more photos doesn't
     * always raise a media-store change, so returning to the app asks directly.
     */
    private val manualReload = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    fun reload() {
        manualReload.tryEmit(Unit)
        refreshTrash()
    }

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded

    val library: StateFlow<List<MediaItem>> = hasPermission
        .flatMapLatest { ok ->
            if (ok) merge(repo.library(), manualReload.map { repo.loadAll() }) else flowOf(emptyList())
        }
        .onEach { if (hasPermission.value) { _loaded.value = true; refreshTrash() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val albums: StateFlow<List<Album>> = library
        .map { repo.albums(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The bin, re-read whenever the media store changes or a screen asks. */
    private val _trashed = MutableStateFlow<List<MediaItem>>(emptyList())
    val trashed: StateFlow<List<MediaItem>> = _trashed

    fun refreshTrash() {
        viewModelScope.launch { _trashed.value = repo.loadTrashed() }
    }

    val favourites: StateFlow<Set<Long>> = prefs.favourites
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val columns: StateFlow<Int> = prefs.columns
        .stateIn(viewModelScope, SharingStarted.Eagerly, 4)

    fun toggleFavourite(id: Long) { viewModelScope.launch { prefs.toggleFavourite(id) } }
    fun setColumns(n: Int) { viewModelScope.launch { prefs.setColumns(n) } }

    fun trashRequest(items: List<MediaItem>): PendingIntent = repo.trashRequest(items)
    fun restoreRequest(items: List<MediaItem>): PendingIntent = repo.restoreRequest(items)
    fun deleteForeverRequest(items: List<MediaItem>): PendingIntent = repo.deleteForeverRequest(items)
}
