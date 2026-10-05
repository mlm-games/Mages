package org.mlm.mages.storage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mlm.mages.matrix.MatrixPort

data class UserProfile(
    val displayName: String?,
    val avatarUrl: String?,
)

class ProfileCache(
    val port: MatrixPort,
    private val maxCacheEntries: Int = 2048,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(2))

    private val mu = Mutex()

    private val inFlight = HashMap<String, Deferred<UserProfile?>>()

    private val cache = LinkedHashMap<String, UserProfile?>(maxCacheEntries)

    suspend fun remember(userId: String, displayName: String?, avatarUrl: String? = null) {
        if (userId.isBlank()) return
        val name = displayName?.trim()?.takeIf { it.isNotBlank() }
        val avatar = avatarUrl?.trim()?.takeIf { it.isNotBlank() }
        if (name == null && avatar == null) return
        mu.withLock {
            val existing = cache[userId]
            val merged = UserProfile(name ?: existing?.displayName, avatar ?: existing?.avatarUrl)
            cache.remove(userId)
            cache[userId] = merged
            trim()
        }
    }

    suspend fun rememberAll(entries: Map<String, UserProfile>) {
        entries.forEach { (userId, profile) -> remember(userId, profile.displayName, profile.avatarUrl) }
    }

    suspend fun resolve(userId: String): UserProfile? {
        val id = userId.trim()
        if (id.isBlank()) return null

        mu.withLock {
            if (cache.containsKey(id)) {
                val cached = cache.remove(id)
                cache[id] = cached
                return cached
            }
        }

        val existing = mu.withLock { inFlight[id] }
        if (existing != null) return existing.await()

        val deferred = scope.async {
            runCatching { port.getUserProfile(id) }.getOrNull()
                ?.let { UserProfile(it.displayName?.trim()?.takeIf(String::isNotBlank), it.avatarUrl) }
        }
        mu.withLock { inFlight[id] = deferred }

        val result = try {
            deferred.await()
        } finally {
            mu.withLock { inFlight.remove(id) }
        }

        mu.withLock {
            cache[id] = result
            trim()
        }

        return result
    }

    private fun trim() {
        while (cache.size > maxCacheEntries) {
            val oldest = cache.entries.iterator()
            if (!oldest.hasNext()) break
            oldest.next()
            oldest.remove()
        }
    }

    fun shutdown() {
        scope.cancel()
    }
}
