package com.eddyslarez.kmpsiprtc.data.models

import com.eddyslarez.kmpsiprtc.platform.KFile
import com.eddyslarez.kmpsiprtc.utils.formatTime
import com.eddyslarez.kmpsiprtc.utils.formatTimeWithHours

//enum class RecordingType {
//    LOCAL,      // Solo audio local (micrófono)
//    REMOTE,     // Solo audio remoto (peer)
//    MIXED,      // Audio local + remoto mezclados
//    UNKNOWN
//}

///**
// * Resultado de una grabación
// */
//data class RecordingResult(
//    val localFile: KFile?,
//    val remoteFile: KFile?,
//    val mixedFile: KFile?
//)
//
//data class RecordingInfo(
//    val file: KFile,
//    val callId: String,
//    val type: RecordingType,
//    val timestamp: Long,
//    val sizeBytes: Long,
//    val durationSeconds: Long
//) {
//    fun getDurationFormatted(): String {
//        val hours = durationSeconds / 3600
//        val minutes = (durationSeconds % 3600) / 60
//        val seconds = durationSeconds % 60
//
//        return if (hours > 0) {
//            formatTimeWithHours(hours, minutes, seconds)
//        } else {
//            formatTime(minutes, seconds)
//        }
//    }
//
//    fun getSizeFormatted(): String {
//        val kb = sizeBytes / 1024
//        val mb = kb / 1024
//        val gb = mb / 1024
//
//        return when {
//            gb > 0 -> "$gb GB"
//            mb > 0 -> "$mb MB"
//            kb > 0 -> "$kb KB"
//            else -> "$sizeBytes bytes"
//        }
//    }
//}

/**
 * Resultado de una grabación
 */
data class RecordingResult(
    val localFile: String?,
    val remoteFile: String?,
    val mixedFile: String?
)