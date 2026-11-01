package org.mlm.mages.matrix

fun isMatrixUserId(value: String): Boolean {
    val id = value.trim()
    if (id.length < 4 || !id.startsWith("@")) return false
    if (id.any { it.isWhitespace() }) return false
    val colon = id.indexOf(':')
    if (colon <= 1 || colon == id.lastIndex) return false
    return true
}
