package org.mlm.mages.nav

import org.mlm.mages.MatrixService

suspend fun openMatrixLink(service: MatrixService, link: MatrixLink): DeepLinkAction? {
    return when (link) {
        is MatrixLink.User -> {
            val roomId = service.port.ensureDm(link.mxid) ?: return null
            DeepLinkAction(roomId = roomId)
        }

        is MatrixLink.Room -> {
            // Membership is checked in BindDeepLinks so non-call deep links into
            // rooms the user is not in land on the join preview screen instead of
            // joining silently.
            val target = link.target
            DeepLinkAction(
                roomId = target.roomIdOrAlias,
                eventId = target.eventId,
                via = routingServers(service, target),
            )
        }

        MatrixLink.Unsupported -> null
    }
}

private suspend fun routingServers(service: MatrixService, target: MatrixRoomTarget): List<String> {
    val targetServer = target.roomIdOrAlias.substringAfter(':', "").takeIf { it.isNotBlank() }
    val spaceServers = runCatching {
        service.mySpaces().mapNotNull { it.roomId.substringAfter(':', "").takeIf { server -> server.isNotBlank() } }
    }.getOrDefault(emptyList())
    return (target.via + listOfNotNull(targetServer) + spaceServers).distinct()
}

suspend fun handleMatrixLink(
    service: MatrixService,
    link: MatrixLink,
    openRoom: (DeepLinkAction) -> Unit
): Boolean {
    val action = openMatrixLink(service, link) ?: return false
    openRoom(action)
    return true
}

suspend fun resolveDeepLink(service: MatrixService, rawLink: String): DeepLinkAction? {
    val link = parseMatrixLink(rawLink)
    if (link is MatrixLink.Unsupported) {
        return parseMagesRoomLink(rawLink)
    }
    return openMatrixLink(service, link)
}
