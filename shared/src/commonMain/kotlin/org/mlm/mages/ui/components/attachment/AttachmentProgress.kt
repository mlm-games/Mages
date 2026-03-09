package org.mlm.mages.ui.components.attachment

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import org.mlm.mages.ui.AttachmentUploadStage
import org.mlm.mages.ui.theme.Sizes
import org.mlm.mages.ui.theme.Spacing

@Composable
fun AttachmentProgress(
    fileName: String,
    progress: Float,
    stage: AttachmentUploadStage,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val animatedProgress = animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 450),
        label = "attachmentUploadProgress",
    )
    val secondaryText = when (stage) {
        AttachmentUploadStage.Preparing -> "Preparing upload..."
        AttachmentUploadStage.Uploading -> "${(animatedProgress.value * 100).toInt()}%"
        AttachmentUploadStage.Sending -> "Sending..."
    }

    Card(modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
        Row(modifier = Modifier.fillMaxWidth().padding(Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            when (stage) {
                AttachmentUploadStage.Preparing -> LoadingIndicator(modifier = Modifier.size(Sizes.iconLarge))
                AttachmentUploadStage.Uploading,
                AttachmentUploadStage.Sending,
                -> LoadingIndicator(progress = { animatedProgress.value }, modifier = Modifier.size(Sizes.iconLarge))
            }
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(secondaryText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "Cancel") }
        }
        when (stage) {
            AttachmentUploadStage.Preparing -> LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            AttachmentUploadStage.Uploading,
            AttachmentUploadStage.Sending,
            -> LinearWavyProgressIndicator(progress = { animatedProgress.value }, modifier = Modifier.fillMaxWidth())
        }
    }
}
