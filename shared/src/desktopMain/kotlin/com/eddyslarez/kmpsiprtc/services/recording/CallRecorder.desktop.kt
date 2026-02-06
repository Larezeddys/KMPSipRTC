package com.eddyslarez.kmpsiprtc.services.recording

import com.eddyslarez.kmpsiprtc.data.models.RecordingResult
import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.services.audio.AudioStreamListener
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.*

/**
 * Implementación de CallRecorder para Desktop (JVM)
 */
class DesktopCallRecorder : CallRecorder {
    private val TAG = "DesktopCallRecorder"

    // Configuración de audio
    private val SAMPLE_RATE = 8000f // 8kHz
    private val CHANNELS = 1 // Mono
    private val SAMPLE_SIZE_BITS = 16
    private val SIGNED = true
    private val BIG_ENDIAN = false

    private val audioFormat = AudioFormat(
        SAMPLE_RATE,
        SAMPLE_SIZE_BITS,
        CHANNELS,
        SIGNED,
        BIG_ENDIAN
    )

    // Buffers de audio
    private val localAudioBuffer = mutableListOf<ByteArray>()
    private val remoteAudioBuffer = mutableListOf<ByteArray>()

    // Listener para streaming en tiempo real
    private var audioStreamListener: AudioStreamListener? = null

    // Control de grabación y streaming
    @Volatile
    private var isRecording = false
    @Volatile
    private var isStreamingActive = false
    private var recordingJob: Job? = null
    private val recordingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // TargetDataLine para captura de audio
    private var targetDataLine: TargetDataLine? = null
    private var currentCallId: String? = null

    // Directorio de salida
    private val outputDir: File by lazy {
        val userHome = System.getProperty("user.home")
        File("$userHome/CallRecordings").apply {
            if (!exists()) mkdirs()
        }
    }

    override fun startRecording(callId: String) {
        if (isRecording) {
            log.w(TAG) { "⚠️ Recording already in progress" }
            return
        }

        log.d(TAG) { "🎙️ Starting call recording for: $callId" }

        currentCallId = callId
        isRecording = true

        // Limpiar buffers anteriores
        localAudioBuffer.clear()
        remoteAudioBuffer.clear()

        // Iniciar captura del micrófono
        startMicrophoneCapture()

        log.d(TAG) { "✅ Recording started" }
    }

    private fun startMicrophoneCapture() {
        recordingJob = recordingScope.launch {
            try {
                val dataLineInfo = DataLine.Info(TargetDataLine::class.java, audioFormat)

                if (!AudioSystem.isLineSupported(dataLineInfo)) {
                    log.e(TAG) { "❌ Audio line not supported" }
                    return@launch
                }

                targetDataLine = AudioSystem.getLine(dataLineInfo) as TargetDataLine
                targetDataLine?.open(audioFormat)
                targetDataLine?.start()

                log.d(TAG) { "🎤 Microphone capture started on Desktop" }

                val bufferSize = audioFormat.sampleRate.toInt() / 10 // 100ms buffer
                val buffer = ByteArray(bufferSize)

                while (isRecording || isStreamingActive) {
                    val bytesRead = targetDataLine?.read(buffer, 0, buffer.size) ?: 0

                    if (bytesRead > 0) {
                        val chunk = buffer.copyOf(bytesRead)

                        // Guardar en buffer si está grabando
                        if (isRecording) {
                            synchronized(localAudioBuffer) {
                                localAudioBuffer.add(chunk)
                            }
                        }

                        // Enviar al listener si está haciendo streaming
                        if (isStreamingActive && audioStreamListener != null) {
                            try {
                                audioStreamListener?.onLocalAudioData(
                                    chunk, SAMPLE_RATE.toInt(), CHANNELS, SAMPLE_SIZE_BITS
                                )
                            } catch (e: Exception) {
                                log.e(TAG) { "Error sending local audio stream: ${e.message}" }
                                audioStreamListener?.onStreamError(e.message ?: "Error sending local audio")
                            }
                        }
                    }
                }

                targetDataLine?.stop()
                targetDataLine?.close()
                targetDataLine = null

                log.d(TAG) { "🎤 Microphone capture stopped" }

            } catch (e: Exception) {
                log.e(TAG) { "❌ Error capturing microphone: ${e.message}" }
                e.printStackTrace()
            }
        }
    }

