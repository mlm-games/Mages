package org.mlm.mages.ui.components.sheets

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mlmgames.settings.core.annotations.SettingPlatform
import io.github.mlmgames.settings.core.platform.currentPlatform
import mages.shared.generated.resources.*
import mages.shared.generated.resources.message_info
import mages.shared.generated.resources.retry
import org.jetbrains.compose.resources.stringResource
import org.mlm.mages.MessageEvent
import org.mlm.mages.matrix.EventType
import org.mlm.mages.matrix.ImagePackSummary
import org.mlm.mages.matrix.SendState
import org.mlm.mages.settings.MessageMenuAction
import org.mlm.mages.settings.defaultQuickReactions
import org.mlm.mages.ui.canCancelSend
import org.mlm.mages.ui.components.core.ReactionGlyph
import org.mlm.mages.ui.displayPreview
import org.mlm.mages.ui.hasCaption
import org.mlm.mages.ui.isForwardable
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.theme.Limits
import org.mlm.mages.ui.util.formatTime

@Composable
fun MessageActionSheet(
    event: MessageEvent,
    isMine: Boolean,
    canDeleteOthers: Boolean = false,
    canPin: Boolean = false,
    isPinned: Boolean = false,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onEditCaption: (() -> Unit)? = null,
    onRemoveCaption: (() -> Unit)? = null,
    onEditPoll: (() -> Unit)? = null,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onPin: (() -> Unit)? = null,
    onUnpin: (() -> Unit)? = null,
    isBookmarked: Boolean = false,
    onBookmark: (() -> Unit)? = null,
    onReport: (() -> Unit)? = null,
    onShowMessageInfo: (() -> Unit)? = null,
    onReact: (String) -> Unit,
    onMarkReadHere: () -> Unit,
    onReplyInThread: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onForward: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onCancelSend: (() -> Unit)? = null,
    reactionImagePacks: List<ImagePackSummary> = emptyList(),
    isEncryptedRoom: Boolean = false,
    resolveReactionPreview: suspend (thumbnailMxcUri: String?, mxcUrl: String, mime: String?, full: Boolean) -> String? = { _, _, _, _ -> null },
    menuOrder: List<MessageMenuAction> = MessageMenuAction.entries,
    quickReactions: List<String> = defaultQuickReactions,
) {
    val clipboardManager = LocalClipboardManager.current
    val isRedacted = event.isRedacted
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showImagePicker by remember { mutableStateOf(false) }

    if (showImagePicker) {
        ReactionImagePickerSheet(
            packs = reactionImagePacks,
            isEncryptedRoom = isEncryptedRoom,
            resolvePreview = resolveReactionPreview,
            onImageSelected = { ref ->
                onReact(ref.mxcUri)
                onDismiss()
            },
            onDismiss = { showImagePicker = false }
        )
        return
    }

    if (showEmojiPicker) {
        EmojiPickerSheet(
            onEmojiSelected = { emoji -> onReact(emoji); onDismiss() },
            onDismiss = { showEmojiPicker = false }
        )
        return
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = Spacing.xxl)
        ) {
            MessagePreview(event)
            if (!isRedacted) {
                Spacer(Modifier.height(Spacing.sm))
                val reactionMimes = remember(reactionImagePacks) {
                    reactionImagePacks.flatMap { it.images }.associate { it.mxcUrl to it.mime }
                }
                QuickReactionsRow(
                    reactions = quickReactions,
                    resolvePreview = { mxcUrl ->
                        resolveReactionPreview(null, mxcUrl, reactionMimes[mxcUrl], false)
                    },
                    onReact = { emoji -> onReact(emoji); onDismiss() },
                    onOpenPicker = { showEmojiPicker = true },
                    onOpenImagePicker = { showImagePicker = true }
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            HorizontalDivider(Modifier.padding(horizontal = Spacing.lg))
            Spacer(Modifier.height(Spacing.xs))

            val actions = buildList {
                if (isMine && event.sendState == SendState.Failed && onRetry != null) {
                    add(MenuEntry(painterResource(Res.drawable.ic_refresh), stringResource(Res.string.retry)) { onRetry(); onDismiss() })
                }
                if (isMine && event.canCancelSend() && onCancelSend != null) {
                    val label = if (event.sendState == SendState.Failed) {
                        stringResource(Res.string.remove_message)
                    } else {
                        stringResource(Res.string.cancel_sending)
                    }
                    add(MenuEntry(painterResource(Res.drawable.ic_close), label) { onCancelSend(); onDismiss() })
                }
                menuOrder.forEach { action ->
                    when (action) {
                        MessageMenuAction.Reply -> if (!isRedacted) {
                            add(MenuEntry(painterResource(Res.drawable.ic_reply), stringResource(Res.string.reply_action)) { onReply(); onDismiss() })
                        }

                        MessageMenuAction.ReplyInThread -> if (!isRedacted && onReplyInThread != null) {
                            add(MenuEntry(painterResource(Res.drawable.ic_gesture), stringResource(Res.string.reply_in_thread)) { onReplyInThread(); onDismiss() })
                        }

                        MessageMenuAction.Edit -> if (isMine && !isRedacted && event.sendState != SendState.Failed && event.eventId.isNotBlank()) {
                            if (event.pollData != null) {
                                if (event.pollData?.isEnded == false && onEditPoll != null) {
                                    add(MenuEntry(painterResource(Res.drawable.ic_bar_chart), stringResource(Res.string.edit_poll)) { onEditPoll(); onDismiss() })
                                }
                            } else if (event.attachment != null) {
                                if (onEditCaption != null) {
                                    add(
                                        MenuEntry(
                                            painterResource(Res.drawable.ic_edit),
                                            if (event.hasCaption()) stringResource(Res.string.edit_caption) else stringResource(Res.string.add_caption)
                                        ) { onEditCaption(); onDismiss() }
                                    )
                                }
                                if (event.hasCaption() && onRemoveCaption != null) {
                                    add(
                                        MenuEntry(
                                            painterResource(Res.drawable.ic_close),
                                            stringResource(Res.string.remove_caption),
                                            MaterialTheme.colorScheme.error
                                        ) { onRemoveCaption(); onDismiss() }
                                    )
                                }
                            } else if (event.sticker == null &&
                                event.eventType != EventType.Location &&
                                event.eventType != EventType.LiveLocation
                            ) {
                                add(MenuEntry(painterResource(Res.drawable.ic_edit), stringResource(Res.string.edit)) { onEdit(); onDismiss() })
                            }
                        }

                        MessageMenuAction.Copy -> add(
                            MenuEntry(painterResource(Res.drawable.ic_content_copy), stringResource(Res.string.copy)) {
                                clipboardManager.setText(AnnotatedString(event.body))
                                onDismiss()
                            }
                        )

                        MessageMenuAction.Forward -> if (onForward != null && event.isForwardable()) {
                            add(MenuEntry(painterResource(Res.drawable.ic_forward), stringResource(Res.string.forward)) { onForward(); onDismiss() })
                        }

                        MessageMenuAction.Share -> if (onShare != null) {
                            add(
                                MenuEntry(
                                    painterResource(Res.drawable.ic_share),
                                    if (currentPlatform == SettingPlatform.WEB) {
                                        stringResource(Res.string.download)
                                    } else {
                                        stringResource(Res.string.share)
                                    }
                                ) { onShare(); onDismiss() }
                            )
                        }

                        MessageMenuAction.Pin -> if (canPin && event.eventId.isNotBlank() && (!isRedacted || isPinned)) {
                            if (isPinned && onUnpin != null) {
                                add(MenuEntry(painterResource(Res.drawable.ic_push_pin_filled), stringResource(Res.string.unpin)) { onUnpin(); onDismiss() })
                            } else if (!isPinned && onPin != null) {
                                add(MenuEntry(painterResource(Res.drawable.ic_push_pin), stringResource(Res.string.pin)) { onPin(); onDismiss() })
                            }
                        }

                        MessageMenuAction.Bookmark -> if (onBookmark != null && !isRedacted && event.eventId.isNotBlank()) {
                            add(
                                MenuEntry(
                                    if (isBookmarked) painterResource(Res.drawable.ic_bookmark_filled) else painterResource(Res.drawable.ic_bookmark),
                                    stringResource(if (isBookmarked) Res.string.remove_bookmark else Res.string.bookmark)
                                ) { onBookmark(); onDismiss() }
                            )
                        }

                        MessageMenuAction.MarkRead -> add(
                            MenuEntry(painterResource(Res.drawable.ic_done), stringResource(Res.string.mark_as_read_here)) {
                                onMarkReadHere()
                                onDismiss()
                            }
                        )

                        MessageMenuAction.Select -> add(
                            MenuEntry(painterResource(Res.drawable.ic_deselect), stringResource(Res.string.select)) {
                                onSelect()
                                onDismiss()
                            }
                        )

                        MessageMenuAction.Info -> if (onShowMessageInfo != null) {
                            add(MenuEntry(painterResource(Res.drawable.ic_info), stringResource(Res.string.message_info)) {
                                onShowMessageInfo()
                                onDismiss()
                            })
                        }
                    }
                }
            }

            actions.forEach { entry ->
                ActionItem(
                    icon = entry.icon,
                    text = entry.label,
                    color = entry.color ?: MaterialTheme.colorScheme.onSurface,
                    onClick = entry.onClick,
                )
            }

            val canDelete = event.eventId.isNotBlank() && (isMine || canDeleteOthers)
            val canReport = onReport != null && event.eventId.isNotBlank()
            if (canDelete || canReport) {
                HorizontalDivider(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs))
                if (canDelete) {
                    ActionItem(painterResource(Res.drawable.ic_delete), stringResource(Res.string.delete), MaterialTheme.colorScheme.error) {
                        onDelete()
                        onDismiss()
                    }
                }
                val report = onReport
                if (report != null && event.eventId.isNotBlank()) {
                    ActionItem(painterResource(Res.drawable.ic_flag), stringResource(Res.string.report), MaterialTheme.colorScheme.error) {
                        report()
                        onDismiss()
                    }
                }
            }
        }
    }
}

