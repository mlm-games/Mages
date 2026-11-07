package org.mlm.mages.ui.viewmodel

import androidx.lifecycle.viewModelScope
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import mages.shared.generated.resources.*
import org.koin.core.component.inject
import org.mlm.mages.AttachmentKind
import org.mlm.mages.MatrixService
import org.mlm.mages.MessageEvent
import org.mlm.mages.matrix.BackPaginationSession
import org.mlm.mages.matrix.allowsMediaPreviews
import org.mlm.mages.matrix.BackPaginationStatus
import org.mlm.mages.matrix.TimelineDiff
import org.mlm.mages.matrix.TimelineListReducer
import org.mlm.mages.settings.AppSettings
import org.jetbrains.compose.resources.getString
import mages.shared.generated.resources.Res

data class MediaGalleryUiState(
    val isLoading: Boolean = true,
    val paginationStatus: BackPaginationStatus = BackPaginationStatus.Idle,
    val hasTimelineSnapshot: Boolean = false,
    val allEvents: List<MessageEvent> = emptyList(),
    val links: List<ExtractedLink> = emptyList(),
    val thumbnails: Map<String, String> = emptyMap(),
    val error: String? = null,
    val isSelectionMode: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val settingBlocksPreviews: Boolean = false,
    val previewsEnabledForSession: Boolean = false
) {
    val previewsBlocked: Boolean get() = settingBlocksPreviews && !previewsEnabledForSession
    val isPaginating: Boolean
        get() = paginationStatus is BackPaginationStatus.Paginating

    val hitStart: Boolean
        get() = paginationStatus is BackPaginationStatus.TimelineStartReached

    val showFooterLoader: Boolean get() = isPaginating

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

    private val backPaginationSession = BackPaginationSession(
        scope = viewModelScope,
        maxAttempts = MAX_SCAN_PAGES,
    )

    private companion object {
        const val PAGE_SIZE = 50
        const val MAX_SCAN_PAGES = 24
        const val THUMBNAIL_CONCURRENCY = 6
    }

    private val settingsRepo: SettingsRepository<AppSettings> by inject()

    private val settings = settingsRepo.flow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val thumbnailPermits = Semaphore(THUMBNAIL_CONCURRENCY)

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
        observePaginationStatus()
        observePreviewPolicy()
    }

    private fun observePreviewPolicy() {
        launch {
            settings.collect { current ->
                updateState {
                    copy(
                        settingBlocksPreviews = !current.mediaPreviews.allowsMediaPreviews(isPrivateRoom = null)
                    )
                }
            }
        }
    }

    /**
     * Lifts the media preview block for this screen only. The setting itself is untouched, so
     * previews stay off in the timeline, notifications and every other screen.
     */
    fun loadBlockedPreviews() {
        if (!currentState.previewsBlocked) return
        updateState { copy(previewsEnabledForSession = true) }
        loadThumbnailsFor(currentState.allEvents)
    }

    private fun observePaginationStatus() {
        launch {
            backPaginationSession.status.collect { status ->
                updateState { copy(paginationStatus = status) }
            }
        }
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
            tieOf = { it.stableKey() },
            useDiffIndex = false
        )

        val filtered = result.list.filter { hasMediaOrLink(it) }

        val validSelectedIds = when (diff) {
            is TimelineDiff.RemoveByItemId -> {
                val removedEventId = currentState.allEvents
                    .firstOrNull { it.itemId == diff.itemId }
                    ?.eventId
                if (removedEventId.isNullOrBlank()) currentState.selectedIds
                else currentState.selectedIds - removedEventId
            }
            is TimelineDiff.Clear -> emptySet()
            else -> currentState.selectedIds
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
        if (backPaginationSession.isActive || backPaginationSession.isTimelineStartReached) return

        val countBefore = currentState.allEvents.size

        backPaginationSession.startSession(
            paginateOnce = {
                runSafe { service.paginateBack(roomId, PAGE_SIZE).getOrDefault(false) } ?: false
            },
            shouldStop = {
                currentState.allEvents.size > countBefore
            },
        )
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
                _events.send(Event.ShowError(getString(Res.string.failed_to_prepare_files_for_sharing)))
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
            _events.send(Event.ShowSuccess(getString(Res.string.downloaded_n_files, successCount)))
            clearSelection()
        }
    }

    private fun hasMediaOrLink(event: MessageEvent): Boolean {
        return event.attachment != null || urlRegex.containsMatchIn(event.body)
    }

    private fun prefetchThumbnails(events: List<MessageEvent>) {
        if (currentState.previewsBlocked) return
        loadThumbnailsFor(events)
    }

    private fun loadThumbnailsFor(events: List<MessageEvent>) {
        events.filter {
            val kind = it.attachment?.kind
            kind == AttachmentKind.Image || kind == AttachmentKind.Video
        }.forEach { event ->
            if (event.eventId.isBlank()) return@forEach
            if (currentState.thumbnails.containsKey(event.eventId)) return@forEach

            launch {
                val attachment = event.attachment ?: return@launch
                thumbnailPermits.withPermit {
                    service.thumbnailToCache(attachment, 200, 200, true)
                        .onSuccess { path ->
                            updateState { copy(thumbnails = thumbnails + (event.eventId to path)) }
                        }
                }
            }
        }
    }
}