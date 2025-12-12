package com.eddyslarez.kmpsiprtc.services.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.app.ActivityCompat
import com.eddyslarez.kmpsiprtc.data.models.RecordingResult
import com.eddyslarez.kmpsiprtc.platform.AndroidContext.getApplication
import com.eddyslarez.kmpsiprtc.platform.log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Implementación de CallRecorder para Android con streaming a OpenAI Realtime API
 */
class AndroidCallRecorder() : CallRecorder {
    private val TAG = "AndroidC`allRecorder"
    private val context: Context = getApplication()

    // Configuración de audio
    private val SAMPLE_RATE = 24000 // 24kHz requerido por OpenAI Realtime API
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

    // Chunk size para streaming (100ms de audio)
    private val CHUNK_SIZE_MS = 100
    private val CHUNK_SIZE_BYTES = (SAMPLE_RATE * 2 * CHUNK_SIZE_MS / 1000) // 2 bytes per sample

    // Buffers de audio
    private val localAudioBuffer = mutableListOf<ByteArray>()
    private val remoteAudioBuffer = mutableListOf<ByteArray>()

    // Control de grabación y streaming (independientes)
    @Volatile
    private var isRecording = false
    @Volatile
    private var isStreaming = false

    private var recordingJob: Job? = null
    private var streamingJob: Job? = null
    private val recordingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // AudioRecord para capturar el micrófono
    private var audioRecord: AudioRecord? = null

    // Archivos de salida
    private var outputDir: File? = null
    private var currentCallId: String? = null

    // Callback para streaming
    private var audioStreamCallback: AudioStreamCallback? = null

    // Configuración de funcionalidades
    data class AudioConfig(
        val enableRecording: Boolean = true,  // Guardar archivos
        val enableStreaming: Boolean = false  // Enviar a OpenAI en tiempo real
    )

    private var currentConfig = AudioConfig()

