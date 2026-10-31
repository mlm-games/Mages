package org.mlm.mages.ui.components.core

import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.mlm.mages.ui.UnreadBadgeUi
import org.mlm.mages.ui.theme.Limits

@Composable
fun UnreadBadge(
    unread: UnreadBadgeUi?,
    showSilentCount: Boolean = true,
    modifier: Modifier = Modifier,
) {
    if (unread == null || !unread.hasUnread) return

    val showCount = unread.highlighted || showSilentCount
    Badge(
        modifier = modifier,
        containerColor = if (unread.highlighted) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (unread.highlighted) MaterialTheme.colorScheme.onError
        else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = when {
                !showCount -> "•"
                unread.count > Limits.unreadBadgeCap -> "${Limits.unreadBadgeCap}+"
                else -> unread.count.toString()
            },
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
