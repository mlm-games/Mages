package org.mlm.mages.ui.viewmodel

import androidx.lifecycle.viewModelScope
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.MediaCacheOverview
import org.mlm.mages.settings.AppSettings

data class MediaCacheUiState(
    val isLoading: Boolean = true,
    val overview: MediaCacheOverview? = null,
    val autoDownloadPreviews: Boolean = true,
    val isClearing: Boolean = false,
    val error: String? = null,
)

class MediaCacheViewModel(
    private val service: MatrixService,
    private val settingsRepository: SettingsRepository<AppSettings>,
) : BaseViewModel<MediaCacheUiState>(MediaCacheUiState()) {

    init {
        settingsRepository.flow
            .onEach { updateState { copy(autoDownloadPreviews = !it.blockMediaPreviews) } }
            .launchIn(viewModelScope)

        load()
    }

    fun load() {
        viewModelScope.launch {
            updateState { copy(isLoading = true, error = null) }
            runCatching { service.port.mediaCacheOverview() }
                .onSuccess { updateState { copy(isLoading = false, overview = it) } }
                .onFailure { updateState { copy(isLoading = false, error = it.message) } }
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            updateState { copy(isClearing = true, error = null) }
            service.port.clearMediaCache()
                .onSuccess { load() }
                .onFailure { updateState { copy(isClearing = false, error = it.message) } }
        }
    }

    fun setAutoDownloadPreviews(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(blockMediaPreviews = !enabled) }
        }
    }
}
