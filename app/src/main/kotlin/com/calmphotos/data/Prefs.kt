package com.calmphotos.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("calmphotos")

/** Small, local preferences: favourites and grid density. Plain text, no account. */
class Prefs(private val context: Context) {
    private object Keys {
        val favourites = stringSetPreferencesKey("favourites")
        val columns = intPreferencesKey("columns")
    }

    val favourites: Flow<Set<Long>> = context.store.data.map { p ->
        (p[Keys.favourites] ?: emptySet()).mapNotNull { it.toLongOrNull() }.toSet()
    }

    suspend fun toggleFavourite(id: Long) {
        context.store.edit { p ->
            val cur = p[Keys.favourites] ?: emptySet()
            val s = id.toString()
            p[Keys.favourites] = if (s in cur) cur - s else cur + s
        }
    }

    val columns: Flow<Int> = context.store.data.map { it[Keys.columns] ?: 4 }
    suspend fun setColumns(n: Int) { context.store.edit { it[Keys.columns] = n.coerceIn(2, 6) } }
}
