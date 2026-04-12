package org.mlm.mages.ui.components.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

@Composable
fun WaveformSeekBar(
    waveformData: List<Float>,
    progress: Float,
    onSeek: (Float) -> Unit,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier,
    barCount: Int = 40
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var dragProgress by remember { mutableStateOf<Float?>(null) }

    val bars = remember(waveformData, barCount) {
        when {
            waveformData.isEmpty() -> List(barCount) { 0.2f }
            waveformData.size <= barCount -> {
                // Pad with silence if fewer bars than target
                waveformData + List(barCount - waveformData.size) { 0.2f }
            }
            else -> {
                val step = waveformData.size.toFloat() / barCount
                (0 until barCount).map { i ->
                    val start = (i * step).toInt()
                    val end = ((i + 1) * step).toInt().coerceAtMost(waveformData.size)
                    if (start < waveformData.size) {
                        waveformData.subList(start, end.coerceAtLeast(start + 1)).average().toFloat()
                    } else 0.2f
                }
            }
        }
    }

    val renderedProgress = (dragProgress ?: progress).coerceIn(0f, 1f)
    val progressIndex = (bars.size * renderedProgress).toInt().coerceIn(0, bars.size)

    fun progressFromX(x: Float): Float {
        if (size.width <= 0) return 0f
        return (x / size.width).coerceIn(0f, 1f)
    }

    Row(
        modifier = modifier
            .height(32.dp)
            .fillMaxWidth()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    dragProgress = null
                    onSeek(progressFromX(offset.x))
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        dragProgress = progressFromX(offset.x)
                    },
                    onDragCancel = {
                        dragProgress = null
                    },
                    onDragEnd = {
                        val seek = dragProgress
                        dragProgress = null
                        if (seek != null) {
                            onSeek(seek)
                        }
                    },
                ) { change, _ ->
                    change.consume()
                    dragProgress = progressFromX(change.position.x)
                }
            },
        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        bars.forEachIndexed { idx, amp ->
            // Min 4dp, max 32dp, natural height proportional to amplitude
            val h = (4f + amp.coerceIn(0f, 1f) * 28f).coerceIn(4f, 32f)
            Box(
                Modifier
                    .width(3.dp)
                    .height(h.dp)
                    .background(
                        if (idx < progressIndex) activeColor else inactiveColor,
                        RoundedCornerShape(2.dp)
                    )
            )
        }
    }
}
