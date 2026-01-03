package org.mlm.mages.ui.viewmodel

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.mlm.mages.MatrixService
import org.mlm.mages.MessageEvent
import org.mlm.mages.ui.ForwardableRoom


data class ForwardPickerUiState(
    val isLoading: Boolean = true,
    val rooms: List<ForwardableRoom> = emptyList(),
    val searchQuery: String = "",
    val eventCount: Int = 0,
    val forwardingToRoomId: String? = null
) {
    val filteredRooms: List<ForwardableRoom>
        get() = if (searchQuery.isBlank()) rooms
        else rooms.filter { it.name.contains(searchQuery, ignoreCase = true) }
}

class ForwardPickerViewModel(
    private val service: MatrixService,
    private val sourceRoomId: String,
    private val eventIds: List<String>
) : BaseViewModel<ForwardPickerUiState>(ForwardPickerUiState(eventCount = eventIds.size)) {

    sealed class Event {
        data class ForwardSuccess(val roomId: String, val roomName: String) : Event()
        data class ShowError(val message: String) : Event()
        data class ShowProgress(val current: Int, val total: Int) : Event()
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
            // Prefer RoomList cache (has recency), fallback to listRooms
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
                val mxc = room.avatarUrl ?: return@forEach
                if (!mxc.startsWith("mxc://")) return@forEach

                launch {
                    val path = service.avatars.resolve(mxc, px = 64, crop = true) ?: return@launch
                    updateState {
                        copy(
                            rooms = rooms.map { r ->
                                if (r.roomId == room.roomId) r.copy(avatarUrl = path) else r
                            }
                        )
                    }
                }
            }
        }
    }

    private fun loadEvents() {
        launch {
            val snapshot = runSafe { service.port.recent(sourceRoomId, 500) } ?: emptyList()
            eventsToForward = snapshot.filter { it.eventId in eventIds }
            updateState { copy(eventCount = eventsToForward.size) }
        }
    }

    fun setSearchQuery(query: String) {
        updateState { copy(searchQuery = query) }
    }

    fun forwardTo(targetRoomId: String, targetRoomName: String) {
        if (eventsToForward.isEmpty()) {
            launch { _events.send(Event.ShowError("No messages to forward")) }
            return
        }

        launch {
            updateState { copy(forwardingToRoomId = targetRoomId) }

            var successCount = 0
            val total = eventsToForward.size

            eventsToForward.forEachIndexed { index, event ->
                _events.send(Event.ShowProgress(index + 1, total))

                val success = forwardSingleMessage(event, targetRoomId)
                if (success) successCount++
            }

            updateState { copy(forwardingToRoomId = null) }

            if (successCount == total) {
                _events.send(Event.ForwardSuccess(targetRoomId, targetRoomName))
            } else if (successCount > 0) {
                _events.send(Event.ShowError("Forwarded $successCount of $total messages"))
                _events.send(Event.ForwardSuccess(targetRoomId, targetRoomName))
            } else {
                _events.send(Event.ShowError("Failed to forward messages"))
            }
        }
    }

    private suspend fun forwardSingleMessage(event: MessageEvent, targetRoomId: String): Boolean {
        return try {
            val attachment = event.attachment

            if (attachment != null) {
                service.port.sendExistingAttachment(
                    roomId = targetRoomId,
                    attachment = attachment,
                    body = event.body.takeIf { 
                        it.isNotBlank() && it != attachment.mxcUri && !it.startsWith("mxc://") 
                    }
                )
            } else {
                service.sendMessage(targetRoomId, event.body)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}