    override suspend fun stopRecording(): RecordingResult = withContext(Dispatchers.IO) {
        if (!isRecording) {
            log.w(TAG) { "⚠️ No recording in progress" }
            return@withContext RecordingResult(null, null, null)
        }

        log.d(TAG) { "🛑 Stopping recording..." }
        isRecording = false

        // Esperar a que termine la captura
        recordingJob?.join()

        val callId = currentCallId ?: "unknown"
        val timestamp = System.currentTimeMillis()

        // Guardar archivos
        val localFile = saveAudioToFile(localAudioBuffer, "${callId}_local_${timestamp}.wav")
        val remoteFile = saveAudioToFile(remoteAudioBuffer, "${callId}_remote_${timestamp}.wav")
        val mixedFile = mixAndSaveAudio(localAudioBuffer, remoteAudioBuffer, "${callId}_mixed_${timestamp}.wav")

        // Limpiar buffers
        localAudioBuffer.clear()
        remoteAudioBuffer.clear()
        currentCallId = null

        log.d(TAG) { "✅ Recording stopped and saved" }
        log.d(TAG) { "   Local: ${localFile?.absolutePath}" }
        log.d(TAG) { "   Remote: ${remoteFile?.absolutePath}" }
        log.d(TAG) { "   Mixed: ${mixedFile?.absolutePath}" }

        RecordingResult(localFile?.path, remoteFile?.path, mixedFile?.path)
    }

    override fun captureRemoteAudio(audioData: ByteArray) {
        if (!isRecording && !isStreamingActive) return

        val dataCopy = audioData.copyOf()

        // Guardar en buffer si está grabando
        if (isRecording) {
            synchronized(remoteAudioBuffer) {
                remoteAudioBuffer.add(dataCopy)
            }
        }

        // Enviar al listener si está haciendo streaming
        if (isStreamingActive && audioStreamListener != null) {
            try {
                audioStreamListener?.onRemoteAudioData(
                    dataCopy, SAMPLE_RATE.toInt(), CHANNELS, SAMPLE_SIZE_BITS
                )
            } catch (e: Exception) {
                log.e(TAG) { "Error sending remote audio stream: ${e.message}" }
                audioStreamListener?.onStreamError(e.message ?: "Error sending remote audio")
            }
        }
    }

    override fun isRecording(): Boolean = isRecording

    // ==================== STREAMING EN TIEMPO REAL ====================

    override fun setAudioStreamListener(listener: AudioStreamListener?) {
        audioStreamListener = listener
    }

    override fun startStreaming(callId: String) {
        if (isStreamingActive) {
            log.w(TAG) { "⚠️ Streaming already in progress" }
            return
        }

        log.d(TAG) { "🎙️ Starting audio streaming for: $callId" }
        currentCallId = callId
        isStreamingActive = true

        // Iniciar captura del micrófono si no está ya capturando
        if (!isRecording) {
            startMicrophoneCapture()
        }

        log.d(TAG) { "✅ Audio streaming started" }
    }

    override fun stopStreaming() {
        if (!isStreamingActive) return
        log.d(TAG) { "🛑 Stopping audio streaming..." }
        isStreamingActive = false

        // Si no está grabando tampoco, detener la captura del micrófono
        if (!isRecording) {
            recordingJob?.cancel()
        }

        log.d(TAG) { "✅ Audio streaming stopped" }
    }

    override fun isStreaming(): Boolean = isStreamingActive

    override fun getCurrentCallId(): String? = currentCallId

    override fun getRecordingsDirectory(): String = outputDir.absolutePath

    override fun getAllRecordings(): List<RecordingFileInfo> {
        return outputDir.listFiles()?.filter { it.extension == "wav" }?.map { file ->
            val type = when {
                file.name.contains("_local_") -> RecordingType.LOCAL
                file.name.contains("_remote_") -> RecordingType.REMOTE
                file.name.contains("_mixed_") -> RecordingType.MIXED
                else -> RecordingType.UNKNOWN
            }

            RecordingFileInfo(
                path = file.absolutePath,
                name = file.name,
                sizeBytes = file.length(),
                timestamp = file.lastModified(),
                type = type
            )
        } ?: emptyList()
    }

    override fun deleteRecording(filePath: String): Boolean {
        return try {
            File(filePath).delete()
        } catch (e: Exception) {
            log.e(TAG) { "Error deleting file: ${e.message}" }
            false
        }
    }

    override fun deleteAllRecordings(): Boolean {
        return try {
            outputDir.listFiles()?.forEach { it.delete() }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error deleting recordings: ${e.message}" }
            false
        }
    }

