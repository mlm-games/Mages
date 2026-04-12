package org.mlm.mages.platform

import kotlinx.coroutines.flow.StateFlow

sealed class RecordingState {
    data object Idle : RecordingState()
    data class Recording(val durationMs: Long, val amplitude: Float) : RecordingState()
    data class Stopped(
        val filePath: String,
        val durationMs: Long,
        val waveformData: List<Float>
    ) : RecordingState()
    data class Error(val message: String) : RecordingState()
}

interface AudioRecorder {
    val state: StateFlow<RecordingState>
    val maxDurationMs: Long get() = 5 * 60 * 1000L
    suspend fun startRecording(): Boolean
    suspend fun stopRecording(): RecordingState
    suspend fun cancelRecording()
    fun release()
}

expect fun createAudioRecorder(): AudioRecorder
