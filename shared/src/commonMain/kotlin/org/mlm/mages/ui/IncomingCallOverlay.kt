package org.mlm.mages.ui

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.time.Clock
import mages.shared.generated.resources.Res
import mages.shared.generated.resources.decline
import mages.shared.generated.resources.answer
import mages.shared.generated.resources.incoming_call
import org.jetbrains.compose.resources.stringResource
import org.mlm.mages.calls.IncomingCall
import org.mlm.mages.ui.components.core.Avatar
import org.mlm.mages.ui.components.core.TypingDots
import org.mlm.mages.ui.components.core.TypingDots

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
        val waitMs = (call.expiresAtMs - currentTimeMs()).coerceAtLeast(0L)
        delay(waitMs)
        onTimeout()
    }

    val scheme = MaterialTheme.colorScheme
    val ring = rememberInfiniteTransition(label = "incoming-ring")
    val pulse = ring.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(scheme.scrim.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = scheme.surfaceContainerHigh,
            tonalElevation = 8.dp,
            shadowElevation = 16.dp,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TypingDots()
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = stringResource(Res.string.incoming_call),
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.height(16.dp))
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(128.dp)
                            .scale(pulse.value)
                            .alpha(0.35f)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(
                                        scheme.primary.copy(alpha = 0.55f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )
                    Avatar(
                        name = call.callerName,
                        avatarPath = null,
                        size = 96.dp
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = call.callerName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = scheme.onSurface
                )
                if (call.roomName.isNotBlank() && call.roomName != call.callerName) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = call.roomName,
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (moreCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "+$moreCount more",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(24.dp))
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = onDecline,
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = scheme.error,
                                contentColor = scheme.onError
                            ),
                            modifier = Modifier.size(64.dp)
                        ) {
                            Icon(
                                Icons.Default.CallEnd,
                                contentDescription = stringResource(Res.string.decline),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(Res.string.decline),
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = onAnswer,
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = Color(0xFF22C55E),
                                contentColor = Color(0xFF06210F)
                            ),
                            modifier = Modifier.size(64.dp)
                        ) {
                            Icon(
                                Icons.Default.Call,
                                contentDescription = stringResource(Res.string.answer),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(Res.string.answer),
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun currentTimeMs(): Long = Clock.System.now().toEpochMilliseconds()
