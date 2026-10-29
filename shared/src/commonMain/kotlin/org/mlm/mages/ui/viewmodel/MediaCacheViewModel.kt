package org.mlm.mages.ui.viewmodel

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.mlm.mages.MatrixService
import org.mlm.mages.RoomSummary
import org.mlm.mages.matrix.MediaCacheOverview
import org.mlm.mages.matrix.MediaItem
import org.mlm.mages.matrix.MediaRetentionPolicyInfo
import org.mlm.mages.matrix.MediaTypeMetrics
import org.mlm.mages.matrix.RoomStorageUsage

data class MediaCacheUiState(
    val isLoading: Boolean = true,
    val overview: MediaCacheOverview? = null,
    val types: MediaTypeMetrics = MediaTypeMetrics(),
    val policy: MediaRetentionPolicyInfo? = null,
    val rooms: List<RoomSummary> = emptyList(),
    val usageById: Map<String, RoomStorageUsage> = emptyMap(),
    val mediaItemsOpen: Boolean = false,
    val mediaItems: List<MediaItem> = emptyList(),
    val mediaItemsLoading: Boolean = false,
    val mediaItemsRoomId: String? = null,
    val mediaItemsKind: String? = null,
    val selectedMediaUris: Set<String> = emptySet(),
    val isClearing: Boolean = false,
    val isSavingPolicy: Boolean = false,
    val error: String? = null,
)

