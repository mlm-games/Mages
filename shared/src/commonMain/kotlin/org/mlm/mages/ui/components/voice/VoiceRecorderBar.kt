package org.mlm.mages.ui.components.voice

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.mlm.mages.platform.RecordingState
import org.mlm.mages.platform.createAudioRecorder
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.util.formatDuration

@Composable
fun VoiceRecorderBar(
    onSend: (filePath: String, durationMs: Long, waveform: List<Float>) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val recorder = remember { createAudioRecorder() }
    val recordingState by recorder.state.collectAsState()

    DisposableEffect(Unit) { onDispose { recorder.release() } }
    LaunchedEffect(Unit) { recorder.startRecording() }

    LaunchedEffect(recordingState) {
        when (val s = recordingState) {
            is RecordingState.Stopped -> onSend(s.filePath, s.durationMs, s.waveformData)
            is RecordingState.Error -> onCancel()
            else -> Unit
        }
    }

    val duration = (recordingState as? RecordingState.Recording)?.durationMs ?: 0L
    val amplitude = (recordingState as? RecordingState.Recording)?.amplitude ?: 0f

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            // Delete / cancel
            IconButton(onClick = {
                scope.launch { recorder.cancelRecording(); onCancel() }
            }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Cancel recording",
                    tint = MaterialTheme.colorScheme.error
                )
            }

            // Pulsing dot
            PulsingDot()

            // Timer — fixed width so bars don't jump
            Text(
                text = formatDuration(duration),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.widthIn(min = 36.dp)
            )

            // Live amplitude bars — takes all remaining space
            LiveAmplitudeBars(
                amplitude = amplitude,
                modifier = Modifier.weight(1f)
            )

            // Stop & send
            FilledIconButton(
                onClick = { scope.launch { recorder.stopRecording() } },
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send voice message",
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun PulsingDot() {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "dotAlpha"
    )
    Box(
        Modifier
            .size(8.dp)
            .background(MaterialTheme.colorScheme.error.copy(alpha = alpha), CircleShape)
    )
}

@Composable
private fun LiveAmplitudeBars(amplitude: Float, modifier: Modifier = Modifier) {
    val safe = amplitude.coerceIn(0f, 1f)
    // Deterministic weights so bars don't all move identically
    val weights = remember { List(28) { i -> 0.25f + ((i * 13 + 7) % 16) / 16f * 0.75f } }

    Row(
        modifier = modifier.height(32.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        weights.forEach { weight ->
            val h by animateFloatAsState(
                targetValue = (3f + safe * weight * 29f).coerceIn(3f, 32f),
                animationSpec = tween(80),
                label = "bar"
            )
            Box(
                Modifier
                    .width(3.dp)
                    .height(h.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.4f + safe * 0.6f),
                        RoundedCornerShape(2.dp)
                    )
            )
        }
    }
}
