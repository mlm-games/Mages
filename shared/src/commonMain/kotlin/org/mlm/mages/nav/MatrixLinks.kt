package org.mlm.mages.nav

import com.eygraber.uri.Uri
import org.mlm.mages.ui.util.decodeUrl

// Supports:
// - https://matrix.to/#/@user:server
// - https://matrix.to/#/!roomid:server or #alias:server (optionally /$event, ?via=...)
// - matrix:[//authority]{type}/{id without sigil}[/{type}/{id without sigil}...][?query]
//   types: u/user, r/room, roomid (e for event ids after a room)
//   identifiers may be percent-encoded and may keep their sigils (lenient)
// - https://<any-host>/#/room/<id-or-alias>[/$event], /#/user/<id> (Element Web instances)
// - https://<any-host>/home/<id-or-alias>[/$event], /direct/create?userId=<id> (Cinny)
// - element://user/<id>, element://room/<id-or-alias>[/$event][?via=...]

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

// A control or delimiter that can never appear in a matrix identifier, checked
// after percent-decoding so links like matrix:r/%21x%2Fy:s are rejected.
private fun badIdChar(c: Char) =
    c.isWhitespace() || c == '/' || c == '?' || c.isISOControl()

// Sigil-carrying ids. Room ids are domain-less since room v12, so only user
// ids and aliases require a server name.
private fun looksLikeUser(id: String) =
    id.startsWith("@") && ':' in id && id.substringAfter(':').isNotBlank() && id.none(::badIdChar)
private fun looksLikeRoomId(id: String) = id.startsWith("!") && id.length > 1 && id.none(::badIdChar)
private fun looksLikeAlias(id: String) =
    id.startsWith("#") && ':' in id && id.substringAfter(':').isNotBlank() && id.none(::badIdChar)
private fun looksLikeEvent(id: String) = id.startsWith("$") && id.length > 1 && id.none(::badIdChar)

private val SERVER_NAME = Regex("""^[A-Za-z0-9_.\-\[\]]{1,250}(:\d{1,5})?$""")
private fun isServerName(s: String) = SERVER_NAME.matches(s)

private val SIGILS = "@#!$"

// The Matrix URI scheme defines ids WITHOUT sigils while matrix.to links carry
// them; clients emit both, so slots for rooms and users tolerate either form.
private fun withSigil(id: String, expected: Char): String? {
    if (id.isBlank() || id.any(::badIdChar)) return null
    val first = id.first()
    if (first == expected) return if (id.length > 1) id else null
    if (first in SIGILS || '#' in id) return null
    return expected + id
}

private fun normalizeUserId(id: String) = withSigil(id, '@')?.takeIf(::looksLikeUser)
private fun normalizeAlias(id: String) = withSigil(id, '#')?.takeIf(::looksLikeAlias)
private fun normalizeRoomId(id: String) = withSigil(id, '!')?.takeIf(::looksLikeRoomId)
private fun normalizeEventId(id: String) = withSigil(id, '$')?.takeIf(::looksLikeEvent)

// A bare room reference with no sigil is treated as an alias when it carries a
// server name; aliases are by far the common case for shared links. Expects an
// already percent-decoded id.
private fun normalizeRoomOrAlias(decoded: String): String? {
    val first = decoded.firstOrNull() ?: return null
    if (first == '!') return normalizeRoomId(decoded)
    if (first == '#') return normalizeAlias(decoded)
    if (first in SIGILS || '#' in decoded || ':' !in decoded) return null
    return normalizeAlias(decoded)
}

private fun normalizedEventOrNull(id: String): String? =
    id.takeIf { it.isNotBlank() }?.let(::decodeUrl)?.let(::normalizeEventId)

// Extracts the optional event segment after a room reference. Accepts either a
// direct `$event` segment or the spec's `e/$event` pair. Returns ok=false for
// any other shape so the link is reported as unsupported.
private fun extractEventId(leftovers: List<String>): Pair<Boolean, String?> {
    if (leftovers.isEmpty()) return true to null
    if (leftovers.size == 1) {
        if (leftovers[0].equals("e", ignoreCase = true)) return false to null
        val event = normalizedEventOrNull(leftovers[0])
        return (event != null) to event
    }
    if (leftovers.size == 2 && leftovers[0].equals("e", ignoreCase = true)) {
        val event = normalizedEventOrNull(leftovers[1])
        return (event != null) to event
    }
    return false to null
}

