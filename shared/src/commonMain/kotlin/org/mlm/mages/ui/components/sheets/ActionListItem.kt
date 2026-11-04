package org.mlm.mages.ui.components.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp

@Composable
fun ActionListItem(
    icon: Painter,
    label: String,
    description: String?,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    ListItem(
        headlineContent = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = description?.let {
            {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        leadingContent = {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        },
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled, onClick = onClick)
    )
}
