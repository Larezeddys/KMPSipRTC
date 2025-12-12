package com.eddyslarez.kmpsiprtc.services.audio

import com.eddyslarez.kmpsiprtc.platform.log
import dev.onvoid.webrtc.media.audio.AudioTrack
import kotlinx.coroutines.*
import javax.sound.sampled.*

/**
 * Implementación Desktop del capturador de audio usando Java Sound API
 */
class DesktopAudioTrackCapture(
    private val audioTrack: AudioTrack,
    private val callback: AudioCaptureCallback
) : AudioTrackCapture {

    private val TAG = "DesktopAudioCapture"

    @Volatile
    private var isCapturing = false

    private var captureJob: Job? = null
    private val captureScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Configuración de audio
    private val SAMPLE_RATE = 48000f
    private val CHANNELS = 1
    private val SAMPLE_SIZE_BITS = 16

    private val audioFormat = AudioFormat(
        SAMPLE_RATE,
        SAMPLE_SIZE_BITS,
        CHANNELS,
        true,  // signed
        false  // little endian
    )

    override fun startCapture() {
        if (isCapturing) return

        isCapturing = true

        captureJob = captureScope.launch {
            try {
                startAudioCapture()
            } catch (e: Exception) {
                log.e(TAG) { "Error in audio capture: ${e.message}" }
                e.printStackTrace()
            }
        }

        log.d(TAG) { "✅ Desktop audio capture started" }
    }

    private suspend fun startAudioCapture() {
        try {
            // Configurar línea de captura de audio
            val dataLineInfo = DataLine.Info(
                TargetDataLine::class.java,
                audioFormat
            )

            if (!AudioSystem.isLineSupported(dataLineInfo)) {
                log.e(TAG) { "Audio line not supported" }
                return
            }

            val targetDataLine = AudioSystem.getLine(dataLineInfo) as TargetDataLine
            targetDataLine.open(audioFormat)
            targetDataLine.start()

            log.d(TAG) { "Audio line opened and started" }

            val bufferSize = (SAMPLE_RATE / 10).toInt() // 100ms buffer
            val buffer = ByteArray(bufferSize * 2) // 2 bytes per sample

            while (isCapturing) {
                val bytesRead = targetDataLine.read(buffer, 0, buffer.size)

                if (bytesRead > 0) {
                    // Notificar callback con los datos capturados
                    callback.onAudioData(
                        data = buffer.copyOf(bytesRead),
                        bitsPerSample = SAMPLE_SIZE_BITS,
                        sampleRate = SAMPLE_RATE.toInt(),
                        channels = CHANNELS,
                        frames = bytesRead / (SAMPLE_SIZE_BITS / 8) / CHANNELS,
                        timestampMs = System.currentTimeMillis()
                    )
                }
            }

            targetDataLine.stop()
            targetDataLine.close()

            log.d(TAG) { "Audio line closed" }

        } catch (e: Exception) {
            log.e(TAG) { "Error capturing audio: ${e.message}" }
            e.printStackTrace()
        }
    }

    override fun stopCapture() {
        if (!isCapturing) return

        isCapturing = false
        captureJob?.cancel()
        captureJob = null

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
