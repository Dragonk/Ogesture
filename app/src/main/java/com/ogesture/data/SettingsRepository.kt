package com.ogesture.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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

    /** Packages the user turned Ogesture off for. Empty by default: gestures everywhere. */
    val excludedApps: Flow<Set<String>> = store.data.map { it[KEY_EXCLUDED_APPS] ?: emptySet() }

    suspend fun setAppExcluded(packageName: String, excluded: Boolean) {
        store.edit { prefs ->
            val current = prefs[KEY_EXCLUDED_APPS] ?: emptySet()
            prefs[KEY_EXCLUDED_APPS] = if (excluded) current + packageName else current - packageName
        }
    }

    /**
     * The user-tunable gesture-zone geometry, clamped to valid ranges. The controller
     * observes [gestureZoneSettings] and rebuilds the overlay zones when it changes.
     */
    val gestureZoneSettings: Flow<GestureZoneSettings> = combine(
        store.data.map { GestureZoneSettings.clampPercent(it[KEY_BACK_HEIGHT] ?: GestureZoneSettings.DEFAULT_BACK_HEIGHT_PERCENT) },
        store.data.map { GestureZoneSettings.clampPercent(it[KEY_BOTTOM_WIDTH] ?: GestureZoneSettings.DEFAULT_BOTTOM_WIDTH_PERCENT) },
        store.data.map { GestureZoneSettings.clampSensitivity(it[KEY_BACK_SENSITIVITY] ?: GestureZoneSettings.DEFAULT_BACK_SENSITIVITY) },
        store.data.map { GestureZoneSettings.clampSensitivity(it[KEY_BOTTOM_SENSITIVITY] ?: GestureZoneSettings.DEFAULT_BOTTOM_SENSITIVITY) },
    ) { height, width, backSens, bottomSens ->
        GestureZoneSettings(height, width, backSens, bottomSens)
    }

    suspend fun setBackActivationHeight(percent: Int) {
        store.edit { it[KEY_BACK_HEIGHT] = GestureZoneSettings.clampPercent(percent) }
    }

    suspend fun setBottomActivationWidth(percent: Int) {
        store.edit { it[KEY_BOTTOM_WIDTH] = GestureZoneSettings.clampPercent(percent) }
    }

    suspend fun setBackEdgeSensitivity(multiplier: Float) {
        store.edit { it[KEY_BACK_SENSITIVITY] = GestureZoneSettings.clampSensitivity(multiplier) }
    }

    suspend fun setBottomEdgeSensitivity(multiplier: Float) {
        store.edit { it[KEY_BOTTOM_SENSITIVITY] = GestureZoneSettings.clampSensitivity(multiplier) }
    }

    companion object {
        private val KEY_MASTER = booleanPreferencesKey("master_enabled")
        private val KEY_EXCLUDED_APPS = stringSetPreferencesKey("excluded_apps")
        private val KEY_BACK_HEIGHT = intPreferencesKey("back_activation_height_percent")
        private val KEY_BOTTOM_WIDTH = intPreferencesKey("bottom_activation_width_percent")
        private val KEY_BACK_SENSITIVITY = floatPreferencesKey("back_edge_sensitivity")
        private val KEY_BOTTOM_SENSITIVITY = floatPreferencesKey("bottom_edge_sensitivity")

        @Volatile private var INSTANCE: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsRepository(context.applicationContext).also { INSTANCE = it }
            }
    }
}
