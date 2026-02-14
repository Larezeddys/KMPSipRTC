package com.eddyslarez.kmpsiprtc.services.audio

import com.eddyslarez.kmpsiprtc.platform.log
import dev.onvoid.webrtc.media.audio.AudioTrack
import dev.onvoid.webrtc.media.audio.AudioTrackSink

/**
 * Implementación Desktop del capturador de audio remoto usando AudioTrackSink.
 * Captura el audio remoto directamente del AudioTrack de WebRTC (igual que Android).
 */
class DesktopAudioTrackCapture(
    private val audioTrack: AudioTrack,
    private val callback: AudioCaptureCallback
) : AudioTrackCapture {

    private val TAG = "DesktopAudioCapture"

    @Volatile
    private var isCapturing = false
    @Volatile
    private var firstCallbackLogged = false

    private val sink = AudioTrackSink { data, bitsPerSample, sampleRate, channels, frames ->
        if (isCapturing) {
            if (!firstCallbackLogged) {
                firstCallbackLogged = true
                log.d(TAG) { "RAW WebRTC AudioTrackSink: bitsPerSample=$bitsPerSample, sampleRate=$sampleRate, channels=$channels, frames=$frames, data.size=${data.size}" }
            }
            callback.onAudioData(data, bitsPerSample, sampleRate, channels, frames, System.currentTimeMillis())
        }
    }

    override fun startCapture() {
        if (isCapturing) return
        isCapturing = true
        firstCallbackLogged = false
        audioTrack.addSink(sink)
        log.d(TAG) { "✅ Desktop audio capture started (AudioTrackSink)" }
    }

    override fun stopCapture() {
        if (!isCapturing) return
        isCapturing = false
        audioTrack.removeSink(sink)
        log.d(TAG) { "✅ Desktop audio capture stopped" }
    }

    override fun isCapturing(): Boolean = isCapturing
}

/**
 * Factory Desktop
 */
actual fun createRemoteAudioCapture(
    remoteTrack: Any,
    callback: AudioCaptureCallback
): AudioTrackCapture? {
    return try {
        if (remoteTrack is AudioTrack) {
            DesktopAudioTrackCapture(remoteTrack, callback)
        } else {
            null
        }
    } catch (e: Exception) {
        println("Error creating Desktop capture: ${e.message}")
        null
    }
}
