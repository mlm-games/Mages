package org.mlm.mages.ui.components.composer

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import mages.shared.generated.resources.*
import org.mlm.mages.ui.components.AttachmentData
import org.mlm.mages.ui.components.OutgoingMediaMode
import org.mlm.mages.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource

@Composable
fun ComposerAttachmentTray(
    attachments: List<AttachmentData>,
    onRemoveAttachment: ((Int) -> Unit)?,
) {
    if (attachments.isEmpty()) return

    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(start = Spacing.lg, top = Spacing.sm, end = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        attachments.forEachIndexed { index, attachment ->
            val isVisualMedia = attachment.mode == OutgoingMediaMode.Attachment &&
                (attachment.mimeType.startsWith("image/", ignoreCase = true) ||
                    attachment.mimeType.startsWith("video/", ignoreCase = true))
            if (isVisualMedia) {
                AttachmentThumbnail(
                    attachment = attachment,
                    onRemove = onRemoveAttachment?.let { callback -> { callback(index) } },
                )
            } else {
                val typeIcon = when {
                    attachment.mode == OutgoingMediaMode.Sticker -> null
                    attachment.mimeType.startsWith("audio/", ignoreCase = true) -> painterResource(Res.drawable.ic_audio_file)
                    attachment.mimeType.equals("application/pdf", ignoreCase = true) -> painterResource(Res.drawable.ic_picture_as_pdf)
                    else -> painterResource(Res.drawable.ic_attach_file)
                }
                InputChip(
                    selected = true,
                    onClick = {},
                    leadingIcon = typeIcon?.let { icon ->
                        {
                            Icon(
                                painter = icon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    label = {
                        Text(
                            buildString {
                                append(attachment.fileName)
                                if (attachment.mode == OutgoingMediaMode.Sticker) append(" Sticker")
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 160.dp)
                        )
                    },
                    trailingIcon = {
                        Icon(
                            painterResource(Res.drawable.ic_close),
                            contentDescription = stringResource(Res.string.remove_attachment),
                            modifier = Modifier.clickable { onRemoveAttachment?.invoke(index) }
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
}

@Composable
private fun AttachmentThumbnail(
    attachment: AttachmentData,
    onRemove: (() -> Unit)?,
) {
    val context = LocalPlatformContext.current
    val model = remember(attachment.path) {
        ImageRequest.Builder(context).data(attachment.path).crossfade(true).build()
    }
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        AsyncImage(
            model = model,
            contentDescription = attachment.fileName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (attachment.mimeType.startsWith("video/", ignoreCase = true)) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(Res.drawable.ic_play_arrow_filled),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (onRemove != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(Res.drawable.ic_close),
                    contentDescription = stringResource(Res.string.remove_attachment),
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
