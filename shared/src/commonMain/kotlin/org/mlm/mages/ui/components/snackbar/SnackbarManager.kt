package org.mlm.mages.ui.components.snackbar

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import mages.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import mages.shared.generated.resources.Res
import mages.shared.generated.resources.copy
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.mlm.mages.platform.rememberClipboardCopy

class SnackbarManager {
    // Buffer so events aren't lost if emitted quickly (e.g., rapid actions)
    private val _events = MutableSharedFlow<SnackbarEvent>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events = _events.asSharedFlow()

    fun show(
        message: String,
        actionLabel: String? = null,
        withDismissAction: Boolean = false,
        duration: SnackbarDuration = SnackbarDuration.Short,
        onAction: (suspend () -> Unit)? = null,
    ) {
        val normalizedMessage = normalizeMessage(message)
        _events.tryEmit(
            SnackbarEvent(
                message = normalizedMessage,
                actionLabel = actionLabel,
                withDismissAction = withDismissAction,
                duration = duration,
                onAction = onAction
            )
        )
    }

    fun showError(
        message: String,
        actionLabel: String? = null,
        withDismissAction: Boolean = false,
        duration: SnackbarDuration = SnackbarDuration.Short,
        onAction: (suspend () -> Unit)? = null,
    ) {
        val normalizedMessage = normalizeMessage(message)
        _events.tryEmit(
            SnackbarEvent(
                message = normalizedMessage,
                actionLabel = actionLabel,
                withDismissAction = withDismissAction,
                duration = duration,
                onAction = onAction,
            )
        )
    }

    private fun normalizeMessage(message: String): String {
        return message
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .lines()
            .joinToString(" ") { it.trim() }
            .replace(Regex("\\s+"), " ")
            .trim()
            .removePrefix("Error: ")
            .removePrefix("error: ")
    }
}

@Composable
fun rememberErrorPoster(manager: SnackbarManager): (String) -> Unit {
    val copyToClipboard = rememberClipboardCopy()
    val copyLabel = stringResource(Res.string.copy)
    return remember(manager, copyToClipboard, copyLabel) {
        { message: String ->
            manager.showError(
                message = message,
                actionLabel = copyLabel,
                onAction = { copyToClipboard(message, false) }
            )
        }
    }
}

@Suppress("ComposableNaming")
@Composable
fun SnackbarManager.snackbarHost() {
    val hostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(this) {
        events.collect { event ->
            val result = hostState.showSnackbar(
                message = event.message,
                actionLabel = event.actionLabel,
                withDismissAction = event.withDismissAction,
                duration = event.duration
            )

            if (result == SnackbarResult.ActionPerformed && event.onAction != null) {
                scope.launch { event.onAction.invoke() }
            }
        }
    }

    SnackbarHost(hostState = hostState)
}
