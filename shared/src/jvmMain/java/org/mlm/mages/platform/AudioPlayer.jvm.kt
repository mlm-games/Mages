package org.mlm.mages.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mages.AudioEngine
import mages.FfiException
import java.io.File
import java.net.URI

actual fun createAudioPlayer(): AudioPlayer = RustAudioPlayer()

private const val POSITION_UPDATE_INTERVAL_MS = 80L

class RustAudioPlayer : AudioPlayer {

    private val engine = AudioEngine()
    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var durationMs = 0L
    private var updateJob: Job? = null

    override suspend fun load(filePath: String) {
        withContext(Dispatchers.IO) {
            updateJob?.cancel()
            updateJob = null
            durationMs = 0
            _state.value = PlaybackState.Loading
            try {
                val duration = engine.load(filePath.toLocalPath())
                durationMs = duration
                _state.value = PlaybackState.Playing(0, duration, isPaused = false)
                startUpdates()
            } catch (error: Exception) {
                _state.value = PlaybackState.Error("Load failed: ${error.describe()}")
            }
        }
    }

    override fun play() {
        val current = _state.value
        if (current !is PlaybackState.Playing) return
        _state.value = current.copy(isPaused = false)
        engine.play()
        startUpdates()
    }

    override fun pause() {
        engine.pause()
        updateJob?.cancel()
        updateJob = null
        val current = _state.value
        if (current is PlaybackState.Playing) {
            _state.value = current.copy(isPaused = true)
        }
    }

    override fun stop() {
        updateJob?.cancel()
        updateJob = null
        engine.stop()
        durationMs = 0
        _state.value = PlaybackState.Idle
    }

    override fun seekTo(positionMs: Long) {
        val target = if (durationMs > 0) {
            positionMs.coerceIn(0, durationMs)
        } else {
            positionMs.coerceAtLeast(0)
        }
        engine.seek(target)
        val current = _state.value
        if (current is PlaybackState.Playing) {
            _state.value = current.copy(positionMs = target)
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        engine.setSpeed(speed.coerceIn(0.25f, 4.0f))
    }

    override fun release() {
        updateJob?.cancel()
        updateJob = null
        scope.cancel()
        engine.shutdown()
    }

    private fun startUpdates() {
        updateJob?.cancel()
        updateJob = scope.launch {
            while (isActive) {
                engine.error()?.let { message ->
                    _state.value = PlaybackState.Error("Playback failed: $message")
                    break
                }
                if (engine.isFinished()) {
                    _state.value = PlaybackState.Idle
                    break
                }
                val current = _state.value
                if (current is PlaybackState.Playing) {
                    val duration = engine.durationMs().takeIf { it > 0 } ?: durationMs
                    if (duration > 0) {
                        durationMs = duration
                    }
                    val position = engine.positionMs().coerceAtLeast(0)
                    val next = current.copy(
                        positionMs = if (duration > 0) position.coerceAtMost(duration) else position,
                        durationMs = duration,
                    )
                    if (next != current) {
                        _state.value = next
                    }
                }
                delay(POSITION_UPDATE_INTERVAL_MS)
            }
            updateJob = null
        }
    }
}

private fun String.toLocalPath(): String {
    val value = trim()
    if (!value.startsWith("file:")) return value
    return runCatching { File(URI(value)).absolutePath }
        .getOrElse { value.removePrefix("file://") }
}

private fun Throwable.describe(): String = (this as? FfiException.Msg)?.v1 ?: message ?: "unknown"
