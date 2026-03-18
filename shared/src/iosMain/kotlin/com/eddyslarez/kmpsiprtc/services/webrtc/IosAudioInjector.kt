package com.eddyslarez.kmpsiprtc.services.webrtc

import MCNAudioBridge.MCNAudioInjectionBridge
import platform.Foundation.NSData
import platform.Foundation.create
import platform.darwin.NSObject
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned

/**
 * Inyector de audio para traduccion L→R en iOS.
 *
 * Delegacion completa al bridge nativo MCNAudioInjectionBridge (Objective-C)
 * que implementa RTCAudioDevice para inyectar audio PCM en la pipeline WebRTC.
 *
 * El bridge crea una RTCPeerConnectionFactory separada con un dispositivo de audio
 * personalizado, genera un RTCAudioTrack, y lo reemplaza en el RTCRtpSender.
 *
 * Flujo:
 *   Kotlin pushAudio() →
 *   Bridge.pushAudioData() (cola de frames) →
 *   MCNInjectionAudioDevice (RTCAudioDevice nativo) →
 *   deliverRecordedData (ADM nativo WebRTC) →
 *   RTCAudioTrack → RTCRtpSender → Peer remoto
 */
@OptIn(ExperimentalForeignApi::class)
class IosAudioInjector {

    companion object {
        private const val TAG = "IosAudioInjector"
        private const val TARGET_SAMPLE_RATE = 48000
    }

    private var bridge: MCNAudioInjectionBridge? = null

    /**
     * Activa la inyeccion de audio reemplazando el track del mic en el sender RTP.
     * @param nativeSender El RTCRtpSender nativo (sender.ios desde webrtc-kmp)
     * @return true si la activacion fue exitosa
     */
    fun activate(nativeSender: Any): Boolean {
        try {
            val b = MCNAudioInjectionBridge()
            bridge = b

            val success = b.activateWithSender(nativeSender)
            if (!success) {
                println("[$TAG] Bridge activation failed")
                bridge = null
                return false
            }

            b.start()
            println("[$TAG] Injection activated via native bridge")
            return true
        } catch (e: Exception) {
            println("[$TAG] Error activating: ${e.message}")
            bridge = null
            return false
        }
    }

    /**
     * Desactiva la inyeccion y restaura el track original del mic.
     * @param nativeSender El RTCRtpSender nativo
     */
    fun deactivate(nativeSender: Any) {
        try {
            bridge?.deactivateWithSender(nativeSender)
            bridge = null
            println("[$TAG] Injection deactivated")
        } catch (e: Exception) {
            println("[$TAG] Error deactivating: ${e.message}")
            bridge = null
        }
    }

    /**
     * Inyecta audio PCM traducido. Los datos se resamplean a 48kHz si es necesario
     * y se pasan al bridge nativo.
     *
     * @param pcmData Audio PCM 16-bit LE mono
     * @param sampleRate Frecuencia de muestreo (ej: 24000 de OpenAI)
     * @param channels Numero de canales (1 = mono)
     * @param bitsPerSample Bits por muestra (16)
     */
    fun pushAudio(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        val b = bridge ?: return
        if (pcmData.isEmpty()) return

        try {
            // Resamplear a 48kHz si es necesario (OpenAI envia 24kHz)
            val resampled = if (sampleRate != TARGET_SAMPLE_RATE) {
                resamplePcm16(pcmData, sampleRate, TARGET_SAMPLE_RATE)
            } else {
                pcmData
            }

            // Convertir ByteArray a NSData y pasar al bridge
            val nsData = resampled.usePinned { pinned ->
                NSData.create(bytes = pinned.addressOf(0), length = resampled.size.toULong())
            }

            b.pushAudioData(nsData, TARGET_SAMPLE_RATE, 1)
        } catch (e: Exception) {
            println("[$TAG] Error en pushAudio: ${e.message}")
        }
    }

    fun dispose() {
        bridge?.dispose()
        bridge = null
        println("[$TAG] Disposed")
    }

    val isActive: Boolean get() = bridge?.isActive() ?: false

    // ==================== Resampling PCM16 ====================

    private fun resamplePcm16(data: ByteArray, fromRate: Int, toRate: Int): ByteArray {
        if (fromRate == toRate) return data

        val inputSamples = data.size / 2
        val outputSamples = (inputSamples.toLong() * toRate / fromRate).toInt()
        val output = ByteArray(outputSamples * 2)

        for (i in 0 until outputSamples) {
            val srcPos = i.toDouble() * fromRate / toRate
            val srcIdx = srcPos.toInt()
            val frac = srcPos - srcIdx

            val s1 = readSample(data, srcIdx)
            val s2 = if (srcIdx + 1 < inputSamples) readSample(data, srcIdx + 1) else s1
            val interpolated = (s1 * (1.0 - frac) + s2 * frac).toInt().toShort()

            output[i * 2] = (interpolated.toInt() and 0xFF).toByte()
            output[i * 2 + 1] = ((interpolated.toInt() shr 8) and 0xFF).toByte()
        }

        return output
    }

    private fun readSample(data: ByteArray, index: Int): Short {
        val offset = index * 2
        if (offset + 1 >= data.size) return 0
        val low = data[offset].toInt() and 0xFF
        val high = data[offset + 1].toInt() and 0xFF
        return (low or (high shl 8)).toShort()
    }
}
