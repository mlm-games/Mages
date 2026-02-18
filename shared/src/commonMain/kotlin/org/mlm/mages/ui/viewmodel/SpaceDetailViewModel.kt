package org.mlm.mages.ui.viewmodel

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.SpaceChildInfo
import org.mlm.mages.matrix.SpaceInfo
import org.mlm.mages.ui.SpaceDetailUiState

class SpaceDetailViewModel(
    private val service: MatrixService,
    spaceId: String,
    spaceName: String
) : BaseViewModel<SpaceDetailUiState>(
    SpaceDetailUiState(spaceId = spaceId, spaceName = spaceName, isLoading = true)
) {

    // One-time events
    sealed class Event {
        data class OpenSpace(val spaceId: String, val name: String) : Event()
        data class OpenRoom(val roomId: String, val name: String) : Event()
        data class ShowError(val message: String) : Event()
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        loadSpaceInfo()
        loadHierarchy()
    }

    //  Public Actions 

    fun refresh() {
        loadSpaceInfo()
        loadHierarchy()
    }

    fun loadMore() {
        val nextBatch = currentState.nextBatch ?: return
        if (currentState.isLoadingMore) return
        loadHierarchy(from = nextBatch)
    }

    fun openChild(child: SpaceChildInfo) {
        launch {
            val displayName = child.name ?: child.alias ?: child.roomId
            if (child.isSpace) {
                _events.send(Event.OpenSpace(child.roomId, displayName))
            } else {
                _events.send(Event.OpenRoom(child.roomId, displayName))
            }
        }
    }

    //  Private Methods 

    private fun loadSpaceInfo() {
        launch {
            val spaces = runSafe { service.mySpaces() } ?: emptyList()
            val space = spaces.find { it.roomId == currentState.spaceId }
            
            if (space != null) {
                updateState { copy(space = space) }
                val mxc = space.avatarUrl
                if (!mxc.isNullOrBlank() && mxc.startsWith("mxc://")) {
                    val path = runSafe { service.avatars.resolve(mxc, px = 96, crop = true) }
                    if (path != null) updateState { copy(spaceAvatarPath = path) }
                }
            } else {
                updateState { 
                    copy(
                        space = SpaceInfo(
                            roomId = spaceId,
                            name = spaceName,
                            topic = null,
                            memberCount = 0,
                            isEncrypted = false,
                            isPublic = false,
                            avatarUrl = null
                        )
                    ) 
                }
            }
        }
    }

    private fun loadHierarchy(from: String? = null) {
        launch(
            onError = { t ->
                updateState { 
                    copy(
                        isLoading = false,
                        isLoadingMore = false,
                        error = t.message ?: "Failed to load hierarchy"
                    ) 
                }
            }
        ) {
            if (from == null) {
                updateState { copy(isLoading = true, error = null) }
            } else {
                updateState { copy(isLoadingMore = true) }
            }

            val page = service.spaceHierarchy(
                spaceId = currentState.spaceId,
                from = from,
                limit = 50,
                maxDepth = 2,
                suggestedOnly = false
            )

            if (page != null) {
                val children = page.children.filter { it.roomId != currentState.spaceId }
                
                val newHierarchy = if (from == null) {
                    children
                } else {
                    (currentState.hierarchy + children).distinctBy { it.roomId }
                }

                // Fetch room profiles for names (Matrix space hierarchy returns summaries without names)
                newHierarchy.filter { !it.isSpace && it.name.isNullOrBlank() }.forEach { child ->
                    launch {
                        val profile = runSafe { service.port.roomProfile(child.roomId) }
                        if (profile != null && profile.name.isNotBlank()) {
                            updateState {
                                val updatedHierarchy = hierarchy.map { existing ->
                                    if (existing.roomId == child.roomId && existing.name.isNullOrBlank()) {
                                        existing.copy(name = profile.name)
                                    } else {
                                        existing
                                    }
                                }
                                val (subspaces, rooms) = updatedHierarchy.partition { it.isSpace }
                                copy(
                                    hierarchy = updatedHierarchy,
                                    subspaces = subspaces,
                                    rooms = rooms
                                )
                            }
                        }
                    }
                }

                // Prefetch avatars
                newHierarchy.forEach { child ->
                    child.avatarUrl?.let { url ->
                        if (url.startsWith("mxc://")) {
                            launch {
                                val path = service.avatars.resolve(url, px = 64, crop = true)
                                if (path != null) {
                                    updateState { 
                                        copy(avatarPathByRoomId = avatarPathByRoomId + (child.roomId to path))
                                    }
                                }
                            }
                        }
                    }
                }

                val (subspaces, rooms) = newHierarchy.partition { it.isSpace }

                updateState {
                    copy(
                        hierarchy = newHierarchy,
                        subspaces = subspaces,
                        rooms = rooms,
                        nextBatch = page.nextBatch,
                        isLoading = false,
                        isLoadingMore = false
                    )
                }
            } else {
                updateState { 
                    copy(
                        isLoading = false,
                        isLoadingMore = false,
                        error = "Failed to load space contents"
                    ) 
                }
            }
        }
    }
}