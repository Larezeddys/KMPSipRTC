package com.eddyslarez.kmpsiprtc.data.database.converters

import com.eddyslarez.kmpsiprtc.data.database.entities.CallLogEntity
import com.eddyslarez.kmpsiprtc.data.models.CallDirections
import com.eddyslarez.kmpsiprtc.data.models.CallLog
import com.eddyslarez.kmpsiprtc.repository.CallLogWithContact
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.jvm.JvmName
import kotlin.time.ExperimentalTime

/**
 * Convierte CallLogEntity a CallLog (modelo de la librería)
 */
fun CallLogEntity.toCallLog(): CallLog {
    return CallLog(
        id = this.id,
        direction = this.direction,
        to = this.phoneNumber.takeIf { this.direction == CallDirections.OUTGOING } ?: "",
        formattedTo = formatPhoneNumber(this.phoneNumber.takeIf { this.direction == CallDirections.OUTGOING }
            ?: ""),
        from = this.phoneNumber.takeIf { this.direction == CallDirections.INCOMING } ?: "",
        formattedFrom = formatPhoneNumber(this.phoneNumber.takeIf { this.direction == CallDirections.INCOMING }
            ?: ""),
        contact = this.displayName,
        formattedStartDate = formatStartDate(this.startTime),
        duration = this.duration,
        callType = this.callType,
        localAddress = this.localAddress ?: ""
    )
}

/**
 * Convierte CallLogWithContact a CallLog
 */
fun CallLogWithContact.toCallLog(): CallLog {
    val phoneNumber = this.callLog.phoneNumber
    val contactName = this.contact?.displayName ?: this.callLog.displayName

    return CallLog(
        id = this.callLog.id,
        direction = this.callLog.direction,
        to = phoneNumber.takeIf { this.callLog.direction == CallDirections.OUTGOING } ?: "",
        formattedTo = formatPhoneNumber(phoneNumber.takeIf { this.callLog.direction == CallDirections.OUTGOING } ?: ""),
        from = phoneNumber.takeIf { this.callLog.direction == CallDirections.INCOMING } ?: "",
        formattedFrom = formatPhoneNumber(phoneNumber.takeIf { this.callLog.direction == CallDirections.INCOMING } ?: ""),
        contact = contactName,
        formattedStartDate = formatStartDate(this.callLog.startTime),
        duration = this.callLog.duration,
        callType = this.callLog.callType,
        localAddress = this.callLog.localAddress ?: ""
    )
}

/**
 * Convierte lista de CallLogWithContact a lista de CallLog
 */
@JvmName("toCallLogsFromCallLogWithContact")
fun List<CallLogWithContact>.toCallLogs(): List<CallLog> {
    return this.map { it.toCallLog() }
}

/**
 * Convierte lista de CallLogEntity a lista de CallLog
 */
@JvmName("toCallLogsFromCallLogEntity")
fun List<CallLogEntity>.toCallLogs(): List<CallLog> {
    return this.map { it.toCallLog() }
}

@OptIn(ExperimentalTime::class)
private fun formatStartDate(timestamp: Long): String {
    val instant = Instant.fromEpochMilliseconds(timestamp)
    val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())

    return localDateTime.dayOfMonth.toString().padStart(2, '0') +
            ".${localDateTime.monthNumber.toString().padStart(2, '0')}" +
            ".${localDateTime.year} " +
            localDateTime.hour.toString().padStart(2, '0') +
            ":${localDateTime.minute.toString().padStart(2, '0')}"
}

private fun formatPhoneNumber(phoneNumber: String): String {
    return phoneNumber.trim()
}

//
///**
// * Convierte AppConfigEntity a SipConfig
// */
//fun AppConfigEntity.toSipConfig(): EddysSipLibrary.SipConfig {
//    return EddysSipLibrary.SipConfig(
//        defaultDomain = this.defaultDomain,
//        webSocketUrl = this.webSocketUrl,
//        userAgent = this.userAgent,
//        enableLogs = this.enableLogs,
//        enableAutoReconnect = this.enableAutoReconnect,
//        pingIntervalMs = this.pingIntervalMs,
//        incomingRingtoneUri = this.incomingRingtoneUri?.let { Uri.parse(it) },
//        outgoingRingtoneUri = this.outgoingRingtoneUri?.let { Uri.parse(it) }
//    )
//}
//
///**
// * Convierte SipConfig a AppConfigEntity
// */
//fun EddysSipLibrary.SipConfig.toAppConfigEntity(): AppConfigEntity {
//    return AppConfigEntity(
//        defaultDomain = this.defaultDomain,
//        webSocketUrl = this.webSocketUrl,
//        userAgent = this.userAgent,
//        enableLogs = this.enableLogs,
//        enableAutoReconnect = this.enableAutoReconnect,
//        pingIntervalMs = this.pingIntervalMs,
//        incomingRingtoneUri = this.incomingRingtoneUri?.toString(),
//        outgoingRingtoneUri = this.outgoingRingtoneUri?.toString()
//    )
//}
//
///**
// * Merge SipConfig con AppConfigEntity (prioridad a SipConfig)
// */
//fun EddysSipLibrary.SipConfig.mergeWithDatabaseConfig(dbConfig: AppConfigEntity?): EddysSipLibrary.SipConfig {
//    if (dbConfig == null) return this
//
//    return this.copy(
//        // Usar config de parámetros si está presente, sino usar de BD
//        defaultDomain = if (this.defaultDomain.isNotEmpty()) this.defaultDomain else dbConfig.defaultDomain,
//        webSocketUrl = if (this.webSocketUrl.isNotEmpty()) this.webSocketUrl else dbConfig.webSocketUrl,
//        userAgent = if (this.userAgent.isNotEmpty()) this.userAgent else dbConfig.userAgent,
//        // Para ringtones, priorizar BD sobre config inicial
//        incomingRingtoneUri = dbConfig.incomingRingtoneUri?.let { Uri.parse(it) } ?: this.incomingRingtoneUri,
//        outgoingRingtoneUri = dbConfig.outgoingRingtoneUri?.let { Uri.parse(it) } ?: this.outgoingRingtoneUri,
//        // Para booleanos y números, usar config de parámetros
//        enableLogs = this.enableLogs,
//        enableAutoReconnect = this.enableAutoReconnect,
//        pingIntervalMs = this.pingIntervalMs,
//        pushModeConfig = this.pushModeConfig
//    )
//}