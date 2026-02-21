package org.mlm.mages.ui.viewmodel

import androidx.paging.PagingSource
import androidx.paging.PagingState
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.SearchHit

class SearchPagingSource(
    private val service: MatrixService,
    private val roomId: String?,
    private val query: String
) : PagingSource<Int, SearchHit>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, SearchHit> {
        return try {
            val offset = params.key

            if (roomId != null) {
                val page = service.port.searchRoom(
                    roomId = roomId,
                    query = query,
                    limit = params.loadSize,
                    offset = offset
                )
                LoadResult.Page(
                    data = page.hits,
                    prevKey = null,
                    nextKey = page.nextOffset?.toInt()
                )
            } else {
                val allHits = mutableListOf<SearchHit>()
                val rooms = service.port.listRooms()
                for (room in rooms.take(50)) {
                    val page = runCatching {
                        service.port.searchRoom(
                            roomId = room.id,
                            query = query,
                            limit = 10,
                            offset = null
                        )
                    }.getOrNull()
                    if (page != null) allHits.addAll(page.hits)
                }
                LoadResult.Page(
                    data = allHits.sortedByDescending { it.timestampMs.toLong() }.take(100),
                    prevKey = null,
                    nextKey = null
                )
            }
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, SearchHit>): Int? = null
}
