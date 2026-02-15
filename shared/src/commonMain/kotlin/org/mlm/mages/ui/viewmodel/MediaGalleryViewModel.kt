package org.mlm.mages.ui.viewmodel

import androidx.lifecycle.viewModelScope
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import org.koin.core.component.inject
import org.mlm.mages.AttachmentKind
import org.mlm.mages.MatrixService
import org.mlm.mages.MessageEvent
import org.mlm.mages.matrix.TimelineDiff
import org.mlm.mages.matrix.TimelineListReducer
import org.mlm.mages.settings.AppSettings

data class MediaGalleryUiState(
    val isLoading: Boolean = true,
    val isPaginatingBack: Boolean = false,
    val hitStart: Boolean = false,
    val hasTimelineSnapshot: Boolean = false,
    val allEvents: List<MessageEvent> = emptyList(),
    val links: List<ExtractedLink> = emptyList(),
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

enum class MediaTab { Images, Videos, Files, Links }

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

    private fun cleanExtractedUrl(raw: String): String {
        var url = raw
        val trailing = charArrayOf('.', ',', ';', ':', '!', '?', '\'')
        while (url.isNotEmpty()) {
            val last = url.last()
            url = when (last) {
                in trailing -> url.dropLast(1)
                ')' if url.count { it == ')' } > url.count { it == '(' } ->
                    url.dropLast(1)

                ']' if url.count { it == ']' } > url.count { it == '[' } ->
                    url.dropLast(1)

                else -> break
            }
        }
        return url
    }

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
            service.timelineDiffs(roomId)
                .buffer(capacity = Channel.BUFFERED)
                .collect { diff -> processDiff(diff) }
        }
    }

    private fun processDiff(diff: TimelineDiff<MessageEvent>) {
        val result = TimelineListReducer.apply(
            current = currentState.allEvents,
            diff = diff,
            itemIdOf = { it.itemId },
            stableIdOf = { it.stableKey() },
            timeOf = { it.timestampMs },
            tieOf = { it.stableKey() }
        )

        val filtered = result.list.filter { hasMediaOrLink(it) }

        val validSelectedIds = if (diff is TimelineDiff.RemoveByItemId) {
            currentState.selectedIds - diff.itemId
        } else {
            currentState.selectedIds
        }

        val extractedLinks = filtered
            .filter { it.attachment == null }
            .flatMap { event ->
                urlRegex.findAll(event.body).map { match ->
                    ExtractedLink(
                        url = cleanExtractedUrl(match.value),
                        eventId = event.eventId,
                        sender = event.sender,
                        timestamp = event.timestampMs
                    )
                }
            }
            .distinctBy { it.url }

        updateState {
            copy(
                isLoading = false,
                links = extractedLinks,
                hasTimelineSnapshot = when {
                    result.reset -> true
                    result.cleared -> false
                    else -> hasTimelineSnapshot
                },
                allEvents = filtered,
                selectedIds = validSelectedIds,
                isSelectionMode = validSelectedIds.isNotEmpty()
            )
        }

        // Prefetch thumbnails for new items
        if (result.delta.isNotEmpty()) {
            prefetchThumbnails(result.delta)
        }
    }

    private fun MessageEvent.stableKey(): String = when {
        eventId.isNotBlank() -> "e:$eventId"
        !txnId.isNullOrBlank() -> "t:$txnId"
        else -> "i:$itemId"
    }

    fun loadMore() {
        if (currentState.isPaginatingBack || currentState.hitStart) return

        launch {
            updateState { copy(isPaginatingBack = true) }
            try {
                val hitStart = runSafe { service.paginateBack(roomId, 50) } ?: false
                updateState { copy(hitStart = hitStart) }
            } finally {
                updateState { copy(isPaginatingBack = false) }
            }
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
                selectedIds = selectedIds + items.mapNotNull {
                    it.eventId.takeIf { id -> id.isNotBlank() }
                }.toSet(),
                isSelectionMode = true
            )
        }
    }

    fun clearSelection() {
        updateState { copy(selectedIds = emptySet(), isSelectionMode = false) }
    }

    fun enterSelectionMode(eventId: String) {
        if (eventId.isBlank()) return
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
        val selected = currentState.selectedEvents
            .mapNotNull { it.eventId.takeIf { id -> id.isNotBlank() } }
        if (selected.isEmpty()) return

        launch {
            _events.send(Event.OpenForwardPicker(selected))
            clearSelection()
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
            val kind = it.attachment?.kind
            kind == AttachmentKind.Image || kind == AttachmentKind.Video
        }.forEach { event ->
            if (event.eventId.isBlank()) return@forEach
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