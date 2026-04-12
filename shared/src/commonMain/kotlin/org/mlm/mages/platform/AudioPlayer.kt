package org.mlm.mages.platform

import eu.iamkonstantin.kotlin.gadulka.GadulkaPlayer
import eu.iamkonstantin.kotlin.gadulka.GadulkaPlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.mlm.mages.ui.util.nowMs
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

sealed class PlaybackState {
    data object Idle : PlaybackState()
    data object Loading : PlaybackState()
    data class Playing(
        val positionMs: Long,
        val durationMs: Long,
        val isPaused: Boolean = false
    ) : PlaybackState()

    data class Error(val message: String) : PlaybackState()
}

interface AudioPlayer {
    val state: StateFlow<PlaybackState>
    suspend fun load(filePath: String)
    fun play()
    fun pause()
    fun stop()
    fun seekTo(positionMs: Long)
    fun setPlaybackSpeed(speed: Float)
    fun release()
}

class GadulkaAudioPlayer : AudioPlayer {

    private companion object {
        const val POSITION_UPDATE_INTERVAL_MS = 80L
        const val DURATION_WAIT_TIMEOUT_MS = 3000L
        const val DURATION_WAIT_STEP_MS = 50L
        const val COMPLETION_EPSILON_MS = 120L
        const val SEEK_MATCH_TOLERANCE_MS = 180L
    }

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var player: GadulkaPlayer? = null
    private var durationMs: Long = 0
    private var playbackSpeed: Float = 1f
    private var updateJob: Job? = null
    private var pendingSeekMs: Long? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override suspend fun load(filePath: String) {
        try {
            stop()
            player?.release()
            player = GadulkaPlayer()
            _state.value = PlaybackState.Loading

            val url = if (filePath.startsWith("http")) filePath else "file://$filePath"
            player?.setRate(playbackSpeed)
            withContext(Dispatchers.Default) {
                player?.play(url)
            }

            val dur = awaitDurationReady(player)
            durationMs = dur
            pendingSeekMs = null

            _state.value = PlaybackState.Playing(0, durationMs, isPaused = false)
            startUpdates()
        } catch (e: Exception) {
            _state.value = PlaybackState.Error("Load failed: ${e.message}")
        }
    }

    override fun play() {
        val s = _state.value
        if (s is PlaybackState.Playing) {
            _state.value = s.copy(isPaused = false)
        }
        player?.setRate(playbackSpeed)
        player?.play()
        startUpdates()
    }

    override fun pause() {
        player?.pause()
        updateJob?.cancel()
        updateJob = null
        val s = _state.value
        if (s is PlaybackState.Playing) {
            _state.value = s.copy(isPaused = true)
        }
    }

    override fun stop() {
        player?.stop()
        updateJob?.cancel()
        updateJob = null
        pendingSeekMs = null
        durationMs = 0
        _state.value = PlaybackState.Idle
    }

    override fun seekTo(positionMs: Long) {
        val s = _state.value
        val knownDuration = when {
            durationMs > 0L -> durationMs
            s is PlaybackState.Playing && s.durationMs > 0L -> s.durationMs
            else -> 0L
        }
        val targetPosition = if (knownDuration > 0L) {
            positionMs.coerceIn(0L, knownDuration)
        } else {
            positionMs.coerceAtLeast(0L)
        }
        pendingSeekMs = targetPosition
        player?.seekTo(targetPosition)

        if (s is PlaybackState.Playing) {
            _state.value = s.copy(positionMs = targetPosition)
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        playbackSpeed = speed.coerceIn(0.25f, 4.0f)
        player?.setRate(playbackSpeed)
    }

    override fun release() {
        updateJob?.cancel()
        scope.cancel()
        player?.release()
        player = null
    }

    private fun startUpdates() {
        updateJob?.cancel()
        updateJob = scope.launch {
            while (isActive) {
                val p = player ?: break
                val gadulkaState = p.currentPlayerState()
                val pos = p.currentPosition() ?: 0L
                val currentDuration = p.currentDuration() ?: 0L
                if (currentDuration > 0L) {
                    durationMs = currentDuration
                }
                val s = _state.value
                if (s is PlaybackState.Playing) {
                    val resolvedDuration = if (durationMs > 0L) durationMs else s.durationMs

                    var resolvedPosition = if (resolvedDuration > 0L) {
                        pos.coerceIn(0L, resolvedDuration)
                    } else {
                        pos.coerceAtLeast(0L)
                    }

                    val seekTarget = pendingSeekMs
                    if (seekTarget != null) {
                        val delta = abs(resolvedPosition - seekTarget)
                        if (delta <= SEEK_MATCH_TOLERANCE_MS) {
                            pendingSeekMs = null
                        } else {
                            resolvedPosition = if (resolvedDuration > 0L) {
                                seekTarget.coerceIn(0L, resolvedDuration)
                            } else {
                                seekTarget.coerceAtLeast(0L)
                            }
                        }
                    }

                    val next = s.copy(positionMs = resolvedPosition, durationMs = resolvedDuration)
                    if (next != s) {
                        _state.value = next
                    }

                    if (!s.isPaused && resolvedDuration > 0L) {
                        val reachedEnd =
                            resolvedPosition >= (resolvedDuration - COMPLETION_EPSILON_MS).coerceAtLeast(0L)
                        if (reachedEnd || (gadulkaState == GadulkaPlayerState.IDLE && resolvedPosition >= resolvedDuration)) {
                            _state.value = PlaybackState.Idle
                            break
                        }
                    }
                } else if (gadulkaState == GadulkaPlayerState.IDLE) {
                    break
                }
                delay(POSITION_UPDATE_INTERVAL_MS)
            }
            updateJob = null
        }
    }

    private suspend fun awaitDurationReady(player: GadulkaPlayer?): Long {
        val startMs = nowMs()
        while (nowMs() - startMs < DURATION_WAIT_TIMEOUT_MS) {
            val value = player?.currentDuration()?.takeIf { it > 0L }
            if (value != null) {
                return value
            }
            delay(DURATION_WAIT_STEP_MS)
        }
        return player?.currentDuration()?.takeIf { it > 0L } ?: 0L
    }
}

fun createAudioPlayer(): AudioPlayer = GadulkaAudioPlayer()
