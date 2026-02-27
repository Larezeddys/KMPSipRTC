package com.eddyslarez.kmpsiprtc.services.recording

import com.eddyslarez.kmpsiprtc.data.models.RecordingResult
import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.services.audio.AudioStreamListener
import com.eddyslarez.kmpsiprtc.utils.Lock
import com.eddyslarez.kmpsiprtc.utils.synchronized
import kotlinx.cinterop.*
import platform.AVFAudio.*
import platform.Foundation.*
import kotlinx.coroutines.*
import kotlin.concurrent.Volatile

/**
 * Implementación de CallRecorder para iOS
 */
@OptIn(ExperimentalForeignApi::class)
class IosCallRecorder : CallRecorder {
    private val TAG = "IosCallRecorder"

    // Configuración de audio
    private val SAMPLE_RATE = 48000.0 // 48kHz (WebRTC remote audio rate)
    private val CHANNELS = 1 // Mono
    private val BIT_DEPTH = 16

    // Buffers de audio con protección thread-safe
    private val localAudioBuffer = mutableListOf<ByteArray>()
    private val remoteAudioBuffer = mutableListOf<ByteArray>()

    // Formato real del audio remoto (detectado en primer callback)
    @Volatile
    private var remoteSampleRate: Int = 48000

    // Locks simples para callbacks de audio de alta frecuencia
    private val localBufferLock = Lock()
    private val remoteBufferLock = Lock()

    // Listener para streaming en tiempo real
    private var audioStreamListener: AudioStreamListener? = null

    // Control de grabación y streaming
    @Volatile
    private var isRecording = false
    @Volatile
    private var isStreamingActive = false
    private var recordingJob: Job? = null
    private val recordingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // AVAudioEngine para captura de audio
    private var audioEngine: AVAudioEngine? = null
    private var currentCallId: String? = null

    @Volatile
    private var localNumber: String = ""
    @Volatile
    private var remoteNumber: String = ""
    private var audioChunkListener: AudioChunkListener? = null

    // Directorio de salida
    private val outputDir: String by lazy {
        val documentsPath = NSSearchPathForDirectoriesInDomains(
            NSDocumentDirectory,
            NSUserDomainMask,
            true
        ).firstOrNull() as? String ?: ""

        val recordingsPath = "$documentsPath/call_recordings"
        val fileManager = NSFileManager.defaultManager

        if (!fileManager.fileExistsAtPath(recordingsPath)) {
            fileManager.createDirectoryAtPath(
                recordingsPath,
                withIntermediateDirectories = true,
                attributes = null,
                error = null
            )
        }
        recordingsPath
    }

    private fun sanitizeNumber(number: String): String =
        number.replace("+", "").replace(":", "-").replace("/", "-").replace(" ", "")

    private fun buildFilePrefix(callId: String): String {
        val local = sanitizeNumber(localNumber)
        val remote = sanitizeNumber(remoteNumber)
        return if (local.isNotEmpty() && remote.isNotEmpty()) "${local}_to_${remote}" else callId
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

        // Limpiar buffers anteriores
        synchronized(localBufferLock) { localAudioBuffer.clear() }
        synchronized(remoteBufferLock) { remoteAudioBuffer.clear() }

        // Iniciar captura del micrófono
        startMicrophoneCapture()

        log.d(TAG) { "✅ Recording started" }
    }

    private fun startMicrophoneCapture() {
        recordingJob = recordingScope.launch {
            try {
                // Configurar sesión de audio
                val audioSession = AVAudioSession.sharedInstance()
                audioSession.setCategory(
                    AVAudioSessionCategoryPlayAndRecord,
                    error = null
                )
                audioSession.setActive(true, error = null)

                // Crear y configurar AVAudioEngine
                audioEngine = AVAudioEngine()
                val inputNode = audioEngine?.inputNode ?: return@launch

                // Solicitar formato 48kHz explícitamente para que iOS resamplee si el hardware usa otra rate
                val format = AVAudioFormat(standardFormatWithSampleRate = SAMPLE_RATE, channels = CHANNELS.toUInt())

                inputNode.installTapOnBus(
                    bus = 0u,
                    bufferSize = 4096u,
                    format = format
                ) { buffer, _ ->
                    buffer?.let {
                        captureAudioBuffer(it)
                    }
                }

                audioEngine?.prepare()
                audioEngine?.startAndReturnError(null)

                log.d(TAG) { "🎤 Microphone capture started on iOS" }

            } catch (e: Exception) {
                log.e(TAG) { "❌ Error capturing microphone: ${e.message}" }
                e.printStackTrace()
            }
        }
    }

