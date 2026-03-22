package org.mlm.mages.ui.viewmodel

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.math.max
import org.mlm.mages.MatrixService
import org.mlm.mages.MessageEvent
import org.mlm.mages.ui.ForwardableRoom

enum class RoomForwardStage {
    Idle,
    Sending,
    Success,
    PartialSuccess,
    Failed
}

data class RoomForwardStatus(
    val roomId: String,
    val roomName: String,
    val stage: RoomForwardStage = RoomForwardStage.Idle,
    val currentMessage: Int = 0,
    val totalMessages: Int = 0,
    val successfulMessages: Int = 0,
    val errorMessage: String? = null,
)

data class RoomForwardResult(
    val roomId: String,
    val roomName: String,
    val attemptedMessages: Int,
    val successfulMessages: Int,
    val failureMessage: String? = null,
) {
    val failedMessages: Int get() = attemptedMessages - successfulMessages
    val isSuccess: Boolean get() = attemptedMessages > 0 && successfulMessages == attemptedMessages
    val isPartial: Boolean get() = successfulMessages in 1 until attemptedMessages
    val isFailed: Boolean get() = successfulMessages == 0
}

data class BatchForwardProgress(
    val completedRooms: Int,
    val totalRooms: Int,
    val currentRoomId: String? = null,
    val currentMessage: Int = 0,
    val totalMessages: Int = 0,
)

data class BatchForwardSummary(
    val results: List<RoomForwardResult>
) {
    val totalRooms: Int get() = results.size
    val successfulRooms: Int get() = results.count { it.isSuccess }
    val partialRooms: Int get() = results.count { it.isPartial }
    val failedRooms: Int get() = results.count { it.isFailed }
    val firstSuccessfulRoom: RoomForwardResult? get() = results.firstOrNull { it.isSuccess }
}

fun BatchForwardSummary.userMessage(): String = buildString {
    if (results.isEmpty()) {
        append("Nothing was forwarded")
        return@buildString
    }

    append("Forwarded to $successfulRooms/$totalRooms room")
    if (totalRooms != 1) append("s")

    if (partialRooms > 0) append(" • $partialRooms partial")
    if (failedRooms > 0) append(" • $failedRooms failed")
}

data class ForwardPickerUiState(
    val isLoading: Boolean = true,
    val rooms: List<ForwardableRoom> = emptyList(),
    val searchQuery: String = "",
    val eventCount: Int = 0,
    val selectedRoomIds: Set<String> = emptySet(),
    val isSubmitting: Boolean = false,
    val progress: BatchForwardProgress? = null,
    val roomStatuses: Map<String, RoomForwardStatus> = emptyMap(),
) {
    val filteredRooms: List<ForwardableRoom>
        get() = if (searchQuery.isBlank()) {
            rooms
        } else {
            rooms.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }

    val selectedRooms: List<ForwardableRoom>
        get() = rooms.filter { it.roomId in selectedRoomIds }

    val selectedCount: Int
        get() = selectedRoomIds.size

    val canSubmit: Boolean
        get() = !isLoading && !isSubmitting && eventCount > 0 && selectedRoomIds.isNotEmpty()
}

