package org.mlm.mages.ui.viewmodel

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import mages.shared.generated.resources.*
import kotlin.math.max
import org.mlm.mages.MatrixService
import org.mlm.mages.MessageEvent
import org.mlm.mages.ui.ForwardableRoom
import org.mlm.mages.ui.loadForwardableRooms
import org.jetbrains.compose.resources.getString
import mages.shared.generated.resources.Res

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

suspend fun BatchForwardSummary.userMessage(): String = buildString {
    if (results.isEmpty()) {
        append(getString(Res.string.nothing_was_forwarded))
        return@buildString
    }

    append(
        if (totalRooms == 1) getString(Res.string.forwarded_room_one, successfulRooms, totalRooms)
        else getString(Res.string.forwarded_room_other, successfulRooms, totalRooms)
    )

    if (partialRooms > 0) append(" • ").append(getString(Res.string.batch_summary_partial, partialRooms))
    if (failedRooms > 0) append(" • ").append(getString(Res.string.batch_summary_failed, failedRooms))
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
            val forwardable = loadForwardableRooms(service, excludeRoomId = sourceRoomId)
            updateState { copy(isLoading = false, rooms = forwardable) }
        }
    }

    private fun loadEvents() {
        launch {
            val snapshotLimit = max(500, eventIds.size * 50)
            val snapshot = runSafe { service.port.recent(sourceRoomId, snapshotLimit) } ?: emptyList()
            val byId = snapshot.associateBy { it.eventId }
            val missing = eventIds.filterNot { it in byId }
            val fetched = if (missing.isEmpty()) {
                emptyList()
            } else {
                runSafe { service.port.eventsByIds(sourceRoomId, missing) } ?: emptyList()
            }
            val resolved = byId + fetched.associateBy { it.eventId }

            eventsToForward = eventIds.mapNotNull { resolved[it] }
            updateState { copy(eventCount = eventsToForward.size) }

            val missingCount = eventIds.size - eventsToForward.size
            if (missingCount > 0) {
                _events.send(
                    Event.ShowError(
                        getString(Res.string.could_not_load_n_selected, missingCount)
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
                _events.send(Event.ShowError(getString(Res.string.no_messages_available_to_forward)))
            }
            return
        }

        launch {
            val selectedRooms = currentState.rooms.filter { it.roomId in currentState.selectedRoomIds }
            if (selectedRooms.isEmpty()) {
                _events.send(Event.ShowError(getString(Res.string.select_at_least_one_room)))
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
                        firstError = getString(Res.string.some_messages_could_not_be_forwarded)
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
            val result = service.port.forwardEvent(sourceRoomId, event.eventId, listOf(targetRoomId))
            val sent = result.getOrNull()?.sent
            val ok = result.isSuccess && sent != null && targetRoomId in sent
            if (!ok) {
                val message = result.toUserMessage(getString(Res.string.could_not_forward_the_message_try_again))
                _events.send(Event.ShowError(message))
            }
            ok
        } catch (e: Exception) {
            _events.send(Event.ShowError(getString(Res.string.could_not_forward_the_message_try_again)))
            false
        }
    }
}
