package org.mlm.mages.ui.components.composer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.mlm.mages.MessageEvent
import org.mlm.mages.matrix.MemberSummary
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
    roomMembers: List<MemberSummary> = emptyList(),
    avatarPathByUserId: Map<String, String> = emptyMap(),
) {
    val scope = rememberCoroutineScope()
    var fieldValue by remember { mutableStateOf(TextFieldValue(value)) }

    LaunchedEffect(value) {
        if (value != fieldValue.text) {
            fieldValue = TextFieldValue(value, selection = TextRange(value.length))
        }
    }

    val mentionQuery = remember(fieldValue) { findMentionQueryInternal(fieldValue) }
    val mentionSuggestions = remember(mentionQuery, roomMembers) {
        if (mentionQuery == null) emptyList() else filterMentionSuggestionsInternal(roomMembers, mentionQuery.query)
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
                ComposerAttachmentTray(
                    attachments = attachments,
                    onRemoveAttachment = onRemoveAttachment,
                )
            }

            AnimatedVisibility(visible = mentionQuery != null && mentionSuggestions.isNotEmpty()) {
                ComposerMentionPopup(
                    members = mentionSuggestions,
                    avatarPathByUserId = avatarPathByUserId,
                    onMemberSelected = { member ->
                        val query = mentionQuery ?: return@ComposerMentionPopup
                        val updated = insertMentionInternal(fieldValue, member, query)
                        fieldValue = updated
                        onValueChange(updated.text)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg)
                        .padding(top = Spacing.xs)
                )
            }

            ComposerInputRow(
                fieldValue = fieldValue,
                enabled = enabled,
                isUploadingAttachment = isUploadingAttachment,
                attachments = attachments,
                onAttach = onAttach,
                onValueChange = { updated ->
                    fieldValue = updated
                    onValueChange(updated.text)
                },
                onSend = onSend,
                enterSendsMessage = enterSendsMessage,
                clipboardHandler = clipboardHandler,
                onAttachmentPasted = onAttachmentPasted,
                scope = scope,
                isOffline = isOffline,
                editing = editing,
                replyingTo = replyingTo,
            )
        }
    }
}

@Composable
private fun ComposerInputRow(
    fieldValue: TextFieldValue,
    enabled: Boolean,
    isUploadingAttachment: Boolean,
    attachments: List<AttachmentData>,
    onAttach: (() -> Unit)?,
    onValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    enterSendsMessage: Boolean,
    clipboardHandler: ClipboardAttachmentHandler?,
    onAttachmentPasted: ((AttachmentData) -> Unit)?,
    scope: kotlinx.coroutines.CoroutineScope,
    isOffline: Boolean,
    editing: MessageEvent?,
    replyingTo: MessageEvent?,
) {
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
                    val newValue = insertNewlineInternal(fieldValue)
                    onValueChange(newValue)
                },
                onSend = onSend
            )

        OutlinedTextField(
            value = fieldValue,
            onValueChange = onValueChange,
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
            maxLines = 5,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
        )

        Spacer(Modifier.width(Spacing.sm))

        FilledIconButton(
            onClick = onSend,
            enabled = enabled
                    && (fieldValue.text.isNotBlank() || attachments.isNotEmpty())
                    && !isUploadingAttachment,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            )
        ) {
            if (isUploadingAttachment) {
                CircularWavyProgressIndicator(
                    modifier = Modifier.size(Sizes.iconMedium),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Icon(Icons.AutoMirrored.Filled.Send, "Send")
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

private data class MentionQueryInternal(
    val start: Int,
    val end: Int,
    val query: String,
)

private fun insertNewlineInternal(value: TextFieldValue): TextFieldValue {
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

private fun findMentionQueryInternal(value: TextFieldValue): MentionQueryInternal? {
    val text = value.text
    val cursor = value.selection.start
    if (cursor < 0 || cursor > text.length) return null

    var start = cursor
    while (start > 0 && !text[start - 1].isWhitespace()) {
        start--
    }
    if (start >= text.length || text[start] != '@') return null

    var end = cursor
    while (end < text.length && !text[end].isWhitespace()) {
        end++
    }

    return MentionQueryInternal(start = start, end = end, query = text.substring(start + 1, cursor))
}

private fun filterMentionSuggestionsInternal(members: List<MemberSummary>, query: String): List<MemberSummary> {
    val normalized = query.trim().lowercase()
    return members
        .asSequence()
        .filter { it.membership.equals("join", ignoreCase = true) }
        .sortedWith(compareByDescending<MemberSummary> { it.isMe }.thenBy { (it.displayName ?: it.userId).lowercase() })
        .filter {
            if (normalized.isBlank()) return@filter true
            it.displayName.orEmpty().lowercase().contains(normalized) || it.userId.lowercase().contains(normalized)
        }
        .take(5)
        .toList()
}

private fun insertMentionInternal(current: TextFieldValue, member: MemberSummary, mentionQuery: MentionQueryInternal): TextFieldValue {
    val label = member.displayName ?: member.userId.substringAfter("@").substringBefore(":")
    val mentionText = "[@$label](https://matrix.to/#/${member.userId}) "
    val newText = buildString {
        append(current.text.substring(0, mentionQuery.start))
        append(mentionText)
        append(current.text.substring(mentionQuery.end))
    }
    val newCursor = mentionQuery.start + mentionText.length
    return TextFieldValue(newText, selection = TextRange(newCursor))
}
