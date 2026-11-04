package org.mlm.mages.ui.components.message

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import mages.shared.generated.resources.Res
import mages.shared.generated.resources.voice_call_declined
import mages.shared.generated.resources.video_call_declined
import mages.shared.generated.resources.call_declined
import mages.shared.generated.resources.voice_call
import mages.shared.generated.resources.video_call
import mages.shared.generated.resources.ic_call
import mages.shared.generated.resources.ic_call_missed
import mages.shared.generated.resources.ic_videocam
import mages.shared.generated.resources.ic_videocam_off
import org.mlm.mages.matrix.EventType
import org.mlm.mages.ui.components.timeline.TimelineContent
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.util.formatTime

@Composable
private fun CallNotificationBody(event: org.mlm.mages.MessageEvent) {
    val isVideo = event.callType == "video"
    val isVoice = event.callType == "audio"
    val declined = event.callDeclined
    val icon = when {
        declined && isVideo -> painterResource(Res.drawable.ic_videocam_off)
        declined -> painterResource(Res.drawable.ic_call_missed)
        isVideo -> painterResource(Res.drawable.ic_videocam)
        else -> painterResource(Res.drawable.ic_call)
    }
    val tint = if (declined) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val text = when {
        declined && isVideo -> stringResource(Res.string.video_call_declined)
        declined && isVoice -> stringResource(Res.string.voice_call_declined)
        declined -> stringResource(Res.string.call_declined)
        isVideo -> stringResource(Res.string.video_call)
        isVoice -> stringResource(Res.string.voice_call)
        else -> event.body
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm + Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp)
    ) {
        Icon(
            painter = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = formatTime(event.timestampMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

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
            val event = item.event
            if (event.eventType == EventType.CallNotification) {
                CallNotificationBody(event)
            } else {
                Text(
                    text = event.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp)
                )
            }
        }
    }
}
