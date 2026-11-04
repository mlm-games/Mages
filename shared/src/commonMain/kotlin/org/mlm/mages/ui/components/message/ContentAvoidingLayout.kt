package org.mlm.mages.ui.components.message

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class TimestampPosition {
    Overlay,
    Aligned,
    Below
}

@Composable
fun TimestampLayout(
    position: TimestampPosition,
    modifier: Modifier = Modifier,
    timestampPadding: Dp = 4.dp,
    contentAlignment: Alignment.Horizontal = Alignment.Start,
    timestamp: @Composable () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    when (position) {
        TimestampPosition.Overlay -> {
            Box(modifier = modifier) {
                content()
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(timestampPadding)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    timestamp()
                }
            }
        }
        TimestampPosition.Aligned -> {
            ContentAvoidingRow(
                modifier = modifier,
                contentAlignment = contentAlignment,
                timestamp = timestamp,
                content = content
            )
        }
        TimestampPosition.Below -> {
            Column(modifier = modifier, horizontalAlignment = Alignment.End) {
                Box { content() }
                Box(modifier = Modifier.padding(top = 2.dp, start = 8.dp)) {
                    timestamp()
                }
            }
        }
    }
}

private val TIMESTAMP_GAP = 8.dp
private val TIMESTAMP_BELOW_GAP = 4.dp

@Composable
private fun ContentAvoidingRow(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment.Horizontal,
    timestamp: @Composable () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Layout(
        modifier = modifier,
        content = {
            Box(content = content)
            timestamp()
        }
    ) { measurables, constraints ->
        val maxWidth = constraints.maxWidth
        val maxHeight = constraints.maxHeight
        val gap = TIMESTAMP_GAP.roundToPx()
        val belowGap = TIMESTAMP_BELOW_GAP.roundToPx()
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val timestampPlaceable = measurables[1].measure(childConstraints)
        val timestampWidth = timestampPlaceable.width
        val timestampHeight = timestampPlaceable.height

        val contentPlaceable = measurables[0].measure(childConstraints)
        val contentWidth = contentPlaceable.width
        val contentHeight = contentPlaceable.height

        val besideWidth = contentWidth + gap + timestampWidth
        val layoutWidth = maxOf(besideWidth, timestampWidth, constraints.minWidth)
            .coerceAtMost(maxWidth)
        val keepBeside = besideWidth <= layoutWidth
        val layoutHeight = maxOf(
            if (keepBeside) maxOf(contentHeight, timestampHeight)
            else contentHeight + belowGap + timestampHeight,
            constraints.minHeight
        ).coerceAtMost(maxHeight)

        layout(layoutWidth, layoutHeight) {
            val contentX = if (contentAlignment == Alignment.End) {
                (layoutWidth - timestampWidth - gap - contentWidth).coerceAtLeast(0)
            } else {
                0
            }
            contentPlaceable.placeRelative(contentX, 0)
            timestampPlaceable.placeRelative(
                x = layoutWidth - timestampWidth,
                y = if (keepBeside) layoutHeight - timestampHeight
                else contentHeight + belowGap
            )
        }
    }
}