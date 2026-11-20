package org.mlm.mages.push

internal suspend fun roomIsRead(
    serverNotifications: Long,
    serverMentions: Long,
    notifications: Long,
    mentions: Long,
    latestTsMs: Long?,
    readTs: suspend () -> Long?,
): Boolean {
    val serverCleared = serverNotifications == 0L && serverMentions == 0L
    val localCleared = notifications == 0L && mentions == 0L
    if (!serverCleared && !localCleared) return false
    if (latestTsMs == null) return serverCleared && localCleared
    val ts = readTs() ?: return false
    return ts >= latestTsMs
}

internal suspend fun threadIsRead(
    latestTsMs: Long?,
    readTsMs: Long?,
    countsRead: suspend () -> Boolean,
): Boolean {
    if (latestTsMs != null && readTsMs != null) return readTsMs >= latestTsMs
    return countsRead()
}
