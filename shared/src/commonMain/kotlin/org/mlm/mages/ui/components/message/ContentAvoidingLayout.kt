package org.mlm.mages.ui.components.message

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            Row(
                modifier = modifier,
                verticalAlignment = Alignment.Bottom
            ) {
                Box {
                    content()
                }
                Box(
                    modifier = Modifier
                        .padding(start = 8.dp, top = 3.dp)
                ) {
                    timestamp()
                }
            }
        }
        TimestampPosition.Below -> {
            Box(modifier = modifier) {
                content()
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(top = 2.dp, start = 8.dp)
                ) {
                    timestamp()
                }
            }
        }
    }
}
