package org.mlm.mages.ui.viewmodel

import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.SearchHit
import org.mlm.mages.ui.SearchUiState

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
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

    private val _queryFlow = MutableStateFlow("")

    val searchResults: Flow<PagingData<SearchHit>> = _queryFlow
        .debounce(300)
        .filter { it.length >= 2 }
        .flatMapLatest { query ->
            Pager(
                config = PagingConfig(
                    pageSize = 30,
                    enablePlaceholders = false,
                    prefetchDistance = 5
                )
            ) {
                SearchPagingSource(service, scopedRoomId, query)
            }.flow
        }
        .cachedIn(viewModelScope)

    fun setQuery(query: String) {
        updateState { copy(query = query, error = null) }
        _queryFlow.value = query
    }

    fun openResult(hit: SearchHit) {
        launch {
            val roomName = if (hit.roomId == scopedRoomId) {
                scopedRoomName ?: hit.roomId
            } else {
                runSafe { service.port.roomProfile(hit.roomId)?.name } ?: hit.roomId
            }
            _events.send(Event.OpenResult(hit.roomId, hit.eventId, roomName))
        }
    }
}
