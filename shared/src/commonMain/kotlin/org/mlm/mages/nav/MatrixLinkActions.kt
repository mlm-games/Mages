package org.mlm.mages.nav

import org.mlm.mages.MatrixService

suspend fun handleMatrixLink(
    service: MatrixService,
    link: MatrixLink,
    openRoom: (roomId: String, eventId: String?) -> Unit
): Boolean {
    return when (link) {
        is MatrixLink.User -> {
            val rid = service.port.ensureDm(link.mxid) ?: return false
            openRoom(rid, null)
            true
        }
        is MatrixLink.Room -> {
            val target = link.target
            val joinResult = service.port.joinByIdOrAlias(target.roomIdOrAlias, target.via)
            if (joinResult.isFailure) return false

            val roomId = runCatching { service.port.resolveRoomId(target.roomIdOrAlias) }.getOrNull()
                ?: target.roomIdOrAlias
            openRoom(roomId, target.eventId)
            true
        }
        MatrixLink.Unsupported -> false
    }
}

suspend fun resolveDeepLink(service: MatrixService, rawLink: String): DeepLinkAction? {
    val link = parseMatrixLink(rawLink)
    if (link is MatrixLink.Unsupported) {
        return parseMagesRoomLink(rawLink)
    }

    var action: DeepLinkAction? = null
    val opened = handleMatrixLink(service, link) { roomId, eventId ->
        action = DeepLinkAction(roomId = roomId, eventId = eventId)
    }
    return action.takeIf { opened }
}
