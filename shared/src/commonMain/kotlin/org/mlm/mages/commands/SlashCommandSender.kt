package org.mlm.mages.commands

import org.mlm.mages.MatrixService

suspend fun MatrixService.executeSendCommand(
    roomId: String,
    command: SlashCommand.Send,
    body: String,
    formattedBody: String?,
    replyToEventId: String? = null,
    threadRootEventId: String? = null,
): Result<Unit> = if (threadRootEventId != null) {
    sendThreadTextTyped(
        roomId = roomId,
        rootEventId = threadRootEventId,
        body = body,
        replyToEventId = replyToEventId,
        formattedBody = formattedBody,
        sendType = command.sendType,
    )
} else if (replyToEventId != null) {
    replyTyped(roomId, replyToEventId, body, formattedBody, command.sendType)
} else {
    sendMessageTyped(roomId, body, formattedBody, command.sendType)
}
