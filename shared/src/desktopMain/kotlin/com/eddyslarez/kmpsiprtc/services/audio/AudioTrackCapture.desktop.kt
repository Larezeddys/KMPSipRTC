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
    private var sinkCallbackCount = 0

    private val sink = AudioTrackSink { data, bitsPerSample, sampleRate, channels, frames ->
        sinkCallbackCount++
        if (sinkCallbackCount == 1 || sinkCallbackCount % 500 == 0) {
            log.d(tag = "AudioCapture") { "SINK callback #$sinkCallbackCount: data=${data.size}b, rate=$sampleRate, ch=$channels, frames=$frames, isCapturing=$isCapturing" }
        }
        if (isCapturing) {
            callback.onAudioData(data, bitsPerSample, sampleRate, channels, frames, System.currentTimeMillis())
        }
    }

    override fun startCapture() {
        log.d(tag = "AudioCapture") { "startCapture() called. isCapturing=$isCapturing, audioTrack=$audioTrack" }
        if (isCapturing) {
            log.d(tag = "AudioCapture") { "ALREADY CAPTURING — skipping" }
            return
        }
        isCapturing = true
        sinkCallbackCount = 0
        try {
            audioTrack.addSink(sink)
            log.d(tag = "AudioCapture") { "addSink OK" }
        } catch (e: Exception) {
            log.w(tag = "AudioCapture") { "addSink FAILED: ${e.message}" }
            e.printStackTrace()
        }
    }

    override fun stopCapture() {
        log.d(tag = "AudioCapture") { "stopCapture() called. isCapturing=$isCapturing, totalCallbacks=$sinkCallbackCount" }
        if (!isCapturing) return
        isCapturing = false
        try {
            audioTrack.removeSink(sink)
        } catch (e: Exception) {
            log.w(tag = "AudioCapture") { "removeSink FAILED: ${e.message}" }
        }
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
        log.w(tag = "AudioCapture") { "Error creating Desktop capture: ${e.message}" }
        null
    }
}