class MediaCacheViewModel(
    private val service: MatrixService,
) : BaseViewModel<MediaCacheUiState>(MediaCacheUiState()) {

    private var usagesJob: Job? = null
    private var mediaItemsJob: Job? = null

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            updateState { copy(isLoading = true, error = null) }
            runCatching { service.port.mediaCacheOverview() }
                .onSuccess { updateState { copy(isLoading = false, overview = it) } }
                .onFailure { updateState { copy(isLoading = false, error = it.message) } }
            runCatching { service.port.mediaTypeMetrics() }
                .onSuccess { usage -> updateState { copy(types = usage) } }
            runCatching { service.port.mediaRetentionPolicy() }
                .onSuccess { updateState { copy(policy = it) } }
            runCatching { service.port.listRooms() }
                .onSuccess { rooms ->
                    updateState { copy(rooms = rooms) }
                    loadUsages(rooms)
                }
        }
    }

    private fun loadUsages(rooms: List<RoomSummary>) {
        usagesJob?.cancel()
        usagesJob = viewModelScope.launch {
            updateState { copy(usageById = emptyMap()) }
            for (room in rooms) {
                val usage = runCatching { service.port.roomStorageUsage(room.id) }.getOrNull() ?: continue
                updateState { copy(usageById = usageById + (room.id to usage)) }
            }
        }
    }

    fun setKeepMediaFor(days: Long?) = updatePolicy { it.copy(lastAccessExpiryDays = days?.toULong()) }

    fun setCacheLimit(bytes: ULong?) = updatePolicy { it.copy(maxCacheSizeBytes = bytes) }

    fun setMaxFileSize(bytes: ULong?) = updatePolicy { it.copy(maxFileSizeBytes = bytes) }

    fun clearCache() {
        viewModelScope.launch {
            updateState { copy(isClearing = true, error = null) }
            service.port.clearMediaCache()
                .onSuccess {
                    updateState { copy(isClearing = false) }
                    load()
                }
                .onFailure { updateState { copy(isClearing = false, error = it.message) } }
        }
    }

    fun clearCacheOlderThan(days: Long) {
        viewModelScope.launch {
            updateState { copy(isClearing = true, error = null) }
            service.port.clearMediaCacheOlderThan(days)
                .onSuccess {
                    updateState { copy(isClearing = false) }
                    load()
                }
                .onFailure { updateState { copy(isClearing = false, error = it.message) } }
        }
    }

    fun clearRoomMedia(roomId: String) {
        viewModelScope.launch {
            updateState { copy(isClearing = true, error = null) }
            service.port.clearRoomMedia(roomId)
                .onSuccess {
                    updateState { copy(isClearing = false) }
                    load()
                    reloadMediaItems()
                }
                .onFailure { updateState { copy(isClearing = false, error = it.message) } }
        }
    }

    fun openMediaList(roomId: String?, kind: String?) {
        mediaItemsJob?.cancel()
        updateState {
            copy(
                mediaItemsOpen = true,
                mediaItems = emptyList(),
                mediaItemsLoading = true,
                mediaItemsRoomId = roomId,
                mediaItemsKind = kind,
                selectedMediaUris = emptySet(),
                error = null,
            )
        }
        mediaItemsJob = viewModelScope.launch {
            val items = runCatching { service.port.mediaItems(roomId, kind) }
                .getOrDefault(emptyList())
            updateState { copy(mediaItems = items, mediaItemsLoading = false) }
        }
    }

    fun closeMediaList() {
        mediaItemsJob?.cancel()
        updateState {
            copy(
                mediaItemsOpen = false,
                mediaItems = emptyList(),
                mediaItemsLoading = false,
                mediaItemsRoomId = null,
                mediaItemsKind = null,
                selectedMediaUris = emptySet(),
            )
        }
    }

    fun toggleMediaSelection(uri: String) {
        updateState {
            copy(
                selectedMediaUris = if (uri in selectedMediaUris) {
                    selectedMediaUris - uri
                } else {
                    selectedMediaUris + uri
                },
            )
        }
    }

    fun selectAllMedia() {
        updateState { copy(selectedMediaUris = mediaItems.map { it.uri }.toSet()) }
    }

    fun clearSelectedMedia() {
        val state = currentState
        val selected = state.mediaItems.filter { it.uri in state.selectedMediaUris }
        if (selected.isEmpty()) return

        val uris = selected.flatMap { item ->
            listOfNotNull(item.uri, item.thumbnailUri?.takeIf { it != item.uri })
        }.distinct()

        viewModelScope.launch {
            updateState { copy(isClearing = true, error = null) }
            service.port.clearMediaItems(uris)
                .onSuccess {
                    updateState { copy(isClearing = false) }
                    reloadMediaItems()
                    load()
                }
                .onFailure { updateState { copy(isClearing = false, error = it.message) } }
        }
    }

    fun clearMediaType(kind: String) {
        viewModelScope.launch {
            updateState { copy(isClearing = true, error = null) }
            service.port.clearMediaType(kind)
                .onSuccess {
                    updateState { copy(isClearing = false) }
                    load()
                    if (currentState.mediaItemsKind == kind) reloadMediaItems()
                }
                .onFailure { updateState { copy(isClearing = false, error = it.message) } }
        }
    }

    fun clearMediaAboveSize(minSize: ULong) {
        viewModelScope.launch {
            updateState { copy(isClearing = true, error = null) }
            service.port.clearMediaAboveSize(minSize)
                .onSuccess {
                    updateState { copy(isClearing = false) }
                    load()
                    reloadMediaItems()
                }
                .onFailure { updateState { copy(isClearing = false, error = it.message) } }
        }
    }

    private fun reloadMediaItems() {
        val state = currentState
        if (!state.mediaItemsOpen) return

        mediaItemsJob?.cancel()
        updateState { copy(mediaItemsLoading = true, selectedMediaUris = emptySet()) }
        mediaItemsJob = viewModelScope.launch {
            val items = runCatching {
                service.port.mediaItems(state.mediaItemsRoomId, state.mediaItemsKind)
            }.getOrDefault(emptyList())
            updateState { copy(mediaItems = items, mediaItemsLoading = false) }
        }
    }

    private fun updatePolicy(transform: (MediaRetentionPolicyInfo) -> MediaRetentionPolicyInfo) {
        val next = transform(currentState.policy ?: MediaRetentionPolicyInfo())
        viewModelScope.launch {
            updateState { copy(isSavingPolicy = true, error = null) }
            service.port.setMediaRetentionPolicy(next)
                .onSuccess {
                    updateState { copy(isSavingPolicy = false, policy = next) }
                    load()
                }
                .onFailure { updateState { copy(isSavingPolicy = false, error = it.message) } }
        }
    }
}
