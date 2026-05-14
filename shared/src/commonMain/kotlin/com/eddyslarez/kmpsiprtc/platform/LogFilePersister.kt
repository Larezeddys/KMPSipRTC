package com.eddyslarez.kmpsiprtc.platform

/**
 * Persistor de logs a disco con rotación.
 *
 * Mantiene un archivo activo `sip_log_current.txt` y rota a
 * `sip_log_<epochMs>.txt` cuando supera `maxFileSizeBytes`.
 * Conserva como máximo `maxRotatedFiles` archivos antiguos (los más viejos se eliminan).
 *
 * Cada línea: `2026-05-14T12:34:56.789Z [LEVEL/TAG] mensaje`.
 */
expect class LogFilePersister(baseDir: String) {
    fun write(level: String, tag: String, message: String)
    fun getLogFiles(): List<String>
    fun clearLogs()
}

internal const val LOG_FILE_MAX_SIZE_BYTES: Long = 1024L * 1024L
internal const val LOG_FILE_MAX_ROTATED: Int = 3
internal const val LOG_FILE_CURRENT_NAME: String = "sip_log_current.txt"
internal const val LOG_FILE_ROTATED_PREFIX: String = "sip_log_"
internal const val LOG_FILE_ROTATED_SUFFIX: String = ".txt"
