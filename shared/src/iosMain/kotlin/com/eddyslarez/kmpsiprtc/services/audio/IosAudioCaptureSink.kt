package com.eddyslarez.kmpsiprtc.services.audio

import WebRTC.RTCAudioTrack
import platform.AVFoundation.*
import platform.Foundation.*
import kotlinx.cinterop.*
import platform.CoreAudio.*

/**
 * Captura audio desde RTCAudioTrack en iOS
 * Equivalente a AudioCaptureSink de Android
 */
@OptIn(ExperimentalForeignApi::class)
class IosAudioCaptureSink(
    private val onAudioData: (
        data: ByteArray,
        bitsPerSample: Int,
        sampleRate: Int,
        channels: Int,
        frames: Int,
        timestampMs: Long
    ) -> Unit
) {
    private val TAG = "IosAudioCaptureSink"

    /**
     * Procesa datos de audio capturados
     * NOTA: iOS WebRTC no expone directamente el audio raw como Android
     */
    fun processAudioData(
        audioData: NSData?,
        bitsPerSample: Int,
        sampleRate: Int,
        numberOfChannels: Int,
        numberOfFrames: Int,
        timestampMs: Long
    ) {
        if (audioData == null || audioData.length == 0UL) return

        try {
            // Convertir NSData a ByteArray
            val bytes = ByteArray(audioData.length.toInt())
            memScoped {
                val ptr = bytes.refTo(0).getPointer(this)
                audioData.getBytes(ptr, audioData.length)
            }

            onAudioData(
                bytes,
                bitsPerSample,
                sampleRate,
                numberOfChannels,
                numberOfFrames,
                timestampMs
            )

        } catch (e: Exception) {
            println("[$TAG] Error capturing audio: ${e.message}")
        }
    }
}

/**
 * Grabador de audio para iOS
 * LIMITACIÓN: WebRTC en iOS no expone AudioTrack.addSink()
 * Se requiere usar AVAudioEngine o Audio Units
 */
@OptIn(ExperimentalForeignApi::class)
class IosAudioTrackRecorder(
    private val audioTrack: RTCAudioTrack,
    private val onAudioData: (
        data: ByteArray,
        bitsPerSample: Int,
        sampleRate: Int,
        channels: Int,
        frames: Int,
        timestampMs: Long
    ) -> Unit
) {
    private val TAG = "IosAudioTrackRecorder"
    private var isCapturing = false

    fun startCapture() {
        if (isCapturing) return
        isCapturing = true

        println("[$TAG] ⚠️ iOS WebRTC no soporta audio sink directo")
        println("[$TAG] Usar AVAudioEngine como alternativa")
    }

    fun stopCapture() {
        isCapturing = false
    }
}
