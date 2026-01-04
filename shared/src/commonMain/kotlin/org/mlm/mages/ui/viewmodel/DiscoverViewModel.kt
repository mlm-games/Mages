package org.mlm.mages.ui.viewmodel

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.DirectoryUser
import org.mlm.mages.matrix.PublicRoom

data class DiscoverUi(
    val query: String = "",
    val users: List<DirectoryUser> = emptyList(),
    val rooms: List<PublicRoom> = emptyList(),
    val nextBatch: String? = null,
    val directJoinCandidate: String? = null,

    val directoryServer: String = "matrix.org",
    val availableServers: List<String> = listOf("matrix.org"),
    val homeServer: String = "",

    val isBusy: Boolean = false,
    val isPaging: Boolean = false,
    val error: String? = null
)

class DiscoverViewModel(
    private val service: MatrixService
) : BaseViewModel<DiscoverUi>(DiscoverUi()) {

    sealed class Event {
        data class OpenRoom(val roomId: String, val name: String) : Event()
        data class ShowError(val message: String) : Event()
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var searchJob: Job? = null

    init {
        launch {
            initializeServers()
        }
    }

    private suspend fun initializeServers() {
        val homeServer = extractHomeServer()
        val servers = buildList {
            add("matrix.org")
            if (homeServer.isNotBlank() && homeServer != "matrix.org") {
                add(homeServer)
            }
            // Common public servers
            add("gitter.im")
            add("libera.chat")
            add("mozilla.org")
            add("tchncs.de")
            add("envs.net")
        }.distinct()

        updateState {
            copy(
                homeServer = homeServer,
                availableServers = servers,
                directoryServer = homeServer.ifBlank { "matrix.org" }
            )
        }
    }

    private suspend fun extractHomeServer(): String {
        val userId = runSafe { service.port.whoami() } ?: return ""
        return userId.substringAfter(':', "").takeIf { it.isNotBlank() } ?: ""
    }

    fun setDirectoryServer(server: String) {
        updateState { copy(directoryServer = server) }
        val currentQuery = state.value.query
        if (currentQuery.isNotBlank()) {
            triggerSearch(currentQuery)
        }
    }

    fun addCustomServer(server: String) {
        val normalized = server.trim().lowercase()
        if (normalized.isBlank()) return

        updateState {
            val newServers = (availableServers + normalized).distinct()
            copy(
                availableServers = newServers,
                directoryServer = normalized
            )
        }
        val currentQuery = state.value.query
        if (currentQuery.isNotBlank()) {
            triggerSearch(currentQuery)
        }
    }

    fun setQuery(q: String) {
        updateState { copy(query = q) }
        triggerSearch(q)
    }

    private fun triggerSearch(q: String) {
        searchJob?.cancel()

        searchJob = launch {
            delay(300)
            val term = q.trim()

            if (term.isBlank()) {
                updateState {
                    copy(
                        users = emptyList(),
                        rooms = emptyList(),
                        nextBatch = null,
                        directJoinCandidate = null,
                        error = null,
                        isBusy = false
                    )
                }
                return@launch
            }

            val direct = normalizeJoinTarget(term)
            val serverFromQuery = direct?.let { extractServerFromAliasOrId(it) }
            val effectiveServer = serverFromQuery ?: state.value.directoryServer

            updateState {
                copy(
                    isBusy = true,
                    error = null,
                    directJoinCandidate = direct
                )
            }

            val isRoomQuery = direct != null || term.startsWith("#") || term.startsWith("!")

            val users = if (!isRoomQuery) {
                runSafe { service.port.searchUsers(term, 20) } ?: emptyList()
            } else {
                emptyList()
            }

            val searchTerm = extractSearchTerm(term)

            val page = runSafe {
                service.port.publicRooms(
                    server = effectiveServer,
                    search = searchTerm,
                    limit = 50,
                    since = null
                )
            }

            updateState {
                copy(
                    users = users,
                    rooms = page?.rooms ?: emptyList(),
                    nextBatch = page?.nextBatch,
                    isBusy = false
                )
            }
        }
    }

    private fun extractSearchTerm(term: String): String {
        return when {
            term.startsWith("#") && term.contains(":") -> {
                term.substringAfter('#').substringBefore(':')
            }
            term.startsWith("#") -> term.substringAfter('#')
            else -> term
        }
    }

    fun loadMoreRooms() {
        val s = state.value
        val term = s.query.trim()
        val since = s.nextBatch ?: return

        val direct = normalizeJoinTarget(term)
        val serverFromQuery = direct?.let { extractServerFromAliasOrId(it) }
        val effectiveServer = serverFromQuery ?: s.directoryServer

        searchJob?.cancel()
        searchJob = launch {
            updateState { copy(isPaging = true, error = null) }

            val searchTerm = extractSearchTerm(term)

            val page = runSafe {
                service.port.publicRooms(
                    server = effectiveServer,
                    search = searchTerm,
                    limit = 50,
                    since = since
                )
            }

            updateState {
                copy(
                    rooms = rooms + (page?.rooms ?: emptyList()),
                    nextBatch = page?.nextBatch,
                    isPaging = false
                )
            }
        }
    }

    fun openUser(u: DirectoryUser) {
        launch {
            updateState { copy(isBusy = true) }
            val rid = runSafe { service.port.ensureDm(u.userId) }
            updateState { copy(isBusy = false) }

            if (rid != null) {
                _events.send(Event.OpenRoom(rid, u.displayName ?: u.userId))
            } else {
                _events.send(Event.ShowError("Failed to start conversation"))
            }
        }
    }

    fun openRoom(room: PublicRoom) {
        launch {
            updateState { copy(isBusy = true, error = null) }
            val rid = joinRoom(room.alias ?: room.roomId)
            updateState { copy(isBusy = false) }

            if (rid != null) {
                _events.send(
                    Event.OpenRoom(
                        rid,
                        room.name ?: room.alias ?: room.roomId
                    )
                )
            } else {
                _events.send(Event.ShowError("Failed to join room"))
            }
        }
    }

    fun joinDirect(idOrAlias: String) {
        launch {
            updateState { copy(isBusy = true, error = null) }
            val rid = joinRoom(idOrAlias)
            updateState { copy(isBusy = false) }

            if (rid != null) {
                _events.send(Event.OpenRoom(rid, idOrAlias))
            } else {
                _events.send(Event.ShowError("Failed to join $idOrAlias"))
            }
        }
    }

    private suspend fun joinRoom(idOrAlias: String): String? {
        if (idOrAlias.startsWith("!")) {
            val rooms = runSafe { service.port.listRooms() } ?: emptyList()
            if (rooms.any { it.id == idOrAlias }) {
                return idOrAlias
            }
        }

        // For aliases, try to resolve first to check if already joined
        if (idOrAlias.startsWith("#")) {
            val resolvedId = runSafe { service.port.resolveRoomId(idOrAlias) }
            if (resolvedId != null && resolvedId.startsWith("!")) {
                val rooms = runSafe { service.port.listRooms() } ?: emptyList()
                if (rooms.any { it.id == resolvedId }) {
                    return resolvedId
                }
            }
        }

        val joinSuccess = runSafe { service.port.joinByIdOrAlias(idOrAlias) } ?: false
        if (!joinSuccess) {
            return null
        }

        // After successful join, resolve the room ID
        return when {
            idOrAlias.startsWith("!") -> idOrAlias
            idOrAlias.startsWith("#") -> {
                // Give the server a moment to process
                delay(100)
                runSafe { service.port.resolveRoomId(idOrAlias) }
            }
            else -> null
        }
    }

    private fun normalizeJoinTarget(input: String): String? {
        val t = input.trim()
        if (t.isBlank()) return null

        if (t.startsWith("https://matrix.to/#/")) {
            val after = t.removePrefix("https://matrix.to/#/")
                .trim()
                .substringBefore('?')
            return after.takeIf { it.startsWith("#") || it.startsWith("!") }
        }

        // element.io links
        if (t.contains("app.element.io/#/room/")) {
            val after = t.substringAfter("app.element.io/#/room/")
                .trim()
                .substringBefore('?')
            return after.takeIf { it.startsWith("#") || it.startsWith("!") }
        }

        return when {
            t.startsWith("#") && t.contains(":") -> t
            t.startsWith("!") && t.contains(":") -> t
            else -> null
        }
    }

    private fun extractServerFromAliasOrId(idOrAlias: String): String? =
        idOrAlias.substringAfter(':', "").takeIf { it.isNotBlank() }
}