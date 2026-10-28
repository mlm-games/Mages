package org.mlm.mages.settings

val defaultQuickReactions: List<String> = listOf("👍", "❤️", "😂", "😮", "😢", "🎉", "🔥", "💀")

fun parseQuickReactions(encoded: String): List<String> {
    if (encoded.isBlank()) return defaultQuickReactions
    return encoded.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

fun encodeQuickReactions(reactions: List<String>): String =
    reactions.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")
