package org.mlm.mages.ui.viewmodel

import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.CancellationException
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.SearchHit

private const val HITS_PER_ROOM = 20

class SearchPagingSource(
    private val service: MatrixService,
    private val roomId: String?,
    private val query: String
) : PagingSource<Int, SearchHit>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, SearchHit> {
        return try {
            if (roomId != null) {
                val page = service.port.searchRoom(
                    roomId = roomId,
                    query = query,
                    limit = HITS_PER_ROOM,
                    offset = params.key
                )
                LoadResult.Page(
                    data = page.hits,
                    prevKey = null,
                    nextKey = page.nextOffset?.toInt()
                )
            } else {
                val rooms = service.port.listRooms()
                val index = (params.key ?: 0).coerceAtLeast(0)
                val room = rooms.getOrNull(index)
                    ?: return LoadResult.Page(data = emptyList(), prevKey = null, nextKey = null)

                val page = runCatching {
                    service.port.searchRoom(
                        roomId = room.id,
                        query = query,
                        limit = HITS_PER_ROOM,
                        offset = null
                    )
                }.getOrNull()

                LoadResult.Page(
                    data = page?.hits.orEmpty().sortedByDescending { it.timestampMs.toLong() },
                    prevKey = (index - 1).takeIf { it >= 0 },
                    nextKey = (index + 1).takeIf { it < rooms.size }
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, SearchHit>): Int? = null
}
