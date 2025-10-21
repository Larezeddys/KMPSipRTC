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

/**
 * Captura audio remoto de llamadas WebRTC usando AudioRecord del sistema Android
 */
class RemoteAudioCapture(
    private val onAudioData: (ByteArray) -> Unit
) {
    private val TAG = "RemoteAudioCapture"
    private var audioRecord: AudioRecord? = null
    private var isCapturing = false
    private var captureJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    companion object {
        private const val SAMPLE_RATE = 24000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private val bufferSize = AudioRecord.getMinBufferSize(
        SAMPLE_RATE,
        CHANNEL_CONFIG,
        AUDIO_FORMAT
    ) * 2

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startCapture() {
        if (isCapturing) {
            Log.w(TAG, "Already capturing remote audio")
            return
        }

        try {
            // Intenta VOICE_DOWNLINK primero (audio remoto puro)
            audioRecord = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_DOWNLINK,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
            } catch (e: Exception) {
                Log.w(TAG, "VOICE_DOWNLINK not available, using VOICE_CALL: ${e.message}")
                // Fallback: VOICE_CALL (captura ambos lados)
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_CALL,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                audioRecord?.release()
                audioRecord = null
                return
            }

            audioRecord?.startRecording()
            isCapturing = true

            captureJob = coroutineScope.launch {
                captureAudio()
            }

            Log.d(TAG, "Remote audio capture started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting remote audio capture: ${e.message}", e)
            audioRecord?.release()
            audioRecord = null
        }
    }

    private suspend fun captureAudio() {
        val buffer = ByteArray(bufferSize)

        while (isCapturing && coroutineScope.isActive) {
            try {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0

                if (read > 0) {
                    // Enviar audio capturado
                    onAudioData(buffer.copyOf(read))
                }

                delay(20) // ~50fps de captura
            } catch (e: Exception) {
                Log.e(TAG, "Error reading audio: ${e.message}")
                break
            }
        }
    }

    fun stopCapture() {
        if (!isCapturing) return

        isCapturing = false
        captureJob?.cancel()
        captureJob = null

        audioRecord?.apply {
            try {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    stop()
                }
                release()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping audio record: ${e.message}")
            }
        }
        audioRecord = null

        Log.d(TAG, "Remote audio capture stopped")
    }

    fun dispose() {
        stopCapture()
        coroutineScope.cancel()
    }
}
