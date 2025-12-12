package com.eddyslarez.kmpsiprtc.services.audio
//
//import android.Manifest
//import android.content.Context
//import android.content.pm.PackageManager
//import android.media.AudioFormat
//import android.media.AudioRecord
//import android.media.MediaRecorder
//import androidx.core.app.ActivityCompat
//import com.eddyslarez.kmpsiprtc.data.models.RecordingResult
//import com.eddyslarez.kmpsiprtc.platform.log
//import kotlinx.coroutines.*
//import java.io.File
//import java.io.FileOutputStream
//import java.nio.ByteBuffer
//import java.nio.ByteOrder
//
///**
// * Sistema de grabación de llamadas WebRTC
// * Permite grabar: solo local, solo remoto, o ambos mezclados
// */
//class CallRecorder(private val context: Context) {
//    private val TAG = "CallRecorder"
//
//    // Configuración de audio
//    private val SAMPLE_RATE = 8000 // Cambiar a 8kHz para coincidir con WebRTC
//    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
//    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
//    private val BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
//
//    // Buffers de audio
//    private val localAudioBuffer = mutableListOf<ByteArray>()
//    private val remoteAudioBuffer = mutableListOf<ByteArray>()
//    // Control de grabación
//    @Volatile
//    private var isRecording = false
//    private var recordingJob: Job? = null
//    private val recordingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
//
//    // ✅ NUEVO: AudioRecord para capturar el micrófono
//    private var audioRecord: AudioRecord? = null
//
//    // Archivos de salida
//    private var outputDir: File? = null
//    private var currentCallId: String? = null
//
//    init {
//        // Crear directorio para grabaciones
//        outputDir = File(context.filesDir, "call_recordings").apply {
//            if (!exists()) mkdirs()
//        }
//    }
//
//    // ==================== CONTROL DE GRABACIÓN ====================
//
//    /**
//     * Iniciar grabación de llamada
//     * @param callId Identificador único de la llamada
//     */
//    fun startRecording(callId: String) {
//        if (isRecording) {
//            log.w(TAG) { "⚠️ Recording already in progress" }
//            return
//        }
//
//        log.d(TAG) { "🎙️ Starting call recording for: $callId" }
//
//        currentCallId = callId
//        isRecording = true
//
//        // Limpiar buffers anteriores
//        localAudioBuffer.clear()
//        remoteAudioBuffer.clear()
//
//        // ✅ NUEVO: Iniciar captura del micrófono
//        startMicrophoneCapture()
//
//        log.d(TAG) { "✅ Recording started" }
//    }
//    private fun startMicrophoneCapture() {
//        recordingJob = recordingScope.launch {
//            try {
//                if (ActivityCompat.checkSelfPermission(
//                        context,
//                        Manifest.permission.RECORD_AUDIO
//                    ) != PackageManager.PERMISSION_GRANTED
//                ) {
//                    log.e(TAG) { "❌ No RECORD_AUDIO permission" }
//                    return@launch
//                }
//
//                audioRecord = AudioRecord(
//                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
//                    SAMPLE_RATE,
//                    CHANNEL_CONFIG,
//                    AUDIO_FORMAT,
//                    BUFFER_SIZE
//                )
//
//                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
//                    log.e(TAG) { "❌ AudioRecord not initialized" }
//                    return@launch
//                }
//
//                audioRecord?.startRecording()
//                log.d(TAG) { "🎤 Microphone capture started" }
//
//                val buffer = ByteArray(BUFFER_SIZE)
//
//                while (isRecording) {
//                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
//
//                    if (bytesRead > 0) {
//                        synchronized(localAudioBuffer) {
//                            localAudioBuffer.add(buffer.copyOf(bytesRead))
//                        }
//                        log.d(TAG) { "🎤 Local audio captured: $bytesRead bytes" }
//                    }
//                }
//
//                audioRecord?.stop()
//                audioRecord?.release()
//                audioRecord = null
//
//                log.d(TAG) { "🎤 Microphone capture stopped" }
//
//            } catch (e: Exception) {
//                log.e(TAG) { "❌ Error capturing microphone: ${e.message}" }
//                e.printStackTrace()
//            }
//        }
//    }
//    /**
//     * Detener grabación y guardar archivos
//     * @return Triple de rutas (local, remoto, mezclado)
//     */
//    suspend fun stopRecording(): RecordingResult = withContext(Dispatchers.IO) {
//        if (!isRecording) {
//            log.w(TAG) { "⚠️ No recording in progress" }
//            return@withContext RecordingResult(null, null, null)
//        }
//
//        log.d(TAG) { "🛑 Stopping recording..." }
//        isRecording = false
//
//        // ✅ Esperar a que termine la captura
//        recordingJob?.join()
//
//        val callId = currentCallId ?: "unknown"
//        val timestamp = System.currentTimeMillis()
//
//        // Guardar cada stream por separado
//        val localFile = saveAudioToFile(localAudioBuffer, "${callId}_local_${timestamp}.wav")
//        val remoteFile = saveAudioToFile(remoteAudioBuffer, "${callId}_remote_${timestamp}.wav")
//        val mixedFile = mixAndSaveAudio(localAudioBuffer, remoteAudioBuffer, "${callId}_mixed_${timestamp}.wav")
//
//        // Limpiar buffers
//        localAudioBuffer.clear()
//        remoteAudioBuffer.clear()
//        currentCallId = null
//
//        log.d(TAG) { "✅ Recording stopped and saved" }
//        log.d(TAG) { "   Local: ${localFile?.absolutePath}" }
//        log.d(TAG) { "   Remote: ${remoteFile?.absolutePath}" }
//        log.d(TAG) { "   Mixed: ${mixedFile?.absolutePath}" }
//
//        RecordingResult(localFile?.path, remoteFile?.path, mixedFile?.path)
//    }
//    // ==================== CAPTURA DE AUDIO ====================
//
////    /**
////     * Capturar audio local (del micrófono)
////     * Llamar desde el AudioTrack local
////     */
////    fun captureLocalAudio(audioData: ByteArray) {
////        if (!isRecording) return
////        log.d(TAG) { "🎤 Captured local frame: ${audioData.size} bytes" }
////        synchronized(localAudioBuffer) {
////            localAudioBuffer.add(audioData.copyOf())
////        }
////    }
//
//    /**
//     * Capturar audio remoto (del peer)
//     * Llamar desde el AudioTrack remoto
//     */
//    fun captureRemoteAudio(audioData: ByteArray) {
//        if (!isRecording) return
//
//        synchronized(remoteAudioBuffer) {
//            remoteAudioBuffer.add(audioData.copyOf())
//        }
//    }
//
//    // ==================== GUARDADO DE ARCHIVOS ====================
//
//    /**
//     * Guardar buffer de audio a archivo WAV
//     */
//    private fun saveAudioToFile(audioBuffer: List<ByteArray>, fileName: String): File? {
//        if (audioBuffer.isEmpty()) {
//            log.w(TAG) { "⚠️ Empty audio buffer for $fileName" }
//            return null
//        }
//
//        return try {
//            val file = File(outputDir, fileName)
//            val totalAudioLen = audioBuffer.sumOf { it.size }
//            val totalDataLen = totalAudioLen + 36
//
//            FileOutputStream(file).use { fos ->
//                // Escribir encabezado WAV
//                writeWavHeader(fos, totalAudioLen.toLong(), totalDataLen.toLong(), SAMPLE_RATE.toLong())
//
//                // Escribir datos de audio
//                audioBuffer.forEach { chunk ->
//                    fos.write(chunk)
//                }
//            }
//
//            log.d(TAG) { "✅ Saved: ${file.absolutePath} (${file.length() / 1024}KB)" }
//            file
//        } catch (e: Exception) {
//            log.e(TAG) { "❌ Error saving audio: ${e.message}" }
//            e.printStackTrace()
//            null
//        }
//    }
//
//    /**
//     * Mezclar audio local y remoto en un solo archivo
//     */
//    private fun mixAndSaveAudio(
//        localBuffer: List<ByteArray>,
//        remoteBuffer: List<ByteArray>,
//        fileName: String
//    ): File? {
//        if (localBuffer.isEmpty() && remoteBuffer.isEmpty()) {
//            log.w(TAG) { "⚠️ Both buffers are empty" }
//            return null
//        }
//
//        return try {
//            val file = File(outputDir, fileName)
//
//            // Convertir a muestras PCM (16-bit)
//            val localSamples = bufferToSamples(localBuffer)
//            val remoteSamples = bufferToSamples(remoteBuffer)
//
//            // Mezclar (hacer más largo el que sea más corto con silencio)
//            val maxLength = maxOf(localSamples.size, remoteSamples.size)
//            val mixedSamples = ShortArray(maxLength)
//
//            for (i in 0 until maxLength) {
//                val localSample = if (i < localSamples.size) localSamples[i].toInt() else 0
//                val remoteSample = if (i < remoteSamples.size) remoteSamples[i].toInt() else 0
//
//                // Mezclar con balance 50/50 y prevenir clipping
//                var mixed = (localSample + remoteSample) / 2
//                mixed = mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
//                mixedSamples[i] = mixed.toShort()
//            }
//
//            // Convertir de vuelta a bytes
//            val mixedBytes = samplesToBytes(mixedSamples)
//
//            // Guardar como WAV
//            FileOutputStream(file).use { fos ->
//                val totalAudioLen = mixedBytes.size
//                val totalDataLen = totalAudioLen + 36
//
//                writeWavHeader(fos, totalAudioLen.toLong(), totalDataLen.toLong(), SAMPLE_RATE.toLong())
//                fos.write(mixedBytes)
//            }
//
//            log.d(TAG) { "✅ Mixed audio saved: ${file.absolutePath} (${file.length() / 1024}KB)" }
//            file
//        } catch (e: Exception) {
//            log.e(TAG) { "❌ Error mixing audio: ${e.message}" }
//            e.printStackTrace()
//            null
//        }
//    }
//
//    // ==================== UTILIDADES ====================
//
//    /**
//     * Escribir encabezado WAV estándar
//     */
//    private fun writeWavHeader(
//        fos: FileOutputStream,
//        totalAudioLen: Long,
//        totalDataLen: Long,
//        sampleRate: Long
//    ) {
//        val channels = 1 // Mono
//        val byteRate = (16 * sampleRate * channels / 8).toLong()
//
//        val header = ByteArray(44)
//
//        header[0] = 'R'.code.toByte()  // RIFF/WAVE header
//        header[1] = 'I'.code.toByte()
//        header[2] = 'F'.code.toByte()
//        header[3] = 'F'.code.toByte()
//        header[4] = (totalDataLen and 0xff).toByte()
//        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
//        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
//        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
//        header[8] = 'W'.code.toByte()
//        header[9] = 'A'.code.toByte()
//        header[10] = 'V'.code.toByte()
//        header[11] = 'E'.code.toByte()
//        header[12] = 'f'.code.toByte()  // 'fmt ' chunk
//        header[13] = 'm'.code.toByte()
//        header[14] = 't'.code.toByte()
//        header[15] = ' '.code.toByte()
//        header[16] = 16  // 4 bytes: size of 'fmt ' chunk
//        header[17] = 0
//        header[18] = 0
//        header[19] = 0
//        header[20] = 1  // format = 1 (PCM)
//        header[21] = 0
//        header[22] = channels.toByte()
//        header[23] = 0
//        header[24] = (sampleRate and 0xff).toByte()
//        header[25] = ((sampleRate shr 8) and 0xff).toByte()
//        header[26] = ((sampleRate shr 16) and 0xff).toByte()
//        header[27] = ((sampleRate shr 24) and 0xff).toByte()
//        header[28] = (byteRate and 0xff).toByte()
//        header[29] = ((byteRate shr 8) and 0xff).toByte()
//        header[30] = ((byteRate shr 16) and 0xff).toByte()
//        header[31] = ((byteRate shr 24) and 0xff).toByte()
//        header[32] = (2 * 16 / 8).toByte()  // block align
//        header[33] = 0
//        header[34] = 16  // bits per sample
//        header[35] = 0
//        header[36] = 'd'.code.toByte()
//        header[37] = 'a'.code.toByte()
//        header[38] = 't'.code.toByte()
//        header[39] = 'a'.code.toByte()
//        header[40] = (totalAudioLen and 0xff).toByte()
//        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
//        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
//        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()
//
//        fos.write(header, 0, 44)
//    }
//
//    /**
//     * Convertir lista de ByteArrays a muestras PCM (Short[])
//     */
//    private fun bufferToSamples(buffer: List<ByteArray>): ShortArray {
//        val totalBytes = buffer.sumOf { it.size }
//        val samples = ShortArray(totalBytes / 2)
//
//        var sampleIndex = 0
//        buffer.forEach { chunk ->
//            val byteBuffer = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
//            while (byteBuffer.hasRemaining() && sampleIndex < samples.size) {
//                samples[sampleIndex++] = byteBuffer.short
//            }
//        }
//
//        return samples
//    }
//
//    /**
//     * Convertir muestras PCM a ByteArray
//     */
//    private fun samplesToBytes(samples: ShortArray): ByteArray {
//        val bytes = ByteArray(samples.size * 2)
//        val byteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
//
//        samples.forEach { sample ->
//            byteBuffer.putShort(sample)
//        }
//
//        return bytes
//    }
//
//    // ==================== INFORMACIÓN ====================
//
//    fun isRecording(): Boolean = isRecording
//
//    fun getCurrentCallId(): String? = currentCallId
//
//    fun getRecordingsDirectory(): File? = outputDir
//
//    fun getAllRecordings(): List<File> {
//        return outputDir?.listFiles()?.filter { it.extension == "wav" } ?: emptyList()
//    }
//
//    fun deleteRecording(file: File): Boolean {
//        return try {
//            file.delete()
//        } catch (e: Exception) {
//            log.e(TAG) { "Error deleting file: ${e.message}" }
//            false
//        }
//    }
//
//    fun deleteAllRecordings(): Boolean {
//        return try {
//            outputDir?.listFiles()?.forEach { it.delete() }
//            true
//        } catch (e: Exception) {
//            log.e(TAG) { "Error deleting recordings: ${e.message}" }
//            false
//        }
//    }
//
//    // ==================== LIMPIEZA ====================
//
//    fun dispose() {
//        log.d(TAG) { "Disposing CallRecorder" }
//        isRecording = false
//        recordingJob?.cancel()
//        recordingScope.cancel()
//        localAudioBuffer.clear()
//        remoteAudioBuffer.clear()
//    }
//}
//
//enum class RecordingType {
//    LOCAL,      // Solo audio local (micrófono)
//    REMOTE,     // Solo audio remoto (peer)
//    MIXED,      // Audio local + remoto mezclados
//    UNKNOWN
//}
//
//data class RecordingInfo(
//    val file: File,
//    val callId: String,
//    val type: RecordingType,
//    val timestamp: Long,
//    val sizeBytes: Long,
//    val durationSeconds: Long
//) {
//    fun getDurationFormatted(): String {
//        val minutes = durationSeconds / 60
//        val seconds = durationSeconds % 60
//        return String.format("%02d:%02d", minutes, seconds)
//    }
//
//    fun getSizeFormatted(): String {
//        val kb = sizeBytes / 1024
//        val mb = kb / 1024
//        return if (mb > 0) {
//            "$mb MB"
//        } else {
//            "$kb KB"
//        }
//    }
//}
