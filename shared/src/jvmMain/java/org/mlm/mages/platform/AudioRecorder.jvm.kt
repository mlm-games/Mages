package org.mlm.mages.platform

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.*
import kotlin.math.sqrt

class JvmAudioRecorder : AudioRecorder {

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    override val state: StateFlow<RecordingState> = _state.asStateFlow()

    private var targetDataLine: TargetDataLine? = null
    private var recordingJob: Job? = null
    private var amplitudeJob: Job? = null
    private var outputFile: File? = null
    @Volatile private var startTime: Long = 0
    @Volatile private var isRecording: Boolean = false
    private val amplitudeRef = AtomicReference(0f)
    private val amplitudes = Collections.synchronizedList(mutableListOf<Float>())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val SAMPLE_RATE = 44100f
        private const val BITS = 16
        private const val CHANNELS = 1
        private val FORMAT = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED, SAMPLE_RATE, BITS, CHANNELS, 2, SAMPLE_RATE, false
        )
    }

    override suspend fun startRecording(): Boolean = withContext(Dispatchers.IO) {
        try {
            synchronized(amplitudes) { amplitudes.clear() }
            amplitudeRef.set(0f)
            val lineInfo = DataLine.Info(TargetDataLine::class.java, FORMAT)
            if (!AudioSystem.isLineSupported(lineInfo)) {
                _state.value = RecordingState.Error("No audio input available")
                return@withContext false
            }
            outputFile = File(MagesPaths.cacheDir(), "voice_${System.currentTimeMillis()}.wav")
            targetDataLine = (AudioSystem.getLine(lineInfo) as TargetDataLine).apply {
                open(FORMAT)
                start()
            }
            startTime = System.currentTimeMillis()
            isRecording = true
            _state.value = RecordingState.Recording(0, 0f)
            recordingJob = scope.launch { recordLoop() }
            amplitudeJob = scope.launch { amplitudeLoop() }
            true
        } catch (e: Exception) {
            _state.value = RecordingState.Error("Recording failed: ${e.message}")
            false
        }
    }

    private suspend fun recordLoop() = withContext(Dispatchers.IO) {
        val line = targetDataLine ?: return@withContext
        val buf = ByteArray(1024)
        val out = ByteArrayOutputStream()
        try {
            while (isRecording && line.isOpen) {
                val n = line.read(buf, 0, buf.size)
                if (n > 0) {
                    out.write(buf, 0, n)
                    val amp = rms(buf, n)
                    amplitudeRef.set(amp)
                    amplitudes.add(amp)
                }
                if (System.currentTimeMillis() - startTime >= maxDurationMs) break
            }
            outputFile?.let { writeWav(it, out.toByteArray()) }
        } catch (_: Exception) {}
    }

    private suspend fun amplitudeLoop() {
        try {
            while (isRecording) {
                val dur = System.currentTimeMillis() - startTime
                _state.value = RecordingState.Recording(dur, amplitudeRef.get())
                delay(80)
            }
        } catch (_: CancellationException) {}
    }

    override suspend fun stopRecording(): RecordingState = withContext(Dispatchers.IO) {
        isRecording = false
        targetDataLine?.apply { stop(); close() }
        recordingJob?.cancelAndJoin()
        amplitudeJob?.cancel()
        targetDataLine = null
        val file = outputFile
        if (file == null || !file.exists()) {
            RecordingState.Error("No file created").also { _state.value = it }
        } else {
            val dur = System.currentTimeMillis() - startTime
            val wave = synchronized(amplitudes) { downsample(amplitudes.toList()) }
            RecordingState.Stopped(file.absolutePath, dur, wave).also { _state.value = it }
        }
    }

    override suspend fun cancelRecording() = withContext(Dispatchers.IO) {
        isRecording = false
        targetDataLine?.apply { stop(); close() }
        recordingJob?.cancel()
        amplitudeJob?.cancel()
        targetDataLine = null
        outputFile?.delete()
        outputFile = null
        _state.value = RecordingState.Idle
    }

    override fun release() {
        isRecording = false
        scope.cancel()
        targetDataLine?.close()
        targetDataLine = null
    }

    private fun rms(buf: ByteArray, len: Int): Float {
        var sum = 0.0
        var count = 0
        for (i in 0 until len - 1 step 2) {
            val s = (buf[i].toInt() and 0xFF) or ((buf[i + 1].toInt() and 0xFF) shl 8)
            sum += s.toDouble() * s
            count++
        }
        return if (count == 0) 0f else (sqrt(sum / count) / 32768.0).toFloat().coerceIn(0f, 1f)
    }

    private fun downsample(data: List<Float>, target: Int = 100): List<Float> {
        if (data.isEmpty()) return emptyList()
        if (data.size <= target) return data.toList()
        val step = data.size / target
        return (0 until target).map { i ->
            data.subList(i * step, minOf(i * step + step, data.size)).average().toFloat()
        }
    }

    private fun writeWav(file: File, audio: ByteArray) {
        file.parentFile?.mkdirs()
        val byteRate = (SAMPLE_RATE * CHANNELS * BITS / 8).toInt()
        RandomAccessFile(file, "rw").use { f ->
            f.writeBytes("RIFF")
            f.writeInt(Integer.reverseBytes(audio.size + 36))
            f.writeBytes("WAVE")
            f.writeBytes("fmt ")
            f.writeInt(Integer.reverseBytes(16))
            f.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
            f.writeShort(java.lang.Short.reverseBytes(CHANNELS.toShort()).toInt())
            f.writeInt(Integer.reverseBytes(SAMPLE_RATE.toInt()))
            f.writeInt(Integer.reverseBytes(byteRate))
            f.writeShort(java.lang.Short.reverseBytes((CHANNELS * BITS / 8).toShort()).toInt())
            f.writeShort(java.lang.Short.reverseBytes(BITS.toShort()).toInt())
            f.writeBytes("data")
            f.writeInt(Integer.reverseBytes(audio.size))
            f.write(audio)
        }
    }
}

actual fun createAudioRecorder(): AudioRecorder = JvmAudioRecorder()
