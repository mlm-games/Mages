package org.mlm.mages.ui.components.composer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.mlm.mages.MessageEvent
import org.mlm.mages.platform.ClipboardAttachmentHandler
import org.mlm.mages.platform.pasteInterceptor
import org.mlm.mages.platform.sendShortcutHandler
import org.mlm.mages.ui.components.AttachmentData
import org.mlm.mages.ui.theme.Sizes
import org.mlm.mages.ui.theme.Spacing

@Composable
fun MessageComposer(
    value: String,
    enabled: Boolean,
    isOffline: Boolean,
    replyingTo: MessageEvent?,
    editing: MessageEvent?,
    attachments: List<AttachmentData>,
    isUploadingAttachment: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancelReply: () -> Unit,
    onCancelEdit: () -> Unit,
    modifier: Modifier = Modifier,
    onAttach: (() -> Unit)? = null,
    onCancelUpload: (() -> Unit)? = null,
    onRemoveAttachment: ((Int) -> Unit)? = null,
    clipboardHandler: ClipboardAttachmentHandler? = null,
    onAttachmentPasted: ((AttachmentData) -> Unit)? = null,
    enterSendsMessage: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var fieldValue by remember { mutableStateOf(TextFieldValue(value)) }

    LaunchedEffect(value) {
        if (value != fieldValue.text) {
            fieldValue = TextFieldValue(value, selection = TextRange(value.length))
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column {
            AnimatedVisibility(
                visible = attachments.isNotEmpty() && !isUploadingAttachment,
            ) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(start = Spacing.lg, top = Spacing.sm, end = Spacing.lg),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    attachments.forEachIndexed { index, attachment ->
                        InputChip(
                            selected = true,
                            onClick = { },
                            label = {
                                Text(
                                    attachment.fileName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 160.dp)
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove attachment",
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clickable { onRemoveAttachment?.invoke(index) }
                                )
                            },
                            colors = InputChipDefaults.inputChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                selectedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            border = null,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.sm),
                verticalAlignment = Alignment.Bottom
            ) {
                AnimatedVisibility(visible = onAttach != null && !isUploadingAttachment) {
                    IconButton(onClick = { onAttach?.invoke() }) {
                        Icon(
                            Icons.Default.AttachFile,
                            "Attach",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                val textFieldModifier = Modifier
                    .weight(1f)
                    .then(
                        if (clipboardHandler != null && onAttachmentPasted != null) {
                            Modifier.pasteInterceptor {
                                if (clipboardHandler.hasAttachment()) {
                                    scope.launch {
                                        clipboardHandler.getAttachments().forEach { onAttachmentPasted(it) }
                                    }
                                    true
                                } else false
                            }
                        } else Modifier
                    )
                    .sendShortcutHandler(
                        enabled = enabled && !isUploadingAttachment,
                        enterSendsMessage = enterSendsMessage,
                        onInsertNewline = {
                            val updated = insertNewline(fieldValue)
                            fieldValue = updated
                            onValueChange(updated.text)
                        },
                        onSend = onSend
                    )

                OutlinedTextField(
                    value = fieldValue,
                    onValueChange = { updated ->
                        fieldValue = updated
                        onValueChange(updated.text)
                    },
                    modifier = textFieldModifier,
                    enabled = enabled && !isUploadingAttachment,
                    placeholder = {
                        ComposerPlaceholder(isUploadingAttachment, isOffline, editing, replyingTo)
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    ),
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 5
                )

                Spacer(Modifier.width(Spacing.sm))

                FilledIconButton(
                    onClick = onSend,
                    enabled = enabled
                            && (value.isNotBlank() || attachments.isNotEmpty())
                            && !isUploadingAttachment,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                    )
                ) {
                    if (isUploadingAttachment) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(Sizes.iconMedium),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerPlaceholder(isUploading: Boolean, isOffline: Boolean, editing: MessageEvent?, replyingTo: MessageEvent?) {
    Text(
        text = when {
            isUploading -> "Uploading..."
            isOffline -> "Offline - messages queued"
            editing != null -> "Edit message..."
            replyingTo != null -> "Type reply..."
            else -> "Type a message..."
        }
    )
}

private fun insertNewline(value: TextFieldValue): TextFieldValue {
    val selection = value.selection
    val start = selection.start.coerceAtLeast(0)
    val end = selection.end.coerceAtLeast(0)
    val text = value.text
    val newText = buildString(text.length + 1) {
        append(text, 0, start)
        append('\n')
        append(text, end, text.length)
    }
    val newCursor = start + 1
    return TextFieldValue(newText, selection = TextRange(newCursor))
}