    override fun dispose() {
        log.d(TAG) { "Disposing CallRecorder" }
        isRecording = false
        isStreamingActive = false
        audioStreamListener = null
        targetDataLine?.stop()
        targetDataLine?.close()
        targetDataLine = null
        recordingJob?.cancel()
        recordingScope.cancel()
        localAudioBuffer.clear()
        remoteAudioBuffer.clear()
    }

    // ==================== GUARDADO DE ARCHIVOS ====================

    private fun saveAudioToFile(audioBuffer: List<ByteArray>, fileName: String): File? {
        if (audioBuffer.isEmpty()) {
            log.w(TAG) { "⚠️ Empty audio buffer for $fileName" }
            return null
        }

        return try {
            val file = File(outputDir, fileName)
            val totalAudioLen = audioBuffer.sumOf { it.size }
            val totalDataLen = totalAudioLen + 36

            FileOutputStream(file).use { fos ->
                writeWavHeader(fos, totalAudioLen.toLong(), totalDataLen.toLong(), SAMPLE_RATE.toLong())
                audioBuffer.forEach { chunk -> fos.write(chunk) }
            }

            log.d(TAG) { "✅ Saved: ${file.absolutePath} (${file.length() / 1024}KB)" }
            file
        } catch (e: Exception) {
            log.e(TAG) { "❌ Error saving audio: ${e.message}" }
            e.printStackTrace()
            null
        }
    }

    private fun mixAndSaveAudio(
        localBuffer: List<ByteArray>,
        remoteBuffer: List<ByteArray>,
        fileName: String
    ): File? {
        if (localBuffer.isEmpty() && remoteBuffer.isEmpty()) {
            log.w(TAG) { "⚠️ Both buffers are empty" }
            return null
        }

        return try {
            val file = File(outputDir, fileName)
            val localSamples = bufferToSamples(localBuffer)
            val remoteSamples = bufferToSamples(remoteBuffer)
            val maxLength = maxOf(localSamples.size, remoteSamples.size)
            val mixedSamples = ShortArray(maxLength)

            for (i in 0 until maxLength) {
                val localSample = if (i < localSamples.size) localSamples[i].toInt() else 0
                val remoteSample = if (i < remoteSamples.size) remoteSamples[i].toInt() else 0
                var mixed = (localSample + remoteSample) / 2
                mixed = mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                mixedSamples[i] = mixed.toShort()
            }

            val mixedBytes = samplesToBytes(mixedSamples)

            FileOutputStream(file).use { fos ->
                val totalAudioLen = mixedBytes.size
                val totalDataLen = totalAudioLen + 36
                writeWavHeader(fos, totalAudioLen.toLong(), totalDataLen.toLong(), SAMPLE_RATE.toLong())
                fos.write(mixedBytes)
            }

            log.d(TAG) { "✅ Mixed audio saved: ${file.absolutePath} (${file.length() / 1024}KB)" }
            file
        } catch (e: Exception) {
            log.e(TAG) { "❌ Error mixing audio: ${e.message}" }
            e.printStackTrace()
            null
        }
    }

    private fun writeWavHeader(
        fos: FileOutputStream,
        totalAudioLen: Long,
        totalDataLen: Long,
        sampleRate: Long
    ) {
        val channels = CHANNELS
        val byteRate = (SAMPLE_SIZE_BITS * sampleRate * channels / 8)
        val header = ByteArray(44)

        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (2 * SAMPLE_SIZE_BITS / 8).toByte()
        header[33] = 0
        header[34] = SAMPLE_SIZE_BITS.toByte()
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        fos.write(header, 0, 44)
    }

    private fun bufferToSamples(buffer: List<ByteArray>): ShortArray {
        val totalBytes = buffer.sumOf { it.size }
        val samples = ShortArray(totalBytes / 2)
        var sampleIndex = 0

        buffer.forEach { chunk ->
            val byteBuffer = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
            while (byteBuffer.hasRemaining() && sampleIndex < samples.size) {
                samples[sampleIndex++] = byteBuffer.short
            }
        }
        return samples
    }

    private fun samplesToBytes(samples: ShortArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        val byteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { sample -> byteBuffer.putShort(sample) }
        return bytes
    }
}

/**
 * Factory function para Desktop
 */
actual fun createCallRecorder(): CallRecorder {
    return DesktopCallRecorder()
}