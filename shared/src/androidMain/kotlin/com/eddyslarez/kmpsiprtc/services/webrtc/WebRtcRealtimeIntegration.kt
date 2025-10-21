package com.eddyslarez.kmpsiprtc.services.webrtc

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.*
import java.nio.ByteBuffer

class WebRtcRealtimeIntegration(
    private val webRtcManager: AndroidWebRtcManager,
    private val translationManager: RealtimeTranslationManager
) {
    private val TAG = "WebRtcRealtimeIntegration"
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var audioRecordingJob: Job? = null

    private val SAMPLE_RATE = 24000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * 2

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun startTranslatedCall(
        sourceLanguage: String = "es",
        targetLanguage: String = "en"
    ): Result<String> {
        try {
            Log.d(TAG, "Starting translated call...")

            webRtcManager.initialize()

            val sessionResult = translationManager.startTranslationSession(
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage
            )

            if (sessionResult.isFailure) {
                return Result.failure(sessionResult.exceptionOrNull()!!)
            }

            val offer = webRtcManager.createOffer()
            Log.d(TAG, "WebRTC offer created")

            startAudioCapture()

            return Result.success(offer)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting translated call: ${e.message}", e)
            return Result.failure(e)
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun answerTranslatedCall(
        offerSdp: String,
        sourceLanguage: String = "es",
        targetLanguage: String = "en"
    ): Result<String> {
        try {
            Log.d(TAG, "Answering translated call...")

            webRtcManager.initialize()

            val sessionResult = translationManager.startTranslationSession(
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage
            )

            if (sessionResult.isFailure) {
                return Result.failure(sessionResult.exceptionOrNull()!!)
            }

            val answer = webRtcManager.createAnswer(offerSdp)
            Log.d(TAG, "WebRTC answer created")

            startAudioCapture()

            return Result.success(answer)
        } catch (e: Exception) {
            Log.e(TAG, "Error answering translated call: ${e.message}", e)
            return Result.failure(e)
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startAudioCapture() {
        if (isRecording) {
            Log.w(TAG, "Audio capture already running")
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                BUFFER_SIZE
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                return
            }

            audioRecord?.startRecording()
            isRecording = true

            audioRecordingJob = coroutineScope.launch {
                captureAudio()
            }

            Log.d(TAG, "Audio capture started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting audio capture: ${e.message}", e)
        }
    }

    private suspend fun captureAudio() = withContext(Dispatchers.IO) {
        val buffer = ByteArray(BUFFER_SIZE)
        var consecutiveSilentFrames = 0
        val silentThreshold = 500
        val maxSilentFrames = 10

        while (isRecording) {
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0

            if (read > 0) {
                val audioLevel = calculateAudioLevel(buffer, read)

                if (audioLevel < silentThreshold) {
                    consecutiveSilentFrames++
                } else {
                    consecutiveSilentFrames = 0
                }

                translationManager.sendAudio(buffer.copyOf(read))

                if (consecutiveSilentFrames >= maxSilentFrames) {
                    translationManager.commitAudioBuffer()
                    consecutiveSilentFrames = 0
                }
            }

            delay(20)
        }
    }

    private fun calculateAudioLevel(buffer: ByteArray, size: Int): Int {
        var sum = 0L
        for (i in 0 until size step 2) {
            val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
            sum += sample * sample
        }
        return Math.sqrt(sum.toDouble() / (size / 2)).toInt()
    }

    fun stopTranslatedCall() {
        Log.d(TAG, "Stopping translated call")

        stopAudioCapture()
        translationManager.disconnect()
        webRtcManager.closePeerConnection()
    }

    private fun stopAudioCapture() {
        isRecording = false
        audioRecordingJob?.cancel()
        audioRecordingJob = null

        audioRecord?.apply {
            try {
                stop()
                release()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping audio: ${e.message}", e)
            }
        }
        audioRecord = null

        Log.d(TAG, "Audio capture stopped")
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun toggleMute(muted: Boolean) {
        webRtcManager.setMuted(muted)
        if (muted) {
            stopAudioCapture()
        } else {
            startAudioCapture()
        }
    }

    fun dispose() {
        stopTranslatedCall()
        coroutineScope.cancel()
    }
}
