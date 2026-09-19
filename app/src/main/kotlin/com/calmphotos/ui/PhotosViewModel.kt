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

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded

    val library: StateFlow<List<MediaItem>> = hasPermission
        .flatMapLatest { ok -> if (ok) repo.library() else flowOf(emptyList()) }
        .onEach { if (hasPermission.value) _loaded.value = true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val albums: StateFlow<List<Album>> = library
        .map { repo.albums(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val favourites: StateFlow<Set<Long>> = prefs.favourites
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val columns: StateFlow<Int> = prefs.columns
        .stateIn(viewModelScope, SharingStarted.Eagerly, 4)

    fun toggleFavourite(id: Long) { viewModelScope.launch { prefs.toggleFavourite(id) } }
    fun setColumns(n: Int) { viewModelScope.launch { prefs.setColumns(n) } }

    fun trashRequest(items: List<MediaItem>): PendingIntent = repo.trashRequest(items)
}
