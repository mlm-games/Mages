package org.mlm.mages.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.SpaceChildInfo
import org.mlm.mages.matrix.isUserLimitExceeded
import org.koin.core.component.KoinComponent
import org.jetbrains.compose.resources.getString
import mages.shared.generated.resources.*
import mages.shared.generated.resources.Res
import org.mlm.mages.ui.util.guessMimeType

private const val VIEWING_AVATAR_PX = 512
private const val UNKNOWN_MIME = "application/octet-stream"

/** Avatars whose bytes the platform cannot identify are cached with a `.img` extension. */
private fun avatarMimeFor(path: String): String =
    guessMimeType(path).takeIf { it != UNKNOWN_MIME } ?: "image/*"

/**
 * Base ViewModel providing common patterns for state management.
 */
abstract class BaseViewModel<S>(initialState: S) : ViewModel(), KoinComponent {

    protected val _state = MutableStateFlow(initialState)
    val state: StateFlow<S> = _state.asStateFlow()

    protected val currentState: S get() = _state.value

    protected fun updateState(transform: S.() -> S) {
        _state.update { it.transform() }
    }

    protected fun launch(
        onError: (suspend (Throwable) -> Unit)? = null,
        block: suspend CoroutineScope.() -> Unit
    ): Job = viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onError?.invoke(e)
        }
    }

    protected suspend fun <T> runSafe(
        onError: (suspend (Throwable) -> T?)? = null,
        block: suspend () -> T
    ): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        onError?.invoke(e)
    }

    /**
     * Repeats [block] until it yields a value. Sliding sync only ships a room's state once it is
     * subscribed or scrolled into the viewport, so a snapshot requested right after subscribing
     * may only become available a sync or two later.
     */
    protected suspend fun <T> retryUntilPresent(
        attempts: Int = 5,
        delayMs: Long = 500,
        block: suspend () -> T?
    ): T? {
        repeat(attempts) { attempt ->
            if (attempt > 0) delay(delayMs * attempt)
            block()?.let { return it }
        }
        return null
    }

    /**
     * Returns [userMessage] if successful, otherwise returns the exception message, or a
     * localized explanation for failures that carry no usable message of their own.
     */
    protected suspend fun Result<*>.toUserMessage(userMessage: String): String =
        exceptionOrNull()?.failureMessage(userMessage) ?: userMessage

    protected suspend fun Result<*>?.toUserMessage(userMessage: String): String =
        this?.exceptionOrNull()?.failureMessage(userMessage) ?: userMessage

    /** Text to show for [this] failed operation, or [fallback] when it has none. */
    protected suspend fun Throwable.failureMessage(fallback: String): String =
        if (isUserLimitExceeded()) getString(Res.string.user_limit_exceeded) else message ?: fallback

    protected fun resolveAvatar(
        service: MatrixService,
        avatarUrl: String?,
        px: Int,
        update: S.(String) -> S,
    ) {
        val avatar = avatarUrl ?: return
        launch {
            val path = service.avatars.resolve(avatar, px = px, crop = true) ?: return@launch
            updateState { update(path) }
        }
    }

    protected fun openAvatarForViewing(
        service: MatrixService,
        userId: String,
        fallbackAvatarUrl: String?,
        onOpen: (String, String?) -> Unit,
        onError: suspend () -> Unit = {},
    ) {
        launch {
            val source = fallbackAvatarUrl?.takeIf { it.startsWith("mxc://") }
                ?: runSafe { service.port.getUserProfile(userId)?.avatarUrl }
                ?: fallbackAvatarUrl
            val path = source?.let { runSafe { service.avatars.resolve(it, px = VIEWING_AVATAR_PX, crop = false) } }
            if (path.isNullOrBlank()) {
                onError()
                return@launch
            }
            onOpen(path, avatarMimeFor(path))
        }
    }

    protected fun hydrateMissingSpaceChildNames(
        service: MatrixService,
        children: List<SpaceChildInfo>,
        update: S.(roomId: String, name: String) -> S,
    ) {
        children.filter { !it.isSpace && it.name.isNullOrBlank() }.forEach { child ->
            launch {
                val profile = runSafe { service.port.roomProfile(child.roomId) }
                val name = profile?.name?.takeIf { it.isNotBlank() } ?: return@launch
                updateState { update(child.roomId, name) }
            }
        }
    }

    protected fun resolveSpaceChildAvatars(
        service: MatrixService,
        children: List<SpaceChildInfo>,
        update: S.(roomId: String, avatarPath: String) -> S,
    ) {
        children.forEach { child ->
            resolveAvatar(service, child.avatarUrl, 64) { path ->
                update(child.roomId, path)
            }
        }
    }
}
