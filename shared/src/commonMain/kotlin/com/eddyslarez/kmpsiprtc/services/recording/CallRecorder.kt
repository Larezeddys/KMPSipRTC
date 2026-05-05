package com.eddyslarez.kmpsiprtc.services.recording

import com.eddyslarez.kmpsiprtc.data.models.RecordingResult
import com.eddyslarez.kmpsiprtc.services.audio.AudioStreamListener

/**
 * Interface para grabación de llamadas multiplataforma
 */
interface CallRecorder {
    /**
     * Iniciar grabación de llamada
     * @param callId Identificador único de la llamada
     * @param localNumber Número local (propio) para nombre de archivo legible
     * @param remoteNumber Número remoto (del peer) para nombre de archivo legible
     */
    fun startRecording(callId: String, localNumber: String = "", remoteNumber: String = "")

    /**
     * Detener grabación y guardar archivos
     * @return RecordingResult con las rutas de los archivos guardados
     */
    suspend fun stopRecording(): RecordingResult

    /**
     * Guardar lo que hay en buffer sin detener la grabación.
     * Útil cuando el remoto cuelga antes de que se llame stopRecording().
     * @return RecordingResult con las rutas guardadas, o null si no hay grabación activa
     */
    suspend fun forceFlushAndSave(): RecordingResult?

    /**
     * Capturar audio remoto (del peer)
     * @param audioData datos de audio en formato PCM
     * @param sampleRate sample rate real del audio (e.g. 48000, 16000)
     * @param channels número de canales (1=mono, 2=stereo)
     * @param bitsPerSample bits por muestra (16 o 32)
     */
    fun captureRemoteAudio(
        audioData: ByteArray,
        sampleRate: Int = 48000,
        channels: Int = 1,
        bitsPerSample: Int = 16
    )

    /**
     * Verificar si está grabando actualmente
     */
    fun isRecording(): Boolean

    /**
     * Obtener el ID de la llamada actual
     */
    fun getCurrentCallId(): String?

    /**
     * Obtener directorio de grabaciones
     */
    fun getRecordingsDirectory(): String?

    /**
     * Obtener todas las grabaciones
     */
    fun getAllRecordings(): List<RecordingFileInfo>

    /**
     * Eliminar una grabación específica
     * @param filePath ruta del archivo a eliminar
     */
    fun deleteRecording(filePath: String): Boolean

    /**
     * Eliminar todas las grabaciones
     */
    fun deleteAllRecordings(): Boolean

    /**
     * Liberar recursos
     */
    fun dispose()

    // ==================== STREAMING EN TIEMPO REAL ====================

    /**
     * Configurar listener para recibir audio en tiempo real
     * @param listener Listener que recibirá los datos PCM crudos, o null para desregistrar
     */
    fun setAudioStreamListener(listener: AudioStreamListener?)

    /**
     * Iniciar streaming de audio en tiempo real (independiente de grabación)
     * @param callId Identificador único de la llamada
     */
    fun startStreaming(callId: String)

    /**
     * Detener streaming de audio en tiempo real
     */
    fun stopStreaming()

    /**
     * Verificar si el streaming está activo
     */
    fun isStreaming(): Boolean

    // ==================== CHUNKS PARA TRANSCRIPCIÓN ====================

    /**
     * Configurar listener para recibir chunks de audio (para transcripción en tiempo real)
     * @param listener Listener que recibirá los chunks PCM16, o null para desregistrar
     */
    fun setAudioChunkListener(listener: AudioChunkListener?)
}

/**
 * Listener para recibir chunks de audio durante la grabación (usado para transcripción real-time)
 */
interface AudioChunkListener {
    fun onLocalChunk(pcm16Data: ByteArray, sampleRate: Int)
    fun onRemoteChunk(pcm16Data: ByteArray, sampleRate: Int)
}

/**
 * Data class multiplataforma para información de archivo de grabación
 */
data class RecordingFileInfo(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val timestamp: Long,
    val type: RecordingType
) {
    fun getSizeFormatted(): String {
        val kb = sizeBytes / 1024
        val mb = kb / 1024
        return if (mb > 0) {
            "$mb MB"
        } else {
            "$kb KB"
        }
    }
}

enum class RecordingType {
    LOCAL,      // Solo audio local (micrófono)
    REMOTE,     // Solo audio remoto (peer)
    MIXED,      // Audio local + remoto mezclados
    UNKNOWN
}

/**
 * Factory function para crear instancia de CallRecorder según la plataforma
 */
expect fun createCallRecorder(): CallRecorder