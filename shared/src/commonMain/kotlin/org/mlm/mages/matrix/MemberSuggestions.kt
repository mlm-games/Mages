package org.mlm.mages.matrix

const val MEMBER_SUGGESTION_LIMIT = 5

fun normalizeMemberQuery(query: String): String =
    query.trim().removePrefix("@").trim().lowercase()

fun MemberSummary.matchesMemberQuery(query: String): Boolean {
    val needle = normalizeMemberQuery(query)
    if (needle.isEmpty()) return false
    return userId.lowercase().contains(needle) ||
        displayName?.lowercase()?.contains(needle) == true
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
