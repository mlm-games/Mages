package org.mlm.mages.ui.components.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mages.shared.generated.resources.Res
import mages.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.mlm.mages.AttachmentInfo
import org.mlm.mages.AttachmentKind
import org.mlm.mages.MessageEvent
import org.mlm.mages.matrix.SeenByEntry
import org.mlm.mages.ui.components.core.Avatar
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.util.formatAbsoluteDateTime
import org.mlm.mages.ui.util.formatBytes
import org.mlm.mages.ui.util.formatDimensions
import org.mlm.mages.ui.util.formatDurationMs
import org.mlm.mages.ui.util.formatTime
import org.mlm.mages.ui.util.readableEnumName

@Composable
fun MessageInfoSheet(
    event: MessageEvent,
    readers: List<SeenByEntry>,
    isLoadingReaders: Boolean,
    readersError: String?,
    readersTruncated: Boolean,
    onRetryReaders: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    val attachment = event.attachment
    val caption = event.body
        .trim()
        .takeIf { it.isNotEmpty() && it != attachment?.fileName }

    fun copy(value: String) {
        clipboardManager.setText(AnnotatedString(value))
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = Spacing.lg,
                end = Spacing.lg,
                top = Spacing.md,
                bottom = Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Info, contentDescription = null)
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        text = stringResource(Res.string.message_info),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            item {
                MessageInfoPreview(event = event, fallbackFileName = attachment?.fileName)
            }

            item {
                SectionCard(title = stringResource(Res.string.message_details)) {
                    InfoRow(
                        label = stringResource(Res.string.sender_name),
                        value = event.senderDisplayName ?: event.sender,
                    )
                    InfoRow(
                        label = stringResource(Res.string.sender_id),
                        value = event.sender,
                        onCopy = { copy(event.sender) },
                    )
                    InfoRow(
                        label = stringResource(Res.string.sent_at),
                        value = formatAbsoluteDateTime(event.timestampMs),
                    )
                    InfoRow(
                        label = stringResource(Res.string.message_type),
                        value = readableEnumName(event.eventType.name),
                    )
                    InfoRow(
                        label = stringResource(Res.string.send_status),
                        value = readableEnumName(event.sendState?.name ?: "Sent"),
                    )
                    InfoRow(
                        label = stringResource(Res.string.edited),
                        value = if (event.isEdited) {
                            stringResource(Res.string.yes)
                        } else {
                            stringResource(Res.string.no)
                        },
                    )

                    if (!caption.isNullOrBlank()) {
                        InfoRow(
                            label = stringResource(Res.string.caption),
                            value = caption,
                            onCopy = { copy(caption) },
                        )
                    }

                    event.replyToEventId?.takeIf { it.isNotBlank() }?.let {
                        InfoRow(
                            label = stringResource(Res.string.reply_to_event),
                            value = it,
                            onCopy = { copy(it) },
                        )
                    }

                    event.threadRootEventId?.takeIf { it.isNotBlank() }?.let {
                        InfoRow(
                            label = stringResource(Res.string.thread_root_event),
                            value = it,
                            onCopy = { copy(it) },
                        )
                    }
                }
            }

            if (attachment != null) {
                item {
                    AttachmentSection(
                        attachment = attachment,
                        onCopy = ::copy,
                    )
                }
            }

            event.rawJson?.takeIf { it.isNotBlank() }?.let { json ->
                item {
                    SectionCard(title = stringResource(Res.string.raw_json)) {
                        Text(
                            text = json,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Spacing.sm),
                        )
                        IconButton(onClick = { copy(json) }) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = stringResource(Res.string.copy),
                            )
                        }
                    }
                }
            }

            item {
                SectionCard(title = stringResource(Res.string.read_receipts)) {
                    when {
                        isLoadingReaders -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularWavyProgressIndicator(modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(Spacing.md))
                                Text(
                                    text = stringResource(Res.string.loading_read_receipts),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        readersError != null -> {
                            Text(
                                text = readersError.ifBlank {
                                    stringResource(Res.string.failed_to_load_read_receipts)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(Spacing.xs))
                            TextButton(onClick = onRetryReaders) {
                                Text(stringResource(Res.string.retry))
                            }
                        }

                        readers.isEmpty() -> {
                            Text(
                                text = stringResource(Res.string.no_read_receipts_yet),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        else -> {
                            Text(
                                text = stringResource(Res.string.read_by),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.height(Spacing.xs))
                            readers.forEach { entry ->
                                MessageInfoReaderRow(entry)
                            }
                            if (readersTruncated) {
                                Spacer(Modifier.height(Spacing.xs))
                                Text(
                                    text = stringResource(
                                        Res.string.showing_first_read_receipts,
                                        readers.size,
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            item {
                SectionCard(title = stringResource(Res.string.advanced)) {
                    event.eventId.takeIf { it.isNotBlank() }?.let {
                        InfoRow(
                            label = stringResource(Res.string.event_id),
                            value = it,
                            onCopy = { copy(it) },
                        )
                    }

                    event.txnId?.takeIf { it.isNotBlank() }?.let {
                        InfoRow(
                            label = stringResource(Res.string.transaction_id),
                            value = it,
                            onCopy = { copy(it) },
                        )
                    }

                    if (attachment != null) {
                        InfoRow(
                            label = stringResource(Res.string.mxc_uri),
                            value = attachment.mxcUri,
                            onCopy = { copy(attachment.mxcUri) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentSection(
    attachment: AttachmentInfo,
    onCopy: (String) -> Unit,
) {
    val typeLabel = when (attachment.kind) {
        AttachmentKind.Image -> stringResource(Res.string.image)
        AttachmentKind.Video -> stringResource(Res.string.video)
        AttachmentKind.File -> stringResource(Res.string.file)
    }

    SectionCard(title = stringResource(Res.string.attachment_details)) {
        InfoRow(
            label = stringResource(Res.string.attachment_type),
            value = typeLabel,
        )

        attachment.fileName?.takeIf { it.isNotBlank() }?.let {
            InfoRow(
                label = stringResource(Res.string.attachment_name),
                value = it,
                onCopy = { onCopy(it) },
            )
        }

        attachment.mime?.takeIf { it.isNotBlank() }?.let {
            InfoRow(
                label = stringResource(Res.string.mime_type),
                value = it,
                onCopy = { onCopy(it) },
            )
        }

        formatBytes(attachment.sizeBytes)?.let {
            InfoRow(
                label = stringResource(Res.string.file_size),
                value = it,
            )
        }

        formatDimensions(attachment.width, attachment.height)?.let {
            InfoRow(
                label = stringResource(Res.string.dimensions),
                value = it,
            )
        }

        formatDurationMs(attachment.durationMs)?.let {
            InfoRow(
                label = stringResource(Res.string.duration),
                value = it,
            )
        }

        InfoRow(
            label = stringResource(Res.string.encrypted),
            value = if (attachment.encrypted != null) {
                stringResource(Res.string.yes)
            } else {
                stringResource(Res.string.no)
            },
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.md),
            )
            HorizontalDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    onCopy: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.xs))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (onCopy != null) {
                IconButton(onClick = onCopy) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = stringResource(Res.string.copy),
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageInfoPreview(
    event: MessageEvent,
    fallbackFileName: String?,
) {
    val preview = event.body
        .trim()
        .ifBlank { fallbackFileName.orEmpty() }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(Spacing.md)) {
            Text(
                text = event.senderDisplayName ?: event.sender,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = formatTime(event.timestampMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (preview.isNotBlank()) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MessageInfoReaderRow(entry: SeenByEntry) {
    val displayName = entry.displayName ?: entry.userId
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            name = displayName,
            avatarPath = entry.avatarUrl,
            size = 28.dp,
        )
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(displayName, style = MaterialTheme.typography.bodyMedium)
            entry.tsMs?.let { ts ->
                Text(
                    text = formatAbsoluteDateTime(ts.toLong()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun ReadReceiptsSheet(
    entries: List<SeenByEntry>,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = stringResource(Res.string.read_by),
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            if (entries.isEmpty()) {
                item {
                    Text(
                        text = stringResource(Res.string.no_read_receipts_yet),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(entries.sortedByDescending { it.tsMs?.toLong() ?: 0L }) { entry ->
                    Surface(
                        color = ListItemDefaults.colors().containerColor,
                        tonalElevation = 0.dp,
                    ) {
                        ListItem(
                            leadingContent = {
                                Avatar(
                                    name = entry.displayName ?: entry.userId,
                                    avatarPath = entry.avatarUrl,
                                    size = 44.dp,
                                )
                            },
                            headlineContent = {
                                Text(
                                    text = entry.displayName ?: entry.userId,
                                    fontWeight = FontWeight.Medium,
                                )
                            },
                            supportingContent = {
                                entry.tsMs?.let {
                                    Text(formatAbsoluteDateTime(it.toLong()))
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
