package org.mlm.mages.ui.components.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.mlm.mages.platform.PlaybackState
import org.mlm.mages.platform.createAudioPlayer
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.util.formatDuration

private val SPEED_OPTIONS = listOf(1f, 1.5f, 2f)

@Composable
fun VoiceMessageBubble(
    filePath: String?,
    durationMs: Long,
    waveformData: List<Float>,
    isMine: Boolean,
    modifier: Modifier = Modifier
) {
    val player = remember { createAudioPlayer() }
    val playbackState by player.state.collectAsState()

    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) { onDispose { player.release() } }

    val isPlaying = playbackState.let { it is PlaybackState.Playing && !it.isPaused }
    val playingState = playbackState as? PlaybackState.Playing
    val progress = playingState?.let {
        if (it.durationMs > 0) it.positionMs.toFloat() / it.durationMs else 0f
    } ?: 0f
    val position = playingState?.positionMs ?: 0L
    var lastKnownDuration by remember(durationMs) { mutableLongStateOf(durationMs.coerceAtLeast(0L)) }
    val playingDuration = playingState?.durationMs?.takeIf { it > 0L }
    if (playingDuration != null && playingDuration != lastKnownDuration) {
        lastKnownDuration = playingDuration
    }
    val displayDuration = playingDuration ?: lastKnownDuration

    val bubbleColor = if (isMine) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
    val onBubble = if (isMine) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant

    var speedIndex by remember { mutableIntStateOf(0) }
    val currentSpeed = SPEED_OPTIONS[speedIndex]

    Surface(
        color = bubbleColor,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.widthIn(min = 200.dp, max = 300.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(enabled = playbackState !is PlaybackState.Loading) {
                        when {
                            isPlaying -> player.pause()
                            playbackState is PlaybackState.Playing -> {
                                player.setPlaybackSpeed(currentSpeed)
                                player.play()
                            }

                            else -> filePath?.let { fp ->
                                player.setPlaybackSpeed(currentSpeed)
                                scope.launch { player.load(fp) }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (playbackState is PlaybackState.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(Modifier.width(Spacing.sm))

            Column(modifier = Modifier.weight(1f)) {
                WaveformSeekBar(
                    waveformData = waveformData,
                    progress = progress,
                    onSeek = { player.seekTo((it * displayDuration).toLong()) },
                    activeColor = onBubble,
                    inactiveColor = onBubble.copy(alpha = 0.28f),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(2.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(onBubble.copy(alpha = 0.10f))
                            .clickable {
                                speedIndex = (speedIndex + 1) % SPEED_OPTIONS.size
                                player.setPlaybackSpeed(SPEED_OPTIONS[speedIndex])
                            }
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = formatSpeed(currentSpeed),
                            style = MaterialTheme.typography.labelSmall,
                            color = onBubble.copy(alpha = 0.75f)
                        )
                    }

                    Text(
                        text = if (isPlaying || position > 0) formatDuration(position)
                        else formatDuration(displayDuration),
                        style = MaterialTheme.typography.labelSmall,
                        color = onBubble.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

private fun formatSpeed(speed: Float): String = when (speed) {
    1f -> "1×"
    1.5f -> "1.5×"
    2f -> "2×"
    else -> "${speed}×"
}
