package org.mlm.mages.ui.components.composer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mages.shared.generated.resources.*
import org.mlm.mages.MessageEvent
import org.mlm.mages.matrix.MemberSummary
import org.mlm.mages.platform.ClipboardAttachmentHandler
import org.mlm.mages.platform.editLatestShortcutHandler
import org.mlm.mages.platform.pasteInterceptor
import org.mlm.mages.platform.sendShortcutHandler
import org.mlm.mages.ui.components.AttachmentData
import org.mlm.mages.ui.components.AttachmentSourceKind
import org.mlm.mages.ui.components.toMagesAttachment
import org.mlm.mages.ui.components.voice.VoiceRecorderBar
import org.mlm.mages.ui.theme.Sizes
import org.mlm.mages.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource
import mages.shared.generated.resources.Res

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
    canEditLatest: Boolean = false,
    onEditLatest: () -> Unit = {},
    roomMembers: List<MemberSummary> = emptyList(),
    avatarPathByUserId: Map<String, String> = emptyMap(),
    isRecordingVoice: Boolean = false,
    onStartVoiceRecording: (() -> Unit)? = null,
    onCancelVoiceRecording: (() -> Unit)? = null,
    onVoiceRecordingComplete: ((filePath: String, durationMs: Long, waveform: List<Float>) -> Unit)? = null,
    emoteSuggestions: List<EmoteSuggestion> = emptyList(),
    resolveEmotePreview: suspend (thumbnailMxcUri: String?, mxcUrl: String) -> String? = { _, _ -> null },
    isEncryptedRoom: Boolean = false,
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

    val emoteQuery = remember(fieldValue) { findEmoteQueryInternal(fieldValue) }
    val visibleEmotes = remember(emoteQuery, emoteSuggestions) {
        if (emoteQuery == null) emptyList()
        else if (emoteQuery.query.isEmpty()) emoteSuggestions
        else filterEmoteSuggestionsInternal(emoteSuggestions, emoteQuery.query)
    }
    val emotePickerVisible = emoteQuery != null && (emoteQuery.query.isEmpty() || visibleEmotes.isNotEmpty())

    val visualTransformation = remember(emoteSuggestions) {
        ComposerVisualTransformation(emoteSuggestions.mapTo(mutableSetOf<String>()) { it.ref.mxcUri })
    }

    if (isRecordingVoice && onStartVoiceRecording != null && onCancelVoiceRecording != null && onVoiceRecordingComplete != null) {
        VoiceRecorderBar(
            onSend = onVoiceRecordingComplete,
            onCancel = onCancelVoiceRecording,
            modifier = modifier
        )
        return
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

            AnimatedVisibility(visible = emotePickerVisible) {
                ComposerEmotePopup(
                    query = emoteQuery?.query.orEmpty(),
                    emotes = visibleEmotes,
                    resolvePreview = resolveEmotePreview,
                    showUnencryptedNotice = isEncryptedRoom,
                    onEmoteSelected = { suggestion ->
                        val query = emoteQuery ?: return@ComposerEmotePopup
                        val updated = insertEmoteInternal(fieldValue, suggestion, query)
                        fieldValue = updated
                        onValueChange(updated.text)
                    },
                    onEmojiSelected = { emoji ->
                        val query = emoteQuery ?: return@ComposerEmotePopup
                        val updated = insertEmojiInternal(fieldValue, emoji, query)
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
                canEditLatest = canEditLatest,
                onEditLatest = onEditLatest,
                clipboardHandler = clipboardHandler,
                onAttachmentPasted = onAttachmentPasted,
                scope = scope,
                isOffline = isOffline,
                editing = editing,
                replyingTo = replyingTo,
                onStartVoiceRecording = onStartVoiceRecording,
                visualTransformation = visualTransformation,
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
    canEditLatest: Boolean,
    onEditLatest: () -> Unit,
    clipboardHandler: ClipboardAttachmentHandler?,
    onAttachmentPasted: ((AttachmentData) -> Unit)?,
    scope: CoroutineScope,
    isOffline: Boolean,
    editing: MessageEvent?,
    replyingTo: MessageEvent?,
    onStartVoiceRecording: (() -> Unit)?,
    visualTransformation: VisualTransformation,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AnimatedVisibility(visible = onAttach != null && !isUploadingAttachment) {
            IconButton(onClick = { onAttach?.invoke() }) {
                Icon(
                    Icons.Default.AttachFile,
                    stringResource(Res.string.attach),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        val editLatestShortcutEnabled = canEditLatest &&
            enabled && !isUploadingAttachment &&
            editing == null && replyingTo == null &&
            fieldValue.text.isEmpty() && attachments.isEmpty()

        val textFieldModifier = Modifier
            .weight(1f)
            .then(
                if (clipboardHandler != null && onAttachmentPasted != null) {
                    Modifier.pasteInterceptor {
                        if (clipboardHandler.hasAttachment()) {
                            scope.launch {
                                clipboardHandler.getAttachments().forEach { item ->
                                    onAttachmentPasted(item.toMagesAttachment(AttachmentSourceKind.LocalPath))
                                }
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
            .editLatestShortcutHandler(
                enabled = editLatestShortcutEnabled,
                onEditLatest = onEditLatest
            )

        OutlinedTextField(
            value = fieldValue,
            onValueChange = onValueChange,
            modifier = textFieldModifier,
            enabled = enabled && !isUploadingAttachment,
            visualTransformation = visualTransformation,
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
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = if (enterSendsMessage) ImeAction.Send else ImeAction.Default
            ),
            keyboardActions = KeyboardActions(
                onSend = { if (enabled && !isUploadingAttachment) onSend() }
            )
        )

        Spacer(Modifier.width(Spacing.sm))

        val canSend = enabled && (fieldValue.text.isNotBlank() || attachments.isNotEmpty()) && !isUploadingAttachment
        val canRecordVoice = enabled && !isUploadingAttachment && fieldValue.text.isBlank() && attachments.isEmpty() && onStartVoiceRecording != null

        FilledIconButton(
            onClick = {
                if (canSend) {
                    onSend()
                } else if (canRecordVoice) {
                    onStartVoiceRecording()
                }
            },
            enabled = canSend || canRecordVoice,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (canSend) MaterialTheme.colorScheme.primary else Color.Transparent,
                contentColor = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
        ) {
            if (isUploadingAttachment) {
                CircularWavyProgressIndicator(
                    modifier = Modifier.size(Sizes.iconMedium),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else if (canSend) {
                Icon(Icons.AutoMirrored.Filled.Send, stringResource(Res.string.send))
            } else {
                Icon(Icons.Default.Mic, stringResource(Res.string.record_voice))
            }
        }
    }
}

@Composable
private fun ComposerPlaceholder(isUploading: Boolean, isOffline: Boolean, editing: MessageEvent?, replyingTo: MessageEvent?) {
    Text(
        text = when {
            isUploading -> stringResource(Res.string.uploading)
            isOffline -> stringResource(Res.string.offline_messages_queued)
            editing?.attachment != null -> stringResource(Res.string.edit_caption)
            editing != null -> stringResource(Res.string.edit_message)
            replyingTo != null -> stringResource(Res.string.type_reply)
            else -> stringResource(Res.string.type_a_message)
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
    val mentionText = "[@${escapeMarkdown(label)}](https://matrix.to/#/${member.userId}) "
    val newText = buildString {
        append(current.text.substring(0, mentionQuery.start))
        append(mentionText)
        append(current.text.substring(mentionQuery.end))
    }
    val newCursor = mentionQuery.start + mentionText.length
    return TextFieldValue(newText, selection = TextRange(newCursor))
}

internal data class EmoteQueryInternal(
    val start: Int,
    val end: Int,
    val query: String,
)

/**
 * Shortcodes may only contain the characters the spec's grammar allows, so a
 * query is bounded by anything outside `ALPHA / DIGIT / "-" / "_"`. The colon
 * must start a word, which is what keeps a time such as `12:30` or a URL from
 * opening the picker.
 */
internal fun findEmoteQueryInternal(value: TextFieldValue): EmoteQueryInternal? {
    val text = value.text
    val cursor = value.selection.start
    if (cursor < 1 || cursor > text.length) return null

    var start = cursor
    while (start > 0 && (isShortcodeChar(text[start - 1]) || text[start - 1] == ':')) {
        start--
    }
    if (start >= text.length || text[start] != ':') return null
    if (start > 0 && !text[start - 1].isWhitespace()) return null
    if (start >= cursor) return null

    var end = start + 1
    while (end < text.length && (isShortcodeChar(text[end]) || text[end] == ':')) {
        end++
    }

    return EmoteQueryInternal(start = start, end = end, query = text.substring(start + 1, cursor))
}

private fun isShortcodeChar(c: Char): Boolean = c.isLetterOrDigit() || c == '-' || c == '_'

internal fun filterEmoteSuggestionsInternal(
    suggestions: List<EmoteSuggestion>,
    query: String
): List<EmoteSuggestion> {
    val normalized = query.trim(':').lowercase()
    return suggestions
        .asSequence()
        .filter {
            normalized.isEmpty() || it.shortcode.trim(':').lowercase().startsWith(normalized)
        }
        .take(24)
        .toList()
}

internal fun insertEmoteInternal(
    current: TextFieldValue,
    suggestion: EmoteSuggestion,
    query: EmoteQueryInternal
): TextFieldValue {
    val emoteText = suggestion.markdown + " "
    val newText = buildString {
        append(current.text.substring(0, query.start))
        append(emoteText)
        append(current.text.substring(query.end))
    }
    val newCursor = query.start + emoteText.length
    return TextFieldValue(newText, selection = TextRange(newCursor))
}

internal fun insertEmojiInternal(
    current: TextFieldValue,
    emoji: String,
    query: EmoteQueryInternal
): TextFieldValue {
    val emojiText = "$emoji "
    val newText = buildString {
        append(current.text.substring(0, query.start))
        append(emojiText)
        append(current.text.substring(query.end))
    }
    val newCursor = query.start + emojiText.length
    return TextFieldValue(newText, selection = TextRange(newCursor))
}
