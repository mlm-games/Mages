package org.mlm.mages.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.SpaceChildInfo
import org.koin.core.component.KoinComponent

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
        onError: ((Throwable) -> Unit)? = null,
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
        onError: ((Throwable) -> T?)? = null,
        block: suspend () -> T
    ): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        onError?.invoke(e)
    }

    /**
     * Returns [userMessage] if successful, otherwise returns the exception message.
     */
    protected fun Result<*>.toUserMessage(userMessage: String): String {
        return exceptionOrNull()?.message ?: userMessage
    }

    protected fun Result<*>?.toUserMessage(userMessage: String): String {
        return this?.exceptionOrNull()?.message ?: userMessage
    }

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
