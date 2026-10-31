package org.mlm.mages.ui.components.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import mages.shared.generated.resources.Res
import mages.shared.generated.resources.back
import org.jetbrains.compose.resources.stringResource
import org.mlm.mages.ui.theme.Spacing

data class AvatarViewerEntry(
    val path: String,
    val name: String,
)

@Stable
class AvatarViewerState {
    var entry by mutableStateOf<AvatarViewerEntry?>(null)
        private set

    fun show(path: String, name: String?) {
        entry = AvatarViewerEntry(path, name.orEmpty())
    }

    fun dismiss() {
        entry = null
    }
}

@Composable
fun rememberAvatarViewerState(): AvatarViewerState = remember { AvatarViewerState() }

@Composable
fun AvatarViewerHost(state: AvatarViewerState) {
    val entry = state.entry ?: return
    AvatarViewerDialog(
        path = entry.path,
        name = entry.name,
        onDismiss = state::dismiss,
    )
}

@Composable
private fun AvatarViewerDialog(
    path: String,
    name: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        val zoom = remember(path) { ImageZoomState() }
        val focusRequester = remember { FocusRequester() }
        var chromeVisible by remember { mutableStateOf(true) }

        LaunchedEffect(Unit) { focusRequester.requestFocus() }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        (event.key == Key.Escape || event.key == Key.Back)
                    ) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                },
        ) {
            ZoomableImage(
                model = path,
                zoom = zoom,
                contentDescription = name,
                onTap = { chromeVisible = !chromeVisible },
                onDraggingChange = { dragging -> chromeVisible = !dragging },
                onDismiss = onDismiss,
            )

            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.back),
                            tint = Color.White,
                        )
                    }
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