class ForwardPickerViewModel(
    private val service: MatrixService,
    private val sourceRoomId: String,
    private val eventIds: List<String>
) : BaseViewModel<ForwardPickerUiState>(
    ForwardPickerUiState(eventCount = eventIds.size)
) {

    sealed class Event {
        data class BatchForwardCompleted(val summary: BatchForwardSummary) : Event()
        data class ShowError(val message: String) : Event()
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var eventsToForward: List<MessageEvent> = emptyList()

    init {
        loadRooms()
        loadEvents()
    }

    private fun loadRooms() {
        launch {
            val cached = runSafe { service.port.loadRoomListCache() } ?: emptyList()

            val forwardable: List<ForwardableRoom> =
                if (cached.isNotEmpty()) {
                    cached
                        .filter { it.roomId != sourceRoomId }
                        .map { entry ->
                            ForwardableRoom(
                                roomId = entry.roomId,
                                name = entry.name,
                                avatarUrl = entry.avatarUrl,
                                isDm = entry.isDm,
                                lastActivity = entry.lastTs.toLong()
                            )
                        }
                        .sortedByDescending { it.lastActivity }
                } else {
                    val rooms = runSafe { service.port.listRooms() } ?: emptyList()
                    rooms
                        .filter { it.id != sourceRoomId }
                        .map { room ->
                            ForwardableRoom(
                                roomId = room.id,
                                name = room.name,
                                avatarUrl = room.avatarUrl,
                                isDm = room.isDm,
                                lastActivity = 0L
                            )
                        }
                        .sortedBy { it.name.lowercase() }
                }

            updateState { copy(isLoading = false, rooms = forwardable) }

            forwardable.forEach { room ->
                resolveAvatar(service, room.avatarUrl, 64) { path ->
                    copy(
                        rooms = rooms.map { existing ->
                            if (existing.roomId == room.roomId) {
                                existing.copy(avatarUrl = path)
                            } else {
                                existing
                            }
                        }
                    )
                }
            }
        }
    }

    private fun loadEvents() {
        launch {
            val snapshotLimit = max(500, eventIds.size * 50)
            val snapshot = runSafe { service.port.recent(sourceRoomId, snapshotLimit) } ?: emptyList()
            val byId = snapshot.associateBy { it.eventId }

            eventsToForward = eventIds.mapNotNull { byId[it] }
            updateState { copy(eventCount = eventsToForward.size) }

            val missingCount = eventIds.size - eventsToForward.size
            if (missingCount > 0) {
                _events.send(
                    Event.ShowError(
                        "Could not load $missingCount selected message(s). Please reopen the room and try again."
                    )
                )
            }
        }
    }

    fun setSearchQuery(query: String) {
        if (currentState.isSubmitting) return
        updateState { copy(searchQuery = query) }
    }

    fun toggleRoomSelection(roomId: String) {
        if (currentState.isSubmitting) return

        updateState {
            val next = if (roomId in selectedRoomIds) {
                selectedRoomIds - roomId
            } else {
                selectedRoomIds + roomId
            }
            copy(selectedRoomIds = next)
        }
    }

    fun submitForward() {
        if (!currentState.canSubmit) return

        if (eventsToForward.isEmpty()) {
            launch {
                _events.send(Event.ShowError("No messages available to forward"))
            }
            return
        }

        launch {
            val selectedRooms = currentState.rooms.filter { it.roomId in currentState.selectedRoomIds }
            if (selectedRooms.isEmpty()) {
                _events.send(Event.ShowError("Select at least one room"))
                return@launch
            }

            val totalMessages = eventsToForward.size
            updateState {
                copy(
                    isSubmitting = true,
                    roomStatuses = selectedRooms.associate { room ->
                        room.roomId to RoomForwardStatus(
                            roomId = room.roomId,
                            roomName = room.name,
                            stage = RoomForwardStage.Idle,
                            totalMessages = totalMessages
                        )
                    },
                    progress = BatchForwardProgress(
                        completedRooms = 0,
                        totalRooms = selectedRooms.size,
                        totalMessages = totalMessages
                    )
                )
            }

            val results = mutableListOf<RoomForwardResult>()

            selectedRooms.forEachIndexed { roomIndex, room ->
                updateRoomStatus(
                    roomId = room.roomId,
                    roomName = room.name,
                    stage = RoomForwardStage.Sending,
                    currentMessage = 0,
                    totalMessages = totalMessages,
                    successfulMessages = 0,
                    errorMessage = null
                )

                var successCount = 0
                var firstError: String? = null

                eventsToForward.forEachIndexed { messageIndex, event ->
                    updateState {
                        copy(
                            progress = BatchForwardProgress(
                                completedRooms = roomIndex,
                                totalRooms = selectedRooms.size,
                                currentRoomId = room.roomId,
                                currentMessage = messageIndex + 1,
                                totalMessages = totalMessages
                            )
                        )
                    }

                    updateRoomStatus(
                        roomId = room.roomId,
                        roomName = room.name,
                        stage = RoomForwardStage.Sending,
                        currentMessage = messageIndex + 1,
                        totalMessages = totalMessages,
                        successfulMessages = successCount,
                        errorMessage = null
                    )

                    val success = forwardSingleMessage(event, room.roomId)
                    if (success) {
                        successCount++
                    } else if (firstError == null) {
                        firstError = "Some messages could not be forwarded"
                    }
                }

                val result = RoomForwardResult(
                    roomId = room.roomId,
                    roomName = room.name,
                    attemptedMessages = totalMessages,
                    successfulMessages = successCount,
                    failureMessage = firstError
                )
                results += result

                val finalStage = when {
                    result.isSuccess -> RoomForwardStage.Success
                    result.isPartial -> RoomForwardStage.PartialSuccess
                    else -> RoomForwardStage.Failed
                }

                updateRoomStatus(
                    roomId = room.roomId,
                    roomName = room.name,
                    stage = finalStage,
                    currentMessage = totalMessages,
                    totalMessages = totalMessages,
                    successfulMessages = successCount,
                    errorMessage = result.failureMessage
                )
            }

            val summary = BatchForwardSummary(results)

            updateState {
                copy(
                    isSubmitting = false,
                    progress = null
                )
            }

            _events.send(Event.BatchForwardCompleted(summary))
        }
    }

    private fun updateRoomStatus(
        roomId: String,
        roomName: String,
        stage: RoomForwardStage,
        currentMessage: Int,
        totalMessages: Int,
        successfulMessages: Int,
        errorMessage: String?
    ) {
        updateState {
            copy(
                roomStatuses = roomStatuses + (
                    roomId to RoomForwardStatus(
                        roomId = roomId,
                        roomName = roomName,
                        stage = stage,
                        currentMessage = currentMessage,
                        totalMessages = totalMessages,
                        successfulMessages = successfulMessages,
                        errorMessage = errorMessage
                    )
                )
            )
        }
    }

    private suspend fun forwardSingleMessage(
        event: MessageEvent,
        targetRoomId: String
    ): Boolean {
        return try {
            val attachment = event.attachment
            if (attachment != null) {
                service.port.sendExistingAttachment(
                    roomId = targetRoomId,
                    attachment = attachment,
                    body = event.body.takeIf {
                        it.isNotBlank() &&
                            it != attachment.mxcUri &&
                            !it.startsWith("mxc://")
                    }
                ).isSuccess
            } else {
                val body = event.body.takeIf { it.isNotBlank() } ?: return false
                service.sendMessage(targetRoomId, body)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
