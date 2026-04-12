package org.mlm.mages.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class WebAudioRecorder : AudioRecorder {
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    override val state: StateFlow<RecordingState> = _state.asStateFlow()

    override suspend fun startRecording(): Boolean {
        _state.value = RecordingState.Error("Voice recording not supported on web yet")
        return false
    }

    override suspend fun stopRecording(): RecordingState =
        RecordingState.Error("No recording in progress").also { _state.value = it }

    override suspend fun cancelRecording() {
        _state.value = RecordingState.Idle
    }

    override fun release() {}
}

actual fun createAudioRecorder(): AudioRecorder = WebAudioRecorder()
