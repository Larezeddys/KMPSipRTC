package com.eddyslarez.kmpsiprtc.services.audio

import dev.onvoid.webrtc.media.audio.AudioTrack
import java.nio.ByteBuffer

/**
 * Captura audio desde un AudioTrack en Desktop
 * Equivalente a AudioCaptureSink de Android
 */
class DesktopAudioCaptureSink(
    private val onAudioData: (
        data: ByteArray,
        bitsPerSample: Int,
        sampleRate: Int,
        channels: Int,
        frames: Int,
        timestampMs: Long
    ) -> Unit
) {
    private val TAG = "DesktopAudioCaptureSink"

    /**
     * Procesa los datos de audio capturados
     */
    fun processAudioData(
        audioData: ByteBuffer?,
        bitsPerSample: Int,
        sampleRate: Int,
        numberOfChannels: Int,
        numberOfFrames: Int,
        timestampMs: Long
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
                timestampMs
            )

            // Restablecer posición
            try {
                audioData.position(0)
            } catch (_: Exception) {
            }

        } catch (e: Exception) {
            println("[$TAG] Error capturing audio: ${e.message}")
            e.printStackTrace()
        }
    }
}

/**
 * Grabador de audio para Desktop usando dev.onvoid.webrtc
 */
class DesktopAudioTrackRecorder(
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
    private val TAG = "DesktopAudioTrackRecorder"
    private var isCapturing = false
    private val sink = DesktopAudioCaptureSink(onAudioData)

    // Configuración de audio típica
    private val sampleRate = 48000
    private val channels = 1
    private val bitsPerSample = 16

    /**
     * Inicia la captura de audio
     * NOTA: dev.onvoid.webrtc no tiene addSink() como Android WebRTC
     * Necesitamos una aproximación diferente
     */
    fun startCapture() {
        if (isCapturing) return

        isCapturing = true
        println("[$TAG] ⚠️ Desktop WebRTC no soporta audio sink directo")
        println("[$TAG] Se requiere implementación nativa o alternativa")

        // Posibles alternativas:
        // 1. Usar AudioDeviceModule para captura directa
        // 2. Implementar audio processing con JNI
        // 3. Usar biblioteca Java Sound API
    }

    /**
     * Detiene la captura de audio
     */
    fun stopCapture() {
        isCapturing = false
        println("[$TAG] Audio capture stopped")
    }
}