package com.ogesture.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ogesture.data.GestureZoneSettings
import com.ogesture.data.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepository.get(app)

    val masterEnabled: StateFlow<Boolean> = repo.masterEnabled.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = false,
    )

    fun setMasterEnabled(enabled: Boolean) {
        viewModelScope.launch {
            // The accessibility service observes this flow and attaches/detaches the gesture
            // zones itself, so toggling the switch is just a datastore write — no foreground
            // service to start or stop anymore.
            repo.setMasterEnabled(enabled)
        }
    }

    /**
     * The user-tunable gesture-zone geometry. The controller observes the same repository
     * flow and rebuilds the overlay zones when this changes; the ViewModel exposes it for
     * the settings UI. The repository is the source of truth for valid (clamped) values.
     */
    val gestureZoneSettings: StateFlow<GestureZoneSettings> = repo.gestureZoneSettings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = GestureZoneSettings.DEFAULT,
    )

    fun setBackActivationHeight(percent: Int) {
        viewModelScope.launch { repo.setBackActivationHeight(percent) }
    }

    fun setBottomActivationWidth(percent: Int) {
        viewModelScope.launch { repo.setBottomActivationWidth(percent) }
    }

    fun setBackEdgeSensitivity(multiplier: Float) {
        viewModelScope.launch { repo.setBackEdgeSensitivity(multiplier) }
    }

    fun setBottomEdgeSensitivity(multiplier: Float) {
        viewModelScope.launch { repo.setBottomEdgeSensitivity(multiplier) }
    }

    val excludedApps: StateFlow<Set<String>> = repo.excludedApps.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = emptySet(),
    )

    fun setAppExcluded(packageName: String, excluded: Boolean) {
        viewModelScope.launch { repo.setAppExcluded(packageName, excluded) }
    }
}
