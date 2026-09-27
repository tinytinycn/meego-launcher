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
        val KEY_IMMERSIVE_UI = booleanPreferencesKey("immersive_ui")
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

    /**
     * 沉浸式 UI 总开关。
     *
     * 开启后隐藏 Application Grid 与 Multitasking View 的顶部标题栏，
     * 以及底部三屏翻页指示点，使内容真正铺满全屏。
     *
     * 默认 false（保持原有的标题与指示点），避免升级后用户界面被意外改变。
     */
    val immersiveUiFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_IMMERSIVE_UI] ?: false
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

    suspend fun setImmersiveUi(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_IMMERSIVE_UI] = enabled
        }
    }
}
