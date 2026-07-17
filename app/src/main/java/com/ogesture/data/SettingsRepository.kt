package com.ogesture.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository private constructor(appContext: Context) {

    private val store = appContext.dataStore

    val masterEnabled: Flow<Boolean> = store.data.map { it[KEY_MASTER] ?: false }

    suspend fun setMasterEnabled(enabled: Boolean) {
        store.edit { it[KEY_MASTER] = enabled }
    }

    suspend fun isMasterEnabled(): Boolean = store.data.first()[KEY_MASTER] ?: false

    companion object {
        private val KEY_MASTER = booleanPreferencesKey("master_enabled")

        @Volatile private var INSTANCE: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsRepository(context.applicationContext).also { INSTANCE = it }
            }
    }
}
