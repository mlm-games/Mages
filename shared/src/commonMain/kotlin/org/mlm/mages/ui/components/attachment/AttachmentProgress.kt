package org.mlm.mages.ui.components.attachment

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import org.mlm.mages.ui.theme.Sizes
import org.mlm.mages.ui.theme.Spacing

@Composable
fun AttachmentProgress(fileName: String, progress: Float, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
        Row(modifier = Modifier.fillMaxWidth().padding(Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            LoadingIndicator(progress = { progress }, modifier = Modifier.size(Sizes.iconLarge))
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "Cancel") }
        }
        LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    }
}