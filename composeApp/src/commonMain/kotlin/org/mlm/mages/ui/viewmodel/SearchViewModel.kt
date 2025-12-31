package org.mlm.mages.ui.viewmodel

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.SearchHit
import org.mlm.mages.ui.SearchUiState

class SearchViewModel(
    private val service: MatrixService,
    private val scopedRoomId: String? = null,
    private val scopedRoomName: String? = null
) : BaseViewModel<SearchUiState>(
    SearchUiState(
        scopedRoomId = scopedRoomId,
        scopedRoomName = scopedRoomName
    )
) {

    sealed class Event {
        data class OpenResult(val roomId: String, val eventId: String, val roomName: String) : Event()
        data class ShowError(val message: String) : Event()
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var searchJob: Job? = null
    private var debounceJob: Job? = null

    fun setQuery(query: String) {
        updateState { copy(query = query, error = null) }
        
        // Debounce search
        debounceJob?.cancel()
        if (query.length >= 2) {
            debounceJob = launch {
                delay(300)
                performSearch(reset = true)
            }
        } else if (query.isEmpty()) {
            updateState { copy(results = emptyList(), hasSearched = false, nextOffset = null) }
        }
    }

    fun search() {
        if (currentState.query.length < 2) return
        performSearch(reset = true)
    }

    fun loadMore() {
        if (currentState.isSearching || currentState.nextOffset == null) return
        performSearch(reset = false)
    }

    fun openResult(hit: SearchHit) {
        launch {
            // Get room name for navigation
            val roomName = if (hit.roomId == scopedRoomId) {
                scopedRoomName ?: hit.roomId
            } else {
                runSafe { service.port.roomProfile(hit.roomId)?.name } ?: hit.roomId
            }
            _events.send(Event.OpenResult(hit.roomId, hit.eventId, roomName))
        }
    }

    private fun performSearch(reset: Boolean) {
        val query = currentState.query.trim()
        if (query.length < 2) return

        searchJob?.cancel()
        searchJob = launch {
            updateState { 
                copy(
                    isSearching = true,
                    error = null,
                    results = if (reset) emptyList() else results,
                    nextOffset = if (reset) null else nextOffset
                )
            }

            try {
                val offset = if (reset) null else currentState.nextOffset

                if (scopedRoomId != null) {
                    // Room-scoped search
                    val page = service.port.searchRoom(
                        roomId = scopedRoomId,
                        query = query,
                        limit = 30,
                        offset = offset
                    )
                    
                    updateState {
                        copy(
                            isSearching = false,
                            hasSearched = true,
                            results = if (reset) page.hits else results + page.hits,
                            nextOffset = page.nextOffset?.toInt()
                        )
                    }
                } else {
                    // Global search across all rooms
                    val allHits = mutableListOf<SearchHit>()
                    val rooms = service.port.listRooms()
                    
                    for (room in rooms.take(50)) { // Limit rooms to prevent slowdown
                        val page = runSafe {
                            service.port.searchRoom(
                                roomId = room.id,
                                query = query,
                                limit = 10,
                                offset = null
                            )
                        }
                        if (page != null) {
                            allHits.addAll(page.hits)
                        }
                    }
                    
                    // Sort by timestamp descending
                    val sorted = allHits.sortedByDescending { it.timestampMs }
                    
                    updateState {
                        copy(
                            isSearching = false,
                            hasSearched = true,
                            results = sorted.take(100),
                            nextOffset = null // Global search doesn't paginate for now
                        )
                    }
                }
            } catch (e: Exception) {
                updateState {
                    copy(
                        isSearching = false,
                        hasSearched = true,
                        error = e.message ?: "Search failed"
                    )
                }
            }
        }
    }
}