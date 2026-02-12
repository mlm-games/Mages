package org.mlm.mages.ui.components.snackbar

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

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
        _events.tryEmit(
            SnackbarEvent(
                message = message,
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
        _events.tryEmit(
            SnackbarEvent(
                message = "Error: $message",
                actionLabel = actionLabel,
                withDismissAction = withDismissAction,
                duration = duration,
                onAction = onAction
            )
        )
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