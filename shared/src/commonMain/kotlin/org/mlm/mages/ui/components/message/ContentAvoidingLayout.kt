package org.mlm.mages.ui.components.message

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
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

@Composable
private fun ContentAvoidingRow(
    modifier: Modifier = Modifier,
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
        val timestampPlaceable = measurables[1].measure(
            Constraints(minWidth = 0, maxWidth = constraints.maxWidth)
        )
        val timestampWidth = timestampPlaceable.width
        val timestampHeight = timestampPlaceable.height

        val contentConstraints = Constraints(
            minWidth = 0,
            maxWidth = (constraints.maxWidth - timestampWidth - 8).coerceAtLeast(0)
        )
        val contentPlaceable = measurables[0].measure(contentConstraints)

        val contentWidth = contentPlaceable.width
        val contentHeight = contentPlaceable.height

        val needsBelow = contentWidth >= constraints.maxWidth - timestampWidth - 8

        val layoutWidth = if (needsBelow) {
            maxOf(contentWidth, timestampWidth)
        } else {
            contentWidth + timestampWidth + 8
        }
        val layoutHeight = if (needsBelow) {
            contentHeight + timestampHeight + 4
        } else {
            maxOf(contentHeight, timestampHeight)
        }

        layout(layoutWidth, layoutHeight) {
            contentPlaceable.placeRelative(0, 0)
            timestampPlaceable.placeRelative(
                x = if (needsBelow) layoutWidth - timestampWidth else contentWidth + 8,
                y = if (needsBelow) contentHeight + 4 else layoutHeight - timestampHeight
            )
        }
    }
}