package com.eddyslarez.kmpsiprtc.services.recording

import com.eddyslarez.kmpsiprtc.data.models.RecordingResult
import com.eddyslarez.kmpsiprtc.platform.log
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.internal.SynchronizedObject
import kotlinx.coroutines.internal.synchronized
import platform.AVFAudio.*
import platform.Foundation.*
import platform.darwin.NSObject
import kotlin.concurrent.Volatile

/**
 * Implementación de CallRecorder para iOS
 */
class IosCallRecorder : CallRecorder {
    private val TAG = "IosCallRecorder"

    // Configuración de audio
    private val SAMPLE_RATE = 8000.0 // 8kHz
    private val CHANNELS = 1 // Mono
    private val BIT_DEPTH = 16

    // Buffers de audio
    private val localAudioBuffer = mutableListOf<ByteArray>()
    private val remoteAudioBuffer = mutableListOf<ByteArray>()

    // Control de grabación
    @Volatile
    private var isRecording = false
    private var recordingJob: Job? = null
    private val recordingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // AVAudioEngine para captura de audio
    private var audioEngine: AVAudioEngine? = null
    private var currentCallId: String? = null

    // Directorio de salida
    @OptIn(ExperimentalForeignApi::class)
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

    @OptIn(ExperimentalForeignApi::class)
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

                val format = inputNode.outputFormatForBus(0u)

                inputNode.installTapOnBus(
                    bus = 0u,
                    bufferSize = 1024u,
                    format = format
                ) { buffer, _ ->
                    buffer?.let { captureAudioBuffer(it) }
                }

                audioEngine?.prepare()
                audioEngine?.startAndReturnError(null)

                log.d(TAG) { "🎤 Microphone capture started on iOS" }

            } catch (e: Exception) {
                log.e(TAG) { "❌ Error capturing microphone: ${e.message}" }
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class, InternalCoroutinesApi::class)
    private fun captureAudioBuffer(buffer: AVAudioPCMBuffer) {
        if (!isRecording) return

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

        synchronized(localAudioBuffer as SynchronizedObject) {
            localAudioBuffer.add(byteArray)
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

        // Guardar archivos
        val localFile = saveAudioToFile(localAudioBuffer, "${callId}_local_${timestamp}.wav")
        val remoteFile = saveAudioToFile(remoteAudioBuffer, "${callId}_remote_${timestamp}.wav")
        val mixedFile = mixAndSaveAudio(localAudioBuffer, remoteAudioBuffer, "${callId}_mixed_${timestamp}.wav")

        // Limpiar buffers
        localAudioBuffer.clear()
        remoteAudioBuffer.clear()
        currentCallId = null

        log.d(TAG) { "✅ Recording stopped and saved" }
        log.d(TAG) { "   Local: $localFile" }
        log.d(TAG) { "   Remote: $remoteFile" }
        log.d(TAG) { "   Mixed: $mixedFile" }

        RecordingResult(localFile, remoteFile, mixedFile)
    }

    @OptIn(InternalCoroutinesApi::class)
    override fun captureRemoteAudio(audioData: ByteArray) {
        if (!isRecording) return

        synchronized(remoteAudioBuffer as SynchronizedObject) {
            remoteAudioBuffer.add(audioData.copyOf())
        }
    }

    override fun isRecording(): Boolean = isRecording

    override fun getCurrentCallId(): String? = currentCallId

    override fun getRecordingsDirectory(): String = outputDir

    @OptIn(ExperimentalForeignApi::class)
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

    @OptIn(ExperimentalForeignApi::class)
    override fun deleteRecording(filePath: String): Boolean {
        return try {
            val fileManager = NSFileManager.defaultManager
            fileManager.removeItemAtPath(filePath, error = null)
        } catch (e: Exception) {
            log.e(TAG) { "Error deleting file: ${e.message}" }
            false
        }
    }

    @OptIn(ExperimentalForeignApi::class)
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
        audioEngine?.stop()
        audioEngine = null
        recordingJob?.cancel()
        recordingScope.cancel()
        localAudioBuffer.clear()
        remoteAudioBuffer.clear()
    }

    // ==================== GUARDADO DE ARCHIVOS ====================

    @OptIn(ExperimentalForeignApi::class)
    private fun saveAudioToFile(audioBuffer: List<ByteArray>, fileName: String): String? {
        if (audioBuffer.isEmpty()) {
            log.w(TAG) { "⚠️ Empty audio buffer for $fileName" }
            return null
        }

        return try {
            val filePath = "$outputDir/$fileName"
            val totalAudioLen = audioBuffer.sumOf { it.size }
            val totalDataLen = totalAudioLen + 36

            val data = NSMutableData()

            // Escribir encabezado WAV
            val header = createWavHeader(totalAudioLen.toLong(), totalDataLen.toLong())
            data.appendBytes(header.refTo(0) as COpaquePointer?, header.size.toULong())

            // Escribir datos de audio
            audioBuffer.forEach { chunk ->
                data.appendBytes(chunk.refTo(0) as COpaquePointer?, chunk.size.toULong())
            }

            data.writeToFile(filePath, atomically = true)

            log.d(TAG) { "✅ Saved: $filePath (${data.length / 1024u}KB)" }
            filePath
        } catch (e: Exception) {
            log.e(TAG) { "❌ Error saving audio: ${e.message}" }
            null
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun mixAndSaveAudio(
        localBuffer: List<ByteArray>,
        remoteBuffer: List<ByteArray>,
        fileName: String
    ): String? {
        if (localBuffer.isEmpty() && remoteBuffer.isEmpty()) {
            log.w(TAG) { "⚠️ Both buffers are empty" }
            return null
        }

        return try {
            val filePath = "$outputDir/$fileName"

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
            val totalAudioLen = mixedBytes.size
            val totalDataLen = totalAudioLen + 36

            val data = NSMutableData()
            val header = createWavHeader(totalAudioLen.toLong(), totalDataLen.toLong())
            data.appendBytes(header.refTo(0) as COpaquePointer?, header.size.toULong())
            data.appendBytes(mixedBytes.refTo(0) as COpaquePointer?, mixedBytes.size.toULong())
            data.writeToFile(filePath, atomically = true)

            log.d(TAG) { "✅ Mixed audio saved: $filePath (${data.length / 1024u}KB)" }
            filePath
        } catch (e: Exception) {
            log.e(TAG) { "❌ Error mixing audio: ${e.message}" }
            null
        }
    }

    private fun createWavHeader(totalAudioLen: Long, totalDataLen: Long): ByteArray {
        val sampleRate = SAMPLE_RATE.toLong()
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
            (2 * 16 / 8).toByte(), 0, 16, 0,
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