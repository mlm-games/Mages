package org.mlm.mages.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.mlmgames.settings.core.SettingsRepository
import io.github.mlmgames.settings.core.actions.ActionRegistry
import io.github.mlmgames.settings.core.annotations.SettingAction
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.mlm.mages.settings.AppSettings
import kotlin.reflect.KClass

class SettingsViewModel(
    private val settingsRepository: SettingsRepository<AppSettings>
) : ViewModel() {

    val settings = settingsRepository.flow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val schema = settingsRepository.schema

    fun <T> updateSetting(name: String, value: T) {
        viewModelScope.launch {
            @Suppress("UNCHECKED_CAST")
            settingsRepository.set(name, value as Any)
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            settingsRepository.update(transform)
        }
    }

    suspend fun executeAction(actionClass: KClass<out SettingAction>) {
        ActionRegistry.execute(actionClass)
    }
}