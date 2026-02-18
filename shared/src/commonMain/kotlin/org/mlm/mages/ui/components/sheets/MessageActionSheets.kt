package org.mlm.mages.ui.components.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mlm.mages.MessageEvent
import org.mlm.mages.matrix.SendState
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.theme.Limits

private val quickReactions = listOf("👍", "❤️", "😂", "😮", "😢", "🎉", "🔥", "💀")

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
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onPin: (() -> Unit)? = null,
    onUnpin: (() -> Unit)? = null,
    onReport: (() -> Unit)? = null,
    onReact: (String) -> Unit,
    onMarkReadHere: () -> Unit,
    onReplyInThread: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onForward: (() -> Unit)? = null,
) {
    val clipboard = LocalClipboardManager.current
    var showEmojiPicker by remember { mutableStateOf(false) }

    if (showEmojiPicker) {
        EmojiPickerSheet(
            onEmojiSelected = { emoji -> onReact(emoji); onDismiss() },
            onDismiss = { showEmojiPicker = false }
        )
        return
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = Spacing.xxl)) {
            MessagePreview(event)
            Spacer(Modifier.height(Spacing.lg))
            QuickReactionsRow(
                onReact = { emoji -> onReact(emoji); onDismiss() },
                onOpenPicker = { showEmojiPicker = true }
            )
            Spacer(Modifier.height(Spacing.lg))
            HorizontalDivider(Modifier.padding(horizontal = Spacing.lg))
            Spacer(Modifier.height(Spacing.sm))

            ActionItem(Icons.Default.ContentCopy, "Copy") { clipboard.setText(AnnotatedString(event.body)); onDismiss() }
            if (onShare != null) {
                ActionItem(Icons.Default.Share, "Share") { onShare(); onDismiss() }
            }
            if (onForward != null) {
                ActionItem(Icons.AutoMirrored.Filled.Forward, "Forward") { onForward(); onDismiss() }
            }
            ActionItem(Icons.AutoMirrored.Filled.Reply, "Reply") { onReply(); onDismiss() }
            if (onReplyInThread != null) {
                ActionItem(Icons.Default.Forum, "Reply in thread") { onReplyInThread(); onDismiss() }
            }
            ActionItem(Icons.Default.Bookmark, "Mark as read here") { onMarkReadHere(); onDismiss() }
            if (isMine && event.sendState != SendState.Failed && event.eventId.isNotBlank()) {
                ActionItem(Icons.Default.Edit, "Edit") { onEdit(); onDismiss() }
            }
            if (isMine || (canDeleteOthers && event.eventId.isNotBlank())) {
                ActionItem(Icons.Default.Delete, "Delete", MaterialTheme.colorScheme.error) { onDelete(); onDismiss() }
            }
            if (canPin && event.eventId.isNotBlank()) {
                if (isPinned && onUnpin != null) {
                    ActionItem(Icons.Default.PushPin, "Unpin") { onUnpin(); onDismiss() }
                } else if (!isPinned && onPin != null) {
                    ActionItem(Icons.Default.PushPin, "Pin") { onPin(); onDismiss() }
                }
            }
            ActionItem(Icons.Default.Deselect, "Select") { onSelect(); onDismiss() }
            HorizontalDivider(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm))
            if (onReport != null && event.eventId.isNotBlank()) {
                ActionItem(Icons.Default.Flag, "Report", MaterialTheme.colorScheme.error) { onReport(); onDismiss() }
            }
        }
    }
}

@Composable
private fun MessagePreview(event: MessageEvent) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(Spacing.md)) {
            Text(event.sender, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(Spacing.xs))
            Text(event.body.take(Limits.previewCharsLong), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun QuickReactionsRow(onReact: (String) -> Unit, onOpenPicker: () -> Unit) {
    Text("Quick reactions", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = Spacing.lg), fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(Spacing.sm))
    LazyRow(contentPadding = PaddingValues(horizontal = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        items(quickReactions) { emoji ->
            Surface(onClick = { onReact(emoji) }, shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(48.dp)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(emoji, fontSize = 20.sp) }
            }
        }
        item {
            Surface(onClick = onOpenPicker, shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(48.dp)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Add, contentDescription = "More emoji", modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun ActionItem(icon: ImageVector, text: String, color: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(text, color = color) }, leadingContent = { Icon(icon, null, tint = color) }, modifier = Modifier.clickable { onClick() })
}