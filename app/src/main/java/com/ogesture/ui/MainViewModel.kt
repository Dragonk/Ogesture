package com.ogesture.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ogesture.data.SettingsRepository
import com.ogesture.service.EdgeOverlayService
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
            repo.setMasterEnabled(enabled)
            val ctx = getApplication<Application>()
            if (enabled) EdgeOverlayService.start(ctx) else EdgeOverlayService.stop(ctx)
        }
    }
}
