package com.eddyslarez.kmpsiprtc.services.audio

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioTime
import kotlin.concurrent.Volatile

/**
 * Grabador de llamadas usando AVAudioEngine
 * Esta es la forma correcta de capturar audio en iOS
 */
@OptIn(ExperimentalForeignApi::class)
class IosCallRecorderWithAVAudioEngine {
    private val TAG = "IosCallRecorder"

    private var audioEngine: AVAudioEngine? = null
    private val localAudioBuffer = mutableListOf<ByteArray>()

    @Volatile
    var isRecording = false

    /**
     * Inicia grabación usando AVAudioEngine
     */
    fun startRecording(callId: String): Boolean {
        if (isRecording) return false

        return try {
            val engine = AVAudioEngine()
            audioEngine = engine

            val inputNode = engine.inputNode
            val format = inputNode.outputFormatForBus(0u)

            // Instalar tap para capturar audio
            inputNode.installTapOnBus(
                bus = 0u,
                bufferSize = 4096u,
                format = format
            ) { buffer, time ->
                captureAudioBuffer(buffer, time)
            }

            engine.prepare()
            engine.startAndReturnError(null)

            isRecording = true
            localAudioBuffer.clear()

            println("[$TAG] 🎙️ Recording started with AVAudioEngine")
            true

        } catch (e: Exception) {
            println("[$TAG] ❌ Error starting recording: ${e.message}")
            false
        }
    }

    /**
     * Captura el buffer de audio
     */
    private fun captureAudioBuffer(buffer: AVAudioPCMBuffer?, time: AVAudioTime?) {
        if (buffer == null || !isRecording) return

        try {
            val frameLength = buffer.frameLength.toInt()
            if (frameLength == 0) return

            // Obtener datos PCM
            val channels = buffer.format.channelCount.toInt()
            val floatChannelData = buffer.floatChannelData ?: return

            // ✅ CORREGIDO: Acceder correctamente al puntero de floats
            memScoped {
                // floatChannelData es CPointer<CPointerVar<FloatVar>>
                // Necesitamos el primer canal
                val channelPointer = floatChannelData[0] ?: return@memScoped

                // Convertir float a bytes (PCM 16-bit)
                val byteArray = convertFloatToPCM16(channelPointer, frameLength, channels)

                localAudioBuffer.add(byteArray)
            }

        } catch (e: Exception) {
            println("[$TAG] Error capturing buffer: ${e.message}")
        }
    }

    /**
     * Convierte float audio a PCM 16-bit
     */
    private fun convertFloatToPCM16(
        floatData: CPointer<FloatVar>?,
        frameLength: Int,
        channels: Int
    ): ByteArray {
        val byteArray = ByteArray(frameLength * channels * 2) // 2 bytes per sample

        // Implementación simplificada - necesita mejoras para producción
        for (i in 0 until frameLength) {
            val sample = floatData?.get(i) ?: 0f
            val pcmValue = (sample * 32767f).toInt().coerceIn(-32768, 32767)

            byteArray[i * 2] = (pcmValue and 0xFF).toByte()
            byteArray[i * 2 + 1] = ((pcmValue shr 8) and 0xFF).toByte()
        }

        return byteArray
    }
    /**
     * Detiene la grabación
     */
    fun stopRecording(): RecordingResult {
        if (!isRecording) return RecordingResult(null, null, null)

        isRecording = false

        try {
            audioEngine?.inputNode?.removeTapOnBus(0u)
            audioEngine?.stop()
            audioEngine = null

            println("[$TAG] 🛑 Recording stopped")
            println("[$TAG] Captured ${localAudioBuffer.size} audio chunks")

            // Aquí guardarías los archivos como en Android

        } catch (e: Exception) {
            println("[$TAG] Error stopping: ${e.message}")
        }

        return RecordingResult(null, null, null)
    }

    fun dispose() {
        stopRecording()
        localAudioBuffer.clear()
    }
}

// Data class compartida
data class RecordingResult(
    val localPath: String?,
    val remotePath: String?,
    val mixedPath: String?
)
// ==================== CONSTANTES DE AVAudioSession ====================

// Ports comunes
const val AVAudioSessionPortCarAudio = "CarAudio"
const val AVAudioSessionPortBuiltInMic = "MicrophoneBuiltIn"
const val AVAudioSessionPortBuiltInReceiver = "Receiver"
const val AVAudioSessionPortBuiltInSpeaker = "Speaker"
const val AVAudioSessionPortBluetoothHFP = "BluetoothHFP"
const val AVAudioSessionPortHeadphones = "Headphones"
const val AVAudioSessionPortHeadsetMic = "HeadsetMicrophone"