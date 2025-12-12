package com.eddyslarez.kmpsiprtc.services.recording

import com.eddyslarez.kmpsiprtc.data.models.RecordingResult

/**
 * Interface para grabación de llamadas multiplataforma
 */
interface CallRecorder {
    /**
     * Iniciar grabación de llamada
     * @param callId Identificador único de la llamada
     */
    fun startRecording(callId: String)

    /**
     * Detener grabación y guardar archivos
     * @return RecordingResult con las rutas de los archivos guardados
     */
    suspend fun stopRecording(): RecordingResult

    /**
     * Capturar audio remoto (del peer)
     * @param audioData datos de audio en formato PCM 16-bit
     */
    fun captureRemoteAudio(audioData: ByteArray)

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