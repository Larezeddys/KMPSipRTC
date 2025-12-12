package com.eddyslarez.kmpsiprtc.services.audio

import com.eddyslarez.kmpsiprtc.platform.log
import com.shepeliev.webrtckmp.AudioStreamTrack
import kotlinx.cinterop.*
import platform.AVFAudio.*
import platform.Foundation.*
import kotlinx.coroutines.*
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Implementación iOS del capturador de audio usando AVAudioEngine
 */
@OptIn(ExperimentalForeignApi::class)
class IosAudioTrackCapture(
    private val audioTrack: AudioStreamTrack,
    private val callback: AudioCaptureCallback
) : AudioTrackCapture {

    private val TAG = "IosAudioCapture"
    private var audioEngine: AVAudioEngine? = null
    private var audioTap: AVAudioNode? = null

    @Volatile
    private var isCapturing = false

    private val captureScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun startCapture() {
        if (isCapturing) return

        try {
            // En iOS, WebRTC maneja el audio internamente
            // Necesitamos usar AVAudioEngine para interceptar
            setupAudioTap()
            isCapturing = true

            log.d(TAG) { "✅ iOS audio capture started" }
        } catch (e: Exception) {
            log.e(TAG) { "Error starting iOS capture: ${e.message}" }
        }
    }

    private fun setupAudioTap() {
        try {
            val audioSession = AVAudioSession.sharedInstance()

            // Configurar para captura de audio de comunicación
            audioSession.setCategory(
                AVAudioSessionCategoryPlayAndRecord,
                AVAudioSessionCategoryOptionAllowBluetooth or
                        AVAudioSessionCategoryOptionDefaultToSpeaker,
                null
            )
            audioSession.setActive(true, null)

            audioEngine = AVAudioEngine()

            // Usar el inputNode para capturar el audio que está siendo reproducido
            val inputNode = audioEngine?.inputNode ?: return
            val format = inputNode.outputFormatForBus(0u)

            // Instalar tap para capturar audio
            inputNode.installTapOnBus(
                bus = 0u,
                bufferSize = 1024u,
                format = format
            ) { buffer, _ ->
                buffer?.let { processAudioBuffer(it) }
            }

            audioEngine?.prepare()
            audioEngine?.startAndReturnError(null)

            log.d(TAG) { "Audio tap configured successfully" }
        } catch (e: Exception) {
            log.e(TAG) { "Error setting up audio tap: ${e.message}" }
        }
    }

    @OptIn(InternalCoroutinesApi::class, ExperimentalTime::class)
    private fun processAudioBuffer(buffer: AVAudioPCMBuffer) {
        if (!isCapturing) return

        try {
            val frameLength = buffer.frameLength.toInt()
            if (frameLength == 0) return

            val channelData = buffer.floatChannelData ?: return
            val data = channelData[0] ?: return

            // Convertir float samples a PCM 16-bit
            val byteArray = ByteArray(frameLength * 2)
            for (i in 0 until frameLength) {
                val sample = (data[i] * 32767.0f).toInt()
                    .coerceIn(-32768, 32767)
                    .toShort()

                byteArray[i * 2] = (sample.toInt() and 0xFF).toByte()
                byteArray[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
            }

            // Notificar callback
            callback.onAudioData(
                data = byteArray,
                bitsPerSample = 16,
                sampleRate = buffer.format.sampleRate.toInt(),
                channels = buffer.format.channelCount.toInt(),
                frames = frameLength,
                timestampMs = Clock.System.now().toEpochMilliseconds()
            )
        } catch (e: Exception) {
            log.e(TAG) { "Error processing audio buffer: ${e.message}" }
        }
    }

    override fun stopCapture() {
        if (!isCapturing) return

        try {
            audioEngine?.inputNode?.removeTapOnBus(0u)
            audioEngine?.stop()
            audioEngine = null
            isCapturing = false
            captureScope.cancel()

            log.d(TAG) { "✅ iOS audio capture stopped" }
        } catch (e: Exception) {
            log.e(TAG) { "Error stopping iOS capture: ${e.message}" }
        }
    }

    override fun isCapturing(): Boolean = isCapturing
}

/**
 * Factory iOS
 */
actual fun createRemoteAudioCapture(
    remoteTrack: Any,
    callback: AudioCaptureCallback
): AudioTrackCapture? {
    return try {
        if (remoteTrack is AudioStreamTrack) {
            IosAudioTrackCapture(remoteTrack, callback)
        } else {
            null
        }
    } catch (e: Exception) {
        println("Error creating iOS capture: ${e.message}")
        null
    }
}