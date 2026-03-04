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
    private val SAMPLE_RATE = 48000f // 48kHz (WebRTC remote audio rate)
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

    // Formato real del audio remoto (detectado en primer callback)
    @Volatile
    private var remoteSampleRate: Int = 48000

    // Listener para streaming en tiempo real
    private var audioStreamListener: AudioStreamListener? = null

    // Control de grabación y streaming
    @Volatile
    private var isRecording = false
    @Volatile
    private var isStreamingActive = false
    private var recordingJob: Job? = null
    private val recordingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Diagnóstico de audio remoto
    @Volatile
    private var remoteCallbackCount = 0
    @Volatile
    private var remoteTotalBytes = 0L
    @Volatile
    private var remoteFirstCallbackLogged = false

    // TargetDataLine para captura de audio
    private var targetDataLine: TargetDataLine? = null
    private var currentCallId: String? = null

    @Volatile
    private var localNumber: String = ""
    @Volatile
    private var remoteNumber: String = ""
    @Volatile
    private var audioChunkListener: AudioChunkListener? = null

    private fun sanitizeNumber(number: String): String =
        number.replace("+", "").replace(":", "-").replace("/", "-").replace(" ", "")

    private fun sanitizeCallId(callId: String): String =
        callId.replace(Regex("[^A-Za-z0-9._-]"), "-")

    private fun buildFilePrefix(callId: String): String {
        val safeCallId = sanitizeCallId(callId)
        val local = sanitizeNumber(localNumber)
        val remote = sanitizeNumber(remoteNumber)
        return if (local.isNotEmpty() && remote.isNotEmpty()) {
            "${safeCallId}__${local}_to_${remote}"
        } else {
            safeCallId
        }
    }

    // Directorio de salida
    private val outputDir: File by lazy {
        val userHome = System.getProperty("user.home")
        File("$userHome/CallRecordings").apply {
            if (!exists()) mkdirs()
        }
    }

    override fun startRecording(callId: String, localNumber: String, remoteNumber: String) {
        this.localNumber = localNumber
        this.remoteNumber = remoteNumber
        if (isRecording) {
            log.w(TAG) { "⚠️ Recording already in progress" }
            return
        }

        log.d(TAG) { "🎙️ Starting call recording for: $callId" }

        currentCallId = callId
        isRecording = true
        remoteSampleRate = SAMPLE_RATE.toInt()

        // Reset diagnóstico
        remoteCallbackCount = 0
        remoteTotalBytes = 0L
        remoteFirstCallbackLogged = false

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
                            // Emitir chunk para transcripción
                            audioChunkListener?.onLocalChunk(chunk, SAMPLE_RATE.toInt())
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

        // Diagnóstico antes de guardar
        val localTotalBytes = localAudioBuffer.sumOf { it.size }
        val remoteTotalBytesNorm = remoteAudioBuffer.sumOf { it.size }
        val localDurationSec = localTotalBytes.toDouble() / (SAMPLE_RATE * 2)
        val remoteDurationSec = remoteTotalBytesNorm.toDouble() / (remoteSampleRate * 2)
        log.d(TAG) { "RECORDING STATS:" }
        log.d(TAG) { "  Local: ${localTotalBytes} bytes, ~${String.format("%.1f", localDurationSec)}s @ ${SAMPLE_RATE.toInt()}Hz" }
        log.d(TAG) { "  Remote: ${remoteTotalBytesNorm} bytes, ~${String.format("%.1f", remoteDurationSec)}s @ ${remoteSampleRate}Hz" }
        log.d(TAG) { "  Remote callbacks: $remoteCallbackCount, raw bytes: $remoteTotalBytes" }

        // Guardar archivos - local usa SAMPLE_RATE del mic, remote usa remoteSampleRate detectado
        val prefix = buildFilePrefix(callId)
        val localFile = saveAudioToFile(localAudioBuffer, "${prefix}_local_${timestamp}.wav", SAMPLE_RATE.toInt())
        val remoteFile = saveAudioToFile(remoteAudioBuffer, "${prefix}_remote_${timestamp}.wav", remoteSampleRate)
        val mixedFile = mixAndSaveAudio(localAudioBuffer, remoteAudioBuffer, "${prefix}_mixed_${timestamp}.wav")

        // Limpiar buffers
        localAudioBuffer.clear()
        remoteAudioBuffer.clear()
        localNumber = ""
        remoteNumber = ""
        currentCallId = null

        log.d(TAG) { "✅ Recording stopped and saved" }
        log.d(TAG) { "   Local: ${localFile?.absolutePath}" }
        log.d(TAG) { "   Remote: ${remoteFile?.absolutePath}" }
        log.d(TAG) { "   Mixed: ${mixedFile?.absolutePath}" }

        RecordingResult(localFile?.path, remoteFile?.path, mixedFile?.path)
    }

    override suspend fun forceFlushAndSave(): RecordingResult? = withContext(Dispatchers.IO) {
        if (!isRecording) return@withContext null
        val callId = currentCallId ?: return@withContext null
        val timestamp = System.currentTimeMillis()

        val localCopy = synchronized(localAudioBuffer) { localAudioBuffer.toList() }
        val remoteCopy = synchronized(remoteAudioBuffer) { remoteAudioBuffer.toList() }

        val prefix = buildFilePrefix(callId)
        val localFile = saveAudioToFile(localCopy, "${prefix}_local_${timestamp}.wav", SAMPLE_RATE.toInt())
        val remoteFile = saveAudioToFile(remoteCopy, "${prefix}_remote_${timestamp}.wav", remoteSampleRate)
        val mixedFile = mixAndSaveAudio(localCopy, remoteCopy, "${prefix}_mixed_${timestamp}.wav")

        log.d(TAG) { "⚡ forceFlushAndSave: local=${localFile?.absolutePath}, remote=${remoteFile?.absolutePath}" }
        RecordingResult(localFile?.path, remoteFile?.path, mixedFile?.path)
    }

    override fun captureRemoteAudio(
        audioData: ByteArray,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        if (!isRecording && !isStreamingActive) return

        // Log primer callback para diagnóstico
        if (!remoteFirstCallbackLogged) {
            remoteFirstCallbackLogged = true
            log.d(TAG) { "REMOTE AUDIO FIRST CALLBACK: sampleRate=$sampleRate, channels=$channels, bitsPerSample=$bitsPerSample, dataSize=${audioData.size}" }
            val expectedFrames = audioData.size / (channels * (bitsPerSample / 8))
            log.d(TAG) { "REMOTE AUDIO: expectedFrames=$expectedFrames, durationMs=${expectedFrames * 1000.0 / sampleRate}" }
        }
        remoteCallbackCount++
        remoteTotalBytes += audioData.size

        // Detectar sample rate real del audio remoto
        remoteSampleRate = sampleRate

        // Normalizar a mono 16-bit PCM
        val normalized = normalizeToMono16bit(audioData, channels, bitsPerSample)

        // Emitir chunk para transcripción en tiempo real
        audioChunkListener?.onRemoteChunk(normalized, sampleRate)

        // Guardar en buffer si está grabando
        if (isRecording) {
            synchronized(remoteAudioBuffer) {
                remoteAudioBuffer.add(normalized)
            }
        }

        // Enviar al listener si está haciendo streaming
        if (isStreamingActive && audioStreamListener != null) {
            try {
                audioStreamListener?.onRemoteAudioData(
                    normalized, sampleRate, 1, 16
                )
            } catch (e: Exception) {
                log.e(TAG) { "Error sending remote audio stream: ${e.message}" }
                audioStreamListener?.onStreamError(e.message ?: "Error sending remote audio")
            }
        }
    }

    /**
     * Normaliza audio PCM a mono 16-bit little-endian.
     * Maneja conversiones de: stereo→mono, 32-bit(int/float)→16-bit.
     */
    private fun normalizeToMono16bit(data: ByteArray, channels: Int, bitsPerSample: Int): ByteArray {
        if (channels == 1 && bitsPerSample == 16) return data.copyOf()

        val bytesPerSample = bitsPerSample / 8
        val frameSize = bytesPerSample * channels
        val frameCount = data.size / frameSize
        val output = ByteBuffer.allocate(frameCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        val input = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until frameCount) {
            var sum = 0L
            for (ch in 0 until channels) {
                val sample16: Int = when (bitsPerSample) {
                    16 -> input.short.toInt()
                    32 -> {
                        // WebRTC puede enviar int32 o float32
                        val raw = input.int
                        // Heuristica: si los valores son muy pequeños para int32,
                        // probablemente es float32
                        val asFloat = Float.fromBits(raw)
                        if (asFloat in -1.1f..1.1f) {
                            (asFloat * 32767f).toInt().coerceIn(-32768, 32767)
                        } else {
                            (raw shr 16) // int32 → int16 shift
                        }
                    }
                    else -> {
                        // Skip bytes desconocidos
                        repeat(bytesPerSample) { input.get() }
                        0
                    }
                }
                sum += sample16
            }
            // Promediar canales para downmix a mono
            val mono = (sum / channels).coerceIn(-32768, 32767).toShort()
            output.putShort(mono)
        }

        return output.array()
    }

    override fun isRecording(): Boolean = isRecording

    // ==================== STREAMING EN TIEMPO REAL ====================

    override fun setAudioStreamListener(listener: AudioStreamListener?) {
        audioStreamListener = listener
    }

    override fun setAudioChunkListener(listener: AudioChunkListener?) {
        audioChunkListener = listener
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
        audioChunkListener = null
        targetDataLine?.stop()
        targetDataLine?.close()
        targetDataLine = null
        recordingJob?.cancel()
        recordingScope.cancel()
        localAudioBuffer.clear()
        remoteAudioBuffer.clear()
    }

    // ==================== GUARDADO DE ARCHIVOS ====================

    private fun saveAudioToFile(audioBuffer: List<ByteArray>, fileName: String, sampleRate: Int = SAMPLE_RATE.toInt()): File? {
        return try {
            val file = File(outputDir, fileName)
            val totalAudioLen = audioBuffer.sumOf { it.size }
            val totalDataLen = totalAudioLen + 36

            FileOutputStream(file).use { fos ->
                writeWavHeader(fos, totalAudioLen.toLong(), totalDataLen.toLong(), sampleRate.toLong())
                audioBuffer.forEach { chunk -> fos.write(chunk) }
            }

            log.d(TAG) { "✅ Saved: ${file.absolutePath} (${file.length() / 1024}KB) @ ${sampleRate}Hz" }
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
        return try {
            val file = File(outputDir, fileName)
            val localSamples = bufferToSamples(localBuffer)
            var remoteSamples = bufferToSamples(remoteBuffer)

            // Si los sample rates difieren, resamplear el remoto al rate local
            val localRate = SAMPLE_RATE.toInt()
            if (remoteSampleRate != localRate && remoteSamples.isNotEmpty()) {
                remoteSamples = resample(remoteSamples, remoteSampleRate, localRate)
            }

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
                writeWavHeader(fos, totalAudioLen.toLong(), totalDataLen.toLong(), localRate.toLong())
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

    /**
     * Resample lineal simple de samples a un nuevo sample rate
     */
    private fun resample(samples: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate || samples.isEmpty()) return samples
        val ratio = fromRate.toDouble() / toRate.toDouble()
        val newLength = (samples.size / ratio).toInt()
        val result = ShortArray(newLength)
        for (i in result.indices) {
            val srcPos = i * ratio
            val srcIndex = srcPos.toInt()
            val frac = srcPos - srcIndex
            val s0 = samples[srcIndex.coerceIn(0, samples.lastIndex)].toInt()
            val s1 = samples[(srcIndex + 1).coerceIn(0, samples.lastIndex)].toInt()
            result[i] = (s0 + (s1 - s0) * frac).toInt().coerceIn(-32768, 32767).toShort()
        }
        return result
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
        header[32] = (CHANNELS * SAMPLE_SIZE_BITS / 8).toByte()
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
