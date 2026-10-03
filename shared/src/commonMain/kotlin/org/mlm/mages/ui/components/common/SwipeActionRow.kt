package org.mlm.mages.ui.components.common

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import mages.shared.generated.resources.*
import mages.shared.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import org.mlm.mages.settings.RoomSwipeAction
import kotlin.math.abs
import kotlin.math.max

@Composable
fun SwipeActionRow(
    swipeRightAction: RoomSwipeAction,
    swipeLeftAction: RoomSwipeAction,
    onAction: (RoomSwipeAction) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val rightEnabled = swipeRightAction != RoomSwipeAction.Nothing
    val leftEnabled = swipeLeftAction != RoomSwipeAction.Nothing

    if (!rightEnabled && !leftEnabled) {
        Box(modifier = modifier.fillMaxWidth()) { content() }
        return
    }

    val haptics = LocalHapticFeedback.current
    val thresholdPx = with(LocalDensity.current) { 72.dp.toPx() }

    var revealWidthPx by remember { mutableFloatStateOf(0f) }

    var offsetPx by remember { mutableFloatStateOf(0f) }
    val animatedOffsetPx by animateFloatAsState(
        targetValue = offsetPx,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "swipeActionOffset"
    )
    var hapticArmed by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxWidth()) {
        val goingRight = animatedOffsetPx > 0f
        val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val action = if (goingRight) swipeRightAction else swipeLeftAction
        val progress = (abs(animatedOffsetPx) / thresholdPx).coerceIn(0f, 1f)
        val background = when (action) {
            RoomSwipeAction.MarkRead -> MaterialTheme.colorScheme.primaryContainer
            RoomSwipeAction.MarkUnread -> MaterialTheme.colorScheme.secondaryContainer
            RoomSwipeAction.Nothing -> Color.Transparent
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(background.copy(alpha = progress)),
            contentAlignment = if (goingRight) AbsoluteAlignment.CenterLeft else AbsoluteAlignment.CenterRight
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .onSizeChanged { revealWidthPx = it.width.toFloat() }
            ) {
                if (goingRight != isRtl) {
                    RevealIcon(action, progress)
                    Spacer(Modifier.width(8.dp))
                    RevealLabel(action, progress)
                } else {
                    RevealLabel(action, progress)
                    Spacer(Modifier.width(8.dp))
                    RevealIcon(action, progress)
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = animatedOffsetPx }
                .pointerInput(swipeRightAction, swipeLeftAction) {
                    detectHorizontalDragGestures(
                        onDragStart = { hapticArmed = false },
                        onDragEnd = {
                            val committed = offsetPx
                            offsetPx = 0f
                            val triggered = when {
                                committed >= thresholdPx && rightEnabled -> swipeRightAction
                                -committed >= thresholdPx && leftEnabled -> swipeLeftAction
                                else -> RoomSwipeAction.Nothing
                            }
                            if (triggered != RoomSwipeAction.Nothing) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onAction(triggered)
                            }
                        },
                        onDragCancel = {
                            offsetPx = 0f
                            hapticArmed = false
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            val cap = max(thresholdPx * 1.2f, revealWidthPx)
                            val dragMax = if (rightEnabled) cap else 0f
                            val dragMin = if (leftEnabled) -cap else 0f
                            offsetPx = (offsetPx + dragAmount).coerceIn(dragMin, dragMax)
                            if (abs(offsetPx) >= thresholdPx) {
                                if (!hapticArmed) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    hapticArmed = true
                                }
                            } else {
                                hapticArmed = false
                            }
                        }
                    )
                }
        ) { content() }
    }
}

@Composable
private fun RevealIcon(action: RoomSwipeAction, alpha: Float) {
    val icon = when (action) {
        RoomSwipeAction.MarkRead -> Icons.Default.Done
        RoomSwipeAction.MarkUnread -> Icons.Default.MarkEmailUnread
        RoomSwipeAction.Nothing -> null
    } ?: return
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier
            .size(20.dp)
            .graphicsLayer { this.alpha = alpha },
        tint = revealContentColor(action)
    )
}

@Composable
private fun RevealLabel(action: RoomSwipeAction, alpha: Float) {
    val label = when (action) {
        RoomSwipeAction.MarkRead -> stringResource(Res.string.mark_read)
        RoomSwipeAction.MarkUnread -> stringResource(Res.string.mark_as_unread)
        RoomSwipeAction.Nothing -> return
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = revealContentColor(action),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.graphicsLayer { this.alpha = alpha }
    )
}

@Composable
private fun revealContentColor(action: RoomSwipeAction): Color = when (action) {
    RoomSwipeAction.MarkRead -> MaterialTheme.colorScheme.onPrimaryContainer
    RoomSwipeAction.MarkUnread -> MaterialTheme.colorScheme.onSecondaryContainer
    RoomSwipeAction.Nothing -> Color.Transparent
}
