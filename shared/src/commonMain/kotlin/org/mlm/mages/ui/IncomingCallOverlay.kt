package org.mlm.mages.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import mages.shared.generated.resources.Res
import mages.shared.generated.resources.answer
import mages.shared.generated.resources.decline
import mages.shared.generated.resources.incoming_call
import org.jetbrains.compose.resources.stringResource
import org.mlm.mages.calls.IncomingCall
import org.mlm.mages.ui.components.core.Avatar
import kotlin.time.Clock

@Composable
fun IncomingCallOverlay(
    call: IncomingCall,
    moreCount: Int = 0,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onTimeout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(call.roomId, call.eventId) {
        val waitMs = (call.expiresAtMs - Clock.System.now().toEpochMilliseconds()).coerceAtLeast(0L)
        delay(waitMs)
        onTimeout()
    }

    val scheme = MaterialTheme.colorScheme

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = scheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 76.dp)
                .widthIn(max = 380.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Avatar(
                    name = call.callerName,
                    avatarPath = null,
                    size = 48.dp
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = call.callerName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = scheme.onSurface
                    )
                    val subtitle = if (call.roomName.isNotBlank() && call.roomName != call.callerName) {
                        "${stringResource(Res.string.incoming_call)} • ${call.roomName}"
                    } else {
                        stringResource(Res.string.incoming_call)
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (moreCount > 0) {
                        Text(
                            text = "+$moreCount more",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = onDecline,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = scheme.error,
                            contentColor = scheme.onError
                        ),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            Icons.Default.CallEnd,
                            contentDescription = stringResource(Res.string.decline),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    IconButton(
                        onClick = onAnswer,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = Color(0xFF22C55E),
                            contentColor = Color(0xFF06210F)
                        ),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            Icons.Default.Call,
                            contentDescription = stringResource(Res.string.answer),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}
