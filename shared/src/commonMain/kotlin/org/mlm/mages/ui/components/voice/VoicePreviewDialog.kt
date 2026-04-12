package org.mlm.mages.ui.components.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.mlm.mages.platform.PlaybackState
import org.mlm.mages.platform.createAudioPlayer
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.util.formatDuration

@Composable
fun VoicePreviewDialog(
    filePath: String,
    durationMs: Long,
    waveformData: List<Float>,
    onSend: () -> Unit,
    onReRecord: () -> Unit,
    onCancel: () -> Unit,
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

    BasicAlertDialog(
        onDismissRequest = onCancel,
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.xl, vertical = Spacing.xl)
            ) {
                Text(
                    text = "Voice message",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = formatDuration(displayDuration),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(Spacing.lg))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable(enabled = playbackState !is PlaybackState.Loading) {
                                when {
                                    isPlaying -> player.pause()
                                    playbackState is PlaybackState.Playing -> player.play()
                                    else -> scope.launch { player.load(filePath) }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (playbackState is PlaybackState.Loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(Spacing.md))

                    Column(modifier = Modifier.weight(1f)) {
                        WaveformSeekBar(
                            waveformData = waveformData,
                            progress = progress,
                            onSeek = { player.seekTo((it * displayDuration).toLong()) },
                            activeColor = MaterialTheme.colorScheme.primary,
                            inactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = buildString {
                                if (position > 0 || isPlaying) {
                                    append(formatDuration(position))
                                    append(" / ")
                                }
                                append(formatDuration(displayDuration))
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(Spacing.xl))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    OutlinedButton(
                        onClick = onReRecord,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    IconButton(
                        onClick = onSend,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}
