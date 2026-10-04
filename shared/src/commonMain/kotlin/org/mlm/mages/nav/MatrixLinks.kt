package org.mlm.mages.nav

import com.eygraber.uri.Uri
import org.mlm.mages.ui.util.decodeUrl

// Minimal,
// supports:
// - https://matrix.to/#/@user:server
// - https://matrix.to/#/!roomid:server or #alias:server
// - https://matrix.to/#/!roomid:server/$eventid?via=server1&via=server2
// - matrix:u/@user:server
// - matrix:r/!roomid:server or #alias:server[/ $eventid][?via=...]
//

data class MatrixRoomTarget(
    val roomIdOrAlias: String,
    val eventId: String? = null,
    val via: List<String> = emptyList(),
)
sealed class MatrixLink {
    data class User(val mxid: String): MatrixLink()
    data class Room(val target: MatrixRoomTarget): MatrixLink()
    object Unsupported: MatrixLink()
}

// Accept @user:server, !room:server, #alias:server, $event
private fun looksLikeUser(id: String) = id.startsWith("@") && ':' in id
private fun looksLikeRoomId(id: String) = id.startsWith("!") && ':' in id
private fun looksLikeAlias(id: String) = id.startsWith("#") && ':' in id
private fun looksLikeEvent(id: String) = id.startsWith("$")

fun matrixToUserLink(mxid: String): String? {
    val id = mxid.trim()
    return if (looksLikeUser(id)) "https://matrix.to/#/$id" else null
}

fun parseMagesRoomLink(rawLink: String): DeepLinkAction? {
    return runCatching {
        val uri = Uri.parse(rawLink.trim())
        if (uri.scheme != "mages" || uri.host != "room") return null
        val roomId = uri.getQueryParameter("id")?.takeIf { it.isNotBlank() } ?: return null
        DeepLinkAction(
            roomId = roomId,
            eventId = uri.getQueryParameter("event")?.takeIf { it.isNotBlank() },
            joinCall = uri.getQueryParameter("join_call") == "1",
            voiceOnly = uri.getQueryParameter("voice_only") == "1",
        )
    }.getOrNull()
}

fun parseMatrixLink(urlOrId: String): MatrixLink {
    val raw = urlOrId.trim()
    if (raw.isEmpty()) return MatrixLink.Unsupported

    // Direct MXID/alias/id (e.g., pasted into search)
    if (looksLikeUser(raw)) return MatrixLink.User(raw)
    if (looksLikeRoomId(raw) || looksLikeAlias(raw)) {
        return MatrixLink.Room(MatrixRoomTarget(roomIdOrAlias = raw))
    }

    // matrix.to style
    if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) {
        return parseMatrixTo(raw)
    }

    // matrix: scheme (MSC2312)
    if (raw.startsWith("matrix:", true)) {
        return parseMatrixScheme(raw)
    }

    return MatrixLink.Unsupported
}

private fun parseMatrixTo(u: String): MatrixLink {
    return runCatching {
        val uri = Uri.parse(u)
        if (!uri.host.equals("matrix.to", true)) return MatrixLink.Unsupported
        val frag = uri.fragment ?: return MatrixLink.Unsupported // after "#/"
        // trim leading "/"
        val path = frag.removePrefix("/")

        // Split on "/" for optional event id
        val parts = path.split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return MatrixLink.Unsupported

        val first = decodeUrl(parts[0])
        val eventId = parts.getOrNull(1)?.let(::decodeUrl)?.takeIf(::looksLikeEvent)

        val via = uri.getQueryParameters("via").map(::decodeUrl)

        return when {
            looksLikeUser(first) -> MatrixLink.User(first)
            looksLikeRoomId(first) || looksLikeAlias(first) ->
                MatrixLink.Room(MatrixRoomTarget(first, eventId, via))
            else -> MatrixLink.Unsupported
        }
    }.getOrElse { MatrixLink.Unsupported }
}

private fun parseMatrixScheme(raw: String): MatrixLink {
    // Forms: matrix:u/@user:server  | matrix:r/!room:server[/ $event][?via=...]
    val body = raw.removePrefix("matrix:") // e.g., "u/@user:domain?via=server"
    val pathAndQuery = body.split('?', limit = 2)
    val path = pathAndQuery[0].trimStart('/')
    val query = pathAndQuery.getOrNull(1)
    val segs = path.split('/').filter { it.isNotBlank() }
    if (segs.isEmpty()) return MatrixLink.Unsupported

    val kind = segs[0].lowercase()
    val id = segs.getOrNull(1)?.let(::decodeUrl) ?: return MatrixLink.Unsupported
    val eventId = segs.getOrNull(2)?.let(::decodeUrl)?.takeIf(::looksLikeEvent)

    val via = query?.split('&')?.mapNotNull {
        val kv = it.split('=', limit = 2)
        if (kv.size == 2 && kv[0] == "via") decodeUrl(kv[1]) else null
    }
        ?: emptyList()

    return when (kind) {
        "u", "user" -> if (looksLikeUser(id)) MatrixLink.User(id) else MatrixLink.Unsupported
        "r", "room" -> if (looksLikeRoomId(id) || looksLikeAlias(id))
            MatrixLink.Room(MatrixRoomTarget(id, eventId, via)) else MatrixLink.Unsupported
        else -> MatrixLink.Unsupported
    }
}