private fun parseQuery(query: String?): List<String> {
    if (query.isNullOrBlank()) return emptyList()
    val vias = mutableListOf<String>()
    for (part in query.split('&')) {
        val eq = part.indexOf('=')
        if (eq < 0) continue
        val key = part.substring(0, eq)
        val value = decodeUrl(part.substring(eq + 1))
        if (key == "via" && isServerName(value)) vias += value
    }
    return vias
}

private fun roomLink(id: String, event: String?, via: List<String>): MatrixLink {
    val room = normalizeRoomOrAlias(id) ?: return MatrixLink.Unsupported
    return MatrixLink.Room(MatrixRoomTarget(room, event, via))
}

// id list may be [id], [id, event], [id, "e", event]; anything else is not a room link.
private fun roomFromArgList(args: List<String>, via: List<String>): MatrixLink {
    if (args.isEmpty()) return MatrixLink.Unsupported
    val (ok, eventId) = extractEventId(args.drop(1))
    if (!ok) return MatrixLink.Unsupported
    return roomLink(decodeUrl(args[0]), eventId, via)
}

private fun userFromArg(idRaw: String?): MatrixLink {
    val id = idRaw?.let(::decodeUrl)?.takeIf { it.isNotBlank() } ?: return MatrixLink.Unsupported
    val user = normalizeUserId(id) ?: return MatrixLink.Unsupported
    return MatrixLink.User(user)
}

fun matrixToUserLink(mxid: String): String? {
    val id = mxid.trim()
    return if (looksLikeUser(id)) "https://matrix.to/#/$id" else null
}

fun isMatrixPermalink(raw: String): Boolean = parseMatrixLink(raw) !is MatrixLink.Unsupported

fun matrixUserIdFromLink(raw: String): String? = (parseMatrixLink(raw) as? MatrixLink.User)?.mxid

fun parseMagesRoomLink(rawLink: String): DeepLinkAction? {
    return runCatching {
        val uri = Uri.parse(rawLink.trim())
        if (uri.scheme != "mages" || uri.host != "room") return null
        val roomId = uri.getQueryParameter("id")?.takeIf { it.isNotBlank() } ?: return null
        DeepLinkAction(
            roomId = roomId,
            eventId = uri.getQueryParameter("event")?.takeIf { it.isNotBlank() },
            threadId = uri.getQueryParameter("thread")?.takeIf { it.isNotBlank() },
            joinCall = uri.getQueryParameter("join_call") == "1",
            voiceOnly = uri.getQueryParameter("voice_only") == "1",
            incoming = uri.getQueryParameter("incoming") == "1",
        )
    }.getOrNull()
}

fun parseMatrixLink(urlOrId: String): MatrixLink {
    val raw = urlOrId.trim()
    if (raw.isEmpty()) return MatrixLink.Unsupported

    if (looksLikeUser(raw)) return MatrixLink.User(raw)
    if (looksLikeRoomId(raw) || looksLikeAlias(raw)) {
        return MatrixLink.Room(MatrixRoomTarget(roomIdOrAlias = raw))
    }

    if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) {
        return parseWebLink(raw)
    }

    if (raw.startsWith("matrix:", true)) {
        return parseMatrixScheme(raw)
    }

    if (raw.startsWith("element://", true)) {
        return parseElementScheme(raw)
    }

    return MatrixLink.Unsupported
}

