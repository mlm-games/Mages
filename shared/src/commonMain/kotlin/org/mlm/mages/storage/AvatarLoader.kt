package org.mlm.mages.storage

import co.touchlab.kermit.Logger
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
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** How long a failed fetch is remembered before it may be retried. */
internal const val AVATAR_FAILURE_TTL_MS = 30_000L

/** Upper bound on remembered failures, so broken avatars cannot grow the map forever. */
internal const val AVATAR_MAX_FAILURES = 256

class AvatarLoader(
    val port: MatrixPort,
    parallelism: Int = 4,
    dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(parallelism),
    private val maxCacheEntries: Int = 1024
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val mu = Mutex()

    private val inFlight = HashMap<String, Deferred<String?>>()

    // resolved cache (key -> local file path), bounded (simple oldest-drop)
    private val cache = LinkedHashMap<String, String>(maxCacheEntries)

    // keys that failed recently (key -> epoch ms before which it is not retried), bounded
    private val failures = LinkedHashMap<String, Long>()

    /**
     * Resolve avatarUrl to something UI-renderable.
     *
     * If avatarUrl is:
     * - null/blank -> null
     * - not mxc://  -> returned as-is
     * - mxc://      -> returns local cached file path
     *
     * A failed fetch is not retried for [AVATAR_FAILURE_TTL_MS], so a broken
     * avatar cannot be re-requested on every render pass.
     */
    suspend fun resolve(avatarUrl: String?, px: Int, crop: Boolean = true): String? {
        val url = avatarUrl?.trim().takeIf { !it.isNullOrBlank() } ?: return null
        if (!url.startsWith("mxc://")) return url

        val key = "$url|${px}x$px|crop=$crop"

        mu.withLock {
            val cached = cache[key]
            if (cached != null) {
                cache.remove(key)
                cache[key] = cached
                return cached
            }
            val retryAt = failures[key]
            if (retryAt != null) {
                if (retryAt > nowMs()) return null
                failures.remove(key)
            }
        }

        val existing = mu.withLock { inFlight[key] }
        if (existing != null) return existing.await()

        // One shared download per key, owned by AvatarLoader's own scope, so a
        // cancelled caller neither kills the fetch nor loses its result.
        val deferred = scope.async {
            val fetched = runCatching { port.mxcThumbnailToCache(url, px, px, crop) }
                .onFailure { Logger.w(it) { "AvatarLoader: failed to fetch $url" } }
                .getOrNull()
            mu.withLock {
                if (!fetched.isNullOrBlank()) {
                    cache[key] = fetched
                    trimOldest(cache, maxCacheEntries)
                } else {
                    failures[key] = nowMs() + AVATAR_FAILURE_TTL_MS
                    trimOldest(failures, AVATAR_MAX_FAILURES)
                }
            }
            fetched
        }

        mu.withLock { inFlight[key] = deferred }

        return try {
            deferred.await()
        } finally {
            mu.withLock { inFlight.remove(key) }
        }
    }

    suspend fun resolveAll(avatarUrls: List<String?>, px: Int, crop: Boolean = true): List<String?> {
        return avatarUrls.map { resolve(it, px, crop) }
    }

    fun shutdown() {
        scope.cancel()
    }

    private fun trimOldest(map: LinkedHashMap<String, *>, max: Int) {
        while (map.size > max) {
            val it = map.entries.iterator()
            if (!it.hasNext()) break
            it.next()
            it.remove()
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
}
