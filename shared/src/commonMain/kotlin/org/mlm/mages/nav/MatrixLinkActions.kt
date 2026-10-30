package org.mlm.mages.nav

import mages.shared.generated.resources.*
import mages.shared.generated.resources.Res
import org.jetbrains.compose.resources.getString
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.RoomJoinRule
import org.mlm.mages.matrix.RoomPreviewMembership

suspend fun openMatrixLink(service: MatrixService, link: MatrixLink): DeepLinkAction? {
    return when (link) {
        is MatrixLink.User -> {
            val roomId = service.port.ensureDm(link.mxid) ?: return null
            DeepLinkAction(roomId = roomId)
        }

        is MatrixLink.Room -> {
            val target = link.target
            val via = routingServers(service, target)
            val preview = service.port.roomPreview(target.roomIdOrAlias, via).getOrNull()
            val roomId = preview?.roomId ?: target.roomIdOrAlias

            when (preview?.membership) {
                RoomPreviewMembership.Knocked -> return DeepLinkAction(
                    roomId = roomId,
                    notice = getString(Res.string.already_requested_access),
                )

                RoomPreviewMembership.Banned -> return DeepLinkAction(
                    roomId = roomId,
                    notice = getString(Res.string.you_are_banned_from_this_room),
                )

                else -> Unit
            }

            if (preview?.membership != RoomPreviewMembership.Joined) {
                val joinRule = preview?.joinRule
                val mustKnock = preview?.membership != RoomPreviewMembership.Invited &&
                    (joinRule == RoomJoinRule.Knock || joinRule == RoomJoinRule.KnockRestricted)

                if (mustKnock) {
                    val knocked = service.port.knock(target.roomIdOrAlias, via)
                    return if (knocked.isSuccess) {
                        DeepLinkAction(
                            roomId = roomId,
                            notice = getString(Res.string.knock_request_sent_waiting_for_approval),
                        )
                    } else {
                        null
                    }
                }

                val joined = service.port.joinByIdOrAlias(target.roomIdOrAlias, via)
                if (joined.isFailure) {
                    val knocked = service.port.knock(target.roomIdOrAlias, via)
                    return if (knocked.isSuccess) {
                        DeepLinkAction(
                            roomId = roomId,
                            notice = getString(Res.string.knock_request_sent_waiting_for_approval),
                        )
                    } else {
                        null
                    }
                }
            }

            val resolvedRoomId = preview?.roomId
                ?: service.port.roomPreview(target.roomIdOrAlias, via).getOrNull()?.roomId
                ?: runCatching { service.port.resolveRoomId(target.roomIdOrAlias) }.getOrNull()
                ?: target.roomIdOrAlias
            DeepLinkAction(roomId = resolvedRoomId, eventId = target.eventId)
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
    openRoom: (roomId: String, eventId: String?) -> Unit
): Boolean {
    val action = openMatrixLink(service, link) ?: return false
    if (action.notice != null) return false
    openRoom(action.roomId, action.eventId)
    return true
}

suspend fun resolveDeepLink(service: MatrixService, rawLink: String): DeepLinkAction? {
    val link = parseMatrixLink(rawLink)
    if (link is MatrixLink.Unsupported) {
        return parseMagesRoomLink(rawLink)
    }
    return openMatrixLink(service, link)
}
