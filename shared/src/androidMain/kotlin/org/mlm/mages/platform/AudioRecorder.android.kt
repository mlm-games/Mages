package org.mlm.mages.platform

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.context.GlobalContext
import java.io.File

class AndroidAudioRecorder : AudioRecorder {

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    override val state: StateFlow<RecordingState> = _state.asStateFlow()

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    @Volatile private var startTime: Long = 0
    @Volatile private var isRecording: Boolean = false
    private val amplitudes = mutableListOf<Float>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var amplitudeJob: Job? = null

    private val context
        get() = runCatching { GlobalContext.get().get<android.content.Context>() }.getOrNull()

    override suspend fun startRecording(): Boolean = withContext(Dispatchers.IO) {
        try {
            val ctx = context ?: run {
                _state.value = RecordingState.Error("Context not available")
                return@withContext false
            }
            val permissionCheck = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
            if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
                _state.value = RecordingState.Error("Microphone permission required")
                return@withContext false
            }

            amplitudes.clear()
            val cachePath = MagesPaths.cacheDir()
            outputFile = File(cachePath, "voice_${System.currentTimeMillis()}.ogg")

            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(ctx)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setOutputFormat(MediaRecorder.OutputFormat.OGG)
                    setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                } else {
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                }
                setOutputFile(outputFile!!.absolutePath)
                prepare()
                start()
            }

            startTime = System.currentTimeMillis()
            isRecording = true
            _state.value = RecordingState.Recording(0, 0f)
            amplitudeJob = scope.launch { amplitudeLoop() }
            true
        } catch (e: Exception) {
            _state.value = RecordingState.Error("Recording failed: ${e.message}")
            false
        }
    }

    private suspend fun amplitudeLoop() {
        try {
            while (isRecording) {
                val amp = try {
                    (recorder?.maxAmplitude ?: 0) / 32767f.coerceAtLeast(1f)
                } catch (_: Exception) { 0f }
                amplitudes.add(amp.coerceIn(0f, 1f))
                _state.value = RecordingState.Recording(
                    System.currentTimeMillis() - startTime,
                    amp.coerceIn(0f, 1f)
                )
                delay(100)
            }
        } catch (_: CancellationException) {}
    }

    override suspend fun stopRecording(): RecordingState = withContext(Dispatchers.IO) {
        isRecording = false
        amplitudeJob?.cancel()
        recorder?.apply { 
            try { stop(); release() } catch (_: Exception) {} 
        }
        recorder = null
        val file = outputFile
        if (file == null || !file.exists()) {
            RecordingState.Error("No file created").also { _state.value = it }
        } else {
            val dur = System.currentTimeMillis() - startTime
            RecordingState.Stopped(file.absolutePath, dur, downsample(amplitudes)).also {
                _state.value = it
            }
        }
    }

    override suspend fun cancelRecording() = withContext(Dispatchers.IO) {
        isRecording = false
        amplitudeJob?.cancel()
        recorder?.apply { try { stop(); release() } catch (_: Exception) {} }
        recorder = null
        outputFile?.delete()
        outputFile = null
        _state.value = RecordingState.Idle
    }

    override fun release() {
        isRecording = false
        scope.cancel()
        amplitudeJob?.cancel()
        recorder?.apply { try { stop(); release() } catch (_: Exception) {} }
        recorder = null
    }

    private fun downsample(data: List<Float>, target: Int = 100): List<Float> {
        if (data.isEmpty()) return emptyList()
        if (data.size <= target) return data.toList()
        val step = data.size / target
        return (0 until target).map { i ->
            data.subList(i * step, minOf(i * step + step, data.size)).average().toFloat()
        }
    }
}

actual fun createAudioRecorder(): AudioRecorder = AndroidAudioRecorder()
