package com.eddyslarez.kmpsiprtc.services.audio

import org.webrtc.AudioTrack
import org.webrtc.AudioTrackSink
import java.nio.ByteBuffer

/**
 * Sink personalizado para capturar audio desde un AudioTrack de WebRTC
 */
class AudioCaptureSink(
    private val onAudioData: (
        data: ByteArray,
        bitsPerSample: Int,
        sampleRate: Int,
        channels: Int,
        frames: Int,
        timestampMs: Long
    ) -> Unit
) : AudioTrackSink {

    private val TAG = "AudioCaptureSink"

    override fun onData(
        audioData: ByteBuffer?,
        bitsPerSample: Int,
        sampleRate: Int,
        numberOfChannels: Int,
        numberOfFrames: Int,
        absoluteCaptureTimestampMs: Long
    ) {
        try {
            if (audioData == null || audioData.remaining() == 0) return

            val bytes = ByteArray(audioData.remaining())
            audioData.get(bytes)

            onAudioData(
                bytes,
                bitsPerSample,
                sampleRate,
                numberOfChannels,
                numberOfFrames,
                absoluteCaptureTimestampMs
            )

            // Restablecer posición si es posible
            try {
                audioData.position(0)
            } catch (_: Exception) {
            }

        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error capturing audio: ${e.message}", e)
        }
    }
}

/**
 * Implementación Android del capturador de audio
 */
class AndroidAudioTrackCapture(
    private val audioTrack: AudioTrack,
    private val callback: AudioCaptureCallback
) : AudioTrackCapture {

    private val sink = AudioCaptureSink { data, bitsPerSample, sampleRate, channels, frames, timestampMs ->
        callback.onAudioData(data, bitsPerSample, sampleRate, channels, frames, timestampMs)
    }

    @Volatile
    private var isCapturing = false

    override fun startCapture() {
        if (isCapturing) return
        audioTrack.addSink(sink)
        isCapturing = true
    }

    override fun stopCapture() {
        if (!isCapturing) return
        audioTrack.removeSink(sink)
        isCapturing = false
    }

    override fun isCapturing(): Boolean = isCapturing
}

/**
 * Factory Android
 */
actual fun createRemoteAudioCapture(
    remoteTrack: Any,
    callback: AudioCaptureCallback
): AudioTrackCapture? {
    return try {
        if (remoteTrack is AudioTrack) {
            AndroidAudioTrackCapture(remoteTrack, callback)
        } else {
            null
        }
    } catch (e: Exception) {
        android.util.Log.e("AudioCapture", "Error creating Android capture: ${e.message}")
        null
    }
}