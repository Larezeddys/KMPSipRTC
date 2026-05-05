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
            println("[AudioCapture] SINK callback #$sinkCallbackCount: data=${data.size}b, rate=$sampleRate, ch=$channels, frames=$frames, isCapturing=$isCapturing")
        }
        if (isCapturing) {
            callback.onAudioData(data, bitsPerSample, sampleRate, channels, frames, System.currentTimeMillis())
        }
    }

    override fun startCapture() {
        println("[AudioCapture] startCapture() called. isCapturing=$isCapturing, audioTrack=$audioTrack")
        if (isCapturing) {
            println("[AudioCapture] ALREADY CAPTURING — skipping")
            return
        }
        isCapturing = true
        sinkCallbackCount = 0
        try {
            audioTrack.addSink(sink)
            println("[AudioCapture] addSink OK. signalLevel=${try { audioTrack.getSignalLevel() } catch (_: Exception) { "ERROR" }}")
        } catch (e: Exception) {
            println("[AudioCapture] addSink FAILED: ${e.message}")
            e.printStackTrace()
        }
    }

    override fun stopCapture() {
        println("[AudioCapture] stopCapture() called. isCapturing=$isCapturing, totalCallbacks=$sinkCallbackCount")
        if (!isCapturing) return
        isCapturing = false
        try {
            audioTrack.removeSink(sink)
        } catch (e: Exception) {
            println("[AudioCapture] removeSink FAILED: ${e.message}")
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
        println("Error creating Desktop capture: ${e.message}")
        null
    }
}
