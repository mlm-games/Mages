package org.mlm.mages.ui.components.message

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import mages.shared.generated.resources.Res
import mages.shared.generated.resources.close
import mages.shared.generated.resources.message
import mages.shared.generated.resources.show_more
import org.jetbrains.compose.resources.stringResource
import org.mlm.mages.ui.components.core.FormattedBodyText
import org.mlm.mages.ui.components.core.MentionRef
import org.mlm.mages.ui.theme.Spacing

private const val LONG_MESSAGE_CHARS = 1200
private const val LONG_MESSAGE_NEWLINES = 16
private const val COLLAPSED_LINES = 12

@Composable
internal fun LongMessageBody(
    formattedBody: String?,
    fallbackBody: String,
    color: Color,
    containerColor: Color,
    emotePaths: Map<String, String>,
    mentionRefs: Map<String, MentionRef>,
    mentionsRoom: Boolean,
    onMentionClick: ((String) -> Unit)?,
) {
    val collapsible = remember(fallbackBody) {
        fallbackBody.length > LONG_MESSAGE_CHARS ||
            fallbackBody.count { it == '\n' } >= LONG_MESSAGE_NEWLINES
    }
    var showFullScreen by rememberSaveable { mutableStateOf(false) }

    Column {
        FormattedBodyText(
            formattedBody = formattedBody,
            fallbackBody = fallbackBody,
            color = color,
            containerColor = containerColor,
            emotePaths = emotePaths,
            mentionRefs = mentionRefs,
            mentionsRoom = mentionsRoom,
            onMentionClick = onMentionClick,
            maxLines = if (collapsible) COLLAPSED_LINES else Int.MAX_VALUE,
            overflow = if (collapsible) TextOverflow.Ellipsis else TextOverflow.Clip,
        )

        if (collapsible) {
            Text(
                text = stringResource(Res.string.show_more),
                style = MaterialTheme.typography.labelLarge,
                color = color,
                modifier = Modifier
                    .padding(top = Spacing.xs)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { showFullScreen = true }
                    .padding(horizontal = 2.dp, vertical = 14.dp)
            )
        }
    }

    if (showFullScreen) {
        FullMessageDialog(
            formattedBody = formattedBody,
            fallbackBody = fallbackBody,
            emotePaths = emotePaths,
            mentionRefs = mentionRefs,
            mentionsRoom = mentionsRoom,
            onMentionClick = onMentionClick,
            onDismiss = { showFullScreen = false },
        )
    }
}

@Composable
private fun FullMessageDialog(
    formattedBody: String?,
    fallbackBody: String,
    emotePaths: Map<String, String>,
    mentionRefs: Map<String, MentionRef>,
    mentionsRoom: Boolean,
    onMentionClick: ((String) -> Unit)?,
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
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(Res.string.message),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = Spacing.sm)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(Res.string.close),
                        )
                    }
                }
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(Spacing.md)
                ) {
                    SelectionContainer {
                        FormattedBodyText(
                            formattedBody = formattedBody,
                            fallbackBody = fallbackBody,
                            color = MaterialTheme.colorScheme.onSurface,
                            containerColor = MaterialTheme.colorScheme.surface,
                            emotePaths = emotePaths,
                            mentionRefs = mentionRefs,
                            mentionsRoom = mentionsRoom,
                            onMentionClick = onMentionClick,
                        )
                    }
                }
            }
        }
    }
}
