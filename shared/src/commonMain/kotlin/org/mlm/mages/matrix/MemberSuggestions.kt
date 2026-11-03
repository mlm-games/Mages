package org.mlm.mages.matrix

const val MEMBER_SUGGESTION_LIMIT = 5
const val MEMBER_SUGGESTION_CANDIDATES = 50

fun normalizeMemberQuery(query: String): String =
    query.trim().removePrefix("@").trim().lowercase()

fun MemberSummary.matchesMemberQuery(query: String): Boolean =
    matchesNormalizedMemberQuery(normalizeMemberQuery(query))

fun MemberSummary.matchesNormalizedMemberQuery(needle: String): Boolean {
    if (needle.isEmpty()) return false
    return userId.contains(needle, ignoreCase = true) ||
        displayName?.contains(needle, ignoreCase = true) == true
}

fun List<MemberSummary>.memberSuggestions(
    query: String,
    limit: Int = MEMBER_SUGGESTION_LIMIT,
    exclude: Set<String> = emptySet(),
): List<MemberSummary> {
    if (normalizeMemberQuery(query).isEmpty()) return emptyList()
    val typed = query.trim()
    return asSequence()
        .filter { it.userId !in exclude }
        .filterNot { it.userId.equals(typed, ignoreCase = true) }
        .filter { it.matchesMemberQuery(query) }
        .take(limit)
        .toList()
}

fun DirectoryUser.toMemberSummary(): MemberSummary =
    MemberSummary(userId = userId, displayName = displayName, avatarUrl = avatarUrl)

fun List<MemberSummary>.sortedForDisplay(): List<MemberSummary> =
    sortedWith(
        compareByDescending<MemberSummary> { it.isMe }
            .thenBy { (it.displayName ?: it.userId).lowercase() }
    )
