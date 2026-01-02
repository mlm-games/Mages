package org.mlm.mages.storage

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mlm.mages.matrix.MatrixPort


class AvatarLoader(
    private val port: MatrixPort,
    parallelism: Int = 4,
    dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(parallelism),
    private val maxCacheEntries: Int = 1024
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val mu = Mutex()

    private val inFlight = HashMap<String, Deferred<String?>>()

    // resolved cache (key -> local file path), bounded (simple oldest-drop)
    private val cache = LinkedHashMap<String, String>(maxCacheEntries)

    /**
     * Resolve avatarUrl to something UI-renderable.
     *
     * If avatarUrl is:
     * - null/blank -> null
     * - not mxc://  -> returned as-is
     * - mxc://      -> returns local cached file path
     */
    suspend fun resolve(avatarUrl: String?, px: Int, crop: Boolean = true): String? {
        val url = avatarUrl?.trim().takeIf { !it.isNullOrBlank() } ?: return null
        if (!url.startsWith("mxc://")) return url

        val key = "$url|${px}x$px|crop=$crop"

        mu.withLock {
            cache[key]?.let { return it }
        }

        val existing = mu.withLock { inFlight[key] }
        if (existing != null) return existing.await()

        // Start a shared download in AvatarLoader's own scope (so one caller cancel won't kill others).
        val deferred = scope.async {
            runCatching { port.mxcThumbnailToCache(url, px, px, crop) }.getOrNull()
        }

        mu.withLock { inFlight[key] = deferred }

        val result = try {
            deferred.await()
        } finally {
            mu.withLock { inFlight.remove(key) }
        }

        if (!result.isNullOrBlank()) {
            mu.withLock {
                cache[key] = result
                // trim oldest
                while (cache.size > maxCacheEntries) {
                    val it = cache.entries.iterator()
                    if (it.hasNext()) {
                        it.next()
                        it.remove()
                    } else break
                }
            }
        }

        return result
    }

    fun shutdown() {
        scope.cancel()
    }
}