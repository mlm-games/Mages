package org.mlm.mages.ui.viewmodel
import co.touchlab.kermit.Logger

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import mages.shared.generated.resources.*
import org.koin.core.component.inject
import org.mlm.mages.MatrixService
import org.mlm.mages.RoomSummary
import org.mlm.mages.matrix.LatestRoomEvent
import org.mlm.mages.matrix.MatrixPort
import org.mlm.mages.matrix.RoomListEntry
import org.mlm.mages.platform.LiveLocationSharingCoordinator
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.settings.RoomSwipeAction
import org.mlm.mages.ui.LastMessageType
import org.mlm.mages.ui.RoomListItemUi
import org.mlm.mages.ui.RoomTypeFilter
import org.mlm.mages.ui.RoomsUiState
import org.mlm.mages.ui.SpaceBadgeUi
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.StringResource
import mages.shared.generated.resources.Res

class RoomsViewModel(
    private val service: MatrixService
) : BaseViewModel<RoomsUiState>(RoomsUiState(isLoading = true)) {

    private val settingsRepo: SettingsRepository<AppSettings> by inject()
    private val settings = settingsRepo.flow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    // One-time events
    sealed class Event {
        data class OpenRoom(val roomId: String, val name: String) : Event()
        data class ShowError(val message: String) : Event()
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var connToken: ULong? = null
    private var roomListToken: ULong? = null
    private var initialized = false
    private var observerJob: Job? = null
    private var subscribePrefetchJob: Job? = null

    init {
        launch {
            settingsRepo.flow.collect {
                recomputeGroupedRooms()
                updateState {
                    copy(
                        swipeRightAction = it.swipeRightAction,
                        swipeLeftAction = it.swipeLeftAction
                    )
                }
            }
        }

        observerJob = launch {
            while (!service.isLoggedInSuspend()) {
                delay(100)
            }

            service.activeAccount.collect { account ->
                if (account != null) {
                    resetObservers()
                    observeConnection()
                    bootstrapRoomListFromCache()
                    observeRoomList()
                }
            }
        }
    }

    private fun resetObservers() {
        roomListToken?.let { token ->
            runCatching { service.portOrNull?.unobserveRoomList(token) }
        }
        roomListToken = null

        connToken?.let { token ->
            runCatching { service.portOrNull?.stopConnectionObserver(token) }
        }
        connToken = null

        initialized = false
        updateState { RoomsUiState(isLoading = true) }
    }

    //  Public Actions

    fun setSearchQuery(query: String) {
        updateState { copy(roomSearchQuery = query) }
        recomputeGroupedRooms()
    }

    fun toggleUnreadOnly() {
        val next = !currentState.unreadOnly
        updateState { copy(unreadOnly = next) }

        roomListToken?.let { token ->
            launch { service.port.roomListSetUnreadOnly(token, next) }
        }
        recomputeGroupedRooms()
    }

    fun setTypeFilter(filter: RoomTypeFilter) {
        if (currentState.typeFilter == filter) {
            updateState { copy(typeFilter = RoomTypeFilter.All) }
        } else {
            updateState { copy(typeFilter = filter) }
        }
        recomputeGroupedRooms()
    }

    fun openRoom(room: RoomSummary) {
        launch {
            _events.send(Event.OpenRoom(room.id, room.name))
        }
    }

    fun acceptInvite(roomId: String) {
        launch {
            val result = runCatching { service.port.acceptInvite(roomId) }
            val success = result.getOrNull()?.isSuccess == true
            if (success) {
                recomputeGroupedRooms()
            } else {
                val message = result.exceptionOrNull()?.message
                    ?: result.getOrNull()?.exceptionOrNull()?.message
                    ?: getString(Res.string.could_not_accept_the_invite_try_again)
                _events.send(Event.ShowError(message))
            }
        }
    }

    fun showDeclineInvite(roomId: String) {
        launch {
            val room = currentState.inviteItems.firstOrNull { it.roomId == roomId }
            updateState {
                copy(
                    declineInviteRoomId = roomId,
                    declineInviteRoomName = room?.name ?: roomId,
                    declineInviteInviterName = null,
                )
            }
            val inviterId = runCatching { service.port.roomInviter(roomId) }.getOrNull()
            if (inviterId.isNullOrBlank()) return@launch
            val profile = runCatching { service.port.getUserProfile(inviterId) }.getOrNull()
            if (currentState.declineInviteRoomId != roomId) return@launch
            updateState { copy(declineInviteInviterName = profile?.displayName ?: inviterId) }
        }
    }

    fun hideDeclineInvite() = updateState {
        copy(
            declineInviteRoomId = null,
            declineInviteRoomName = "",
            declineInviteInviterName = null,
            isDecliningInvite = false,
        )
    }

    fun declineInvite(blockUser: Boolean, reportRoom: Boolean, reportReason: String) {
        val roomId = currentState.declineInviteRoomId ?: return
        if (currentState.isDecliningInvite) return
        launch {
            updateState { copy(isDecliningInvite = true) }
            val inviterId = if (blockUser) {
                runCatching { service.port.roomInviter(roomId) }.getOrNull()
            } else {
                null
            }

            val leaveResult = service.port.leaveRoom(roomId)
            if (leaveResult.isFailure) {
                updateState { copy(isDecliningInvite = false) }
                _events.send(Event.ShowError(getString(Res.string.could_not_decline_the_invite_try_again)))
                return@launch
            }

            if (!inviterId.isNullOrBlank()) {
                runCatching { service.port.ignoreUser(inviterId) }
            }
            if (reportRoom) {
                runCatching { service.port.reportRoom(roomId, reportReason.ifBlank { null }) }
            }

            hideDeclineInvite()
            recomputeGroupedRooms()
        }
    }

    fun markRead(roomId: String) {
        launch {
            service.port.markRead(roomId, settings.value.sendReadReceipts).onSuccess {
                updateState {
                    copy(unread = unread - roomId)
                }
                recomputeGroupedRooms()
            }
        }
    }

    fun markUnread(roomId: String) {
        launch {
            service.port.setMarkUnread(roomId, true).onSuccess {
                updateState {
                    copy(
                        unread = unread + (roomId to 1),
                        allItems = allItems.map { item ->
                            if (item.roomId == roomId) item.copy(hasUnreadMessages = true, isMarkedUnread = true) else item
                        }
                    )
                }
                recomputeGroupedRooms()
            }
        }
    }

    fun onListSwipeAction(action: RoomSwipeAction, roomId: String) {
        when (action) {
            RoomSwipeAction.MarkRead -> markRead(roomId)
            RoomSwipeAction.MarkUnread -> markUnread(roomId)
            RoomSwipeAction.Nothing -> Unit
        }
    }

    fun toggleFavourite(roomId: String, currentFavourite: Boolean) {
        launch {
            service.port.setRoomFavourite(roomId, !currentFavourite).onSuccess {
                updateState {
                    copy(
                        favourites = if (!currentFavourite) favourites + roomId else favourites - roomId
                    )
                }
                recomputeGroupedRooms()
            }
        }
    }

    fun toggleLowPriority(roomId: String, currentLowPriority: Boolean) {
        launch {
            service.port.setRoomLowPriority(roomId, !currentLowPriority).onSuccess {
                updateState {
                    copy(
                        lowPriority = if (!currentLowPriority) lowPriority + roomId else lowPriority - roomId
                    )
                }
                recomputeGroupedRooms()
            }
        }
    }

    fun refresh() {
        updateState { copy(isLoading = true) }
        runCatching { service.port.enterForeground() }
    }

    fun updateVisibleRange(range: IntRange, threshold: Int = 60) {
        roomListToken?.let { token ->
            launch {
                service.port.roomListUpdateVisibleRange(token, range.toList(), threshold)
                    .onFailure { Logger.w("Failed to update visible range: ${it.message}") }
            }
        }
        subscribePrefetchJob?.cancel()
        subscribePrefetchJob = launch {
            delay(300)
            if (range.isEmpty()) return@launch
            val items = currentState.allItems
            if (items.isEmpty()) return@launch
            val extendedEnd = (range.last + 20).coerceAtMost(items.lastIndex)
            val clamped = range.first.coerceIn(0, items.lastIndex)..extendedEnd
            if (clamped.isEmpty()) return@launch
            val roomIds = clamped.mapNotNull { items.getOrNull(it)?.roomId }
                .take(40)
            if (roomIds.isEmpty()) return@launch
            service.port.subscribeToVisibleRooms(roomIds)
                .onFailure { Logger.w("Failed to subscribe visible rooms: ${it.message}") }
        }
    }

    private fun RoomListEntry.unreadMarkerCount(): Int {
        val messages = messages.toInt()
        return if (markedUnread) maxOf(messages, 1) else messages
    }

    private fun mapRoomSummary(entry: RoomListEntry): RoomSummary {
        return RoomSummary(
            id = entry.roomId,
            name = entry.name,
            avatarUrl = entry.avatarUrl,
            isDm = entry.isDm,
            isEncrypted = entry.isEncrypted
        )
    }

    private fun mapRoomEntryToUi(entry: RoomListEntry): RoomListItemUi {
        val lastEvent = entry.latestEvent
        val lastType = determineMessageType(lastEvent)
        val lastBody = formatBodyForPreview(lastEvent, lastType)
        val lastLabel = bodyLabelForPreview(lastEvent, lastType)
        val spaces = currentState.parentSpaces[entry.roomId].orEmpty()

        return RoomListItemUi(
            roomId = entry.roomId,
            name = entry.name,
            avatarUrl = entry.avatarUrl,
            isDm = entry.isDm,
            isEncrypted = entry.isEncrypted,
            unreadCount = entry.notifications.toInt(),
            hasUnreadMessages = entry.messages > 0u || entry.markedUnread,
            isMarkedUnread = entry.markedUnread,
            isFavourite = entry.isFavourite,
            isLowPriority = entry.isLowPriority,
            isInvited = entry.isInvited,
            lastMessageBody = lastBody,
            lastMessageLabel = lastLabel,
            lastMessageSender = lastEvent?.sender,
            lastMessageType = lastType,
            lastMessageTs = lastEvent?.timestamp,
            isSharingLocation = LiveLocationSharingCoordinator.isSharing(entry.roomId),
            parentSpaces = if (settings.value.showSpaceBadgeInRoomList) {
                spaces.map { space ->
                    SpaceBadgeUi(
                        spaceId = space.spaceId,
                        name = space.name,
                        avatarUrl = currentState.parentSpaceAvatarPath[space.spaceId]
                    )
                }
            } else {
                emptyList()
            }
        )
    }

    // Only re-resolve when the visible room set actually changes; the search box and the
    // type filter call recomputeGroupedRooms on every keystroke. The key lives in the state
    // so it is cleared together with the maps it guards when observers are reset.
    private fun loadParentSpaces() {
        val roomIds = currentState.allItems.map { it.roomId }
        if (roomIds.isEmpty()) return
        val key = roomIds.joinToString(",")
        if (key == currentState.parentSpacesResolvedKey) return
        updateState { copy(parentSpacesResolvedKey = key) }
        launch {
            val byRoom = buildMap {
                for (roomId in roomIds) {
                    val spaces = runSafe { service.roomParentSpaces(roomId) }.orEmpty()
                    if (spaces.isNotEmpty()) put(roomId, spaces)
                }
            }
            updateState {
                fun List<RoomListItemUi>.withBadgeVisibility() = map { item ->
                    if (settings.value.showSpaceBadgeInRoomList) item
                    else item.copy(parentSpaces = emptyList())
                }

                fun List<RoomListItemUi>.withSpaces() = map { item ->
                    val spaces = byRoom[item.roomId]
                    if (spaces == null) item
                    else item.copy(
                        parentSpaces = spaces.map { space ->
                            SpaceBadgeUi(
                                spaceId = space.spaceId,
                                name = space.name,
                                avatarUrl = parentSpaceAvatarPath[space.spaceId]
                            )
                        }
                    )
                }.withBadgeVisibility()
                copy(
                    parentSpaces = byRoom,
                    allItems = allItems.withSpaces(),
                    favouriteItems = favouriteItems.withSpaces(),
                    normalItems = normalItems.withSpaces(),
                    lowPriorityItems = lowPriorityItems.withSpaces(),
                    inviteItems = inviteItems.withSpaces()
                )
            }
            byRoom.values.flatten().distinctBy { it.spaceId }.forEach { space ->
                maybePrefetchParentSpaceAvatar(space.spaceId, space.avatarUrl)
            }
            recomputeGroupedRooms()
        }
    }


    private fun determineMessageType(event: LatestRoomEvent?): LastMessageType {
        if (event == null) return LastMessageType.Unknown
        if (event.isRedacted) return LastMessageType.Redacted

        val msgtype = event.msgtype
        val evType = event.eventType

        return when {
            msgtype == "m.image"    -> LastMessageType.Image
            msgtype == "m.video"    -> LastMessageType.Video
            msgtype == "m.audio"    -> LastMessageType.Audio
            msgtype == "m.file"     -> LastMessageType.File
            msgtype == "m.sticker"  -> LastMessageType.Sticker
            msgtype == "m.location" -> LastMessageType.Location
            evType  == "m.poll.start"   -> LastMessageType.Poll
            evType  == "m.call.invite"  -> LastMessageType.Call
            evType  == "m.rtc.notification" -> LastMessageType.Call
            evType  == "m.call.notify" -> LastMessageType.Call
            evType  == "m.room.member" -> LastMessageType.Membership
            event.isEncrypted && event.body == null -> LastMessageType.Encrypted
            else -> LastMessageType.Text
        }
    }

    private fun formatBodyForPreview(event: LatestRoomEvent?, type: LastMessageType): String? {
        if (event == null) return null
        if (type == LastMessageType.Call) return event.body
        val body = event.body

        if (body != null && body.startsWith("mxc://")) return null
        return body
    }

    private fun bodyLabelForPreview(event: LatestRoomEvent?, type: LastMessageType): StringResource? {
        if (event == null) return null
        if (type == LastMessageType.Call) return if (event.body == null) Res.string.call else null
        val body = event.body

        if (body != null && body.startsWith("mxc://")) {
            return when (type) {
                LastMessageType.Image -> Res.string.photo
                LastMessageType.Video -> Res.string.video
                LastMessageType.Audio -> Res.string.audio
                LastMessageType.File  -> Res.string.file
                else -> null
            }
        }
        return null
    }

    //  Private Methods

    private fun observeRoomList() {
        if (roomListToken != null) return

        launch {
            try {
                roomListToken = service.port.observeRoomList(object : MatrixPort.RoomListObserver {
                    override fun onReset(items: List<RoomListEntry>) {
                        initialized = true

                        if (items.isEmpty() && currentState.allItems.isNotEmpty() && currentState.offlineBanner != null) {
                            updateState { copy(isLoading = false) }
                            return
                        }

                        items.forEach { maybePrefetchRoomAvatar(it.roomId, it.avatarUrl) }
                        val domainRooms = items.map(::mapRoomSummary)
                        val uiItems     = items.map(::mapRoomEntryToUi)

                        updateState {
                            copy(
                                rooms = domainRooms,
                                unread = items.associate { e -> e.roomId to e.unreadMarkerCount() },
                                favourites = items.filter { e -> e.isFavourite }.map { e -> e.roomId }.toSet(),
                                lowPriority = items.filter { e -> e.isLowPriority }.map { e -> e.roomId }.toSet(),
                                allItems = uiItems,
                                isLoading = false
                            )
                        }
                        recomputeGroupedRooms()
                    }

                    override fun onUpdate(item: RoomListEntry) {
                        maybePrefetchRoomAvatar(item.roomId, item.avatarUrl)
                        updateState {
                            val updatedRooms = rooms.map { room ->
                                if (room.id == item.roomId) mapRoomSummary(item) else room
                            }

                            val updatedUiItems = allItems.map { existing ->
                                if (existing.roomId == item.roomId) mapRoomEntryToUi(item) else existing
                            }

                            val updatedUnread = unread.toMutableMap().apply {
                                put(item.roomId, item.unreadMarkerCount())
                            }

                            val updatedFavourites =
                                if (item.isFavourite) favourites + item.roomId else favourites - item.roomId
                            val updatedLowPriority =
                                if (item.isLowPriority) lowPriority + item.roomId else lowPriority - item.roomId

                            copy(
                                rooms = updatedRooms,
                                unread = updatedUnread,
                                favourites = updatedFavourites,
                                lowPriority = updatedLowPriority,
                                allItems = updatedUiItems
                            )
                        }
                        recomputeGroupedRooms()
                    }
                })
            } catch (e: Exception) {
                Logger.w("Failed to observe room list: ${e.message}")
            }
        }
    }

    private fun observeConnection() {
        if (connToken != null) return

        launch {
            try {
                connToken = service.portOrNull?.observeConnection(object : MatrixPort.ConnectionObserver {
                    override fun onConnectionChange(state: MatrixPort.ConnectionState) {
                        val banner: StringResource? = when (state) {
                            MatrixPort.ConnectionState.Disconnected -> Res.string.no_connection
                            MatrixPort.ConnectionState.Reconnecting -> Res.string.reconnecting
                            MatrixPort.ConnectionState.Connecting -> Res.string.connecting
                            else -> null
                        }
                        updateState {
                            copy(
                                offlineBanner = banner,
                                isLoading = if (banner != null && allItems.isEmpty()) false else isLoading
                            )
                        }
                    }
                })
            } catch (e: Exception) {
                Logger.w("Failed to observe connection: ${e.message}")
            }
        }
    }


    private fun recomputeGroupedRooms() {
        val s = currentState
        val query = s.roomSearchQuery.trim()
        val includeSilent = settings.value.includeSilentUnreadInFilter

        fun RoomListItemUi.hasUnread(): Boolean =
            isMarkedUnread || if (includeSilent) hasUnreadMessages || unreadCount > 0 else unreadCount > 0

        val visibleRooms = if (settings.value.hideSpaceRoomsInRoomList) {
            s.allItems.filter {
                it.isInvited || s.parentSpaces[it.roomId].isNullOrEmpty()
            }
        } else {
            s.allItems
        }

        var list = visibleRooms

        if (query.isNotBlank()) {
            list = list.filter {
                it.name.contains(query, ignoreCase = true) ||
                        it.roomId.contains(query, ignoreCase = true)
            }
        }

        if (s.unreadOnly) {
            list = list.filter { it.hasUnread() }
        }

        list = when (s.typeFilter) {
            RoomTypeFilter.All -> list
            RoomTypeFilter.Groups -> list.filter { !it.isDm }
            RoomTypeFilter.Dms -> list.filter { it.isDm }
            RoomTypeFilter.Invites -> list.filter { it.isInvited }
        }

        fun sortUnread(items: List<RoomListItemUi>): List<RoomListItemUi> {
            if (!s.unreadOnly || !includeSilent) return items
            return items.sortedByDescending { it.unreadCount > 0 || it.isMarkedUnread }
        }

        val allFiltered = visibleRooms.filter { !it.isInvited }
        val unreadChatCount = allFiltered.count { it.hasUnread() }
        val unreadGroupsCount = allFiltered.count { !it.isDm && it.hasUnread() }
        val unreadDmsCount = allFiltered.count { it.isDm && it.hasUnread() }
        val spacesUnreadCount = s.allItems.count { item ->
            if (item.isInvited || s.parentSpaces[item.roomId].isNullOrEmpty()) {
                false
            } else {
                item.hasUnread()
            }
        }

        val favourites  = sortUnread(list.filter { it.isFavourite })
        val lowPriority = sortUnread(list.filter { it.isLowPriority })
        val normal      = sortUnread(list.filter { !it.isFavourite && !it.isLowPriority && !it.isInvited })
        val invites     = visibleRooms.filter { it.isInvited }

        updateState {
            copy(
                favouriteItems = favourites,
                normalItems = normal,
                lowPriorityItems = lowPriority,
                inviteItems = invites,
                unreadChatCount = unreadChatCount,
                unreadGroupsCount = unreadGroupsCount,
                unreadDmsCount = unreadDmsCount,
                spacesUnreadCount = spacesUnreadCount,
            )
        }
        loadParentSpaces()
    }

    private fun maybePrefetchRoomAvatar(roomId: String, avatarMxc: String?) {
        if (avatarMxc.isNullOrBlank()) return
        if (currentState.roomAvatarPath.containsKey(roomId)) return

        resolveAvatar(service, avatarMxc, 96) { path ->
            copy(roomAvatarPath = roomAvatarPath + (roomId to path))
        }
    }

    private fun maybePrefetchParentSpaceAvatar(spaceId: String, avatarMxc: String?) {
        if (avatarMxc.isNullOrBlank()) return
        if (currentState.parentSpaceAvatarPath.containsKey(spaceId)) return

        resolveAvatar(service, avatarMxc, 64) { path ->
            val updated = copy(parentSpaceAvatarPath = parentSpaceAvatarPath + (spaceId to path))
            fun List<RoomListItemUi>.withSpacePath() = map { item ->
                if (item.parentSpaces.none { it.spaceId == spaceId }) item
                else item.copy(
                    parentSpaces = item.parentSpaces.map { badge ->
                        if (badge.spaceId == spaceId) badge.copy(avatarUrl = path) else badge
                    }
                )
            }
            updated.copy(
                allItems = updated.allItems.withSpacePath(),
                favouriteItems = updated.favouriteItems.withSpacePath(),
                normalItems = updated.normalItems.withSpacePath(),
                lowPriorityItems = updated.lowPriorityItems.withSpacePath(),
                inviteItems = updated.inviteItems.withSpacePath()
            )
        }
    }

    private fun bootstrapRoomListFromCache() {
        viewModelScope.launch {
            if (initialized) return@launch
            if (currentState.allItems.isNotEmpty()) return@launch

            val cached = runCatching { service.port.loadRoomListCache() }
                .getOrElse { emptyList() }

            if (cached.isEmpty()) return@launch
            cached.forEach { maybePrefetchRoomAvatar(it.roomId, it.avatarUrl) }

            val domainRooms = cached.map(::mapRoomSummary)
            val uiItems = cached.map(::mapRoomEntryToUi)

            updateState {
                if (initialized || allItems.isNotEmpty()) this
                else copy(
                    rooms = domainRooms,
                    unread = cached.associate { e -> e.roomId to e.messages.toInt() },
                    favourites = cached.filter { it.isFavourite }.map { it.roomId }.toSet(),
                    lowPriority = cached.filter { it.isLowPriority }.map { it.roomId }.toSet(),
                    allItems = uiItems
                )
            }

            recomputeGroupedRooms()
        }
    }

    override fun onCleared() {
        super.onCleared()
        observerJob?.cancel()

        roomListToken?.let { token ->
            runCatching { service.portOrNull?.unobserveRoomList(token) }
        }
        connToken?.let { token ->
            runCatching { service.portOrNull?.stopConnectionObserver(token) }
        }
    }
}
