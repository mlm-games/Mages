package org.mlm.mages.settings

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val defaultQuickReactions: List<String> = listOf("👍", "❤️", "😂", "😮", "😢", "🎉", "🔥", "💀")

fun isImageReaction(key: String): Boolean = key.startsWith("mxc://")

fun parseQuickReactions(encoded: String): List<String> {
    val raw = encoded.trim()
    if (raw.isEmpty()) return defaultQuickReactions
    val entries = if (raw.startsWith("[")) {
        runCatching { Json.decodeFromString<List<String>>(raw) }.getOrNull() ?: return defaultQuickReactions
    } else {
        raw.split(',')
    }
    return entries.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

fun encodeQuickReactions(reactions: List<String>): String =
    Json.encodeToString(reactions.map { it.trim() }.filter { it.isNotEmpty() }.distinct())
