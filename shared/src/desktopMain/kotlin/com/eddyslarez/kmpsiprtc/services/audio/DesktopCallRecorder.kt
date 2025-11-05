package com.eddyslarez.kmpsiprtc.services.audio

/**
 * Grabador de llamadas para Desktop usando Java Sound API
 * Esta es una alternativa práctica al AudioTrack de WebRTC
 */
import javax.sound.sampled.*
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*

class DesktopCallRecorder {
    private val TAG = "DesktopCallRecorder"

    // Buffers de audio
    private val localAudioBuffer = mutableListOf<ByteArray>()
    private val remoteAudioBuffer = mutableListOf<ByteArray>()

    @Volatile
    private var isRecording = false

    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Configuración de audio
    private val sampleRate = 48000f
    private val channels = 1
    private val sampleSizeInBits = 16

    /**
     * Inicia grabación usando el micrófono del sistema
     */
    fun startRecording(callId: String) {
        if (isRecording) return

        isRecording = true
        localAudioBuffer.clear()
        remoteAudioBuffer.clear()

        recordingJob = scope.launch {
            captureSystemAudio()
        }

        println("[$TAG] 🎙️ Recording started for: $callId")
    }

    /**
     * Captura audio del sistema usando Java Sound API
     */
    private suspend fun captureSystemAudio() {
        try {
            val format = AudioFormat(
                sampleRate,
                sampleSizeInBits,
                channels,
                true, // signed
                false // little endian
            )

            val info = DataLine.Info(TargetDataLine::class.java, format)

            if (!AudioSystem.isLineSupported(info)) {
                println("[$TAG] ❌ Line not supported")
                return
            }

            val line = AudioSystem.getLine(info) as TargetDataLine
            line.open(format)
            line.start()

            println("[$TAG] ✅ Audio capture started")

            val buffer = ByteArray(4096)

            while (isRecording) {
                val bytesRead = line.read(buffer, 0, buffer.size)

                if (bytesRead > 0) {
                    localAudioBuffer.add(buffer.copyOf(bytesRead))
                }
            }

            line.stop()
            line.close()

        } catch (e: Exception) {
            println("[$TAG] ❌ Error capturing audio: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Detiene la grabación
     */
    fun stopRecording(): RecordingResult {
        isRecording = false
        recordingJob?.cancel()

        println("[$TAG] 🛑 Recording stopped")
        println("[$TAG] Local buffer: ${localAudioBuffer.size} chunks")

        // Aquí guardarías los archivos WAV como en Android
        return RecordingResult(null, null, null)
    }

    fun dispose() {
        isRecording = false
        recordingJob?.cancel()
        scope.cancel()
    }
}

// Data class compartida
data class RecordingResult(
    val localPath: String?,
    val remotePath: String?,
    val mixedPath: String?
)