private fun parseWebLink(u: String): MatrixLink {
    val uri = runCatching { Uri.parse(u) }.getOrNull() ?: return MatrixLink.Unsupported
    val host = uri.host?.lowercase() ?: return MatrixLink.Unsupported
    val frag = uri.fragment?.trimStart('/').orEmpty()
    val fragAndQuery = frag.split('?', limit = 2)
    val path = if (frag.isEmpty()) uri.path?.trim('/').orEmpty() else fragAndQuery[0]
    val query = fragAndQuery.getOrNull(1) ?: uri.query
    val segs = path.split('/').filter { it.isNotBlank() }
    val via = parseQuery(query)

    if (host == "matrix.to") {
        return when {
            segs.isEmpty() -> MatrixLink.Unsupported
            decodeUrl(segs[0]).startsWith('@') -> userFromArg(segs[0])
            else -> roomFromArgList(segs, via)
        }
    }

    // Element Web instances: #/room/<id-or-alias>[/$event], #/user/<id>
    if (segs.size >= 2 && segs[0].equals("room", ignoreCase = true)) {
        return roomFromArgList(segs.drop(1), via)
    }
    if (segs.size == 2 && segs[0].equals("user", ignoreCase = true)) {
        return userFromArg(segs[1])
    }

    // Cinny: /home/<id-or-alias>[/$event], /direct/create?userId=<id>
    if (segs.size >= 2 && segs[0].equals("home", ignoreCase = true)) {
        return roomFromArgList(segs.drop(1), via)
    }
    if (segs.size == 2 && segs[0].equals("direct", ignoreCase = true) && segs[1].equals("create", ignoreCase = true)) {
        val userId = uri.getQueryParameter("userId") ?: return MatrixLink.Unsupported
        return userFromArg(userId)
    }

    return MatrixLink.Unsupported
}

private val QUALIFIER_SET = setOf("u", "user", "r", "room", "roomid")

private fun parseMatrixScheme(raw: String): MatrixLink {
    // Forms:
    //   matrix:[//{authority}]/{type}/{id without sigil}[/{type}/{id without sigil}...][?{query}]
    //   e.g. matrix:u/@user:server?action=chat, matrix:r/alias:server?via=s1&via=s2,
    //        matrix:roomid/id:server/e/$event, matrix:roomid/id:server (sigil-less)
    //        matrix://matrix.example/r/alias:server (authority is reserved but tolerated)
    // Path parsing is done manually because '#' is a legal alias character.
    val body = raw.substringAfter(':')
    val pathAndQuery = body.split('?', limit = 2)
    val query = pathAndQuery.getOrNull(1)
    val rawSegs = pathAndQuery[0].split('/').filter { it.isNotBlank() }
    if (rawSegs.isEmpty()) return MatrixLink.Unsupported

    // Drop a leading authority segment when one is present (matrix://host/u/id).
    val segs = if (rawSegs.size >= 3 && rawSegs[1].lowercase() in QUALIFIER_SET) {
        rawSegs.drop(1)
    } else {
        rawSegs
    }

    val qualifier = segs[0].lowercase()
    val args = segs.drop(1)
    val via = parseQuery(query)

    return when (qualifier) {
        "u", "user" -> userFromArg(args.getOrNull(0))
        "r", "room", "roomid" -> roomFromArgListForScheme(args, via, qualifier)
        else -> MatrixLink.Unsupported
    }
}

private fun roomFromArgListForScheme(
    args: List<String>,
    via: List<String>,
    qualifier: String,
): MatrixLink {
    if (args.isEmpty()) return MatrixLink.Unsupported
    val (ok, eventId) = extractEventId(args.drop(1))
    if (!ok) return MatrixLink.Unsupported
    val id = decodeUrl(args[0]).takeIf { it.isNotBlank() } ?: return MatrixLink.Unsupported
    val target = when (qualifier) {
        // roomid links are room ids, not aliases
        "roomid" -> normalizeRoomId(id)
        else -> normalizeRoomOrAlias(id)
    } ?: return MatrixLink.Unsupported
    return MatrixLink.Room(MatrixRoomTarget(target, eventId, via))
}

private fun parseElementScheme(raw: String): MatrixLink {
    // element://user/<id>, element://room/<id-or-alias>[/$event][?via=...]
    val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return MatrixLink.Unsupported
    val kind = uri.host?.lowercase() ?: return MatrixLink.Unsupported
    val segs = uri.path?.split('/')?.filter { it.isNotBlank() } ?: emptyList()
    val via = parseQuery(uri.query)

    return when (kind) {
        "user" -> userFromArg(segs.firstOrNull())
        "room" -> roomFromArgList(segs, via)
        else -> MatrixLink.Unsupported
    }
}
