package org.mlm.mages.ui.viewmodel

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import mages.shared.generated.resources.*
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.RoomJoinRule
import org.mlm.mages.matrix.RoomListMembership
import org.mlm.mages.matrix.SpaceChildInfo
import org.mlm.mages.matrix.SpaceInfo
import org.mlm.mages.matrix.SpaceSection
import org.mlm.mages.ui.SpaceDetailUiState
import org.mlm.mages.ui.SpaceSectionEntry
import org.jetbrains.compose.resources.getString
import mages.shared.generated.resources.Res

private fun List<SpaceChildInfo>.withoutSpace(spaceId: String): List<SpaceChildInfo> =
    filter { it.roomId != spaceId }

private fun mergeSpaceChildren(
    existing: List<SpaceChildInfo>,
    incoming: List<SpaceChildInfo>,
    append: Boolean,
): List<SpaceChildInfo> =
    if (!append) incoming else (existing + incoming).distinctBy { it.roomId }

private const val HIERARCHY_PAGE_SIZE = 50

private fun groupChildrenIntoSections(
    children: List<SpaceChildInfo>,
    sections: List<SpaceSection>,
    defaultName: String,
): List<SpaceSectionEntry> {
    val known = sections.associateBy { it.tag }
    val entries = sections.map { section ->
        val (spaces, rooms) = children
            .filter { it.sectionTag == section.tag }
            .partition { it.isSpace }
        SpaceSectionEntry(
            tag = section.tag,
            name = section.name,
            bornIn = section.spaceId,
            subspaces = spaces,
            rooms = rooms
        )
    }

    val (spaces, rooms) = children
        .filter { child -> child.sectionTag == null || child.sectionTag !in known }
        .partition { it.isSpace }

    return entries + SpaceSectionEntry(
        name = defaultName,
        subspaces = spaces,
        rooms = rooms
    )
}

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
        data class ShowMessage(val message: String) : Event()
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        loadSpaceInfo()
        loadHierarchy()
        loadSections()
    }

    //  Public Actions 

    fun refresh() {
        loadSpaceInfo()
        loadHierarchy()
        loadSections()
    }

    fun refreshUntilRoomPresent(roomId: String) {
        launch {
            repeat(CHILDREN_RELOAD_ATTEMPTS) { attempt ->
                if (attempt > 0) delay(CHILDREN_RELOAD_DELAY_MS * attempt)
                loadHierarchy(silent = true).join()
                if (currentState.hierarchy.any { it.roomId == roomId }) return@launch
            }
        }
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
                return@launch
            }
            when (child.membership) {
                RoomListMembership.Joined,
                RoomListMembership.Invited,
                RoomListMembership.Knocked -> _events.send(Event.OpenRoom(child.roomId, displayName))

                else -> joinChild(child, displayName)
            }
        }
    }

    fun showCreateSection() = updateState {
        copy(editingSection = SpaceSectionEntry(name = ""))
    }

    fun showEditSection(entry: SpaceSectionEntry) = updateState {
        copy(editingSection = entry)
    }

    fun hideSectionEditor() = updateState { copy(editingSection = null) }

    fun setSectionName(name: String) = updateState {
        copy(editingSection = editingSection?.copy(name = name))
    }

    fun saveSection() {
        val editing = currentState.editingSection ?: return
        val name = editing.name.trim()
        if (name.isBlank()) {
            launch { _events.send(Event.ShowError(getString(Res.string.give_the_section_a_name))) }
            return
        }
        launch(
            onError = { t ->
                updateState { copy(isSavingSection = false, editingSection = editing) }
                launch {
                    _events.send(Event.ShowError(t.failureMessage(getString(Res.string.failed_to_save_section))))
                }
            }
        ) {
            updateState { copy(isSavingSection = true) }
            if (editing.tag == null) {
                val created = service.createSection(name, currentState.spaceId)
                    .getOrElse { error ->
                        updateState { copy(isSavingSection = false, editingSection = editing) }
                        _events.send(Event.ShowError(error.failureMessage(getString(Res.string.failed_to_create_section))))
                        return@launch
                    }
                loadSections()
                _events.send(Event.ShowMessage(getString(Res.string.section_created_named, created.name)))
            } else {
                service.renameSection(editing.tag, name)
                    .onFailure { error ->
                        updateState { copy(isSavingSection = false, editingSection = editing) }
                        _events.send(Event.ShowError(error.failureMessage(getString(Res.string.failed_to_save_section))))
                        return@launch
                    }
                loadSections()
                _events.send(Event.ShowMessage(getString(Res.string.section_renamed_to_named, name)))
            }
            updateState { copy(isSavingSection = false, editingSection = null) }
        }
    }

    fun deleteSection(tag: String) {
        launch(
            onError = { t ->
                updateState { copy(isSavingSection = false) }
                launch {
                    _events.send(Event.ShowError(t.failureMessage(getString(Res.string.failed_to_delete_section))))
                }
            }
        ) {
            updateState { copy(isSavingSection = true) }
            service.deleteSection(tag)
                .onFailure { error ->
                    updateState { copy(isSavingSection = false) }
                    _events.send(Event.ShowError(error.failureMessage(getString(Res.string.failed_to_delete_section))))
                    return@launch
                }
            updateState { copy(isSavingSection = false) }
            loadSections()
            loadHierarchy(silent = true)
            _events.send(Event.ShowMessage(getString(Res.string.section_deleted)))
        }
    }

    fun moveSection(entry: SpaceSectionEntry, steps: Int) {
        val tag = entry.tag ?: return
        val order = allSections.map { it.tag }
        val from = order.indexOf(tag)
        if (from == -1) return
        val to = (from + steps).coerceIn(0, order.lastIndex)
        if (to == from) return
        launch {
            service.moveSection(tag, to)
                .onFailure {
                    _events.send(Event.ShowError(it.failureMessage(getString(Res.string.failed_to_reorder_sections))))
                    return@launch
                }
            loadSections()
        }
    }

    fun showMoveToSection(roomId: String) = updateState { copy(movingRoomId = roomId) }

    fun hideMoveToSection() = updateState { copy(movingRoomId = null) }

    fun setRoomSection(roomId: String, tag: String?) {
        launch {
            service.setRoomSection(roomId, tag)
                .onFailure {
                    _events.send(Event.ShowError(it.failureMessage(getString(Res.string.failed_to_move_into_section))))
                    return@launch
                }
            updateState {
                copy(
                    movingRoomId = null,
                    hierarchy = hierarchy.map {
                        if (it.roomId == roomId) it.copy(sectionTag = tag) else it
                    }
                )
            }
            regroupHierarchy()
            _events.send(
                Event.ShowMessage(
                    if (tag == null) getString(Res.string.moved_out_of_a_section)
                    else getString(Res.string.moved_into_section)
                )
            )
        }
    }

    private fun joinChild(child: SpaceChildInfo, displayName: String) {
        launch {
            val target = child.alias ?: child.roomId
            val joinRule = service.port.roomPreview(target).getOrNull()?.joinRule

            val result = when (joinRule) {
                RoomJoinRule.Knock, RoomJoinRule.KnockRestricted -> service.port.knock(target)
                else -> service.port.joinByIdOrAlias(target)
            }

            if (result.isFailure) {
                _events.send(Event.ShowError(getString(Res.string.could_not_join_named, displayName)))
                return@launch
            }

            if (joinRule == RoomJoinRule.Knock || joinRule == RoomJoinRule.KnockRestricted) {
                _events.send(Event.ShowMessage(getString(Res.string.knocked_on_named, displayName)))
            } else {
                _events.send(Event.OpenRoom(child.roomId, displayName))
            }
            loadHierarchy()
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

    private var allSections: List<SpaceSection> = emptyList()

    private fun loadSections() {
        launch {
            val sections = runSafe { service.listSections() } ?: return@launch
            allSections = sections
            regroupHierarchy()
        }
    }

    private fun regroupHierarchy() {
        launch {
            val defaultName = getString(Res.string.rooms_and_spaces)
            val entries = groupChildrenIntoSections(currentState.hierarchy, allSections, defaultName)
            updateState { copy(sections = entries) }
        }
    }

    private fun loadHierarchy(from: String? = null, silent: Boolean = false): Job =
        launch(
            onError = { t ->
                if (!silent) {
                    val text = t.message ?: getString(Res.string.failed_to_load_hierarchy)
                    updateState {
                        copy(
                            isLoading = false,
                            isLoadingMore = false,
                            error = text
                        )
                    }
                }
            }
        ) {
            if (from == null) {
                if (!silent) updateState { copy(isLoading = true, error = null) }
            } else {
                updateState { copy(isLoadingMore = true) }
            }

            val result = service.spaceHierarchy(
                spaceId = currentState.spaceId,
                from = from,
                limit = HIERARCHY_PAGE_SIZE,
                maxDepth = 1,
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
                    copy(hierarchy = updatedHierarchy)
                }

                resolveSpaceChildAvatars(service, newHierarchy) { roomId, path ->
                    copy(avatarPathByRoomId = avatarPathByRoomId + (roomId to path))
                }

                updateState {
                    copy(
                        hierarchy = newHierarchy,
                        nextBatch = page.nextBatch,
                        isLoading = false,
                        isLoadingMore = false
                    )
                }
                regroupHierarchy()
            } else if (!silent) {
                val text = result.toUserMessage(getString(Res.string.failed_to_load_space_contents))
                updateState {
                    copy(
                        isLoading = false,
                        isLoadingMore = false,
                        error = text
                    )
                }
            }
        }
}
