package org.mlm.mages.ui.viewmodel

import androidx.lifecycle.viewModelScope
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import org.koin.core.component.inject
import org.mlm.mages.AttachmentKind
import org.mlm.mages.MatrixService
import org.mlm.mages.MessageEvent
import org.mlm.mages.matrix.TimelineDiff
import org.mlm.mages.settings.AppSettings

data class MediaGalleryUiState(
    val isLoading: Boolean = true,
    val isPaginatingBack: Boolean = false,
    val hitStart: Boolean = false,
    val allEvents: List<MessageEvent> = emptyList(),
    val thumbnails: Map<String, String> = emptyMap(),
    val error: String? = null,

    val isSelectionMode: Boolean = false,
    val selectedIds: Set<String> = emptySet()
) {
    val images: List<MessageEvent>
        get() = allEvents.filter { it.attachment?.kind == AttachmentKind.Image }

    val videos: List<MessageEvent>
        get() = allEvents.filter { it.attachment?.kind == AttachmentKind.Video }

    val files: List<MessageEvent>
        get() = allEvents.filter { it.attachment?.kind == AttachmentKind.File }

    val selectedEvents: List<MessageEvent>
        get() = allEvents.filter { it.eventId in selectedIds }

    val selectedCount: Int
        get() = selectedIds.size
}

data class ExtractedLink(
    val url: String,
    val eventId: String,
    val sender: String,
    val timestamp: Long
)

class MediaGalleryViewModel(
    private val service: MatrixService,
    private val roomId: String
) : BaseViewModel<MediaGalleryUiState>(MediaGalleryUiState()) {

    private val settingsRepo: SettingsRepository<AppSettings> by inject()

    private val settings = settingsRepo.flow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    sealed class Event {
        data class ShowError(val message: String) : Event()
        data class ShowSuccess(val message: String) : Event()
        data class ShareFiles(val paths: List<String>, val mimeTypes: List<String?>) : Event()
        data class OpenForwardPicker(val events: List<String>) : Event()
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val urlRegex = Regex("""https?://[^\s<>"{}|\\^`\[\]]+""")

    val links: List<ExtractedLink>
        get() = currentState.allEvents
            .filter { it.attachment == null }
            .flatMap { event ->
                urlRegex.findAll(event.body).map { match ->
                    ExtractedLink(
                        url = match.value,
                        eventId = event.eventId,
                        sender = event.sender,
                        timestamp = event.timestampMs
                    )
                }
            }
            .distinctBy { it.url }

    init {
        observeTimeline()
    }

    private fun observeTimeline() {
        launch {
            service.timelineDiffs(roomId).collectLatest { diff ->
                when (diff) {
                    is TimelineDiff.Reset -> {
                        updateState {
                            copy(
                                isLoading = false,
                                allEvents = diff.items.filter { hasMediaOrLink(it) }
                            )
                        }
                        prefetchThumbnails(diff.items)
                    }
                    is TimelineDiff.Append -> {
                        val mediaItems = diff.items.filter { hasMediaOrLink(it) }
                        updateState { copy(allEvents = allEvents + mediaItems) }
                        prefetchThumbnails(diff.items)
                    }
                    is TimelineDiff.UpdateByItemId -> {
                        if (hasMediaOrLink(diff.item)) {
                            updateState {
                                val idx = allEvents.indexOfFirst { it.itemId == diff.itemId }
                                if (idx >= 0) {
                                    copy(allEvents = allEvents.toMutableList().apply { set(idx, diff.item) })
                                } else {
                                    copy(allEvents = allEvents + diff.item)
                                }
                            }
                        }
                    }
                    is TimelineDiff.RemoveByItemId -> {
                        updateState {
                            copy(
                                allEvents = allEvents.filter { it.itemId != diff.itemId },
                                selectedIds = selectedIds - diff.itemId
                            )
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    fun loadMore() {
        if (currentState.isPaginatingBack || currentState.hitStart) return

        launch {
            updateState { copy(isPaginatingBack = true) }
            val hitStart = runSafe { service.paginateBack(roomId, 50) } ?: false
            updateState { copy(isPaginatingBack = false, hitStart = hitStart) }
        }
    }

    // Selection

    fun toggleSelection(eventId: String) {
        updateState {
            val newSelected = if (eventId in selectedIds) {
                selectedIds - eventId
            } else {
                selectedIds + eventId
            }
            copy(
                selectedIds = newSelected,
                isSelectionMode = newSelected.isNotEmpty()
            )
        }
    }

    fun selectAll(tab: MediaTab) {
        val items = when (tab) {
            MediaTab.Images -> currentState.images
            MediaTab.Videos -> currentState.videos
            MediaTab.Files -> currentState.files
            MediaTab.Links -> emptyList()
        }
        updateState {
            copy(
                selectedIds = selectedIds + items.map { it.eventId }.toSet(),
                isSelectionMode = true
            )
        }
    }

    fun clearSelection() {
        updateState { copy(selectedIds = emptySet(), isSelectionMode = false) }
    }

    fun enterSelectionMode(eventId: String) {
        updateState {
            copy(
                isSelectionMode = true,
                selectedIds = setOf(eventId)
            )
        }
    }

    // Actions

    fun shareSelected() {
        val selected = currentState.selectedEvents
        if (selected.isEmpty()) return

        launch {
            val results = selected.mapNotNull { event ->
                val att = event.attachment ?: return@mapNotNull null
                val hint = event.body.takeIf { it.contains('.') && !it.startsWith("mxc://") }
                val path = service.port.downloadAttachmentToCache(att, hint).getOrNull()
                if (path != null) path to att.mime else null
            }

            if (results.isNotEmpty()) {
                _events.send(Event.ShareFiles(
                    paths = results.map { it.first },
                    mimeTypes = results.map { it.second }
                ))
                clearSelection()
            } else {
                _events.send(Event.ShowError("Failed to prepare files for sharing"))
            }
        }
    }

    fun forwardSelected() {
        val selected = currentState.selectedEvents.map { event -> event.eventId }
        if (selected.isEmpty()) return

        launch {
            _events.send(Event.OpenForwardPicker(selected))
        }
    }

    fun downloadSelected() {
        val selected = currentState.selectedEvents
        if (selected.isEmpty()) return

        launch {
            var successCount = 0
            selected.forEach { event ->
                val att = event.attachment ?: return@forEach
                val hint = event.body.takeIf { it.contains('.') && !it.startsWith("mxc://") }
                service.port.downloadAttachmentToCache(att, hint)
                    .onSuccess { successCount++ }
            }
            _events.send(Event.ShowSuccess("Downloaded $successCount files"))
            clearSelection()
        }
    }

    private fun hasMediaOrLink(event: MessageEvent): Boolean {
        return event.attachment != null || urlRegex.containsMatchIn(event.body)
    }

    private fun prefetchThumbnails(events: List<MessageEvent>) {
        if (settings.value.blockMediaPreviews) return

        events.filter {
            it.attachment?.kind == AttachmentKind.Image ||
                    it.attachment?.kind == AttachmentKind.Video
        }.forEach { event ->
            if (currentState.thumbnails.containsKey(event.eventId)) return@forEach

            launch {
                val attachment = event.attachment ?: return@launch
                service.thumbnailToCache(attachment, 200, 200, true)
                    .onSuccess { path ->
                        updateState { copy(thumbnails = thumbnails + (event.eventId to path)) }
                    }
            }
        }
    }
}

enum class MediaTab { Images, Videos, Files, Links }