package com.meego.launcher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "meego_settings")

class LauncherPreferences(private val context: Context) {
    companion object {
        val KEY_GRID_COLUMNS = intPreferencesKey("grid_columns")
        val KEY_RECENTS_COLUMNS = intPreferencesKey("recents_columns")
        val KEY_EDGE_SWIPE = booleanPreferencesKey("edge_swipe")
    }

    val gridColumnsFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[KEY_GRID_COLUMNS] ?: 4
    }

    val recentsColumnsFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[KEY_RECENTS_COLUMNS] ?: 2
    }

    val edgeSwipeFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_EDGE_SWIPE] ?: true
    }

    suspend fun setGridColumns(columns: Int) {
        context.dataStore.edit { preferences ->
            preferences[KEY_GRID_COLUMNS] = columns
        }
    }

    suspend fun setRecentsColumns(columns: Int) {
        context.dataStore.edit { preferences ->
            preferences[KEY_RECENTS_COLUMNS] = columns
        }
    }

    suspend fun setEdgeSwipe(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_EDGE_SWIPE] = enabled
        }
    }
}