    init {
        outputDir = File(context.filesDir, "call_recordings").apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Interface para recibir audio en tiempo real
     */
    interface AudioStreamCallback {
        /**
         * Llamado cuando hay nuevo audio local (micrófono) disponible
         * @param audioData PCM16 mono a 24kHz en base64
         */
        fun onLocalAudioChunk(audioData: String)

        /**
         * Llamado cuando hay nuevo audio remoto disponible
         * @param audioData PCM16 mono a 24kHz en base64
         */
        fun onRemoteAudioChunk(audioData: String)

        /**
         * Llamado cuando hay un error en el streaming
         */
        fun onStreamError(error: Exception)
    }

    /**
     * Configura el callback para recibir audio en tiempo real
     */
    fun setAudioStreamCallback(callback: AudioStreamCallback?) {
        audioStreamCallback = callback
    }

    /**
     * Inicia la captura de audio con configuración personalizada
     * @param callId Identificador de la llamada
     * @param config Configuración de grabación y/o streaming
     */
    fun startAudioCapture(callId: String, config: AudioConfig = AudioConfig()) {
        if (isRecording || isStreaming) {
            log.w(TAG) { "⚠️ Audio capture already in progress" }
            return
        }

        log.d(TAG) { "🎙️ Starting audio capture for: $callId" }
        log.d(TAG) { "   Recording: ${config.enableRecording}" }
        log.d(TAG) { "   Streaming: ${config.enableStreaming}" }

        if (config.enableStreaming && audioStreamCallback == null) {
            log.w(TAG) { "⚠️ Streaming enabled but no callback set!" }
        }

        currentCallId = callId
        currentConfig = config
        isRecording = config.enableRecording
        isStreaming = config.enableStreaming

        if (config.enableRecording) {
            localAudioBuffer.clear()
            remoteAudioBuffer.clear()
        }

        // Iniciar captura del micrófono
        startMicrophoneCapture()

        log.d(TAG) { "✅ Audio capture started" }
    }

    override fun startRecording(callId: String) {
        // Método legacy - solo grabación
        startAudioCapture(callId, AudioConfig(enableRecording = true, enableStreaming = false))
    }

    /**
     * Inicia solo streaming sin grabar
     */
    fun startStreaming(callId: String) {
        startAudioCapture(callId, AudioConfig(enableRecording = false, enableStreaming = true))
    }

    /**
     * Inicia grabación Y streaming simultáneamente
     */
    fun startRecordingAndStreaming(callId: String) {
        startAudioCapture(callId, AudioConfig(enableRecording = true, enableStreaming = true))
    }

    private fun startMicrophoneCapture() {
        recordingJob = recordingScope.launch {
            try {
                if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    log.e(TAG) { "❌ No RECORD_AUDIO permission" }
                    return@launch
                }

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    BUFFER_SIZE * 4
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    log.e(TAG) { "❌ AudioRecord not initialized" }
                    return@launch
                }

                audioRecord?.startRecording()
                log.d(TAG) { "🎤 Microphone capture started" }

                val buffer = ByteArray(CHUNK_SIZE_BYTES)
                val accumulatedBuffer = mutableListOf<Byte>()

                while (isRecording || isStreaming) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0

                    if (bytesRead > 0) {
                        val chunk = buffer.copyOf(bytesRead)

                        // Guardar para archivo final (solo si está habilitado)
                        if (currentConfig.enableRecording) {
                            synchronized(localAudioBuffer) {
                                localAudioBuffer.add(chunk)
                            }
                        }

                        // Enviar al callback (solo si está habilitado)
                        if (currentConfig.enableStreaming && audioStreamCallback != null) {
                            accumulatedBuffer.addAll(chunk.toList())

                            // Enviar chunks completos
                            while (accumulatedBuffer.size >= CHUNK_SIZE_BYTES) {
                                val chunkToSend = accumulatedBuffer.take(CHUNK_SIZE_BYTES).toByteArray()
                                accumulatedBuffer.subList(0, CHUNK_SIZE_BYTES).clear()

                                try {
                                    val base64Audio = android.util.Base64.encodeToString(
                                        chunkToSend,
                                        android.util.Base64.NO_WRAP
                                    )
                                    audioStreamCallback?.onLocalAudioChunk(base64Audio)
                                } catch (e: Exception) {
                                    log.e(TAG) { "Error sending local audio chunk: ${e.message}" }
                                    audioStreamCallback?.onStreamError(e)
                                }
                            }
                        }
                    }
                }

                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null

                log.d(TAG) { "🎤 Microphone capture stopped" }

            } catch (e: Exception) {
                log.e(TAG) { "❌ Error capturing microphone: ${e.message}" }
                audioStreamCallback?.onStreamError(e)
                e.printStackTrace()
            }
        }
    }

    override fun captureRemoteAudio(audioData: ByteArray) {
        if (!isRecording && !isStreaming) return

        // Guardar para archivo final (solo si está habilitado)
        if (currentConfig.enableRecording) {
            synchronized(remoteAudioBuffer) {
                remoteAudioBuffer.add(audioData.copyOf())
            }
        }

        // Enviar a OpenAI en tiempo real (solo si está habilitado)
        if (currentConfig.enableStreaming && audioStreamCallback != null) {
            try {
                val base64Audio = android.util.Base64.encodeToString(
                    audioData,
                    android.util.Base64.NO_WRAP
                )
                audioStreamCallback?.onRemoteAudioChunk(base64Audio)
            } catch (e: Exception) {
                log.e(TAG) { "Error sending remote audio chunk: ${e.message}" }
                audioStreamCallback?.onStreamError(e)
            }
        }
    }

    override suspend fun stopRecording(): RecordingResult = withContext(Dispatchers.IO) {
        if (!isRecording && !isStreaming) {
            log.w(TAG) { "⚠️ No audio capture in progress" }
            return@withContext RecordingResult(null, null, null)
        }

        log.d(TAG) { "🛑 Stopping audio capture..." }

        val wasRecording = isRecording
        isRecording = false
        isStreaming = false

        recordingJob?.join()

        // Solo guardar archivos si la grabación estaba habilitada
        if (wasRecording && currentConfig.enableRecording) {
            val callId = currentCallId ?: "unknown"
            val timestamp = System.currentTimeMillis()

            val localFile = saveAudioToFile(localAudioBuffer, "${callId}_local_${timestamp}.wav")
            val remoteFile = saveAudioToFile(remoteAudioBuffer, "${callId}_remote_${timestamp}.wav")
            val mixedFile = mixAndSaveAudio(localAudioBuffer, remoteAudioBuffer, "${callId}_mixed_${timestamp}.wav")

            localAudioBuffer.clear()
            remoteAudioBuffer.clear()
            currentCallId = null

            log.d(TAG) { "✅ Recording stopped and saved" }
            log.d(TAG) { "   Local: ${localFile?.absolutePath}" }
            log.d(TAG) { "   Remote: ${remoteFile?.absolutePath}" }
            log.d(TAG) { "   Mixed: ${mixedFile?.absolutePath}" }

            return@withContext RecordingResult(localFile?.path, remoteFile?.path, mixedFile?.path)
        } else {
            // Solo streaming, no hay archivos que guardar
            localAudioBuffer.clear()
            remoteAudioBuffer.clear()
            currentCallId = null

            log.d(TAG) { "✅ Streaming stopped (no files saved)" }
            return@withContext RecordingResult(null, null, null)
        }
    }

    override fun isRecording(): Boolean = isRecording

    fun isStreaming(): Boolean = isStreaming

    fun isActive(): Boolean = isRecording || isStreaming

    override fun getCurrentCallId(): String? = currentCallId

    override fun getRecordingsDirectory(): String? = outputDir?.absolutePath

    override fun getAllRecordings(): List<RecordingFileInfo> {
        return outputDir?.listFiles()?.filter { it.extension == "wav" }?.map { file ->
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
            outputDir?.listFiles()?.forEach { it.delete() }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error deleting recordings: ${e.message}" }
            false
        }
    }

    override fun dispose() {
        log.d(TAG) { "Disposing CallRecorder" }
        isRecording = false
        isStreaming = false
        recordingJob?.cancel()
        recordingScope.cancel()
        localAudioBuffer.clear()
        remoteAudioBuffer.clear()
        audioStreamCallback = null
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
        val channels = 1
        val byteRate = (16 * sampleRate * channels / 8).toLong()
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
        header[32] = (2 * 16 / 8).toByte()
        header[33] = 0
        header[34] = 16
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
 * Factory function para Android
 */
actual fun createCallRecorder(): CallRecorder {
    return AndroidCallRecorder()
}