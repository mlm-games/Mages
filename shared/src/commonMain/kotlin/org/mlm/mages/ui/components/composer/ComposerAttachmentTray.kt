package org.mlm.mages.ui.components.composer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mlm.mages.ui.components.AttachmentData
import org.mlm.mages.ui.components.OutgoingMediaMode
import org.mlm.mages.ui.theme.Spacing

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
            InputChip(
                selected = true,
                onClick = {},
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
                        Icons.Default.Close,
                        contentDescription = "Remove attachment",
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
