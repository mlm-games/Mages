package org.mlm.mages.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.mlm.mages.MessageEvent
import org.mlm.mages.matrix.EventType
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.util.formatTime

@Composable
fun SystemMessageItem(
    event: MessageEvent,
    modifier: Modifier = Modifier
) {
    val isSystemEvent = event.eventType != EventType.Message &&
            event.eventType != EventType.Poll &&
            event.eventType != EventType.Sticker

    if (!isSystemEvent || event.body.isBlank()) {
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(0.75f)
        ) {
            Text(
                text = event.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)
            )
        }

        Text(
            text = formatTime(event.timestampMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
        )
    }
}
