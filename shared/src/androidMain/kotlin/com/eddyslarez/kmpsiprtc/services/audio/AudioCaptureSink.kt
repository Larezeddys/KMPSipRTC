package com.eddyslarez.kmpsiprtc.services.audio

import com.eddyslarez.kmpsiprtc.platform.log
import org.webrtc.AudioTrack
import java.nio.ByteBuffer

import org.webrtc.AudioTrackSink
//
//
//class AudioCaptureSink(
//    private val onAudioData: (data: ByteArray, bitsPerSample: Int, sampleRate: Int, channels: Int, frames: Int, timestampMs: Long) -> Unit
//) : AudioTrackSink {
//
//    private val TAG = "AudioCaptureSink"
//
//    override fun onData(
//        audioData: ByteBuffer?,
//        bitsPerSample: Int,
//        sampleRate: Int,
//        numberOfChannels: Int,
//        numberOfFrames: Int,
//        absoluteCaptureTimestampMs: Long
//    ) {
//        try {
//            if (audioData == null || audioData.remaining() == 0) {
//                // Nada que procesar
//                return
//            }
//
//            // Copiamos los bytes disponibles
//            val bytes = ByteArray(audioData.remaining())
//            audioData.get(bytes)
//
//            // Llamamos al callback con metadatos (si no los necesitas, ignóralos en tu lambda)
//            onAudioData(bytes, bitsPerSample, sampleRate, numberOfChannels, numberOfFrames, absoluteCaptureTimestampMs)
//
//            // Si WebRTC espera la posición original, volverla a 0
//            try {
//                audioData.position(0)
//            } catch (_: Exception) {
//                // Algunos ByteBuffer no permiten position() si vienen como read-only; ignoramos silenciosamente
//            }
//
//        } catch (e: Exception) {
//            android.util.Log.e(TAG, "Error capturing audio: ${e.message}", e)
//        }
//    }
//}
//
//class AudioTrackRecorder(
//    private val audioTrack: AudioTrack,
//    private val onAudioData: (data: ByteArray, bitsPerSample: Int, sampleRate: Int, channels: Int, frames: Int, timestampMs: Long) -> Unit
//) {
//    private val sink = AudioCaptureSink(onAudioData)
//
//    fun startCapture() {
//        audioTrack.addSink(sink)
//    }
//
//    fun stopCapture() {
//        audioTrack.removeSink(sink)
//    }
//}

/**
 * Sink personalizado para capturar audio desde un AudioTrack de WebRTC.
 * Permite interceptar el audio sin afectar su reproducción normal.
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
 * Clase que facilita la vinculación de un AudioTrack con el AudioCaptureSink.
 */
class AudioTrackRecorder(
    private val audioTrack: AudioTrack,
    private val onAudioData: (
        data: ByteArray,
        bitsPerSample: Int,
        sampleRate: Int,
        channels: Int,
        frames: Int,
        timestampMs: Long
    ) -> Unit
) {
    private val sink = AudioCaptureSink(onAudioData)

    fun startCapture() {
        audioTrack.addSink(sink)
    }

    fun stopCapture() {
        audioTrack.removeSink(sink)
    }
}