    private fun captureAudioBuffer(buffer: AVAudioPCMBuffer) {
        if (!isRecording && !isStreamingActive) return

        try {
            val frameLength = buffer.frameLength.toInt()
            if (frameLength == 0) return

            val channelData = buffer.floatChannelData ?: return
            val data = channelData[0] ?: return

            // Convertir float samples a PCM 16-bit
            val byteArray = ByteArray(frameLength * 2)
            for (i in 0 until frameLength) {
                val sample = (data[i] * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
                byteArray[i * 2] = (sample.toInt() and 0xFF).toByte()
                byteArray[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
            }

            // Guardar en buffer si está grabando
            if (isRecording) {
                synchronized(localBufferLock) {
                    localAudioBuffer.add(byteArray)
                }
                // Emitir chunk para transcripción en tiempo real
                audioChunkListener?.onLocalChunk(byteArray, SAMPLE_RATE.toInt())
            }

            // Enviar al listener si está haciendo streaming
            if (isStreamingActive && audioStreamListener != null) {
                try {
                    audioStreamListener?.onLocalAudioData(
                        byteArray, SAMPLE_RATE.toInt(), CHANNELS, BIT_DEPTH
                    )
                } catch (e: Exception) {
                    log.e(TAG) { "Error sending local audio stream: ${e.message}" }
                    audioStreamListener?.onStreamError(e.message ?: "Error sending local audio")
                }
            }
        } catch (e: Exception) {
            log.e(TAG) { "❌ Error processing audio buffer: ${e.message}" }
            e.printStackTrace()
        }
    }

    override suspend fun stopRecording(): RecordingResult = withContext(Dispatchers.Default) {
        if (!isRecording) {
            log.w(TAG) { "⚠️ No recording in progress" }
            return@withContext RecordingResult(null, null, null)
        }

        log.d(TAG) { "🛑 Stopping recording..." }
        isRecording = false

        // Detener audio engine
        audioEngine?.inputNode?.removeTapOnBus(0u)
        audioEngine?.stop()
        audioEngine = null

        // Esperar a que termine la captura
        recordingJob?.join()

        val callId = currentCallId ?: "unknown"
        val timestamp = NSDate().timeIntervalSince1970.toLong() * 1000

        // Copiar buffers de forma segura antes de guardar
        val localCopy = synchronized(localBufferLock) { localAudioBuffer.toList() }
        val remoteCopy = synchronized(remoteBufferLock) { remoteAudioBuffer.toList() }

        // Guardar archivos - local usa SAMPLE_RATE del mic, remote usa remoteSampleRate detectado
        val prefix = buildFilePrefix(callId)
        val localFile = saveAudioToFile(localCopy, "${prefix}_local_${timestamp}.wav", SAMPLE_RATE.toInt())
        val remoteFile = saveAudioToFile(remoteCopy, "${prefix}_remote_${timestamp}.wav", remoteSampleRate)
        val mixedFile = mixAndSaveAudio(localCopy, remoteCopy, "${prefix}_mixed_${timestamp}.wav")

        // Limpiar buffers
        synchronized(localBufferLock) { localAudioBuffer.clear() }
        synchronized(remoteBufferLock) { remoteAudioBuffer.clear() }
        localNumber = ""
        remoteNumber = ""
        currentCallId = null

        log.d(TAG) { "✅ Recording stopped and saved" }
        log.d(TAG) { "   Local: $localFile" }
        log.d(TAG) { "   Remote: $remoteFile" }
        log.d(TAG) { "   Mixed: $mixedFile" }

        RecordingResult(localFile, remoteFile, mixedFile)
    }

    override suspend fun forceFlushAndSave(): RecordingResult? = withContext(Dispatchers.Default) {
        if (!isRecording) return@withContext null
        val callId = currentCallId ?: return@withContext null
        val timestamp = NSDate().timeIntervalSince1970.toLong() * 1000

        val localCopy = synchronized(localBufferLock) { localAudioBuffer.toList() }
        val remoteCopy = synchronized(remoteBufferLock) { remoteAudioBuffer.toList() }

        val prefix = buildFilePrefix(callId)
        val localFile = saveAudioToFile(localCopy, "${prefix}_local_${timestamp}.wav", SAMPLE_RATE.toInt())
        val remoteFile = saveAudioToFile(remoteCopy, "${prefix}_remote_${timestamp}.wav", remoteSampleRate)
        val mixedFile = mixAndSaveAudio(localCopy, remoteCopy, "${prefix}_mixed_${timestamp}.wav")

        log.d(TAG) { "⚡ forceFlushAndSave: local=$localFile, remote=$remoteFile, mixed=$mixedFile" }
        RecordingResult(localFile, remoteFile, mixedFile)
    }

    override fun captureRemoteAudio(
        audioData: ByteArray,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        if (!isRecording && !isStreamingActive) return

        // Detectar sample rate real del audio remoto
        remoteSampleRate = sampleRate

        // Normalizar a mono 16-bit PCM
        val normalized = normalizeToMono16bit(audioData, channels, bitsPerSample)

        // Emitir chunk para transcripción en tiempo real
        audioChunkListener?.onRemoteChunk(normalized, sampleRate)

        // Guardar en buffer si está grabando
        if (isRecording) {
            synchronized(remoteBufferLock) {
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
     */
    private fun normalizeToMono16bit(data: ByteArray, channels: Int, bitsPerSample: Int): ByteArray {
        if (channels == 1 && bitsPerSample == 16) return data.copyOf()

        val bytesPerSample = bitsPerSample / 8
        val frameSize = bytesPerSample * channels
        val frameCount = data.size / frameSize
        val output = ByteArray(frameCount * 2)
        var outIdx = 0

        for (f in 0 until frameCount) {
            var sum = 0L
            for (ch in 0 until channels) {
                val offset = f * frameSize + ch * bytesPerSample
                val sample16: Int = when (bitsPerSample) {
                    16 -> {
                        val lo = data[offset].toInt() and 0xFF
                        val hi = data[offset + 1].toInt()
                        (hi shl 8) or lo
                    }
                    32 -> {
                        val b0 = data[offset].toInt() and 0xFF
                        val b1 = data[offset + 1].toInt() and 0xFF
                        val b2 = data[offset + 2].toInt() and 0xFF
                        val b3 = data[offset + 3].toInt()
                        val raw = (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
                        val asFloat = Float.fromBits(raw)
                        if (asFloat in -1.1f..1.1f) {
                            (asFloat * 32767f).toInt().coerceIn(-32768, 32767)
                        } else {
                            (raw shr 16)
                        }
                    }
                    else -> 0
                }
                sum += sample16
            }
            val mono = (sum / channels).coerceIn(-32768, 32767).toInt()
            output[outIdx++] = (mono and 0xFF).toByte()
            output[outIdx++] = ((mono shr 8) and 0xFF).toByte()
        }
        return output
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
            audioEngine?.inputNode?.removeTapOnBus(0u)
            audioEngine?.stop()
            audioEngine = null
            recordingJob?.cancel()
        }

        log.d(TAG) { "✅ Audio streaming stopped" }
    }

    override fun isStreaming(): Boolean = isStreamingActive

    override fun getCurrentCallId(): String? = currentCallId

    override fun getRecordingsDirectory(): String = outputDir

    override fun getAllRecordings(): List<RecordingFileInfo> {
        val fileManager = NSFileManager.defaultManager
        val files = fileManager.contentsOfDirectoryAtPath(outputDir, error = null) as? List<*>

        return files?.mapNotNull { fileName ->
            val name = fileName as? String ?: return@mapNotNull null
            if (!name.endsWith(".wav")) return@mapNotNull null

            val filePath = "$outputDir/$name"
            val attributes = fileManager.attributesOfItemAtPath(filePath, error = null)
            val size = (attributes?.get(NSFileSize) as? NSNumber)?.longValue ?: 0L
            val modificationDate = attributes?.get(NSFileModificationDate) as? NSDate
            val timestamp = (modificationDate?.timeIntervalSince1970 ?: 0.0).toLong() * 1000

            val type = when {
                name.contains("_local_") -> RecordingType.LOCAL
                name.contains("_remote_") -> RecordingType.REMOTE
                name.contains("_mixed_") -> RecordingType.MIXED
                else -> RecordingType.UNKNOWN
            }

            RecordingFileInfo(
                path = filePath,
                name = name,
                sizeBytes = size,
                timestamp = timestamp,
                type = type
            )
        } ?: emptyList()
    }

    override fun deleteRecording(filePath: String): Boolean {
        return try {
            val fileManager = NSFileManager.defaultManager
            fileManager.removeItemAtPath(filePath, error = null)
        } catch (e: Exception) {
            log.e(TAG) { "Error deleting file: ${e.message}" }
            false
        }
    }

    override fun deleteAllRecordings(): Boolean {
        return try {
            val fileManager = NSFileManager.defaultManager
            val files = fileManager.contentsOfDirectoryAtPath(outputDir, error = null) as? List<*>
            files?.forEach { fileName ->
                val name = fileName as? String ?: return@forEach
                fileManager.removeItemAtPath("$outputDir/$name", error = null)
            }
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
        audioEngine?.stop()
        audioEngine = null
        recordingJob?.cancel()
        recordingScope.cancel()

        synchronized(localBufferLock) { localAudioBuffer.clear() }
        synchronized(remoteBufferLock) { remoteAudioBuffer.clear() }
    }

    // ==================== GUARDADO DE ARCHIVOS ====================

    private fun saveAudioToFile(audioBuffer: List<ByteArray>, fileName: String, sampleRate: Int = SAMPLE_RATE.toInt()): String? {
        return try {
            val filePath = "$outputDir/$fileName"
            val totalAudioLen = audioBuffer.sumOf { it.size }
            val totalDataLen = totalAudioLen + 36

            val data = NSMutableData()

            // Escribir encabezado WAV con sample rate real
            val header = createWavHeader(totalAudioLen.toLong(), totalDataLen.toLong(), sampleRate.toLong())
            header.usePinned { pinned ->
                data.appendBytes(pinned.addressOf(0), header.size.toULong())
            }

            // Escribir datos de audio
            audioBuffer.forEach { chunk ->
                chunk.usePinned { pinned ->
                    data.appendBytes(pinned.addressOf(0), chunk.size.toULong())
                }
            }

            data.writeToFile(filePath, atomically = true)

            log.d(TAG) { "✅ Saved: $filePath (${data.length / 1024u}KB) @ ${sampleRate}Hz" }
            filePath
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
    ): String? {
        return try {
            val filePath = "$outputDir/$fileName"

            val localSamples = bufferToSamples(localBuffer)
            var remoteSamples = bufferToSamples(remoteBuffer)
            val localRate = SAMPLE_RATE.toInt()

            // Si los sample rates difieren, resamplear el remoto al rate local
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
            val totalAudioLen = mixedBytes.size
            val totalDataLen = totalAudioLen + 36

            val data = NSMutableData()
            val header = createWavHeader(totalAudioLen.toLong(), totalDataLen.toLong(), localRate.toLong())
            header.usePinned { pinned ->
                data.appendBytes(pinned.addressOf(0), header.size.toULong())
            }
            mixedBytes.usePinned { pinned ->
                data.appendBytes(pinned.addressOf(0), mixedBytes.size.toULong())
            }
            data.writeToFile(filePath, atomically = true)

            log.d(TAG) { "✅ Mixed audio saved: $filePath (${data.length / 1024u}KB)" }
            filePath
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

    private fun createWavHeader(totalAudioLen: Long, totalDataLen: Long, sampleRate: Long = SAMPLE_RATE.toLong()): ByteArray {
        val channels = CHANNELS
        val byteRate = (16 * sampleRate * channels / 8)

        return byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            (totalDataLen and 0xff).toByte(),
            ((totalDataLen shr 8) and 0xff).toByte(),
            ((totalDataLen shr 16) and 0xff).toByte(),
            ((totalDataLen shr 24) and 0xff).toByte(),
            'W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte(),
            'f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte(),
            16, 0, 0, 0, 1, 0,
            channels.toByte(), 0,
            (sampleRate and 0xff).toByte(),
            ((sampleRate shr 8) and 0xff).toByte(),
            ((sampleRate shr 16) and 0xff).toByte(),
            ((sampleRate shr 24) and 0xff).toByte(),
            (byteRate and 0xff).toByte(),
            ((byteRate shr 8) and 0xff).toByte(),
            ((byteRate shr 16) and 0xff).toByte(),
            ((byteRate shr 24) and 0xff).toByte(),
            (CHANNELS * BIT_DEPTH / 8).toByte(), 0, BIT_DEPTH.toByte(), 0,
            'd'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte(),
            (totalAudioLen and 0xff).toByte(),
            ((totalAudioLen shr 8) and 0xff).toByte(),
            ((totalAudioLen shr 16) and 0xff).toByte(),
            ((totalAudioLen shr 24) and 0xff).toByte()
        )
    }

    private fun bufferToSamples(buffer: List<ByteArray>): ShortArray {
        val totalBytes = buffer.sumOf { it.size }
        val samples = ShortArray(totalBytes / 2)
        var sampleIndex = 0

        buffer.forEach { chunk ->
            var i = 0
            while (i < chunk.size - 1 && sampleIndex < samples.size) {
                val low = chunk[i].toInt() and 0xFF
                val high = chunk[i + 1].toInt() and 0xFF
                samples[sampleIndex++] = ((high shl 8) or low).toShort()
                i += 2
            }
        }
        return samples
    }

    private fun samplesToBytes(samples: ShortArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = (sample.toInt() and 0xFF).toByte()
            bytes[index * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        return bytes
    }
}

/**
 * Factory function para iOS
 */
actual fun createCallRecorder(): CallRecorder {
    return IosCallRecorder()
}
