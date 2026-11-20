package org.mlm.mages.platform

import kotlinx.coroutines.flow.StateFlow

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

expect fun createAudioPlayer(): AudioPlayer
