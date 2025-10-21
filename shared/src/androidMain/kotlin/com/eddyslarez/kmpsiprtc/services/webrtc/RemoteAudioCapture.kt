package com.eddyslarez.kmpsiprtc.services.webrtc
import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captura audio remoto del flujo WebRTC para procesarlo
 * Este componente intercepta el audio que viene del otro usuario
 */
class RemoteAudioCapture(
    private val onAudioData: (ByteArray) -> Unit
) {
    private val TAG = "RemoteAudioCapture"

    private var audioRecord: AudioRecord? = null
    private var isCapturing = AtomicBoolean(false)
    private var captureJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Configuración de audio
    private val SAMPLE_RATE = 24000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val BUFFER_SIZE = AudioRecord.getMinBufferSize(
        SAMPLE_RATE,
        CHANNEL_CONFIG,
        AUDIO_FORMAT
    ) * 2

    /**
     * Iniciar captura de audio remoto
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startCapture() {
        if (isCapturing.get()) {
            Log.w(TAG, "Already capturing remote audio")
            return
        }

        try {
            // Usar VOICE_COMMUNICATION para capturar el audio del peer remoto
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                BUFFER_SIZE
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Failed to initialize AudioRecord for remote capture")
                return
            }

            audioRecord?.startRecording()
            isCapturing.set(true)

            captureJob = coroutineScope.launch {
                captureLoop()
            }

            Log.d(TAG, "Remote audio capture started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting remote audio capture: ${e.message}", e)
            isCapturing.set(false)
        }
    }

    /**
     * Loop principal de captura
     */
    private suspend fun captureLoop() = withContext(Dispatchers.IO) {
        val buffer = ByteArray(BUFFER_SIZE)
        var consecutiveEmptyReads = 0
        val maxEmptyReads = 50 // ~1 segundo de silencio

        Log.d(TAG, "Remote capture loop started")

        while (isCapturing.get()) {
            try {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0

                when {
                    bytesRead > 0 -> {
                        consecutiveEmptyReads = 0

                        // Calcular nivel de audio
                        val audioLevel = calculateAudioLevel(buffer, bytesRead)

                        // Solo enviar si hay contenido real (no silencio)
                        if (audioLevel > 100) {
                            val audioChunk = buffer.copyOf(bytesRead)

                            // Callback con los datos
                            onAudioData(audioChunk)

                            Log.v(TAG, "Captured ${bytesRead} bytes, level: $audioLevel")
                        }
                    }
                    bytesRead == AudioRecord.ERROR_INVALID_OPERATION -> {
                        Log.e(TAG, "Invalid operation during read")
                        delay(100)
                        consecutiveEmptyReads++
                    }
                    bytesRead == AudioRecord.ERROR_BAD_VALUE -> {
                        Log.e(TAG, "Bad value during read")
                        delay(100)
                        consecutiveEmptyReads++
                    }
                    else -> {
                        consecutiveEmptyReads++
                    }
                }

                // Si hay demasiados reads vacíos consecutivos, pausar
                if (consecutiveEmptyReads > maxEmptyReads) {
                    Log.w(TAG, "Too many empty reads, pausing...")
                    delay(500)
                    consecutiveEmptyReads = 0
                }

                // Pequeño delay para no saturar
                delay(10)

            } catch (e: Exception) {
                Log.e(TAG, "Error in capture loop: ${e.message}", e)
                if (e is CancellationException) throw e
                delay(100)
            }
        }

        Log.d(TAG, "Remote capture loop ended")
    }

    /**
     * Calcular nivel de audio (RMS)
     */
    private fun calculateAudioLevel(buffer: ByteArray, size: Int): Int {
        var sum = 0L
        val sampleCount = size / 2

        for (i in 0 until size step 2) {
            if (i + 1 < size) {
                // Convertir bytes a muestra PCM de 16 bits
                val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
                sum += sample * sample
            }
        }

        return if (sampleCount > 0) {
            kotlin.math.sqrt(sum.toDouble() / sampleCount).toInt()
        } else {
            0
        }
    }

    /**
     * Detener captura
     */
    fun stopCapture() {
        Log.d(TAG, "Stopping remote audio capture")

        isCapturing.set(false)

        captureJob?.cancel()
        captureJob = null

        audioRecord?.apply {
            try {
                if (state == AudioRecord.STATE_INITIALIZED) {
                    stop()
                }
                release()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping AudioRecord: ${e.message}", e)
            }
        }
        audioRecord = null

        Log.d(TAG, "Remote audio capture stopped")
    }

    /**
     * Verificar si está capturando
     */
    fun isCapturing(): Boolean = isCapturing.get()

    /**
     * Dispose
     */
    fun dispose() {
        stopCapture()
        coroutineScope.cancel()
    }
}
//
///**
// * Captura audio remoto de llamadas WebRTC usando AudioRecord del sistema Android
// */
//class RemoteAudioCapture(
//    private val onAudioData: (ByteArray) -> Unit
//) {
//    private val TAG = "RemoteAudioCapture"
//    private var audioRecord: AudioRecord? = null
//    private var isCapturing = false
//    private var captureJob: Job? = null
//    private val coroutineScope = CoroutineScope(Dispatchers.IO)
//
//    companion object {
//        private const val SAMPLE_RATE = 24000
//        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
//        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
//    }
//
//    private val bufferSize = AudioRecord.getMinBufferSize(
//        SAMPLE_RATE,
//        CHANNEL_CONFIG,
//        AUDIO_FORMAT
//    ) * 2
//
//    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
//    fun startCapture() {
//        if (isCapturing) {
//            Log.w(TAG, "Already capturing remote audio")
//            return
//        }
//
//        try {
//            // Intenta VOICE_DOWNLINK primero (audio remoto puro)
//            audioRecord = try {
//                AudioRecord(
//                    MediaRecorder.AudioSource.VOICE_DOWNLINK,
//                    SAMPLE_RATE,
//                    CHANNEL_CONFIG,
//                    AUDIO_FORMAT,
//                    bufferSize
//                )
//            } catch (e: Exception) {
//                Log.w(TAG, "VOICE_DOWNLINK not available, using VOICE_CALL: ${e.message}")
//                // Fallback: VOICE_CALL (captura ambos lados)
//                AudioRecord(
//                    MediaRecorder.AudioSource.VOICE_CALL,
//                    SAMPLE_RATE,
//                    CHANNEL_CONFIG,
//                    AUDIO_FORMAT,
//                    bufferSize
//                )
//            }
//
//            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
//                Log.e(TAG, "AudioRecord initialization failed")
//                audioRecord?.release()
//                audioRecord = null
//                return
//            }
//
//            audioRecord?.startRecording()
//            isCapturing = true
//
//            captureJob = coroutineScope.launch {
//                captureAudio()
//            }
//
//            Log.d(TAG, "Remote audio capture started successfully")
//        } catch (e: Exception) {
//            Log.e(TAG, "Error starting remote audio capture: ${e.message}", e)
//            audioRecord?.release()
//            audioRecord = null
//        }
//    }
//
//    private suspend fun captureAudio() {
//        val buffer = ByteArray(bufferSize)
//
//        while (isCapturing && coroutineScope.isActive) {
//            try {
//                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
//
//                if (read > 0) {
//                    // Enviar audio capturado
//                    onAudioData(buffer.copyOf(read))
//                }
//
//                delay(20) // ~50fps de captura
//            } catch (e: Exception) {
//                Log.e(TAG, "Error reading audio: ${e.message}")
//                break
//            }
//        }
//    }
//
//    fun stopCapture() {
//        if (!isCapturing) return
//
//        isCapturing = false
//        captureJob?.cancel()
//        captureJob = null
//
//        audioRecord?.apply {
//            try {
//                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
//                    stop()
//                }
//                release()
//            } catch (e: Exception) {
//                Log.e(TAG, "Error stopping audio record: ${e.message}")
//            }
//        }
//        audioRecord = null
//
//        Log.d(TAG, "Remote audio capture stopped")
//    }
//
//    fun dispose() {
//        stopCapture()
//        coroutineScope.cancel()
//    }
//}
