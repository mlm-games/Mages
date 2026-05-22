package org.mlm.mages.ui.viewmodel

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.SpaceChildInfo
import org.mlm.mages.matrix.SpaceInfo
import org.mlm.mages.ui.SpaceDetailUiState

private fun List<SpaceChildInfo>.withoutSpace(spaceId: String): List<SpaceChildInfo> =
    filter { it.roomId != spaceId }

private fun mergeSpaceChildren(
    existing: List<SpaceChildInfo>,
    incoming: List<SpaceChildInfo>,
    append: Boolean,
): List<SpaceChildInfo> =
    if (!append) incoming else (existing + incoming).distinctBy { it.roomId }

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
                resolveAvatar(service, space.avatarUrl, 96) { path -> copy(spaceAvatarPath = path) }
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

            val result = service.spaceHierarchy(
                spaceId = currentState.spaceId,
                from = from,
                limit = 50,
                maxDepth = 2,
                suggestedOnly = false
            )

            if (result.isSuccess) {
                val page = result.getOrThrow()
                val children = page.children.withoutSpace(currentState.spaceId)
                val newHierarchy = mergeSpaceChildren(currentState.hierarchy, children, append = from != null)

                hydrateMissingSpaceChildNames(service, newHierarchy) { roomId, name ->
                    val updatedHierarchy = hierarchy.map { existing ->
                        if (existing.roomId == roomId && existing.name.isNullOrBlank()) {
                            existing.copy(name = name)
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

                resolveSpaceChildAvatars(service, newHierarchy) { roomId, path ->
                    copy(avatarPathByRoomId = avatarPathByRoomId + (roomId to path))
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
                        error = result.toUserMessage("Failed to load space contents")
                    ) 
                }
            }
        }
    }
}
