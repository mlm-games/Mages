package org.mlm.mages.ui.components.message

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.mlm.mages.ui.components.timeline.TimelineContent
import org.mlm.mages.ui.theme.Spacing

@Composable
internal fun SystemMessageItem(
    item: TimelineContent.System,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            onClick = onClick ?: {},
            enabled = onClick != null,
            color = Color.Transparent,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.widthIn(min = 80.dp, max = 320.dp)
        ) {
            Text(
                text = item.event.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp)
            )
        }
    }
}