private class MenuEntry(
    val icon: Painter,
    val label: String,
    val color: Color? = null,
    val onClick: () -> Unit,
)

@Composable
private fun MessagePreview(event: MessageEvent) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(event.sender, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    formatTime(event.timestampMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(event.displayPreview().take(Limits.previewCharsLong), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun QuickReactionsRow(
    reactions: List<String>,
    resolvePreview: suspend (String) -> String?,
    onReact: (String) -> Unit,
    onOpenPicker: () -> Unit,
    onOpenImagePicker: () -> Unit
) {
    Text(stringResource(Res.string.quick_reactions), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = Spacing.lg), fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(Spacing.sm))
    LazyRow(contentPadding = PaddingValues(horizontal = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        items(reactions) { key ->
            Surface(onClick = { onReact(key) }, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.size(48.dp)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ReactionGlyph(key = key, size = 26.dp, resolvePreview = resolvePreview)
                }
            }
        }
        item {
            Surface(onClick = onOpenPicker, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.size(48.dp)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(painterResource(Res.drawable.ic_add), contentDescription = stringResource(Res.string.more_emoji), modifier = Modifier.size(20.dp))
                }
            }
        }
        item {
            Surface(onClick = onOpenImagePicker, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.size(48.dp)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(painterResource(Res.drawable.ic_image), contentDescription = stringResource(Res.string.react_with_an_image), modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun ActionItem(
    icon: Painter,
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        ListItem(
            headlineContent = { Text(text, color = color) },
            leadingContent = {
                Icon(
                    painter = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(24.dp)
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}
